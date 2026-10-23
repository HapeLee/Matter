package org.kazusa.matter.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import org.kazusa.matter.data.repository.AuthRepository
import org.kazusa.matter.data.repository.SearchHistoryRepository
import org.kazusa.matter.ui.theme.ThemePreferences
import org.kazusa.matter.data.repository.FavoritesRepository
import org.kazusa.matter.data.repository.FollowRepository
import org.kazusa.matter.ui.detail.OnlineEmojiViewModel
import org.kazusa.matter.core.util.LocalOnlineEmojiImages
import org.kazusa.matter.data.repository.LikeRepository
import org.kazusa.matter.ui.components.LocalCommentLikeStates
import org.kazusa.matter.ui.components.LocalFavoritesRepository
import org.kazusa.matter.ui.components.LocalLikeRepository
import org.kazusa.matter.ui.components.LocalPostLikeStates
import org.kazusa.matter.ui.components.LocalUserActionScope
import org.kazusa.matter.ui.components.LocalWeiboApiService
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
    val themePreferences = koinInject<ThemePreferences>()
    val mediaPreferences = koinInject<MediaPreferences>()
    val apiService = koinInject<WeiboApiService>()
    val searchHistoryRepository = koinInject<SearchHistoryRepository>()
    val likeRepository = koinInject<LikeRepository>()
    val followRepository = koinInject<FollowRepository>()
    val onlineEmojiViewModel: OnlineEmojiViewModel = koinViewModel()
    val onlineEmojiImages by onlineEmojiViewModel.images.collectAsStateWithLifecycle()
    val koin = getKoin()
    // 点赞状态在仓库里是只读 StateFlow；这里收集一次下发给列表项，避免每张卡片各自订阅
    val postLikeStates by likeRepository.postStates.collectAsStateWithLifecycle()
    val commentLikeStates by likeRepository.commentStates.collectAsStateWithLifecycle()
    val session by authPreferences.sessionFlow.collectAsStateWithLifecycle(initialValue = null)
    val isOnboardingCompleted by authPreferences.isOnboardingCompletedFlow.collectAsStateWithLifecycle(initialValue = null)
    val coroutineScope = rememberCoroutineScope()

    // 换账号时清空会话内的即时状态：点赞 / 关注覆盖与正文预存缓存都带着账号身份
    LaunchedEffect(session?.uid) {
        likeRepository.clear()
        followRepository.clear()
        PostDetailCache.clear()
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

    CompositionLocalProvider(
        LocalOnlineEmojiImages provides onlineEmojiImages,
        LocalImageMorphState provides morphState,
        LocalSharedElementTarget provides sharedElementTarget,
        LocalFavoritesRepository provides favoritesRepository,
        LocalLikeRepository provides likeRepository,
        LocalPostLikeStates provides postLikeStates,
        LocalCommentLikeStates provides commentLikeStates,
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
                                                onBackClick = { navigateBack() }
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
        }
    }
}
