package org.kazusa.matter.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.datastore.MediaPreferences
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.core.session.WebSessionCookieStore
import org.kazusa.matter.core.session.SessionExpiredBus
import org.kazusa.matter.data.repository.AuthRepository
import org.kazusa.matter.data.repository.SessionProbe
import org.kazusa.matter.data.repository.SearchHistoryRepository
import org.kazusa.matter.ui.theme.ThemePreferences
import org.kazusa.matter.data.repository.FavoritesRepository
import org.kazusa.matter.data.repository.FollowRepository
import org.kazusa.matter.data.repository.GroupRepository
import org.kazusa.matter.ui.components.composer.OnlineEmojiViewModel
import org.kazusa.matter.core.util.LocalOnlineEmojiImages
import org.kazusa.matter.data.repository.LikeRepository
import org.kazusa.matter.data.repository.PublishRepository
import org.kazusa.matter.data.repository.RepostRepository
import org.kazusa.matter.ui.composer.post.PostComposerScreen
import org.kazusa.matter.ui.composer.repost.RepostComposerViewModel
import org.kazusa.matter.ui.composer.repost.RepostSheet
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.ui.components.LocalCommentLikeStates
import org.kazusa.matter.ui.components.LocalFavoritesRepository
import org.kazusa.matter.ui.components.LocalLikeRepository
import org.kazusa.matter.ui.components.LocalPostLikeStates
import org.kazusa.matter.ui.components.LocalRepostRequest
import org.kazusa.matter.ui.components.LocalRepostStates
import org.kazusa.matter.ui.components.LocalUserActionScope
import org.kazusa.matter.ui.components.LocalWeiboApiService
import org.kazusa.matter.ui.components.MatterSnackbar
import org.kazusa.matter.ui.saved.SavedPostsScreen
import org.kazusa.matter.ui.saved.SavedPostsViewModel
import org.kazusa.matter.data.local.BrowsingCategory
import org.kazusa.matter.ui.detail.PostDetailCache
import org.kazusa.matter.ui.detail.PostDetailScreen
import org.kazusa.matter.ui.history.BrowsingHistoryScreen
import org.kazusa.matter.ui.history.BrowsingHistoryViewModel
import org.kazusa.matter.ui.group.GroupListScreen
import org.kazusa.matter.ui.group.GroupViewModel
import org.kazusa.matter.ui.login.LoginScreen
import org.kazusa.matter.ui.login.LoginViewModel
import org.kazusa.matter.ui.main.MainTabsScreen
import org.kazusa.matter.ui.media.morph.LocalImageMorphState
import org.kazusa.matter.ui.media.morph.rememberImageMorphState
import org.kazusa.matter.ui.media.morph.ImageMorphOverlay
import org.kazusa.matter.ui.media.ImageViewerScreen
import org.kazusa.matter.ui.media.VideoPlayerScreen
import org.kazusa.matter.ui.message.ChatScreen
import org.kazusa.matter.ui.message.MessagesScreen
import org.kazusa.matter.ui.onboarding.OnboardingScreen
import org.kazusa.matter.ui.profile.MyPostsScreen
import org.kazusa.matter.ui.profile.UserListKind
import org.kazusa.matter.ui.profile.UserListScreen
import org.kazusa.matter.ui.profile.UserListViewModel
import org.kazusa.matter.ui.profile.UserProfileScreen
import org.kazusa.matter.ui.search.SearchResultScreen
import org.kazusa.matter.ui.settings.SettingsScreen
import org.kazusa.matter.ui.settings.OpenSourceLibrariesScreen
import org.kazusa.matter.ui.theme.AppearanceScreen
import org.kazusa.matter.ui.timeline.TimelineViewModel
import org.kazusa.matter.ui.topic.TopicStatusScreen
import kotlinx.coroutines.launch

@Composable
fun MatterNavGraph() {
    val authPreferences = koinInject<AuthPreferences>()
    val authRepository = koinInject<AuthRepository>()
    val webCookieStore = koinInject<WebSessionCookieStore>()
    val themePreferences = koinInject<ThemePreferences>()
    val mediaPreferences = koinInject<MediaPreferences>()
    val apiService = koinInject<WeiboApiService>()
    val searchHistoryRepository = koinInject<SearchHistoryRepository>()
    val likeRepository = koinInject<LikeRepository>()
    val followRepository = koinInject<FollowRepository>()
    val groupRepository = koinInject<GroupRepository>()
    val onlineEmojiViewModel: OnlineEmojiViewModel = koinViewModel()
    val onlineEmojiImages by onlineEmojiViewModel.images.collectAsStateWithLifecycle()
    // 转发面板挂在导航根：卡片与正文页共用一份，列表项不需要各自持有 sheet
    val repostRepository = koinInject<RepostRepository>()
    val repostViewModel: RepostComposerViewModel = koinViewModel()
    val repostStates by repostRepository.postStates.collectAsStateWithLifecycle()
    val publishRepository = koinInject<PublishRepository>()
    val rootSnackbar = remember { SnackbarHostState() }
    val requestRepost: (WeiboStatusDto) -> Unit = remember(repostViewModel) {
        { post -> repostViewModel.open(post) }
    }
    val koin = getKoin()
    // 点赞状态在仓库里是只读 StateFlow；这里收集一次下发给列表项，避免每张卡片各自订阅
    val postLikeStates by likeRepository.postStates.collectAsStateWithLifecycle()
    val commentLikeStates by likeRepository.commentStates.collectAsStateWithLifecycle()
    val session by authPreferences.sessionFlow.collectAsStateWithLifecycle(initialValue = null)
    val isOnboardingCompleted by authPreferences.isOnboardingCompletedFlow.collectAsStateWithLifecycle(initialValue = null)
    val coroutineScope = rememberCoroutineScope()

    // 换账号时清空会话内的即时状态：点赞 / 关注覆盖、正文预存缓存与分组缓存都带着账号身份
    LaunchedEffect(session?.uid) {
        likeRepository.clear()
        followRepository.clear()
        PostDetailCache.clear()
        groupRepository.clear()
        repostRepository.clear()
        repostViewModel.reset()
    }

    // 转发成功 / 失败的一次性提示：面板可能从任意页面唤起，统一在根上出示
    LaunchedEffect(repostViewModel) {
        repostViewModel.messages.collect { message ->
            rootSnackbar.currentSnackbarData?.dismiss()
            rootSnackbar.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }

    // 发微博成功后编辑页会立即返回，提示留给根：用户此时可能已经回到任意标签页。
    // 仓库给的是只增计数，根这一层始终在组合中，按「比已处理的大」提示一次即可。
    val publishedCount by publishRepository.publishedCount.collectAsStateWithLifecycle()
    var handledPublishCount by remember { mutableLongStateOf(publishedCount) }
    LaunchedEffect(publishedCount) {
        if (publishedCount > handledPublishCount) {
            handledPublishCount = publishedCount
            rootSnackbar.currentSnackbarData?.dismiss()
            rootSnackbar.showSnackbar(message = "发布成功", duration = SnackbarDuration.Short)
        }
    }

    // 状态加载中保护，防止 DataStore 首次空流导致欢迎页闪现
    if (isOnboardingCompleted == null || session == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    // 路由分发决策：
    // 1. 只有首次安装且未完成引导时进入 Onboarding；
    // 2. 已登录直接进入 MainTabs；
    // 3. 退出登录或未登录进入 Login，绝不重复触发 Onboarding。
    val startDestination: Screen = when {
        !isOnboardingCompleted!! -> Screen.Onboarding
        session?.isLoggedIn == true -> Screen.MainTabs
        else -> Screen.Login
    }

    val backStack = rememberNavBackStack(ScreenEntry(startDestination))
    val navigate: (Screen) -> Unit = { screen -> backStack.add(ScreenEntry(screen)) }
    val navigateBack: () -> Unit = {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
    val replaceRoot: (Screen) -> Unit = { screen ->
        backStack.clear()
        backStack.add(ScreenEntry(screen))
    }
    val navigateToPostDetail: (org.kazusa.matter.data.model.WeiboStatusDto?, String, Boolean) -> Unit = { post, idStr, scrollToComments ->
        if (post != null) {
            PostDetailCache.put(post)
        }
        if (idStr.isNotBlank()) {
            navigate(Screen.PostDetail(id = idStr, scrollToComments = scrollToComments))
        }
    }
    val morphState = rememberImageMorphState()
    // 记录本次导航真正被点击的共享元素目标，避免同 key 元素在一个页面里重复参与动画
    val sharedElementTarget = remember { SharedElementTarget() }
    val favoritesRepository = remember(session?.uid, session?.cookieHeader, session?.isLoggedIn, koin) {
        koin.get<FavoritesRepository> { parametersOf(requireNotNull(session)) }
    }

    // 会话失效统一收口：任一接口确认 -100 且当前确实处于登录态时，清会话
    // （含 WebView Cookie，防自动登回旧账号）并回到登录页。游客与未登录的
    // -100 不算失效；清掉 DataStore 后 session 变空，后续重复事件自然被挡。
    val sessionExpiredBus = koinInject<SessionExpiredBus>()
    LaunchedEffect(Unit) {
        sessionExpiredBus.expired.collect {
            val current = session ?: return@collect
            if (current.isLoggedIn && current.uid.isNotBlank() && current.uid != "guest") {
                authRepository.logout()
                replaceRoot(Screen.Login)
            }
        }
    }

    // 会话探活：冷启动与回前台时验证一次登录凭据，服务端明确判定未登录才走
    // 统一登出；网络异常不误报。5 分钟节流，避免频繁切换前后台打爆 /api/config。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var lastProbeElapsed by remember { mutableLongStateOf(0L) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastProbeElapsed >= SESSION_PROBE_INTERVAL_MS) {
                    lastProbeElapsed = now
                    coroutineScope.launch {
                        if (authRepository.probeSession() == SessionProbe.EXPIRED) {
                            sessionExpiredBus.notifySessionExpired()
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CompositionLocalProvider(
        LocalOnlineEmojiImages provides onlineEmojiImages,
        LocalImageMorphState provides morphState,
        LocalSharedElementTarget provides sharedElementTarget,
        LocalFavoritesRepository provides favoritesRepository,
        LocalLikeRepository provides likeRepository,
        LocalPostLikeStates provides postLikeStates,
        LocalCommentLikeStates provides commentLikeStates,
        LocalRepostStates provides repostStates,
        LocalRepostRequest provides requestRepost,
        LocalUserActionScope provides coroutineScope,
        LocalWeiboApiService provides apiService
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            @OptIn(ExperimentalSharedTransitionApi::class)
            SharedTransitionLayout {
                CompositionLocalProvider(
                    LocalSharedTransitionScope provides this
                ) {
                    NavDisplay(
                        backStack = backStack,
                        modifier = Modifier.fillMaxSize(),
                        onBack = navigateBack,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator()
                        ),
                        sharedTransitionScope = this@SharedTransitionLayout,
                        transitionSpec = { NavTransitions.forward(this) },
                        popTransitionSpec = { NavTransitions.pop(this) },
                        predictivePopTransitionSpec = { _ -> NavTransitions.predictivePop(this) },
                        entryProvider = entryProvider {
                            entry<ScreenEntry>(
                                clazzContentKey = { it.id },
                                metadata = {
                                    when (it.screen) {
                                        is Screen.ImageViewer -> NavTransitions.mediaMetadata
                                        is Screen.VideoPlayer -> NavTransitions.videoMetadata
                                        // 微博正文与博主主页都承载共享元素（卡片形变 / 头像放大），
                                        // 统一用交叉渐变，避免整页横滑与头像飞行动画互相拉扯
                                        is Screen.PostDetail,
                                        is Screen.UserProfile -> NavTransitions.detailMetadata
                                        else -> emptyMap()
                                    }
                                }
                            ) { destination ->

                                CompositionLocalProvider(
                                    LocalNavAnimatedVisibilityScope provides LocalNavAnimatedContentScope.current
                                ) {
                                    when (val args = destination.screen) {
                                        Screen.Onboarding -> {
                                            OnboardingScreen(
                                                onComplete = {
                                                    coroutineScope.launch {
                                                        authRepository.completeOnboarding()
                                                        replaceRoot(Screen.Login)
                                                    }
                                                }
                                            )
                                        }

                                        Screen.Login -> {
                                            val loginViewModel: LoginViewModel = koinViewModel()
                                            LoginScreen(
                                                viewModel = loginViewModel,
                                                webCookieStore = webCookieStore,
                                                onLoginSuccess = {
                                                    replaceRoot(Screen.MainTabs)
                                                }
                                            )
                                        }

                                        Screen.MainTabs -> {
                                            run {
                                                MainTabsScreen(
                                                    onNavigateToGroups = {
                                                        navigate(Screen.GroupList)
                                                    },
                                                    onNavigateToSettings = {
                                                        navigate(Screen.Settings)
                                                    },
                                                    onNavigateToAppearance = {
                                                        navigate(Screen.Appearance)
                                                    },
                                                    onNavigateToHistory = {
                                                        navigate(Screen.BrowsingHistory)
                                                    },
                                                    onNavigateToSavedPosts = {
                                                        navigate(Screen.SavedPosts)
                                                    },
                                                    onNavigateToMyPosts = {
                                                        navigate(Screen.MyPosts)
                                                    },
                                                    onNavigateToFollowing = {
                                                        navigate(Screen.MyFollowing)
                                                    },
                                                    onNavigateToFollowers = {
                                                        navigate(Screen.MyFollowers)
                                                    },
                                                    onNavigateToMessages = {
                                                        navigate(Screen.Messages)
                                                    },
                                                    onNavigateToComposer = {
                                                        navigate(Screen.PostComposer)
                                                    },
                                                    onUserClick = { uid ->
                                                        if (uid != null) {
                                                            navigate(Screen.UserProfile(uid = uid.toString()))
                                                        }
                                                    },
                                                    onPostClick = { post ->
                                                        val idStr = post.idStr ?: post.mid ?: post.id?.toString() ?: ""
                                                        navigateToPostDetail(post, idStr, false)
                                                    },
                                                    onCommentClick = { post ->
                                                        val idStr = post.idStr ?: post.mid ?: post.id?.toString() ?: ""
                                                        navigateToPostDetail(post, idStr, true)
                                                    },
                                                    onImageClick = { urls, index ->
                                                        navigate(Screen.ImageViewer(initialIndex = index, urls = urls))
                                                    },
                                                    onVideoClick = { url, title, streams, coverUrl, mid ->
                                                        navigate(Screen.VideoPlayer(videoUrl = url, title = title, streams = streams, coverUrl = coverUrl, mid = mid))
                                                    },
                                                    onMentionClick = { mentionName ->
                                                        navigate(Screen.UserProfile(uid = mentionName, screenName = mentionName))
                                                    },
                                                    onTopicClick = { topicName ->
                                                        // 点话题 → 话题微博页（100303type=532 模板，热门 / 实时）
                                                        navigate(Screen.TopicStatus(keyword = topicName, title = "#$topicName#"))
                                                    },
                                                    onSearchSubmit = { keyword ->
                                                        // 榜单条目 / 相关搜索提交后进入独立的搜索结果页
                                                        navigate(Screen.SearchResult(query = keyword, title = keyword))
                                                    },
                                                    onOpenSearch = {
                                                        // 动态页顶栏搜索按钮：空关键词进入，页内展示搜索记录
                                                        navigate(Screen.SearchResult(query = ""))
                                                    },
                                                    onLogout = {
                                                        // 重新登录只回退至登录页，绝不闪现或重置欢迎页
                                                        replaceRoot(Screen.Login)
                                                    }
                                                )
                                            }
                                        }

                                        Screen.MyPosts -> {
                                            MyPostsScreen(
                                                uid = session?.uid.orEmpty(),
                                                onBackClick = { navigateBack() },
                                                onPostClick = { post ->
                                                    val idStr = post.idStr ?: post.mid ?: post.id?.toString() ?: ""
                                                    navigateToPostDetail(post, idStr, false)
                                                },
                                                onCommentClick = { post ->
                                                    val idStr = post.idStr ?: post.mid ?: post.id?.toString() ?: ""
                                                    navigateToPostDetail(post, idStr, true)
                                                },
                                                onUserClick = { uid ->
                                                    if (uid != null) {
                                                        navigate(Screen.UserProfile(uid = uid.toString()))
                                                    }
                                                },
                                                onImageClick = { urls, index ->
                                                    navigate(Screen.ImageViewer(initialIndex = index, urls = urls))
                                                },
                                                onVideoClick = { url, title, streams, coverUrl, mid ->
                                                    navigate(Screen.VideoPlayer(videoUrl = url, title = title, streams = streams, coverUrl = coverUrl, mid = mid))
                                                },
                                                onMentionClick = { mentionName ->
                                                    navigate(Screen.UserProfile(uid = mentionName, screenName = mentionName))
                                                },
                                                onTopicClick = { topicName ->
                                                    navigate(Screen.TopicStatus(keyword = topicName, title = "#$topicName#"))
                                                }
                                            )
                                        }

                                        Screen.MyFollowing -> {
                                            val userListViewModel: UserListViewModel = koinViewModel {
                                                parametersOf(
                                                    session?.uid.orEmpty(),
                                                    UserListKind.FOLLOWING,
                                                    apiService
                                                )
                                            }
                                            UserListScreen(
                                                kind = UserListKind.FOLLOWING,
                                                viewModel = userListViewModel,
                                                onBackClick = { navigateBack() },
                                                onUserClick = { uid ->
                                                    if (uid != null) {
                                                        navigate(Screen.UserProfile(uid = uid.toString()))
                                                    }
                                                }
                                            )
                                        }

                                        Screen.MyFollowers -> {
                                            val userListViewModel: UserListViewModel = koinViewModel {
                                                parametersOf(
                                                    session?.uid.orEmpty(),
                                                    UserListKind.FOLLOWERS,
                                                    apiService
                                                )
                                            }
                                            UserListScreen(
                                                kind = UserListKind.FOLLOWERS,
                                                viewModel = userListViewModel,
                                                onBackClick = { navigateBack() },
                                                onUserClick = { uid ->
                                                    if (uid != null) {
                                                        navigate(Screen.UserProfile(uid = uid.toString()))
                                                    }
                                                }
                                            )
                                        }

                                        Screen.Messages -> {
                                            MessagesScreen(
                                                onBackClick = { navigateBack() },
                                                onConversationClick = { conversation ->
                                                    navigate(
                                                        Screen.Chat(
                                                            peerUid = conversation.peerUid,
                                                            peerName = conversation.peerName
                                                        )
                                                    )
                                                },
                                                onNoticeClick = { notice ->
                                                    // 提醒优先打开对应微博；解析不到 statusId 时退回对方主页
                                                    val statusId = notice.statusId
                                                    when {
                                                        !statusId.isNullOrBlank() -> navigate(
                                                            Screen.PostDetail(id = statusId)
                                                        )
                                                        notice.peerUid.isNotBlank() -> navigate(
                                                            Screen.UserProfile(
                                                                uid = notice.peerUid,
                                                                screenName = notice.peerName
                                                            )
                                                        )
                                                    }
                                                }
                                            )
                                        }

                                        is Screen.Chat -> {
                                            ChatScreen(
                                                peerUid = args.peerUid,
                                                peerName = args.peerName,
                                                onBackClick = { navigateBack() },
                                                onOpenImage = { url ->
                                                    navigate(Screen.ImageViewer(initialIndex = 0, urls = listOf(url)))
                                                }
                                            )
                                        }

                                        is Screen.UserProfile -> {
                                            UserProfileScreen(
                                                uid = args.uid,
                                                initialScreenName = args.screenName,
                                                currentUid = session?.uid.orEmpty(),
                                                onBackClick = {
                                                    navigateBack()
                                                },
                                                onUserClick = { uid ->
                                                    if (uid != null) {
                                                        navigate(Screen.UserProfile(uid = uid.toString()))
                                                    }
                                                },
                                                onPostClick = { post ->
                                                    val idStr = post.idStr ?: post.mid ?: post.id?.toString() ?: ""
                                                    navigateToPostDetail(post, idStr, false)
                                                },
                                                onImageClick = { urls, index ->
                                                    navigate(Screen.ImageViewer(initialIndex = index, urls = urls))
                                                },
                                                onVideoClick = { url, title, streams, coverUrl, mid ->
                                                    navigate(Screen.VideoPlayer(videoUrl = url, title = title, streams = streams, coverUrl = coverUrl, mid = mid))
                                                },
                                                onMentionClick = { mentionName ->
                                                    navigate(Screen.UserProfile(uid = mentionName, screenName = mentionName))
                                                },
                                                onTopicClick = { topicName ->
                                                    navigate(Screen.TopicStatus(keyword = topicName, title = "#$topicName#"))
                                                }
                                            )
                                        }

                                        is Screen.SearchResult -> {
                                            SearchResultScreen(
                                                query = args.query,
                                                title = args.title,
                                                searchHistoryRepository = searchHistoryRepository,
                                                onBackClick = {
                                                    navigateBack()
                                                },
                                                onUserClick = { uid ->
                                                    if (uid != null) {
                                                        navigate(Screen.UserProfile(uid = uid.toString()))
                                                    }
                                                },
                                                onPostClick = { post ->
                                                    val idStr = post.idStr ?: post.mid ?: post.id?.toString() ?: ""
                                                    navigateToPostDetail(post, idStr, false)
                                                },
                                                onImageClick = { urls, index ->
                                                    navigate(Screen.ImageViewer(initialIndex = index, urls = urls))
                                                },
                                                onVideoClick = { url, title, streams, coverUrl, mid ->
                                                    navigate(Screen.VideoPlayer(videoUrl = url, title = title, streams = streams, coverUrl = coverUrl, mid = mid))
                                                },
                                                onMentionClick = { mention ->
                                                    navigate(Screen.UserProfile(uid = mention, screenName = mention))
                                                },
                                                onTopicClick = { topicName ->
                                                    navigate(Screen.TopicStatus(keyword = topicName, title = "#$topicName#"))
                                                },
                                                onChipClick = { keyword ->
                                                    // 点「相关搜索 / 热门话题」词条 → 用该词条再搜一次（压栈可返回）
                                                    navigate(Screen.SearchResult(query = keyword, title = keyword))
                                                }
                                            )
                                        }

                                        is Screen.TopicStatus -> {
                                            TopicStatusScreen(
                                                keyword = args.keyword,
                                                topicId = args.topicId,
                                                title = args.title.ifBlank { args.keyword },
                                                searchHistoryRepository = searchHistoryRepository,
                                                onBackClick = {
                                                    navigateBack()
                                                },
                                                onUserClick = { uid ->
                                                    if (uid != null) {
                                                        navigate(Screen.UserProfile(uid = uid.toString()))
                                                    }
                                                },
                                                onPostClick = { post ->
                                                    val idStr = post.idStr ?: post.mid ?: post.id?.toString() ?: ""
                                                    navigateToPostDetail(post, idStr, false)
                                                },
                                                onImageClick = { urls, index ->
                                                    navigate(Screen.ImageViewer(initialIndex = index, urls = urls))
                                                },
                                                onVideoClick = { url, title, streams, coverUrl, mid ->
                                                    navigate(Screen.VideoPlayer(videoUrl = url, title = title, streams = streams, coverUrl = coverUrl, mid = mid))
                                                },
                                                onMentionClick = { mention ->
                                                    navigate(Screen.UserProfile(uid = mention, screenName = mention))
                                                },
                                                onTopicClick = { topicName ->
                                                    navigate(Screen.TopicStatus(keyword = topicName, title = "#$topicName#"))
                                                }
                                            )
                                        }

                                        Screen.GroupList -> {
                                            val groupViewModel: GroupViewModel = koinViewModel()
                                            val timelineViewModel: TimelineViewModel = koinViewModel()
                                            GroupListScreen(
                                                viewModel = groupViewModel,
                                                onSelectGroup = { gid, groupName ->
                                                    timelineViewModel.setGroup(gid, groupName)
                                                    navigateBack()
                                                },
                                                onBackClick = {
                                                    navigateBack()
                                                }
                                            )
                                        }

                                        Screen.BrowsingHistory -> {
                                            val historyViewModel: BrowsingHistoryViewModel = koinViewModel()
                                            BrowsingHistoryScreen(
                                                viewModel = historyViewModel,
                                                onBackClick = {
                                                    navigateBack()
                                                },
                                                onOpen = { item ->
                                                    val targetId = item.statusId.orEmpty()
                                                    if (targetId.isNotBlank()) {
                                                        if (item.category and BrowsingCategory.USER != 0) {
                                                            navigate(
                                                                Screen.UserProfile(
                                                                    uid = targetId,
                                                                    screenName = item.username.orEmpty()
                                                                )
                                                            )
                                                        } else {
                                                            navigate(Screen.PostDetail(id = targetId))
                                                        }
                                                    }
                                                }
                                            )
                                        }

                                        Screen.SavedPosts -> {
                                            val savedPostsViewModel: SavedPostsViewModel = koinViewModel {
                                                parametersOf(favoritesRepository)
                                            }
                                            SavedPostsScreen(
                                                viewModel = savedPostsViewModel,
                                                onBackClick = navigateBack,
                                                onPostClick = { post ->
                                                    val id = post.idStr ?: post.mid ?: post.id?.toString().orEmpty()
                                                    navigateToPostDetail(post, id, false)
                                                },
                                                onCommentClick = { post ->
                                                    val id = post.idStr ?: post.mid ?: post.id?.toString().orEmpty()
                                                    navigateToPostDetail(post, id, true)
                                                },
                                                onUserClick = { uid ->
                                                    if (uid != null) navigate(Screen.UserProfile(uid.toString()))
                                                },
                                                onImageClick = { urls, index ->
                                                    navigate(Screen.ImageViewer(initialIndex = index, urls = urls))
                                                },
                                                onVideoClick = { url, title, streams, coverUrl, mid ->
                                                    navigate(Screen.VideoPlayer(videoUrl = url, title = title, streams = streams, coverUrl = coverUrl, mid = mid))
                                                },
                                                onMentionClick = { name -> navigate(Screen.UserProfile(name, name)) },
                                                onTopicClick = { topic -> navigate(Screen.TopicStatus(topic, title = "#$topic#")) }
                                            )
                                        }

                                        Screen.Settings -> {
                                            SettingsScreen(
                                                themePreferences = themePreferences,
                                                mediaPreferences = mediaPreferences,
                                                onNavigateToOpenSource = {
                                                    navigate(Screen.OpenSourceLibraries)
                                                },
                                                onNavigateToAppearance = {
                                                    navigate(Screen.Appearance)
                                                },
                                                onBackClick = {
                                                    navigateBack()
                                                }
                                            )
                                        }

                                        Screen.OpenSourceLibraries -> {
                                            OpenSourceLibrariesScreen(onBackClick = navigateBack)
                                        }

                                        Screen.Appearance -> {
                                            AppearanceScreen(
                                                themePreferences = themePreferences,
                                                onBackClick = {
                                                    navigateBack()
                                                }
                                            )
                                        }

                                        Screen.PostComposer -> {
                                            PostComposerScreen(
                                                onDismiss = { navigateBack() },
                                                onPublished = { navigateBack() }
                                            )
                                        }

                                        is Screen.PostDetail -> {
                                            run {
                                                PostDetailScreen(
                                                    postId = args.id,
                                                    scrollToComments = args.scrollToComments,
                                                    onBackClick = {
                                                        navigateBack()
                                                    },
                                                    onUserClick = { uid ->
                                                        if (uid != null) {
                                                            navigate(Screen.UserProfile(uid = uid.toString()))
                                                        }
                                                    },
                                                    onImageClick = { urls, index ->
                                                        navigate(Screen.ImageViewer(initialIndex = index, urls = urls))
                                                    },
                                                    onVideoClick = { url, title, streams, coverUrl, mid ->
                                                        navigate(Screen.VideoPlayer(videoUrl = url, title = title, streams = streams, coverUrl = coverUrl, mid = mid))
                                                    },
                                                    onMentionClick = { mentionName ->
                                                        navigate(Screen.UserProfile(uid = mentionName, screenName = mentionName))
                                                    },
                                                    onTopicClick = { topicName ->
                                                        navigate(Screen.TopicStatus(keyword = topicName, title = "#$topicName#"))
                                                    },
                                                    onPostClick = { clickedPost ->
                                                        val idStr = clickedPost.idStr ?: clickedPost.mid ?: clickedPost.id?.toString() ?: ""
                                                        navigateToPostDetail(clickedPost, idStr, false)
                                                    }
                                                )
                                            }
                                        }


                                        is Screen.ImageViewer -> {
                                            run {
                                                ImageViewerScreen(
                                                    initialIndex = args.initialIndex,
                                                    urls = args.urls,
                                                    mediaPreferences = mediaPreferences,
                                                    onBackClick = {
                                                        navigateBack()
                                                    }
                                                )
                                            }
                                        }

                                        is Screen.VideoPlayer -> {
                                            VideoPlayerScreen(
                                                videoUrl = args.videoUrl,
                                                title = args.title,
                                                coverUrl = args.coverUrl,
                                                mid = args.mid,
                                                streams = args.streams,
                                                onBackClick = {
                                                    navigateBack()
                                                }
                                            )
                                        }
                                        is Screen.Timeline -> error("Timeline is hosted by MainTabs")
                                    }
                                }
                            }
                        }
                    )
                }
            }

            ImageMorphOverlay(
                morphState = morphState,
                mediaPreferences = mediaPreferences
            )

            // 转发面板（任意页面的卡片 / 正文页悬浮栏都可唤起）与它的一次性提示
            RepostSheet(
                state = repostViewModel.state,
                pickerState = repostViewModel.pickerState,
                onlineEmojiState = onlineEmojiViewModel.state,
                onOpenOnlineEmojis = onlineEmojiViewModel::openCatalog,
                onRefreshOnlineEmojis = onlineEmojiViewModel::refreshCatalog,
                onInstallEmojiPackage = onlineEmojiViewModel::install,
                onDismiss = repostViewModel::dismiss,
                onDraftChange = repostViewModel::updateDraft,
                onAddImage = repostViewModel::addImage,
                onRemoveImage = repostViewModel::removeImage,
                onAlsoCommentChange = repostViewModel::setAlsoComment,
                onVisibilityChange = repostViewModel::setVisibility,
                onOpenPicker = repostViewModel::openPicker,
                onPickerQueryChange = repostViewModel::updatePickerQuery,
                onPickerDismiss = repostViewModel::dismissPicker,
                onSubmit = repostViewModel::submit
            )
            SnackbarHost(
                hostState = rootSnackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) { data ->
                MatterSnackbar(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text(
                        text = data.visuals.message,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/** 会话探活最小间隔：频繁切换前后台时不重复打 /api/config。 */
private const val SESSION_PROBE_INTERVAL_MS = 5 * 60 * 1000L
