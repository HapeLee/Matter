package org.kazusa.matter.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.UserPostsPage
import org.kazusa.matter.data.model.UserPostCollection
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.WeiboStatusDto

val WeiboStatusDto.favoriteId: String?
    get() = listOfNotNull(idStr, id?.toString(), mid).firstOrNull { value ->
        value.isNotBlank() && value.all(Char::isDigit) && value != "0"
    }

data class FavoriteState(
    val overrides: Map<String, Boolean> = emptyMap(),
    val pending: Set<String> = emptySet(),
    val revision: Int = 0
)

/** 由导航根节点按会话创建，退出或切换账号时丢弃，避免跨账号复用收藏状态。 */
class FavoritesRepository(
    private val session: UserSession,
    private val apiService: WeiboApiService
) {
    private val mutableState = MutableStateFlow(FavoriteState())
    val state = mutableState.asStateFlow()
    val canFavorite = session.isLoggedIn && session.uid.isNotBlank() &&
        session.uid != "guest" && session.cookieHeader.isNotBlank()

    suspend fun getFavorites(page: Int): ApiResult<UserPostsPage> =
        if (canFavorite) apiService.getFavorites(session.cookieHeader, page)
        else ApiResult.Error(401, "登录微博账号后即可查看收藏")

    suspend fun getPosts(collection: UserPostCollection, page: Int): ApiResult<UserPostsPage> =
        when (collection) {
            UserPostCollection.FAVORITES -> getFavorites(page)
            UserPostCollection.LIKES -> if (canFavorite) apiService.getLikedPosts(session.cookieHeader, page)
                else ApiResult.Error(401, "登录微博账号后即可查看赞过的微博")
        }

    suspend fun toggle(post: WeiboStatusDto): ApiResult<Boolean> {
        if (!canFavorite) return ApiResult.Error(401, "登录微博账号后即可收藏微博")
        val id = post.favoriteId ?: return ApiResult.Error(message = "这条微博无法收藏")
        if (id in state.value.pending) return ApiResult.Error(message = "正在更新收藏，请稍候")
        val favorite = !(state.value.overrides[id] ?: post.isFavorited)
        mutableState.update { it.copy(pending = it.pending + id) }
        try {
            return when (val result = apiService.setFavorite(session.cookieHeader, id, favorite)) {
                is ApiResult.Success -> {
                    mutableState.update { it.copy(overrides = it.overrides + (id to favorite), revision = it.revision + 1) }
                    ApiResult.Success(favorite)
                }
                is ApiResult.Error -> result
                ApiResult.Loading -> ApiResult.Error(message = "收藏操作未完成，请重试")
            }
        } finally {
            mutableState.update { it.copy(pending = it.pending - id) }
        }
    }
}
