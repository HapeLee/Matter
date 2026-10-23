package org.kazusa.matter.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.AttitudesDataDto
import org.kazusa.matter.data.model.CommentsDataDto
import org.kazusa.matter.data.model.RepostsDataDto
import org.kazusa.matter.data.model.WeiboAttitudeDto
import org.kazusa.matter.data.model.WeiboCommentDto
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.BrowsingHistoryRepository
import org.kazusa.matter.data.model.ReplyImageHandle
import org.kazusa.matter.data.model.ReplyPickerKind
import org.kazusa.matter.data.model.ReplySuggestion
import org.kazusa.matter.data.model.WeiboUserDto
import org.kazusa.matter.data.repository.ReplyRepository
import org.kazusa.matter.data.repository.ReplySession
import org.kazusa.matter.data.repository.LikeRepository

/** 正文页的子页签。 */
internal enum class DetailSubTab(val label: String) {
    REPOSTS("转发"),
    COMMENTS("评论"),
    ATTITUDES("赞")
}

/** 正文页的数据入口：抽成接口便于单测模拟慢响应、失败与取消。 */
internal interface PostDetailDataSource {
    suspend fun post(postId: String): ApiResult<WeiboStatusDto>
    suspend fun comments(postId: String, sortByHot: Boolean): ApiResult<CommentsDataDto>
    suspend fun reposts(postId: String, page: Int): ApiResult<RepostsDataDto>
    suspend fun attitudes(postId: String, page: Int): ApiResult<AttitudesDataDto>
    suspend fun replies(postId: String, commentId: Long, maxId: Long, maxIdType: Int): ApiResult<CommentsDataDto>

}

/** 输入长度保护；不把 UTF-16 代理对截成半个字符。 */
internal const val MAX_REPLY_LENGTH = 2000

internal fun limitReplyDraft(text: String): String {
    val end = if (text.length > MAX_REPLY_LENGTH && text[MAX_REPLY_LENGTH - 1].isHighSurrogate()
        && text[MAX_REPLY_LENGTH].isLowSurrogate()) MAX_REPLY_LENGTH - 1 else MAX_REPLY_LENGTH
    return text.take(end)
}

private class ApiPostDetailDataSource(private val apiService: WeiboApiService) : PostDetailDataSource {
    override suspend fun post(postId: String): ApiResult<WeiboStatusDto> =
        apiService.getPostDetail(postId)

    override suspend fun comments(postId: String, sortByHot: Boolean): ApiResult<CommentsDataDto> =
        apiService.getComments(postId, sortByHot = sortByHot)

    override suspend fun reposts(postId: String, page: Int): ApiResult<RepostsDataDto> =
        apiService.getReposts(postId, page = page)

    override suspend fun attitudes(postId: String, page: Int): ApiResult<AttitudesDataDto> =
        apiService.getAttitudes(postId, page = page)

    override suspend fun replies(
        postId: String,
        commentId: Long,
        maxId: Long,
        maxIdType: Int
    ): ApiResult<CommentsDataDto> =
        apiService.getCommentReplies(postId, commentId, maxId, maxIdType)

}

/**
 * 内容版本：不是业务数据，而是「这份内容被整体替换过」的稳定身份。
 *
 * 界面需要它做两件事，且两件事都必须发生在组合提交阶段（放到协程里会先按旧 alpha 画一帧）：
 * - [comments]：评论块整体替换时重启一次淡入；
 * - [commentsSubtree]：下拉刷新后重建评论子树，让「展开 / 收起」的界面状态归零。
 *
 * 之所以留在这里而不是改成一次性事件：事件在协程里送达，赶不上 `SideEffect` 的提交时机；
 * 自增版本号才能在同一帧内完成 syncKey + animateIn。它不参与业务判断，只做 Compose key。
 */
internal data class DetailContentVersion(
    val comments: Int = 0,
    val commentsSubtree: Int = 0
)

/**
 * 正文页页面状态；只读暴露，界面不再持有可变副本。
 *
 * 正文 / 评论 / 转发 / 赞四个分区各自持有数据、加载标记（在 ViewModel 内部分别跟踪请求任务）。
 */
internal data class PostDetailUiState(
    // ---- 正文 ----
    val post: WeiboStatusDto? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    // ---- 评论 ----
    val comments: List<WeiboCommentDto> = emptyList(),
    /** 博主开启了「精选评论」：接口只下发精选过的评论。 */
    val commentsApprovalOnly: Boolean = false,
    val isCommentsLoading: Boolean = false,
    /** 评论首屏请求是否已经结束：未结束前界面铺骨架屏，避免先闪一下空占位。 */
    val hasLoadedComments: Boolean = false,
    val sortByHot: Boolean = true,
    /** 内容版本（Compose key 用），见 [DetailContentVersion]。 */
    val contentVersion: DetailContentVersion = DetailContentVersion(),
    // ---- 转发 ----
    val reposts: List<WeiboStatusDto> = emptyList(),
    val isRepostsLoading: Boolean = false,
    val repostsPage: Int = 1,
    // ---- 赞 ----
    val attitudes: List<WeiboAttitudeDto> = emptyList(),
    val isAttitudesLoading: Boolean = false,
    val attitudesPage: Int = 1,
    // ---- 页签 ----
    val selectedTab: DetailSubTab = DetailSubTab.COMMENTS
)

/**
 * 悬浮回复条的状态。
 *
 * 单独一条流而不是并进 [PostDetailUiState]：草稿每敲一个字都会变，若挂在页面状态上，
 * 整个正文页（正文排版 + 列表）会跟着逐字重组，长正文 + 图片时打字会发涩。
 * 只有回复组件自己收集这条流，输入时页面其余部分不参与重组。
 */
internal data class ReplyComposerState(
    /** 输入条是否展开；收起时草稿保留，避免误触关闭丢内容。 */
    val isOpen: Boolean = false,
    val draft: String = "",
    /** 回复请求在途：期间禁止重复提交。 */
    val isSending: Boolean = false,
    val target: ReplyTarget? = null,
    val attachment: ReplyAttachment? = null,
    val isUploading: Boolean = false,
    val errorMessage: String? = null
)

internal data class ReplyAttachment(val image: ReplyImageHandle, val uploadedId: String? = null)

internal data class ReplyPickerState(
    val kind: ReplyPickerKind? = null,
    val query: String = "",
    val loading: Boolean = false,
    val suggestions: List<ReplySuggestion> = emptyList(),
    val error: String? = null
)

internal data class ReplyTarget(val commentId: Long, val rootId: Long, val screenName: String)

/**
 * 微博正文页的业务状态。
 *
 * 正文 / 评论 / 转发 / 赞四个分区各自持有数据、加载标记与独立请求任务：
 * - 任一分区的新请求（刷新、切排序、重试）都会取消该分区的旧任务使其结果失效，
 *   晚返回的旧响应不能再覆盖新数据或改写页码；
 * - 点赞状态不在页面里复制一份，统一读写 [LikeRepository]，列表与正文页看到的是同一份事实；
 * - 一次性提示（点赞失败、刷新失败）通过 [messages] 交给界面展示。
 *
 * 滚动位置、Snackbar、淡入动画等纯 UI 状态仍留在 Composable。
 */
internal class PostDetailViewModel internal constructor(
    private val postId: String,
    private val recordHistory: suspend (WeiboStatusDto) -> Unit,
    private val likeRepository: LikeRepository,
    private val dataSource: PostDetailDataSource,
    private val replyRepository: ReplyRepository
) : ViewModel() {

    constructor(
        postId: String,
        apiService: WeiboApiService,
        historyRepository: BrowsingHistoryRepository,
        likeRepository: LikeRepository,
        replyRepository: ReplyRepository
    ) : this(
        postId = postId,
        recordHistory = { post -> historyRepository.recordPost(post) },
        likeRepository = likeRepository,
        dataSource = ApiPostDetailDataSource(apiService),
        replyRepository = replyRepository
    )

    private val _uiState = MutableStateFlow(PostDetailUiState())
    val uiState: StateFlow<PostDetailUiState> = _uiState.asStateFlow()

    private val _replyState = MutableStateFlow(ReplyComposerState())

    /** 悬浮回复条状态；只有回复组件收集，逐字输入不会带动整页重组。 */
    val replyState: StateFlow<ReplyComposerState> = _replyState.asStateFlow()

    private val _repliesStates = MutableStateFlow<Map<String, CommentRepliesState>>(emptyMap())

    /** 楼中楼状态（按评论 id），只读；界面收集后按 key 取用。 */
    val repliesStates: StateFlow<Map<String, CommentRepliesState>> = _repliesStates.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private var started = false

    private var detailJob: Job? = null
    private var detailToken = 0

    private var commentsJob: Job? = null
    private var commentsToken = 0
    private var commentsRequestedForId: String? = null

    // ---- 楼中楼（按评论 id 管理）----
    private val repliesJobs = mutableMapOf<String, Job>()
    private val repliesTokens = mutableMapOf<String, Int>()

    private var repostsJob: Job? = null
    private var repostsToken = 0

    private var attitudesJob: Job? = null
    private var attitudesToken = 0

    /** 首次进入时调用；重复调用（重组、返回）不会重复请求。 */
    fun load() {
        if (started) return
        started = true
        // 命中缓存先铺内容，避免正文区空白，再用网络结果静默刷新
        PostDetailCache.get(postId)?.let { cached ->
            _uiState.update { it.copy(post = cached, isLoading = false) }
        }
        if (_uiState.value.post == null) _uiState.update { it.copy(isLoading = true) }
        requestDetail()
    }

    fun selectTab(tab: DetailSubTab) {
        val current = _uiState.value
        if (current.selectedTab == tab) return
        _uiState.update { it.copy(selectedTab = tab) }
        val targetId = targetId()
        when (tab) {
            DetailSubTab.COMMENTS ->
                if (current.comments.isEmpty() && !current.isCommentsLoading) loadComments(targetId)
            DetailSubTab.REPOSTS ->
                if (current.reposts.isEmpty() && !current.isRepostsLoading) loadReposts(targetId = targetId)
            DetailSubTab.ATTITUDES ->
                if (current.attitudes.isEmpty() && !current.isAttitudesLoading) loadAttitudes(targetId = targetId)
        }
    }

    fun toggleSort() {
        val hotSort = !_uiState.value.sortByHot
        _uiState.update { it.copy(sortByHot = hotSort) }
        loadComments(hotSort = hotSort)
    }

    /** 点赞只发出事件，乐观更新、失败回滚与 pending 全部由仓库处理。 */
    fun toggleLike() {
        val target = _uiState.value.post ?: return
        viewModelScope.launch {
            val message = likeRepository.togglePost(target) ?: return@launch
            messageChannel.trySend(message)
        }
    }

    private val _replyPickerState = MutableStateFlow(ReplyPickerState())
    val replyPickerState: StateFlow<ReplyPickerState> = _replyPickerState.asStateFlow()
    private var pickerJob: Job? = null
    private var pickerToken = 0
    private val replyAttachments = mutableMapOf<Long?, ReplyAttachment>()

    fun openReplyPicker(kind: ReplyPickerKind) {
        composerRevision++
        _replyPickerState.value = ReplyPickerState(kind = kind)
        searchReplyPicker("")
    }

    fun closeReplyPicker() {
        pickerToken++
        pickerJob?.cancel()
        _replyPickerState.value = ReplyPickerState()
    }

    fun searchReplyPicker(query: String) {
        val kind = _replyPickerState.value.kind ?: return
        val token = ++pickerToken
        pickerJob?.cancel()
        _replyPickerState.update { it.copy(query = query, loading = true, suggestions = emptyList(), error = null) }
        pickerJob = viewModelScope.launch {
            try {
                if (query.isNotBlank()) delay(250)
                val result = replyRepository.suggestions(kind, query)
                currentCoroutineContext().ensureActive()
                if (token != pickerToken) return@launch
                when (result) {
                    is ApiResult.Success -> _replyPickerState.update { it.copy(loading = false, suggestions = result.data) }
                    is ApiResult.Error -> _replyPickerState.update { it.copy(loading = false, error = result.message) }
                    ApiResult.Loading -> _replyPickerState.update { it.copy(loading = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (token == pickerToken) _replyPickerState.update { it.copy(loading = false, error = "加载失败，请重试") }
            }
        }
    }

    fun beginReplyImageSelection(): Int = ++composerRevision

    fun selectReplyImage(image: ReplyImageHandle, owner: Int) {
        if (owner != composerRevision || _replyState.value.isSending) return
        val key = _replyState.value.target?.commentId
        val attachment = ReplyAttachment(image)
        replyAttachments[key] = attachment
        draftVersions[key] = (draftVersions[key] ?: 0) + 1
        composerRevision++
        _replyState.update { it.copy(attachment = attachment, errorMessage = null) }
    }

    fun removeReplyImage() {
        if (_replyState.value.isSending) return
        val key = _replyState.value.target?.commentId
        replyAttachments.remove(key)
        draftVersions[key] = (draftVersions[key] ?: 0) + 1
        composerRevision++
        _replyState.update { it.copy(attachment = null, errorMessage = null) }
    }

    private var replyJob: Job? = null
    private var replyToken = 0
    private var composerRevision = 0
    private var replySession: ReplySession? = null
    private val replyDrafts = mutableMapOf<Long?, String>()
    private val draftVersions = mutableMapOf<Long?, Int>()
    // 写入已成功、列表尚未确认的评论；旧列表响应不能抹掉它们。
    private val publishedComments = linkedMapOf<Long, WeiboCommentDto>()
    private val publishedReplies = mutableMapOf<Long, LinkedHashMap<Long, WeiboCommentDto>>()

    init {
        viewModelScope.launch {
            replyRepository.sessions.collect { session ->
                if (replySession != null && replySession != session) {
                    replyToken++
                    composerRevision++
                    replyJob?.cancel()
                    closeReplyPicker()
                    replyAttachments.clear()
                    replyDrafts.clear()
                    draftVersions.clear()
                    publishedComments.clear()
                    publishedReplies.clear()
                    clearReplies()
                    _replyState.value = ReplyComposerState()
                    _uiState.update { it.copy(comments = emptyList()) }
                    if (started) loadComments()
                }
                replySession = session
            }
        }
    }

    /** 不同回复目标各自保留草稿，收起后重开仍回复原目标。 */
    fun openReplyComposer() = openComposer(null)

    fun replyToComment(comment: WeiboCommentDto, rootId: Long) {
        val id = comment.id ?: return
        openComposer(ReplyTarget(id, rootId, comment.user?.screenName.orEmpty()))
    }

    private fun openComposer(target: ReplyTarget?) {
        closeReplyPicker()
        val old = _replyState.value
        replyDrafts[old.target?.commentId] = old.draft
        composerRevision++
        _replyState.value = old.copy(isOpen = true, target = target,
            draft = replyDrafts[target?.commentId].orEmpty(),
            attachment = replyAttachments[target?.commentId],
            errorMessage = old.errorMessage.takeIf { old.target == target })
    }

    fun dismissReplyComposer() {
        closeReplyPicker()
        composerRevision++
        _replyState.update { it.copy(isOpen = false) }
    }

    fun updateReplyDraft(text: String) {
        val limited = limitReplyDraft(text)
        if (_replyState.value.draft == limited) return
        composerRevision++
        val key = _replyState.value.target?.commentId
        draftVersions[key] = (draftVersions[key] ?: 0) + 1
        _replyState.update { it.copy(draft = limited, errorMessage = null) }
    }

    fun submitReply() {
        val post = _uiState.value.post ?: return
        val composer = _replyState.value
        val content = composer.draft.trim()
        if ((content.isEmpty() && composer.attachment == null) || composer.isSending) return
        val session = replySession ?: return
        val token = ++replyToken
        val revision = composerRevision
        val target = composer.target
        val draftVersion = draftVersions[target?.commentId] ?: 0
        _replyState.update { it.copy(isSending = true, errorMessage = null) }
        replyJob = viewModelScope.launch {
            try {
                var pictureId = composer.attachment?.uploadedId
                if (composer.attachment != null && pictureId == null) {
                    _replyState.update { it.copy(isUploading = true) }
                    val upload = replyRepository.upload(session, composer.attachment.image)
                    currentCoroutineContext().ensureActive()
                    if (token != replyToken || replyRepository.currentSession() != session) return@launch
                    when (upload) {
                        is ApiResult.Success -> {
                            pictureId = upload.data
                            val attachment = composer.attachment.copy(uploadedId = upload.data)
                            replyAttachments[target?.commentId] = attachment
                            if (_replyState.value.target == target) _replyState.update { it.copy(attachment = attachment) }
                        }
                        is ApiResult.Error -> {
                            if (_replyState.value.target == target) _replyState.update { it.copy(errorMessage = upload.message) }
                            else messageChannel.trySend(upload.message)
                            return@launch
                        }
                        ApiResult.Loading -> {
                            _replyState.update { it.copy(errorMessage = "图片上传未完成，请重试") }
                            return@launch
                        }
                    }
                    _replyState.update { it.copy(isUploading = false) }
                }
                val result = replyRepository.submit(session, targetIdOf(post), target?.commentId, content, pictureId)
                currentCoroutineContext().ensureActive()
                if (token != replyToken || replyRepository.currentSession() != session) return@launch
                when (result) {
                    is ApiResult.Success -> {
                        val created = result.data?.takeIf { composer.attachment == null }?.let {
                            WeiboCommentDto(id = it.id, text = it.text, createdAt = it.createdAt,
                                user = WeiboUserDto(id = it.authorId, screenName = it.authorName,
                                    avatarLarge = it.authorAvatar),
                                replyComment = target?.let { reply -> WeiboCommentDto(id = reply.commentId,
                                    user = WeiboUserDto(screenName = reply.screenName)) })
                        }
                        if (target == null) {
                            if (created != null) publishedComments[requireNotNull(created.id)] = created
                            _uiState.update { it.copy(comments = mergePublishedComments(it.comments),
                                hasLoadedComments = true, selectedTab = DetailSubTab.COMMENTS) }
                        } else if (created != null) {
                            publishedReplies.getOrPut(target.rootId) { linkedMapOf() }[requireNotNull(created.id)] = created
                            val root = findComment(target.rootId)
                            if (root != null) {
                                val current = commentRepliesOf(root)
                                _repliesStates.update { it + (repliesKeyOf(root) to current.copy(
                                    replies = mergeCommentReplies(listOf(created), current.replies, target.rootId))) }
                            }
                        }
                        if ((draftVersions[target?.commentId] ?: 0) == draftVersion) {
                            replyDrafts[target?.commentId] = ""
                            replyAttachments.remove(target?.commentId)
                            if (_replyState.value.target == target) _replyState.update {
                                it.copy(draft = "", attachment = null, isOpen = if (composerRevision == revision) false else it.isOpen,
                                    errorMessage = null)
                            }
                        }
                        if (created == null) {
                            if (target == null) loadComments() else {
                                val root = findComment(target.rootId)
                                if (root == null) loadComments() else {
                                    val key = repliesKeyOf(root)
                                    repliesTokens[key] = (repliesTokens[key] ?: 0) + 1
                                    repliesJobs.remove(key)?.cancel()
                                    _repliesStates.update { it + (key to commentRepliesOf(root).copy(
                                        loading = false, loadedPage = false, nextMaxId = 0, nextMaxIdType = 0)) }
                                    loadCommentReplies(root)
                                }
                            }
                        }
                        messageChannel.trySend("回复成功")
                    }
                    is ApiResult.Error -> {
                        val error = result.message.ifBlank { "回复失败，请稍后重试" }
                        if (_replyState.value.target == target) _replyState.update { it.copy(errorMessage = error) }
                        else messageChannel.trySend(error)
                    }
                    ApiResult.Loading -> _replyState.update { it.copy(errorMessage = "回复未完成，请重试") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (token == replyToken) _replyState.update { it.copy(errorMessage = failure.message ?: "回复失败，请稍后重试") }
            } finally {
                if (token == replyToken) _replyState.update { it.copy(isSending = false, isUploading = false) }
            }
        }
    }

    private fun findComment(id: Long): WeiboCommentDto? =
        _uiState.value.comments.firstOrNull { it.id == id }

    private fun mergePublishedComments(
        incoming: List<WeiboCommentDto>,
        knownAtRequestStart: Set<Long> = emptySet()
    ): List<WeiboCommentDto> {
        // 写入之后才发起的读取可用服务端快照替换；写入前的旧读取须保留新回复。
        knownAtRequestStart.forEach(publishedComments::remove)
        val local = publishedComments.values.toList().asReversed()
        return (local.filter { pending -> incoming.none { it.id == pending.id } } + incoming).distinctBy { it.listKey }
    }

    /**
     * 下拉刷新：重新拉取正文与当前子页签第一页。
     *
     * 刷新会先让该分区的在途请求失效，旧结果不可能再追加到刷新后的列表里。
     */
    fun refresh() {
        val current = _uiState.value
        if (current.isRefreshing || current.isLoading) return
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                var refreshedPost = current.post
                when (val res = dataSource.post(postId)) {
                    is ApiResult.Success -> {
                        refreshedPost = res.data
                        _uiState.update { it.copy(post = res.data, errorMessage = null) }
                        PostDetailCache.put(res.data)
                    }

                    is ApiResult.Error -> {
                        if (_uiState.value.post == null) {
                            _uiState.update { it.copy(errorMessage = res.message) }
                        } else {
                            messageChannel.trySend(res.message.ifBlank { "刷新失败，请稍后重试" })
                        }
                    }

                    ApiResult.Loading -> Unit
                }

                val targetId = targetIdOf(refreshedPost)
                currentCoroutineContext().ensureActive()
                when (_uiState.value.selectedTab) {
                    DetailSubTab.COMMENTS -> refreshComments(targetId)
                    DetailSubTab.REPOSTS -> loadReposts(targetId = targetId)?.join()
                    DetailSubTab.ATTITUDES -> loadAttitudes(targetId = targetId)?.join()
                }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun loadComments(targetPostId: String? = null, hotSort: Boolean = _uiState.value.sortByHot) {
        val knownWrites = publishedComments.keys.toSet()
        val idToQuery = targetPostId ?: targetId()
        commentsRequestedForId = idToQuery
        commentsToken += 1
        val token = commentsToken
        commentsJob?.cancel()
        _uiState.update { it.copy(isCommentsLoading = true) }
        commentsJob = viewModelScope.launch {
            try {
                when (val res = dataSource.comments(idToQuery, hotSort)) {
                    is ApiResult.Success -> {
                        if (token != commentsToken) return@launch
                        _uiState.update { current ->
                            val incoming = mergePublishedComments(res.data.data, knownWrites)
                            // 只有内容真的换了才触发淡入，否则重复拉同一份数据会闪一下
                            val changed = incoming.map { it.listKey } != current.comments.map { it.listKey }
                            val version = current.contentVersion
                            current.copy(
                                comments = incoming,
                                // 只有热门流会下发评论管理信息
                                commentsApprovalOnly = if (hotSort) {
                                    res.data.approvalCommentsOnly
                                } else {
                                    current.commentsApprovalOnly
                                },
                                contentVersion = if (changed) {
                                    version.copy(comments = version.comments + 1)
                                } else {
                                    version
                                }
                            )
                        }
                    }
                    // 接口失败与「本来就没有内容」统一展示为空占位
                    is ApiResult.Error -> Unit
                    ApiResult.Loading -> Unit
                }
            } finally {
                // 已被更新的请求取代时不要收起 loading，否则新请求还在路上就会去画空态
                if (token == commentsToken) {
                    // 首屏请求真正结束（成功或失败）后才允许展示「没有内容」占位
                    _uiState.update { it.copy(isCommentsLoading = false, hasLoadedComments = true) }
                }
            }
        }
    }

    /** 读取某条评论的楼中楼状态；界面通常从 [repliesStates] 自行取用。 */
    fun commentRepliesOf(comment: WeiboCommentDto): CommentRepliesState =
        _repliesStates.value[repliesKeyOf(comment)] ?: initialCommentReplies(comment).let { state ->
            state.copy(replies = mergeCommentReplies(publishedReplies[comment.id]?.values?.toList().orEmpty(), state.replies, comment.id))
        }

    /** 楼中楼状态在 Map 里的 key；界面按 key 取用，避免每帧重算。 */
    fun repliesKeyOf(comment: WeiboCommentDto): String =
        comment.id?.toString() ?: comment.listKey

    /** 加载下一页回复：同一评论同时只跑一个请求，旧的游标结果不会再追加。 */
    fun loadCommentReplies(comment: WeiboCommentDto) {
        val commentId = comment.id ?: return
        val key = repliesKeyOf(comment)
        val current = commentRepliesOf(comment)
        if (current.loading) return
        val token = (repliesTokens[key] ?: 0) + 1
        repliesTokens[key] = token
        _repliesStates.update { it + (key to current.copy(loading = true, error = null)) }
        repliesJobs.remove(key)?.cancel()
        val postKey = targetId()
        repliesJobs[key] = viewModelScope.launch {
            try {
                when (val res = dataSource.replies(
                    postId = postKey,
                    commentId = commentId,
                    maxId = current.nextMaxId,
                    maxIdType = current.nextMaxIdType
                )) {
                    is ApiResult.Success -> {
                        if (repliesTokens[key] != token) return@launch
                        _repliesStates.update { it + (key to commentRepliesOf(comment).withPage(res.data, commentId)) }
                    }

                    is ApiResult.Error -> {
                        if (repliesTokens[key] != token) return@launch
                        _repliesStates.update {
                            it + (
                                key to commentRepliesOf(comment).copy(
                                    loading = false,
                                    error = res.message.ifBlank { "加载回复失败，请重试" }
                                )
                                )
                        }
                    }

                    ApiResult.Loading -> {
                        if (repliesTokens[key] == token) {
                            _repliesStates.update { it + (key to commentRepliesOf(comment).copy(loading = false)) }
                        }
                    }
                }
            } finally {
                if (repliesTokens[key] == token) repliesJobs.remove(key)
            }
        }
    }

    /** 评论整体换数据（下拉刷新）后，旧的楼中楼分页与缓存一并作废。 */
    private fun clearReplies() {
        repliesJobs.values.forEach(Job::cancel)
        repliesJobs.clear()
        repliesTokens.keys.toList().forEach { key -> repliesTokens[key] = (repliesTokens[key] ?: 0) + 1 }
        _repliesStates.value = emptyMap()
    }

    fun loadReposts(page: Int = 1, targetId: String? = null): Job? {
        val idToQuery = targetId ?: targetId()
        repostsToken += 1
        val token = repostsToken
        repostsJob?.cancel()
        _uiState.update { it.copy(isRepostsLoading = true) }
        return viewModelScope.launch {
            try {
                when (val res = dataSource.reposts(idToQuery, page)) {
                    is ApiResult.Success -> {
                        if (token != repostsToken) return@launch
                        _uiState.update { current ->
                            current.copy(
                                reposts = if (page == 1) {
                                    res.data.data
                                } else {
                                    (current.reposts + res.data.data).distinctBy { it.listKey }
                                },
                                repostsPage = page
                            )
                        }
                    }

                    is ApiResult.Error -> {
                        if (token == repostsToken && _uiState.value.reposts.isNotEmpty()) {
                            messageChannel.trySend(res.message.ifBlank { "转发刷新失败，请稍后重试" })
                        }
                    }

                    ApiResult.Loading -> Unit
                }
            } finally {
                if (token == repostsToken) _uiState.update { it.copy(isRepostsLoading = false) }
            }
        }.also { repostsJob = it }
    }

    fun loadAttitudes(page: Int = 1, targetId: String? = null): Job? {
        val idToQuery = targetId ?: targetId()
        attitudesToken += 1
        val token = attitudesToken
        attitudesJob?.cancel()
        _uiState.update { it.copy(isAttitudesLoading = true) }
        return viewModelScope.launch {
            try {
                when (val res = dataSource.attitudes(idToQuery, page)) {
                    is ApiResult.Success -> {
                        if (token != attitudesToken) return@launch
                        _uiState.update { current ->
                            current.copy(
                                attitudes = if (page == 1) {
                                    res.data.data
                                } else {
                                    (current.attitudes + res.data.data).distinctBy { it.listKey }
                                },
                                attitudesPage = page
                            )
                        }
                    }

                    is ApiResult.Error -> {
                        if (token == attitudesToken && _uiState.value.attitudes.isNotEmpty()) {
                            messageChannel.trySend(res.message.ifBlank { "点赞列表刷新失败，请稍后重试" })
                        }
                    }

                    ApiResult.Loading -> Unit
                }
            } finally {
                if (token == attitudesToken) _uiState.update { it.copy(isAttitudesLoading = false) }
            }
        }.also { attitudesJob = it }
    }

    private fun requestDetail() {
        detailToken += 1
        val token = detailToken
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            try {
                when (val res = dataSource.post(postId)) {
                    is ApiResult.Success -> {
                        if (token != detailToken) return@launch
                        _uiState.update { it.copy(post = res.data, errorMessage = null) }
                        PostDetailCache.put(res.data)
                        // 本地浏览历史：同一条微博只保留最近一次
                        recordHistory(res.data)
                        // 命中缓存时页签可能已经用同一个 id 拉过一次评论，这里按 id 去重
                        val resolvedId = targetIdOf(res.data)
                        if (commentsRequestedForId != resolvedId) loadComments(resolvedId)
                    }

                    is ApiResult.Error -> {
                        if (token == detailToken && _uiState.value.post == null) {
                            _uiState.update { it.copy(errorMessage = res.message) }
                        }
                    }

                    ApiResult.Loading -> Unit
                }
            } finally {
                if (token == detailToken) _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /** 刷新评论区并等待结果，确保刷新指示器覆盖整轮请求。 */
    private suspend fun refreshComments(targetId: String) {
        val knownWrites = publishedComments.keys.toSet()
        commentsRequestedForId = targetId
        commentsToken += 1
        val token = commentsToken
        // 先丢掉还在路上的排序请求，避免它晚回来覆盖刷新结果
        commentsJob?.cancel()
        commentsJob = null
        _uiState.update { it.copy(isCommentsLoading = false) }
        when (val res = dataSource.comments(targetId, _uiState.value.sortByHot)) {
            is ApiResult.Success -> {
                if (token != commentsToken) return
                val hotSort = _uiState.value.sortByHot
                _uiState.update { current ->
                    current.copy(
                        comments = mergePublishedComments(res.data.data, knownWrites),
                        commentsApprovalOnly = if (hotSort) {
                            res.data.approvalCommentsOnly
                        } else {
                            current.commentsApprovalOnly
                        },
                        // 刷新：既要淡入一次，也要重建评论子树（清掉展开 / 收起状态）
                        contentVersion = current.contentVersion.let {
                            DetailContentVersion(
                                comments = it.comments + 1,
                                commentsSubtree = it.commentsSubtree + 1
                            )
                        }
                    )
                }
                // 评论内容已经整体换掉，旧的楼中楼分页不能再挂在新评论上
                clearReplies()
            }

            is ApiResult.Error -> if (_uiState.value.comments.isNotEmpty()) {
                messageChannel.trySend(res.message.ifBlank { "评论刷新失败，请稍后重试" })
            }

            ApiResult.Loading -> Unit
        }
        // 刷新这条路绕过了 loadComments，同样要标记「首屏已结束」
        if (token == commentsToken) _uiState.update { it.copy(hasLoadedComments = true) }
    }

    private fun targetId(): String =
        _uiState.value.post?.idStr ?: _uiState.value.post?.id?.toString() ?: postId

    private fun targetIdOf(target: WeiboStatusDto?): String =
        target?.idStr ?: target?.id?.toString() ?: postId
}
