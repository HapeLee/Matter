package org.kazusa.matter.data.repository

import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.HotSearchBoard

/**
 * 热搜榜数据入口。
 *
 * 榜单结构、默认榜回退等「取数」逻辑集中在 Repository / ViewModel；
 * 界面只关心当前榜单内容与切换动作。
 */
interface HotSearchRepository {
    suspend fun board(containerId: String, latLon: Pair<Double, Double>? = null): ApiResult<HotSearchBoard>
}

class WeiboHotSearchRepository(private val apiService: WeiboApiService) : HotSearchRepository {
    override suspend fun board(
        containerId: String,
        latLon: Pair<Double, Double>?
    ): ApiResult<HotSearchBoard> = apiService.getHotSearch(containerId = containerId, latLon = latLon)
}
