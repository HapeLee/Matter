package org.kazusa.matter.data.repository

import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.AlbumPhotoPage
import org.kazusa.matter.data.model.ArticleFeedPage
import org.kazusa.matter.data.model.FollowingListTab
import org.kazusa.matter.data.model.UserAlbum
import org.kazusa.matter.data.model.UserProfileDetail
import org.kazusa.matter.data.model.UserTimelineResult
import org.kazusa.matter.data.model.VideoFeedPage
import org.kazusa.matter.data.model.WeiboUserDto
import org.kazusa.matter.data.model.WeiboUserListPage

/**
 * 用户域的数据入口：博主资料、博主动态、相册 / 视频 / 文章分栏、关注关系。
 *
 * 页面 ViewModel 只依赖这个接口，不再直接持有 [WeiboApiService]：
 * - 分页、并发与取消规则留在 ViewModel；
 * - 网络实现集中在这里，后续要加缓存 / 重试也只改这一处；
 * - 单测可以直接替换实现，不必构造 Ktor 客户端。
 */
interface UserRepository {
    suspend fun profile(uid: String): ApiResult<WeiboUserDto>
    suspend fun profileDetail(uid: String): ApiResult<UserProfileDetail>
    suspend fun profileTimeline(uid: String, page: Int): ApiResult<UserTimelineResult>
    suspend fun albums(uid: String): ApiResult<List<UserAlbum>>
    suspend fun albumPhotos(containerId: String, cursor: String): ApiResult<AlbumPhotoPage>
    suspend fun videos(uid: String, cursor: String): ApiResult<VideoFeedPage>
    suspend fun articles(uid: String, page: Int): ApiResult<ArticleFeedPage>
    suspend fun follow(uid: String): ApiResult<Unit>
    suspend fun unfollow(uid: String): ApiResult<Unit>

    /** 「我的关注」分栏列表（全部 / 最新发博 / 经常访问）。 */
    suspend fun following(uid: String, page: Int, tab: FollowingListTab): ApiResult<WeiboUserListPage>

    /** 「我的粉丝」列表。 */
    suspend fun followers(uid: String, page: Int): ApiResult<WeiboUserListPage>
}

/** 基于微博接口的实现；这里只做转发，具体解析仍在 [WeiboApiService]。 */
class WeiboUserRepository(private val apiService: WeiboApiService) : UserRepository {
    override suspend fun profile(uid: String): ApiResult<WeiboUserDto> =
        apiService.getUserProfile(uid)

    override suspend fun profileDetail(uid: String): ApiResult<UserProfileDetail> =
        apiService.getUserProfileDetail(uid)

    override suspend fun profileTimeline(uid: String, page: Int): ApiResult<UserTimelineResult> =
        apiService.getUserTimeline(uid, page = page)

    override suspend fun albums(uid: String): ApiResult<List<UserAlbum>> =
        apiService.getUserAlbums(uid)

    override suspend fun albumPhotos(containerId: String, cursor: String): ApiResult<AlbumPhotoPage> =
        apiService.getAlbumPhotos(containerId, cursor)

    override suspend fun videos(uid: String, cursor: String): ApiResult<VideoFeedPage> =
        apiService.getUserVideos(uid, cursor)

    override suspend fun articles(uid: String, page: Int): ApiResult<ArticleFeedPage> =
        apiService.getUserArticles(uid, page)

    override suspend fun follow(uid: String): ApiResult<Unit> = apiService.followUser(uid)

    override suspend fun unfollow(uid: String): ApiResult<Unit> = apiService.unfollowUser(uid)

    override suspend fun following(
        uid: String,
        page: Int,
        tab: FollowingListTab
    ): ApiResult<WeiboUserListPage> = apiService.getFollowingUsers(uid, page, tab = tab)

    override suspend fun followers(uid: String, page: Int): ApiResult<WeiboUserListPage> =
        apiService.getFollowerUsers(uid, page)
}

/**
 * 合并两份博主资料：新数据优先，缺失时保留旧值。
 *
 * 主页会并发拿到「详细资料」与「动态列表里的用户快照」，两者完整度不同；
 * 合并规则集中在这里，避免每个页面各写一份。
 */
internal fun mergeUserProfile(base: WeiboUserDto?, incoming: WeiboUserDto): WeiboUserDto {
    if (base == null) return incoming
    return base.copy(
        screenName = incoming.screenName.ifBlank { base.screenName },
        profileImageUrl = incoming.profileImageUrl ?: base.profileImageUrl,
        avatarLarge = incoming.avatarLarge ?: base.avatarLarge,
        avatarHd = incoming.avatarHd ?: base.avatarHd,
        followersCount = incoming.followersCount.ifBlank { base.followersCount },
        friendsCount = incoming.friendsCount.ifBlank { base.friendsCount },
        followCount = incoming.followCount.ifBlank { base.followCount },
        statusesCount = incoming.statusesCount.ifBlank { base.statusesCount },
        description = incoming.description ?: base.description,
        verified = incoming.verified || base.verified,
        verifiedReason = incoming.verifiedReason ?: base.verifiedReason,
        coverImagePhone = incoming.coverImagePhone ?: base.coverImagePhone,
        location = incoming.location ?: base.location,
        gender = incoming.gender ?: base.gender,
        domain = incoming.domain ?: base.domain,
        url = incoming.url ?: base.url,
        createdAt = incoming.createdAt ?: base.createdAt
    )
}
