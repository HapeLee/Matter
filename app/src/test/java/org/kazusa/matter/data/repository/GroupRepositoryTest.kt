package org.kazusa.matter.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.WeiboGroupDto

class GroupRepositoryTest {

    private var currentUid = "userA"

    private fun group(gid: String) = WeiboGroupDto(gid = gid, name = gid, visible = 1)

    private fun repository(
        fetchGroups: suspend (String) -> ApiResult<List<WeiboGroupDto>>
    ) = GroupRepository(
        currentSession = { UserSession(uid = currentUid) },
        fetchGroups = fetchGroups,
        createRemoteGroup = { _, _, _ -> ApiResult.Error(message = "未在测试中使用") },
        renameRemoteGroup = { _, _, _ -> ApiResult.Error(message = "未在测试中使用") },
        destroyRemoteGroup = { _, _ -> ApiResult.Error(message = "未在测试中使用") },
        pushRemoteOrder = { _, _ -> ApiResult.Error(message = "未在测试中使用") },
        hiddenGroupIdsFlow = MutableStateFlow(emptySet()),
        customOrderFlow = MutableStateFlow(emptyList()),
        persistGroupVisibility = { _, _ -> },
        persistCustomOrder = { }
    )

    @Test
    fun 同账号内缓存命中不再重复请求() = runTest {
        var fetchCalls = 0
        val repository = repository(fetchGroups = { _ ->
            fetchCalls += 1
            ApiResult.Success(listOf(group("all"), group("friends")))
        })

        val first = repository.getGroups()
        val second = repository.getGroups()

        assertEquals(1, fetchCalls)
        assertEquals(listOf("all", "friends"), (second as ApiResult.Success).data.map { it.identifier })
        assertEquals((first as ApiResult.Success).data, (second as ApiResult.Success).data)
        // 缓存同时写进 groupsFlow，界面观察者拿到同一份结果
        assertEquals(listOf("all", "friends"), repository.groupsFlow.first().map { it.identifier })
    }

    @Test
    fun 换号后旧账号缓存失效并重新拉取() = runTest {
        var fetchCalls = 0
        val repository = repository(fetchGroups = { _ ->
            fetchCalls += 1
            ApiResult.Success(listOf(group("result-$fetchCalls")))
        })

        repository.getGroups()
        currentUid = "userB"
        val afterSwitch = repository.getGroups()

        assertEquals(2, fetchCalls)
        assertEquals("result-2", (afterSwitch as ApiResult.Success).data.single().identifier)
    }

    @Test
    fun 在途请求完成后不得把旧账号分组写回缓存() = runTest {
        val gate = CompletableDeferred<Unit>()
        var fetchCalls = 0
        val repository = repository(fetchGroups = { _ ->
            fetchCalls += 1
            if (fetchCalls == 1) gate.await()
            ApiResult.Success(listOf(group("result-$fetchCalls")))
        })

        val inFlight = async { repository.getGroups() }
        runCurrent()
        // 请求在途期间换号：旧账号的结果必须被拒绝，不能进缓存
        currentUid = "userB"
        gate.complete(Unit)

        assertTrue(inFlight.await() is ApiResult.Error)
        assertTrue(repository.groupsFlow.first().isEmpty())

        // 新账号加载拿到自己的分组
        val fresh = repository.getGroups()
        assertEquals("result-2", (fresh as ApiResult.Success).data.single().identifier)
    }

    @Test
    fun 换号后请求失败不回落旧账号缓存() = runTest {
        var failRemote = false
        val repository = repository(fetchGroups = { _ ->
            if (failRemote) ApiResult.Error(message = "网络断开")
            else ApiResult.Success(listOf(group("A")))
        })

        repository.getGroups()
        currentUid = "userB"
        failRemote = true

        assertTrue(repository.getGroups() is ApiResult.Error)
    }

    @Test
    fun 迟到的旧请求不覆盖新请求写入的缓存() = runTest {
        val gate = CompletableDeferred<Unit>()
        var fetchCalls = 0
        val repository = repository(fetchGroups = { _ ->
            // 进入时先捕获本次序号：gate 恢复后再读计数器已经变了
            val call = ++fetchCalls
            if (call == 1) gate.await()
            ApiResult.Success(listOf(group("result-$call")))
        })

        val first = async { repository.getGroups() }
        runCurrent()
        // 新请求立即完成并写入缓存
        val second = repository.getGroups()
        gate.complete(Unit)

        assertEquals("result-2", (second as ApiResult.Success).data.single().identifier)
        // 旧请求迟到：结果只交回调用方，缓存仍是新请求的结果
        assertEquals("result-1", (first.await() as ApiResult.Success).data.single().identifier)
        assertEquals("result-2", repository.groupsFlow.first().single().identifier)
    }

    @Test
    fun clear清空缓存下次重新拉取() = runTest {
        var fetchCalls = 0
        val repository = repository(fetchGroups = { _ ->
            fetchCalls += 1
            ApiResult.Success(listOf(group("all")))
        })

        repository.getGroups()
        repository.clear()

        assertTrue(repository.groupsFlow.first().isEmpty())
        repository.getGroups()
        assertEquals(2, fetchCalls)
    }
}
