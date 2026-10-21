package org.kazusa.matter.ui.detail

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.AttitudesDataDto
import org.kazusa.matter.data.model.CommentsDataDto
import org.kazusa.matter.data.model.RepostsDataDto
import org.kazusa.matter.data.model.WeiboCommentDto
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.LikeRepository

@OptIn(ExperimentalCoroutinesApi::class)
class PostDetailViewModelTest {
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

    private fun keep(viewModel: PostDetailViewModel): PostDetailViewModel = viewModel.also { vm ->
        ViewModelStore().also { store ->
            store.put("detail", vm)
            stores += store
        }
    }

    private fun viewModel(
        postId: String,
        likeRepository: LikeRepository = LikeRepository { _, isLike, _ -> ApiResult.Success(isLike) },
        source: FakeSource = FakeSource()
    ) = keep(
        PostDetailViewModel(
            postId = postId,
            recordHistory = {},
            likeRepository = likeRepository,
            dataSource = source
        )
    )

    @Test
    fun refreshSupersedesInFlightRepostsRequest() = runTest {
        var repostCalls = 0
        val vm = viewModel(
            postId = "detail-reposts-race",
            source = FakeSource(
                repostsResponse = { _, _ ->
                    repostCalls += 1
                    if (repostCalls == 1) {
                        delay(1_000)
                        ApiResult.Success(RepostsDataDto(data = listOf(WeiboStatusDto(id = 1))))
                    } else {
                        ApiResult.Success(RepostsDataDto(data = listOf(WeiboStatusDto(id = 2))))
                    }
                }
            )
        )

        vm.load()
        advanceUntilIdle()
        vm.selectTab(DetailSubTab.REPOSTS)
        runCurrent()
        assertTrue(vm.uiState.value.isRepostsLoading)

        // 刷新期间旧的分页请求仍在路上，它的结果不能再落到列表里
        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf(2L), vm.uiState.value.reposts.map { it.id })
        assertFalse(vm.uiState.value.isRepostsLoading)
        assertEquals(1, vm.uiState.value.repostsPage)
    }

    @Test
    fun refreshSupersedesInFlightCommentsRequest() = runTest {
        var commentCalls = 0
        val vm = viewModel(
            postId = "detail-comments-race",
            source = FakeSource(
                commentsResponse = { _, _ ->
                    commentCalls += 1
                    if (commentCalls == 1) {
                        delay(1_000)
                        ApiResult.Success(CommentsDataDto(data = listOf(WeiboCommentDto(id = 100))))
                    } else {
                        ApiResult.Success(CommentsDataDto(data = listOf(WeiboCommentDto(id = 200))))
                    }
                }
            )
        )

        vm.load()
        runCurrent()
        assertTrue(vm.uiState.value.isCommentsLoading)

        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf(200L), vm.uiState.value.comments.map { it.id })
        assertFalse(vm.uiState.value.isCommentsLoading)
    }

    @Test
    fun likeFailureRollsBackSharedStateAndReachesTheUi() = runTest {
        val messages = mutableListOf<String>()
        val repository = LikeRepository { _, _, _ -> ApiResult.Error(message = "点赞失败") }
        val vm = viewModel(postId = "detail-like", likeRepository = repository)

        vm.load()
        advanceUntilIdle()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.messages.collect { messages += it }
        }

        vm.toggleLike()
        advanceUntilIdle()

        val post = requireNotNull(vm.uiState.value.post)
        assertFalse(repository.postState(post).liked)
        assertFalse(repository.postState(post).pending)
        assertEquals(listOf("点赞失败"), messages)
    }

    @Test
    fun commentRepliesArePagedInsideTheViewModelAndResetOnRefresh() = runTest {
        var replyCalls = 0
        val vm = viewModel(
            postId = "detail-replies",
            source = FakeSource(
                commentsResponse = { _, _ ->
                    ApiResult.Success(
                        CommentsDataDto(
                            data = listOf(
                                WeiboCommentDto(
                                    id = 100,
                                    comments = listOf(WeiboCommentDto(id = 101, text = "预览"))
                                )
                            )
                        )
                    )
                },
                repliesResponse = { _, _, _, _ ->
                    replyCalls += 1
                    if (replyCalls == 1) {
                        ApiResult.Success(
                            CommentsDataDto(data = listOf(WeiboCommentDto(id = 102)), maxId = 200)
                        )
                    } else {
                        ApiResult.Success(
                            CommentsDataDto(data = listOf(WeiboCommentDto(id = 103)), maxId = 0)
                        )
                    }
                }
            )
        )

        vm.load()
        advanceUntilIdle()
        val comment = vm.uiState.value.comments.single()
        assertEquals(listOf(101L), vm.commentRepliesOf(comment).replies.map { it.id })

        vm.loadCommentReplies(comment)
        advanceUntilIdle()
        val firstPage = vm.commentRepliesOf(comment)
        assertEquals(listOf(101L, 102L), firstPage.replies.map { it.id })
        assertTrue(firstPage.hasMore)
        assertTrue(firstPage.loadedPage)

        vm.loadCommentReplies(comment)
        advanceUntilIdle()
        val secondPage = vm.commentRepliesOf(comment)
        assertEquals(listOf(101L, 102L, 103L), secondPage.replies.map { it.id })
        assertFalse(secondPage.hasMore)

        // 下拉刷新后评论整体换新，旧的楼中楼分页不能挂在新评论上
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf(101L), vm.commentRepliesOf(vm.uiState.value.comments.single()).replies.map { it.id })
    }

    @Test
    fun likeStateIsSharedWithTheFeedInsteadOfCopiedIntoThePage() = runTest {
        val repository = LikeRepository { _, isLike, _ -> ApiResult.Success(isLike) }
        val vm = viewModel(postId = "detail-like-shared", likeRepository = repository)

        vm.load()
        advanceUntilIdle()
        // 信息流侧点赞（同一份仓库）会立刻反映到正文页
        val post = requireNotNull(vm.uiState.value.post)
        repository.togglePost(post)
        advanceUntilIdle()

        // 正文页直接读仓库的同一份状态（见 PostDetailScreen），页面不再持有副本
        val state = repository.postState(post)
        assertTrue(state.liked)
        assertEquals(1, state.count)
    }

    private class FakeSource(
        private val postResponse: suspend (String) -> ApiResult<WeiboStatusDto> = {
            ApiResult.Success(WeiboStatusDto(id = 9, attitudesCount = "0"))
        },
        private val commentsResponse: suspend (String, Boolean) -> ApiResult<CommentsDataDto> =
            { _, _ -> ApiResult.Success(CommentsDataDto()) },
        private val repostsResponse: suspend (String, Int) -> ApiResult<RepostsDataDto> =
            { _, _ -> ApiResult.Success(RepostsDataDto()) },
        private val attitudesResponse: suspend (String, Int) -> ApiResult<AttitudesDataDto> =
            { _, _ -> ApiResult.Success(AttitudesDataDto()) },
        private val repliesResponse: suspend (String, Long, Long, Int) -> ApiResult<CommentsDataDto> =
            { _, _, _, _ -> ApiResult.Success(CommentsDataDto()) }
    ) : PostDetailDataSource {
        override suspend fun post(postId: String): ApiResult<WeiboStatusDto> = postResponse(postId)

        override suspend fun comments(postId: String, sortByHot: Boolean): ApiResult<CommentsDataDto> =
            commentsResponse(postId, sortByHot)

        override suspend fun reposts(postId: String, page: Int): ApiResult<RepostsDataDto> =
            repostsResponse(postId, page)

        override suspend fun attitudes(postId: String, page: Int): ApiResult<AttitudesDataDto> =
            attitudesResponse(postId, page)

        override suspend fun replies(
            postId: String,
            commentId: Long,
            maxId: Long,
            maxIdType: Int
        ): ApiResult<CommentsDataDto> = repliesResponse(postId, commentId, maxId, maxIdType)
    }
}
