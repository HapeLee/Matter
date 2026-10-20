package org.kazusa.matter.ui.navigation

import kotlinx.serialization.Serializable
import androidx.navigation3.runtime.NavKey

import org.kazusa.matter.data.model.VideoStreamInfo

@Serializable
sealed interface Screen : NavKey {
    @Serializable
    data object Onboarding : Screen

    @Serializable
    data object Login : Screen

    @Serializable
    data object MainTabs : Screen

    @Serializable
    data class Timeline(
        val gid: String? = null,
        val groupName: String? = null
    ) : Screen

    @Serializable
    data object GroupList : Screen

    @Serializable
    data class PostDetail(
        val id: String,
        val scrollToComments: Boolean = false
    ) : Screen

    @Serializable
    data class ImageViewer(
        val initialIndex: Int = 0,
        val urls: List<String> = emptyList()
    ) : Screen

    @Serializable
    data class VideoPlayer(
        val videoUrl: String,
        val title: String = "",
        val coverUrl: String = "",
        val mid: String = "",
        val streams: List<VideoStreamInfo> = emptyList()
    ) : Screen

    /** 浏览记录（本地 Room 表，见 BrowsingHistoryEntity） */
    @Serializable
    data object BrowsingHistory : Screen

    @Serializable
    data object SavedPosts : Screen

    @Serializable
    data object Settings : Screen

    @Serializable
    data object OpenSourceLibraries : Screen

    @Serializable
    data object Appearance : Screen

    @Serializable
    data class UserProfile(
        val uid: String,
        val screenName: String = ""
    ) : Screen

    /** 我的微博（当前登录账号发布的时间线） */
    @Serializable
    data object MyPosts : Screen

    /** 我的关注 */
    @Serializable
    data object MyFollowing : Screen

    /** 我的粉丝 */
    @Serializable
    data object MyFollowers : Screen

    /** 消息（私信会话列表） */
    @Serializable
    data object Messages : Screen

    /** 与某个人的私信记录 */
    @Serializable
    data class Chat(
        val peerUid: String,
        val peerName: String = ""
    ) : Screen

    @Serializable
    data class SearchResult(
        val query: String,
        val title: String = ""
    ) : Screen

    /**
     * 话题微博页。
     *
     * 分栏「热门 / 实时」共用 `100303type=532` 模板，实时档多一个 `&sort=time`。
     *
     * @param keyword 关键词，填充模板里的 `%s`
     * @param topicId 话题 id（`extparam.topicid`）。目前列表数据里拿不到，先留空，
     *                留空时不下发 extparam；后续可从话题接口补齐。
     */
    @Serializable
    data class TopicStatus(
        val keyword: String,
        val topicId: String = "",
        val title: String = ""
    ) : Screen
}

/** A unique identity keeps repeated visits to the same route's state independent. */
@Serializable
internal data class ScreenEntry(
    val screen: Screen,
    val id: String = java.util.UUID.randomUUID().toString()
) : NavKey
