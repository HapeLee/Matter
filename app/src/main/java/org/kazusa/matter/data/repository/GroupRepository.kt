package org.kazusa.matter.data.repository

import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.WeiboGroupDto
import kotlinx.coroutines.flow.first

class GroupRepository(
    private val authPreferences: AuthPreferences,
    private val apiService: WeiboApiService
) {

    /**
     * 获取关注分组列表
     */
    suspend fun getGroups(): ApiResult<List<WeiboGroupDto>> {
        val session = authPreferences.sessionFlow.first()
        val cookie = session.cookieHeader
        return apiService.getFollowGroups(cookie)
    }
}
