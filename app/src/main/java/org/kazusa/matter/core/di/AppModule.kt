package org.kazusa.matter.core.di

import androidx.lifecycle.SavedStateHandle
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.datastore.MediaPreferences
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.core.network.KtorClientFactory
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.cache.TimelineCacheManager
import org.kazusa.matter.data.cache.TimelinePositionManager
import org.kazusa.matter.data.local.MatterDatabase
import org.kazusa.matter.data.repository.AuthRepository
import org.kazusa.matter.data.repository.BrowsingHistoryRepository
import org.kazusa.matter.data.repository.FavoritesRepository
import org.kazusa.matter.data.repository.FollowRepository
import org.kazusa.matter.data.repository.GroupRepository
import org.kazusa.matter.data.repository.HotFeedRepository
import org.kazusa.matter.data.repository.HotSearchRepository
import org.kazusa.matter.data.repository.SearchHistoryRepository
import org.kazusa.matter.data.repository.TimelineRepository
import org.kazusa.matter.data.repository.MessageRepository
import org.kazusa.matter.data.repository.UserRepository
import org.kazusa.matter.data.repository.WeiboHotSearchRepository
import org.kazusa.matter.data.repository.WeiboMessageRepository
import org.kazusa.matter.data.repository.WeiboUserRepository
import org.kazusa.matter.data.model.ReplyImageReader
import org.kazusa.matter.core.media.AndroidReplyImageReader
import org.kazusa.matter.data.repository.EmojiRepository
import org.kazusa.matter.data.model.EmojiPackageStore
import org.kazusa.matter.core.network.OnlineEmojiSource
import org.kazusa.matter.core.network.WeiboEmojiApi
import org.kazusa.matter.core.platform.AndroidWebCookieStore
import org.kazusa.matter.core.session.WebSessionCookieStore
import org.kazusa.matter.core.media.AndroidEmojiPackageStore
import org.kazusa.matter.ui.detail.OnlineEmojiViewModel
import java.io.File
import org.kazusa.matter.data.repository.ReplyRepository
import org.kazusa.matter.data.repository.LikeRepository
import org.kazusa.matter.ui.detail.PostDetailViewModel
import org.kazusa.matter.ui.group.GroupViewModel
import org.kazusa.matter.ui.history.BrowsingHistoryViewModel
import org.kazusa.matter.ui.hotfeed.HotFeedViewModel
import org.kazusa.matter.ui.login.LoginViewModel
import org.kazusa.matter.ui.message.ChatViewModel
import org.kazusa.matter.ui.message.MessageCenterViewModel
import org.kazusa.matter.ui.profile.MyPostsViewModel
import org.kazusa.matter.ui.profile.UserListKind
import org.kazusa.matter.ui.profile.UserListViewModel
import org.kazusa.matter.ui.profile.UserProfileViewModel
import org.kazusa.matter.ui.search.HotSearchBoardViewModel
import org.kazusa.matter.ui.search.SearchResultViewModel
import org.kazusa.matter.ui.saved.SavedPostsViewModel
import org.kazusa.matter.ui.theme.ThemePreferences
import org.kazusa.matter.ui.timeline.TimelineViewModel

val dataModule = module {
    single { AuthPreferences(androidContext()) }
    single<WebSessionCookieStore> { AndroidWebCookieStore() }
    single { ThemePreferences(androidContext()) }
    single { MediaPreferences(androidContext()) }
    single<Json> { KtorClientFactory.jsonConfig }
    single<HttpClient> { KtorClientFactory.createClient() } onClose { it?.close() }
    single { WeiboApiService(get(), get()) }
    single<OnlineEmojiSource> { WeiboEmojiApi(get()) }
    single<EmojiPackageStore> { AndroidEmojiPackageStore(File(androidContext().filesDir, "emoji-packs")) }
    single { EmojiRepository(get(), get()) }

    single { MatterDatabase.getInstance(androidContext()) }
    single { get<MatterDatabase>().browsingHistoryDao() }
    single { get<MatterDatabase>().searchHistoryDao() }
    single { TimelineCacheManager(androidContext()) }
    single { TimelinePositionManager(androidContext()) }

    single { AuthRepository(get(), get(), get()) }
    single { TimelineRepository(get(), get(), get(), get()) }
    single { GroupRepository(get(), get()) }
    single { HotFeedRepository(get(), get()) }
    single { BrowsingHistoryRepository(get(), get(), get()) }
    single { SearchHistoryRepository(get(), get()) }
    // 点赞的唯一事实来源：跨页面共享状态 + 取消/失败回滚都由它负责，换账号时由导航根清空
    single { LikeRepository(get<WeiboApiService>()) }
    single<ReplyImageReader> { AndroidReplyImageReader(androidContext()) }
    single { ReplyRepository(get<WeiboApiService>(), get<AuthPreferences>(), get<ReplyImageReader>()) }
    // 关注关系的唯一事实来源：用户列表与博主主页读同一份状态，换账号时同样由导航根清空
    single { FollowRepository(get<UserRepository>()) }

    // 用户域 / 热搜域的数据入口：页面 ViewModel 依赖接口，不直接持有 WeiboApiService
    single<UserRepository> { WeiboUserRepository(get()) }
    single<HotSearchRepository> { WeiboHotSearchRepository(get()) }

    // 私信域：独立协议（api.weibo.com/webim），与会话绑定的 Cookie 每次请求时重新读取
    single<MessageRepository> { WeiboMessageRepository(get(), get()) }

    // 由导航根按会话请求并记忆；不能声明为应用单例，否则收藏状态会跨账号复用。
    factory { parameters -> FavoritesRepository(parameters.get<UserSession>(), get()) }
}

val viewModelModule = module {
    viewModel { OnlineEmojiViewModel(get()) }
    viewModel { LoginViewModel(get()) }
    viewModel { TimelineViewModel(get(), get()) }
    viewModel { GroupViewModel(get()) }
    viewModel { HotFeedViewModel(get()) }
    viewModel { BrowsingHistoryViewModel(get()) }
    viewModel { parameters -> SavedPostsViewModel(parameters.get<FavoritesRepository>(), get()) }
    viewModel { parameters ->
        UserListViewModel(
            uid = parameters.get<String>(),
            kind = parameters.get<UserListKind>(),
            userRepository = get(),
            followRepository = get()
        )
    }
    // 按 NavEntry 作用域创建：主页 → 正文 → 返回时状态仍在（见 UserProfileViewModel 注释）
    viewModel { parameters ->
        UserProfileViewModel(
            uid = parameters.get<String>(),
            userRepository = get(),
            historyRepository = get(),
            followRepository = get()
        )
    }
    // 热搜榜状态：榜单数据与加载策略在 ViewModel，菜单 / 滚动仍在界面
    viewModel { HotSearchBoardViewModel(get()) }
    // 同上：结果页点进正文再返回时，分栏与各分栏的滚动位置都还在
    viewModel { parameters ->
        SearchResultViewModel(
            initialQuery = parameters.get<String>(),
            apiService = get(),
            searchHistoryRepository = get(),
            // 由 Koin 从 ViewModel 的 CreationExtras 创建，真正参与进程恢复；
            // 不要用构造函数默认的 SavedStateHandle()，那只是内存对象
            savedStateHandle = parameters.get<SavedStateHandle>()
        )
    }
    // 正文页：正文 / 评论 / 转发 / 赞四个分区的业务状态与请求生命周期
    viewModel { parameters ->
        PostDetailViewModel(
            postId = parameters.get<String>(),
            apiService = get(),
            historyRepository = get(),
            likeRepository = get(),
            replyRepository = get()
        )
    }
    // 「我的微博」：列表与分页由 ViewModel 持有，失败可重试
    viewModel { parameters ->
        MyPostsViewModel(
            uid = parameters.get<String>(),
            userRepository = get()
        )
    }
    // 消息页：私信 / @我 / 评论 / 赞 四个分区各自分页，状态都在同一个 ViewModel 里
    viewModel { MessageCenterViewModel(get()) }
    // 会话详情：按 NavEntry 作用域创建，返回列表再进来时聊天记录仍在
    viewModel { parameters ->
        ChatViewModel(
            peerUid = parameters.get<String>(),
            peerName = parameters.get<String>(),
            repository = get()
        )
    }
}

val appModule = module {
    includes(dataModule, viewModelModule)
}
