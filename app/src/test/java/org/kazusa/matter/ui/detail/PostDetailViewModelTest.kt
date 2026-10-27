package org.kazusa.matter.ui.detail

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import org.kazusa.matter.data.repository.ReplyRepository
import org.kazusa.matter.data.repository.ReplySession
import org.kazusa.matter.data.repository.PublishedReply
import org.kazusa.matter.data.model.ReplyImageHandle
import org.kazusa.matter.data.model.ReplyImageReader
import org.kazusa.matter.data.model.ReplyImageContent
import org.kazusa.matter.data.model.ReplyPickerKind
import org.kazusa.matter.data.model.ReplySuggestion
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.AttitudesDataDto
import org.kazusa.matter.data.model.CommentsDataDto
import org.kazusa.matter.data.model.RepostsDataDto
import org.kazusa.matter.data.model.WeiboCommentDto
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.model.WeiboUserDto
import org.kazusa.matter.data.repository.LikeRepository
import org.kazusa.matter.data.repository.RepostRepository
import org.kazusa.matter.data.repository.SuggestionRepository

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
        source: FakeSource = FakeSource(),
        sessions: MutableStateFlow<ReplySession> = MutableStateFlow(ReplySession("1", "SUB=test", true)),
        repository: ReplyRepository? = null,
        suggestionRepository: SuggestionRepository = SuggestionRepository(sessions),
        repostRepository: RepostRepository? = null
    ) = keep(
        PostDetailViewModel(
            postId = postId,
            recordHistory = {},
            likeRepository = likeRepository,
            dataSource = source,
            replyRepository = repository ?: ReplyRepository(sessions) { _, postId, commentId, text, _ ->
                when (val result = source.publish(postId, commentId, text)) {
                    is ApiResult.Success -> ApiResult.Success(result.data?.let {
                        PublishedReply(requireNotNull(it.id), it.text, it.createdAt, it.user?.id,
                            it.user?.screenName.orEmpty(), it.user?.avatarLarge)
                    })
                    is ApiResult.Error -> result
                    ApiResult.Loading -> ApiResult.Loading
                }
            },
            suggestionRepository = suggestionRepository,
            repostRepository = repostRepository
        )
    )

    @Test
    fun repostSuccessRefreshesTheRepostTab() = runTest {
        var repostCalls = 0
        val repostRepository = RepostRepository { _, _ -> ApiResult.Success(Unit) }
        val vm = viewModel(
            postId = "detail-repost-refresh",
            source = FakeSource(
                repostsResponse = { _, _ ->
                    repostCalls += 1
                    ApiResult.Success(RepostsDataDto(data = listOf(WeiboStatusDto(id = 1))))
                }
            ),
            repostRepository = repostRepository
        )
        vm.load()
        advanceUntilIdle()
        vm.selectTab(DetailSubTab.REPOSTS)
        advanceUntilIdle()
        assertEquals(1, repostCalls)

        // 转发面板在导航根，正文页只能通过仓库事件得知写入成功
        repostRepository.repost(WeiboStatusDto(id = 9), "")
        advanceUntilIdle()

        assertEquals(2, repostCalls)
    }

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

    @Test
    fun replyIsPrependedAndClosesTheComposer() = runTest {
        val messages = mutableListOf<String>()
        val vm = viewModel(
            postId = "detail-reply-success",
            source = FakeSource(
                createCommentResponse = { _, text ->
                    ApiResult.Success(WeiboCommentDto(id = 777, text = text))
                }
            )
        )

        vm.load()
        advanceUntilIdle()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.messages.collect { messages += it }
        }

        vm.openReplyComposer()
        vm.updateReplyDraft("  回复内容  ")
        vm.submitReply()
        advanceUntilIdle()

        // 新回复立刻出现在评论区首位，输入条收起、草稿清空
        assertEquals(listOf(777L), vm.uiState.value.comments.map { it.id })
        assertEquals("回复内容", vm.uiState.value.comments.single().text)
        assertEquals("", vm.replyState.value.draft)
        assertFalse(vm.replyState.value.isOpen)
        assertFalse(vm.replyState.value.isSending)
        assertEquals(listOf("回复成功"), messages)
    }

    @Test
    fun replyFailureKeepsTheDraftForRetry() = runTest {
        val messages = mutableListOf<String>()
        val vm = viewModel(
            postId = "detail-reply-failure",
            source = FakeSource(
                createCommentResponse = { _, _ -> ApiResult.Error(message = "操作频繁，请稍后再试") }
            )
        )

        vm.load()
        advanceUntilIdle()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.messages.collect { messages += it }
        }

        vm.openReplyComposer()
        vm.updateReplyDraft("回复内容")
        vm.submitReply()
        advanceUntilIdle()

        // 失败不能清空草稿或收起输入条，否则用户得重新打一遍
        assertEquals("回复内容", vm.replyState.value.draft)
        assertTrue(vm.replyState.value.isOpen)
        assertFalse(vm.replyState.value.isSending)
        assertEquals("操作频繁，请稍后再试", vm.replyState.value.errorMessage)
    }

    @Test
    fun blankReplyIsNotSubmitted() = runTest {
        var calls = 0
        val vm = viewModel(
            postId = "detail-reply-blank",
            source = FakeSource(
                createCommentResponse = { _, _ ->
                    calls += 1
                    ApiResult.Success(null)
                }
            )
        )

        vm.load()
        advanceUntilIdle()
        vm.openReplyComposer()
        vm.updateReplyDraft("   ")
        vm.submitReply()
        advanceUntilIdle()

        assertEquals(0, calls)
        assertTrue(vm.replyState.value.isOpen)
    }

    @Test
    fun replyDoesNotSubmitTwiceWhileInFlight() = runTest {
        var calls = 0
        val vm = viewModel(
            postId = "detail-reply-inflight",
            source = FakeSource(
                createCommentResponse = { _, text ->
                    calls += 1
                    delay(1_000)
                    ApiResult.Success(WeiboCommentDto(id = 888, text = text))
                }
            )
        )

        vm.load()
        advanceUntilIdle()
        vm.updateReplyDraft("连点两次")
        vm.submitReply()
        vm.submitReply()
        advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals(listOf(888L), vm.uiState.value.comments.map { it.id })
    }

    @Test
    fun oldCommentsResponseCannotEraseSuccessfulReply() = runTest {
        val vm = viewModel("reply-list-race", source = FakeSource(
            commentsResponse = { _, _ -> delay(1000); ApiResult.Success(CommentsDataDto()) },
            createCommentResponse = { _, text -> ApiResult.Success(WeiboCommentDto(id = 111, text = text)) }
        ))
        vm.load()
        runCurrent()
        vm.updateReplyDraft("新评论")
        vm.submitReply()
        advanceUntilIdle()
        assertEquals(listOf(111L), vm.uiState.value.comments.map { it.id })
    }

    @Test
    fun changingDraftDuringSendKeepsNewContent() = runTest {
        val vm = viewModel("reply-edit-race", source = FakeSource(createCommentResponse = { _, text ->
            delay(1000)
            ApiResult.Success(WeiboCommentDto(id = 112, text = text))
        }))
        vm.load(); advanceUntilIdle()
        vm.openReplyComposer(); vm.updateReplyDraft("先发送")
        vm.submitReply(); runCurrent()
        vm.updateReplyDraft("新输入")
        advanceUntilIdle()
        assertEquals("新输入", vm.replyState.value.draft)
        assertTrue(vm.replyState.value.isOpen)
        assertFalse(vm.replyState.value.isSending)
    }

    @Test
    fun repliesUseSelectedCommentAndKeepDraftsSeparate() = runTest {
        var sentId: Long? = null
        val root = WeiboCommentDto(id = 200)
        val vm = viewModel("reply-target", source = FakeSource(
            commentsResponse = { _, _ -> ApiResult.Success(CommentsDataDto(data = listOf(root))) },
            replyCommentResponse = { _, id, text ->
                sentId = id
                ApiResult.Success(WeiboCommentDto(id = 202, text = text))
            }
        ))
        vm.load(); advanceUntilIdle()
        vm.openReplyComposer(); vm.updateReplyDraft("微博草稿")
        vm.replyToComment(WeiboCommentDto(id = 201), 200)
        assertEquals("", vm.replyState.value.draft)
        vm.updateReplyDraft("回复楼中楼")
        vm.submitReply(); advanceUntilIdle()
        assertEquals(201L, sentId)
        assertEquals(listOf(200L), vm.uiState.value.comments.map { it.id })
        assertEquals(listOf(202L), vm.commentRepliesOf(root).replies.map { it.id })
        vm.openReplyComposer()
        assertEquals("微博草稿", vm.replyState.value.draft)
    }

    @Test
    fun switchingAccountDiscardsLateSuccessAndDraft() = runTest {
        val sessions = MutableStateFlow(ReplySession("1", "SUB=old", true))
        val vm = viewModel("reply-session", sessions = sessions, source = FakeSource(
            createCommentResponse = { _, text ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { delay(1000) }
                ApiResult.Success(WeiboCommentDto(id = 333, text = text))
            }
        ))
        vm.load(); advanceUntilIdle()
        vm.openReplyComposer(); vm.updateReplyDraft("旧账号内容")
        vm.submitReply(); runCurrent()
        sessions.value = ReplySession("2", "SUB=new", true)
        advanceUntilIdle()
        assertEquals("", vm.replyState.value.draft)
        assertFalse(vm.replyState.value.isSending)
        assertTrue(vm.uiState.value.comments.isEmpty())
    }

    @Test
    fun failedReplyCanRetryTheSameDraft() = runTest {
        var calls = 0
        val vm = viewModel("reply-retry", source = FakeSource(createCommentResponse = { _, text ->
            if (++calls == 1) ApiResult.Error(message = "失败")
            else ApiResult.Success(WeiboCommentDto(id = 444, text = text))
        }))
        vm.load(); advanceUntilIdle()
        vm.openReplyComposer(); vm.updateReplyDraft("保留后重试")
        vm.submitReply(); advanceUntilIdle()
        assertEquals("失败", vm.replyState.value.errorMessage)
        vm.submitReply(); advanceUntilIdle()
        assertEquals(listOf(444L), vm.uiState.value.comments.map { it.id })
        assertEquals(null, vm.replyState.value.errorMessage)
    }

    @Test
    fun closingAndReopeningWhileSendingClearsSentDraftWithoutClosingNewPanel() = runTest {
        val vm = viewModel("reply-reopen", source = FakeSource(createCommentResponse = { _, text ->
            delay(1000)
            ApiResult.Success(WeiboCommentDto(id = 555, text = text))
        }))
        vm.load(); advanceUntilIdle()
        vm.openReplyComposer(); vm.updateReplyDraft("发送内容")
        vm.submitReply(); runCurrent()
        vm.dismissReplyComposer(); vm.openReplyComposer()
        advanceUntilIdle()
        assertEquals("", vm.replyState.value.draft)
        assertTrue(vm.replyState.value.isOpen)
    }

    @Test
    fun unchangedEditorEchoDuringSendDoesNotKeepAnAlreadySentDraft() = runTest {
        val vm = viewModel("reply-editor-echo", source = FakeSource(createCommentResponse = { _, text ->
            delay(1000)
            ApiResult.Success(WeiboCommentDto(id = 557, text = text))
        }))
        vm.load(); advanceUntilIdle()
        vm.openReplyComposer(); vm.updateReplyDraft("发送内容")
        vm.submitReply(); runCurrent()
        // 编辑器重组、打开或点击发送前可能重复回传相同正文。
        vm.updateReplyDraft("发送内容")
        advanceUntilIdle()
        assertEquals("", vm.replyState.value.draft)
        assertFalse(vm.replyState.value.isOpen)
    }

    @Test
    fun editingThenSwitchingTargetsDoesNotEraseTheEditedDraftWhenSendCompletes() = runTest {
        val vm = viewModel("reply-target-edit", source = FakeSource(createCommentResponse = { _, text ->
            delay(1000)
            ApiResult.Success(WeiboCommentDto(id = 556, text = text))
        }))
        vm.load(); advanceUntilIdle()
        vm.openReplyComposer(); vm.updateReplyDraft("发送内容")
        vm.submitReply(); runCurrent()
        vm.updateReplyDraft("留给下一条")
        vm.replyToComment(WeiboCommentDto(id = 999), 999)
        advanceUntilIdle()
        assertEquals(999L, vm.replyState.value.target?.commentId)
        vm.openReplyComposer()
        assertEquals("留给下一条", vm.replyState.value.draft)
    }

    @Test
    fun cancellingThePageLeavesDraftAndReleasesPending() = runTest {
        val vm = viewModel("reply-cancel", source = FakeSource(createCommentResponse = { _, _ ->
            delay(10000)
            ApiResult.Success(null)
        }))
        vm.load(); advanceUntilIdle()
        vm.updateReplyDraft("取消保留")
        vm.submitReply(); runCurrent()
        assertTrue(vm.replyState.value.isSending)
        stores.forEach(ViewModelStore::clear)
        runCurrent()
        assertFalse(vm.replyState.value.isSending)
        assertEquals("取消保留", vm.replyState.value.draft)
    }

    @Test
    fun replyLimitDoesNotSplitEmojiSurrogatePair() {
        assertEquals("a".repeat(MAX_REPLY_LENGTH - 1), limitReplyDraft("a".repeat(MAX_REPLY_LENGTH - 1) + "😀"))
        assertEquals(MAX_REPLY_LENGTH, limitReplyDraft("a".repeat(MAX_REPLY_LENGTH)).length)
    }

    @Test
    fun slowRepliesPageDoesNotEraseNewlyPublishedReply() = runTest {
        val root = WeiboCommentDto(id = 100)
        val vm = viewModel("reply-page-race", source = FakeSource(
            commentsResponse = { _, _ -> ApiResult.Success(CommentsDataDto(data = listOf(root))) },
            repliesResponse = { _, _, _, _ ->
                delay(1000)
                ApiResult.Success(CommentsDataDto(data = listOf(WeiboCommentDto(id = 101)), maxId = 200))
            },
            replyCommentResponse = { _, _, text -> ApiResult.Success(WeiboCommentDto(id = 102, text = text)) }
        ))
        vm.load(); advanceUntilIdle()
        vm.loadCommentReplies(root); runCurrent()
        vm.replyToComment(root, 100); vm.updateReplyDraft("新回复")
        vm.submitReply(); advanceUntilIdle()
        val state = vm.commentRepliesOf(root)
        assertEquals(setOf(101L, 102L), state.replies.map { it.id }.toSet())
        assertEquals(200L, state.nextMaxId)
        assertFalse(state.loading)
    }

    @Test
    fun imageOnlyReplyRetainsUploadedIdAfterFailureAndReusesItOnRetry() = runTest {
        val sessions = MutableStateFlow(ReplySession("1", "SUB=test", true))
        var uploads = 0
        var writes = 0
        val repository = ReplyRepository(sessions,
            imageReader = ReplyImageReader { ReplyImageContent(byteArrayOf(1, 2)) },
            uploadImage = { _, _ -> uploads++; ApiResult.Success("pic123") }
        ) { _, _, _, text, pictureId ->
            assertEquals("", text)
            assertEquals("pic123", pictureId)
            writes++
            if (writes == 1) ApiResult.Error(message = "发布失败") else ApiResult.Success(null)
        }
        val vm = viewModel("image-retry", sessions = sessions, repository = repository)
        vm.load(); advanceUntilIdle()
        val owner = vm.beginReplyImageSelection()
        vm.selectReplyImage(ReplyImageHandle("content://test/1"), owner)
        vm.submitReply(); advanceUntilIdle()
        assertEquals("pic123", vm.replyState.value.attachment?.uploadedId)
        assertEquals("发布失败", vm.replyState.value.errorMessage)
        vm.submitReply(); advanceUntilIdle()
        assertEquals(1, uploads)
        assertEquals(2, writes)
        assertEquals(null, vm.replyState.value.attachment)
    }

    @Test
    fun oldPickerSearchCannotOverwriteNewQuery() = runTest {
        val sessions = MutableStateFlow(ReplySession("1", "SUB=test", true))
        val suggestions = SuggestionRepository(sessions) { _, _, query ->
            if (query == "old") kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { delay(1000) }
            ApiResult.Success(listOf(ReplySuggestion(query.ifBlank { "follow" }, query.ifBlank { "follow" })))
        }
        val vm = viewModel(
            "picker-race",
            sessions = sessions,
            suggestionRepository = suggestions
        )
        vm.load(); advanceUntilIdle()
        vm.openReplyPicker(ReplyPickerKind.MENTION)
        vm.searchReplyPicker("old")
        testScheduler.advanceTimeBy(300); runCurrent()
        vm.searchReplyPicker("new")
        advanceUntilIdle()
        assertEquals("new", vm.replyPickerState.value.query)
        assertEquals(listOf("new"), vm.replyPickerState.value.suggestions.map { it.name })
    }

    @Test
    fun deletingOwnCommentRemovesItAndStaleReadsCannotResurrectIt() = runTest {
        val sessions = MutableStateFlow(ReplySession("1", "SUB=test", true))
        var destroyed: Long? = null
        val repository = ReplyRepository(sessions, publish = { _, _, _, _, _ -> ApiResult.Success(null) }, destroy = { _, _, commentId ->
            destroyed = commentId
            ApiResult.Success(Unit)
        })
        val vm = viewModel(
            "detail-delete-comment",
            sessions = sessions,
            repository = repository,
            source = FakeSource(
                commentsResponse = { _, _ ->
                    // 删除完成前发起的旧快照仍带着这条评论
                    delay(1_000)
                    ApiResult.Success(CommentsDataDto(data = listOf(WeiboCommentDto(id = 100, user = WeiboUserDto(id = 1)))))
                }
            )
        )

        vm.load()
        runCurrent()
        vm.deleteComment(WeiboCommentDto(id = 100, user = WeiboUserDto(id = 1)))
        advanceUntilIdle()

        assertEquals(100L, destroyed)
        // 服务端确认删除后才移除；在途旧响应不能把评论复活
        assertTrue(vm.uiState.value.comments.isEmpty())
    }

    @Test
    fun deletingAReplyRemovesItFromItsRootThread() = runTest {
        val sessions = MutableStateFlow(ReplySession("1", "SUB=test", true))
        var destroyed: Long? = null
        val repository = ReplyRepository(sessions, publish = { _, _, _, _, _ -> ApiResult.Success(null) }, destroy = { _, _, commentId ->
            destroyed = commentId
            ApiResult.Success(Unit)
        })
        val root = WeiboCommentDto(
            id = 100,
            user = WeiboUserDto(id = 1),
            comments = listOf(WeiboCommentDto(id = 101, user = WeiboUserDto(id = 1)))
        )
        val vm = viewModel(
            "detail-delete-reply",
            sessions = sessions,
            repository = repository,
            source = FakeSource(commentsResponse = { _, _ -> ApiResult.Success(CommentsDataDto(data = listOf(root))) })
        )

        vm.load()
        advanceUntilIdle()
        vm.deleteComment(WeiboCommentDto(id = 101, user = WeiboUserDto(id = 1)), rootId = 100)
        advanceUntilIdle()

        assertEquals(101L, destroyed)
        assertTrue(vm.commentRepliesOf(vm.uiState.value.comments.single()).replies.isEmpty())
    }

    @Test
    fun commentsOfOthersAreNotDeletable() = runTest {
        val sessions = MutableStateFlow(ReplySession("1", "SUB=test", true))
        var destroyed: Long? = null
        val repository = ReplyRepository(sessions, publish = { _, _, _, _, _ -> ApiResult.Success(null) }, destroy = { _, _, commentId ->
            destroyed = commentId
            ApiResult.Success(Unit)
        })
        val vm = viewModel(
            "detail-delete-foreign",
            sessions = sessions,
            repository = repository,
            source = FakeSource(commentsResponse = { _, _ ->
                ApiResult.Success(CommentsDataDto(data = listOf(WeiboCommentDto(id = 100, user = WeiboUserDto(id = 2)))))
            })
        )

        vm.load()
        advanceUntilIdle()
        vm.deleteComment(vm.uiState.value.comments.single())
        advanceUntilIdle()

        assertNull(destroyed)
        assertEquals(listOf(100L), vm.uiState.value.comments.map { it.id })
    }

    @Test
    fun deletingThePostNotifiesTheUiAndDropsTheCachedEntry() = runTest {
        val sessions = MutableStateFlow(ReplySession("1", "SUB=test", true))
        var destroyed = false
        val repository = ReplyRepository(sessions, publish = { _, _, _, _, _ -> ApiResult.Success(null) }, destroy = { _, _, commentId ->
            assertNull(commentId)
            destroyed = true
            ApiResult.Success(Unit)
        })
        val post = WeiboStatusDto(id = 777, user = WeiboUserDto(id = 1))
        PostDetailCache.put(post)
        try {
            val vm = viewModel(
                "detail-delete-post",
                sessions = sessions,
                repository = repository,
                source = FakeSource(postResponse = { ApiResult.Success(post) })
            )
            vm.load()
            advanceUntilIdle()
            val deleted = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.postDeleted.collect { deleted += it }
            }

            vm.deletePost()
            advanceUntilIdle()

            assertTrue(destroyed)
            assertEquals(listOf(Unit), deleted)
            assertNull(PostDetailCache.get("777"))
        } finally {
            PostDetailCache.remove("777")
        }
    }

    @Test
    fun failedPostDeletionKeepsThePageAndShowsTheMessage() = runTest {
        val sessions = MutableStateFlow(ReplySession("1", "SUB=test", true))
        val repository = ReplyRepository(sessions, publish = { _, _, _, _, _ -> ApiResult.Success(null) }, destroy = { _, _, _ -> ApiResult.Error(message = "删除失败") })
        val post = WeiboStatusDto(id = 778, user = WeiboUserDto(id = 1))
        PostDetailCache.put(post)
        try {
            val vm = viewModel(
                "detail-delete-post-failed",
                sessions = sessions,
                repository = repository,
                source = FakeSource(postResponse = { ApiResult.Success(post) })
            )
            vm.load()
            advanceUntilIdle()
            val messages = mutableListOf<String>()
            val deleted = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.messages.collect { messages += it }
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.postDeleted.collect { deleted += it }
            }

            vm.deletePost()
            advanceUntilIdle()

            assertTrue(deleted.isEmpty())
            assertEquals(listOf("删除失败"), messages)
            assertNotNull(PostDetailCache.get("778"))
        } finally {
            PostDetailCache.remove("778")
        }
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
            { _, _, _, _ -> ApiResult.Success(CommentsDataDto()) },
        private val replyCommentResponse: (suspend (String, Long, String) -> ApiResult<WeiboCommentDto?>)? = null,
        private val createCommentResponse: suspend (String, String) -> ApiResult<WeiboCommentDto?> =
            { _, _ -> ApiResult.Success(null) }
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

        suspend fun publish(postId: String, commentId: Long?, text: String): ApiResult<WeiboCommentDto?> =
            if (commentId == null) createCommentResponse(postId, text)
            else requireNotNull(replyCommentResponse)(postId, commentId, text)
    }
}
