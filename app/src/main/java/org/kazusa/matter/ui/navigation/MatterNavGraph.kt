package org.kazusa.matter.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import org.kazusa.matter.core.di.AppContainer
import org.kazusa.matter.data.local.BrowsingCategory
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
import org.kazusa.matter.ui.onboarding.OnboardingScreen
import org.kazusa.matter.ui.profile.UserProfileScreen
import org.kazusa.matter.ui.search.SearchResultScreen
import org.kazusa.matter.ui.settings.SettingsScreen
import org.kazusa.matter.ui.theme.AppearanceScreen
import org.kazusa.matter.ui.topic.TopicStatusScreen
import kotlinx.coroutines.launch

@Composable
fun MatterNavGraph(
    container: AppContainer
) {
    val session by container.authPreferences.sessionFlow.collectAsStateWithLifecycle(initialValue = null)
    val isOnboardingCompleted by container.authPreferences.isOnboardingCompletedFlow.collectAsStateWithLifecycle(initialValue = null)
    val coroutineScope = rememberCoroutineScope()

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
    val morphState = rememberImageMorphState()

    CompositionLocalProvider(
        LocalImageMorphState provides morphState
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
                                        is Screen.ImageViewer, is Screen.VideoPlayer -> NavTransitions.mediaMetadata
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
                                                        container.authRepository.completeOnboarding()
                                                        replaceRoot(Screen.Login)
                                                    }
                                                }
                                            )
                                        }

                                        Screen.Login -> {
                                            val loginViewModel: LoginViewModel = viewModel(
                                                factory = LoginViewModel.provideFactory(container.authRepository)
                                            )
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
                                                    container = container,
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
                                                    onUserClick = { uid ->
                                                        if (uid != null) {
                                                            navigate(Screen.UserProfile(uid = uid.toString()))
                                                        }
                                                    },
                                                    onPostClick = { post ->
                                                        val idStr = post.idStr ?: post.mid ?: post.id?.toString() ?: ""
                                                        if (idStr.isNotBlank()) {
                                                            navigate(Screen.PostDetail(id = idStr, scrollToComments = false))
                                                        }
                                                    },
                                                    onCommentClick = { post ->
                                                        val idStr = post.idStr ?: post.mid ?: post.id?.toString() ?: ""
                                                        if (idStr.isNotBlank()) {
                                                            navigate(Screen.PostDetail(id = idStr, scrollToComments = true))
                                                        }
                                                    },
                                                    onImageClick = { urls, index ->
                                                        navigate(Screen.ImageViewer(initialIndex = index, urls = urls))
                                                    },
                                                    onVideoClick = { url, title ->
                                                        navigate(Screen.VideoPlayer(videoUrl = url, title = title))
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

                                        is Screen.UserProfile -> {
                                            UserProfileScreen(
                                                uid = args.uid,
                                                initialScreenName = args.screenName,
                                                apiService = container.apiService,
                                                historyRepository = container.browsingHistoryRepository,
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
                                                    if (idStr.isNotBlank()) {
                                                        navigate(Screen.PostDetail(id = idStr))
                                                    }
                                                },
                                                onImageClick = { urls, index ->
                                                    navigate(Screen.ImageViewer(initialIndex = index, urls = urls))
                                                },
                                                onVideoClick = { url, title ->
                                                    navigate(Screen.VideoPlayer(videoUrl = url, title = title))
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
                                                apiService = container.apiService,
                                                searchHistoryRepository = container.searchHistoryRepository,
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
                                                    if (idStr.isNotBlank()) {
                                                        navigate(Screen.PostDetail(id = idStr))
                                                    }
                                                },
                                                onImageClick = { urls, index ->
                                                    navigate(Screen.ImageViewer(initialIndex = index, urls = urls))
                                                },
                                                onVideoClick = { url, title ->
                                                    navigate(Screen.VideoPlayer(videoUrl = url, title = title))
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
                                                apiService = container.apiService,
                                                searchHistoryRepository = container.searchHistoryRepository,
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
                                                    if (idStr.isNotBlank()) {
                                                        navigate(Screen.PostDetail(id = idStr))
                                                    }
                                                },
                                                onImageClick = { urls, index ->
                                                    navigate(Screen.ImageViewer(initialIndex = index, urls = urls))
                                                },
                                                onVideoClick = { url, title ->
                                                    navigate(Screen.VideoPlayer(videoUrl = url, title = title))
                                                },
                                                onTopicClick = { topicName ->
                                                    navigate(Screen.TopicStatus(keyword = topicName, title = "#$topicName#"))
                                                }
                                            )
                                        }

                                        Screen.GroupList -> {
                                            val groupViewModel: GroupViewModel = viewModel(
                                                factory = GroupViewModel.provideFactory(container.groupRepository)
                                            )
                                            GroupListScreen(
                                                viewModel = groupViewModel,
                                                onSelectGroup = { gid, groupName ->
                                                    navigateBack()
                                                },
                                                onBackClick = {
                                                    navigateBack()
                                                }
                                            )
                                        }

                                        Screen.BrowsingHistory -> {
                                            val historyViewModel: BrowsingHistoryViewModel = viewModel(
                                                factory = BrowsingHistoryViewModel.provideFactory(
                                                    container.browsingHistoryRepository
                                                )
                                            )
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

                                        Screen.Settings -> {
                                            SettingsScreen(
                                                themePreferences = container.themePreferences,
                                                mediaPreferences = container.mediaPreferences,
                                                onNavigateToAppearance = {
                                                    navigate(Screen.Appearance)
                                                },
                                                onBackClick = {
                                                    navigateBack()
                                                }
                                            )
                                        }

                                        Screen.Appearance -> {
                                            AppearanceScreen(
                                                themePreferences = container.themePreferences,
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
                                                    apiService = container.apiService,
                                                    historyRepository = container.browsingHistoryRepository,
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
                                                    onVideoClick = { url, title ->
                                                        navigate(Screen.VideoPlayer(videoUrl = url, title = title))
                                                    },
                                                    onMentionClick = { mentionName ->
                                                        navigate(Screen.UserProfile(uid = mentionName, screenName = mentionName))
                                                    },
                                                    onTopicClick = { topicName ->
                                                        navigate(Screen.TopicStatus(keyword = topicName, title = "#$topicName#"))
                                                    }
                                                )
                                            }
                                        }

                                        is Screen.ImageViewer -> {
                                            run {
                                                ImageViewerScreen(
                                                    initialIndex = args.initialIndex,
                                                    urls = args.urls,
                                                    mediaPreferences = container.mediaPreferences,
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
                mediaPreferences = container.mediaPreferences
            )
        }
    }
}
