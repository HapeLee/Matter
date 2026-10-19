package org.kazusa.matter.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.HotFeedCategory
import org.kazusa.matter.data.model.HotFeedPage

/**
 * 热门流数据源。
 *
 * 一律使用登录会话的 Cookie（`sessionFlow.cookieHeader`）：
 * 热门分类（`ajax/feed/allGroups`）与热门流（`ajax/feed/hottimeline`）都以登录态为基准，
 * 不做游客兜底 —— 未登录时接口返回 `ok=-100`，由上层展示对应错误。
 */
class HotFeedRepository(
    private val authPreferences: AuthPreferences,
    private val apiService: WeiboApiService
) {

    suspend fun getCategories(): ApiResult<List<HotFeedCategory>> {
        return try {
            val cookie = authPreferences.sessionFlow.first().cookieHeader
            apiService.getHotFeedCategories(cookie = cookie)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.Error(message = e.localizedMessage ?: "读取登录态失败", throwable = e)
        }
    }

    /**
     * 拉取指定分类的一页热门流。
     *
     * @param sinceId 上一页的 [HotFeedPage.nextSinceId]，首页传空串
     * @param maxId 上一页的 [HotFeedPage.nextMaxId]，首页传空串
     */
    suspend fun getFeed(
        category: HotFeedCategory,
        sinceId: String = "",
        maxId: String = ""
    ): ApiResult<HotFeedPage> {
        return try {
            val cookie = authPreferences.sessionFlow.first().cookieHeader
            when (val result = apiService.getHotFeed(
                groupId = category.id,
                containerId = category.containerId,
                sinceId = sinceId,
                maxId = maxId,
                cookie = cookie
            )) {
                is ApiResult.Success -> ApiResult.Success(
                    result.data.copy(posts = result.data.posts.distinctBy { it.listKey })
                )

                is ApiResult.Error -> ApiResult.Error(result.code, result.message, result.throwable)
                ApiResult.Loading -> ApiResult.Loading
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.Error(message = e.localizedMessage ?: "读取登录态失败", throwable = e)
        }
    }
}
