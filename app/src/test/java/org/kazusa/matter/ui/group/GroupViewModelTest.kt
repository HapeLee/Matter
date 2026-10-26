package org.kazusa.matter.ui.group

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.WeiboGroupDto
import org.kazusa.matter.data.repository.GroupRepository

@OptIn(ExperimentalCoroutinesApi::class)
class GroupViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        stores.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    private fun keep(viewModel: GroupViewModel) = viewModel.also { vm ->
        ViewModelStore().also { store ->
            store.put("groups", vm)
            stores += store
        }
    }

    private fun group(gid: String) = WeiboGroupDto(gid = gid, name = gid, visible = 1)

    private fun repository(
        fetchGroups: suspend (String, Boolean) -> ApiResult<List<WeiboGroupDto>>
    ) = GroupRepository(
        currentSession = { UserSession(uid = "userA") },
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
    fun 慢响应晚到不覆盖新请求结果() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val vm = keep(
            GroupViewModel(
                repository(fetchGroups = { _, _ ->
                    calls += 1
                    if (calls == 1) gate.await()
                    ApiResult.Success(listOf(group("result-$calls")))
                })
            )
        )

        advanceUntilIdle() // init 的首次加载挂在 gate 上
        assertEquals(1, calls)
        assertTrue(vm.uiState.value.isLoading)

        vm.loadGroups(forceRefresh = true) // 第二次请求立即完成
        advanceUntilIdle()
        assertEquals(listOf("result-2"), vm.uiState.value.groups.map { it.identifier })
        assertFalse(vm.uiState.value.isLoading)

        gate.complete(Unit) // 第一次的慢响应此刻才回来
        advanceUntilIdle()

        // 迟到结果既不进缓存（不触发 groupsFlow），也不覆盖 UI 状态
        assertEquals(listOf("result-2"), vm.uiState.value.groups.map { it.identifier })
    }

    @Test
    fun 首次加载失败展示错误() = runTest {
        val vm = keep(
            GroupViewModel(
                repository(fetchGroups = { _, _ -> ApiResult.Error(message = "网络断开") })
            )
        )

        advanceUntilIdle()

        assertEquals("网络断开", vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.groups.isEmpty())
        assertFalse(vm.uiState.value.isLoading)
    }
}
