package org.kazusa.matter.ui.search

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.DEFAULT_HOT_SEARCH_CONTAINER_ID
import org.kazusa.matter.data.model.HotSearchBoard
import org.kazusa.matter.data.model.HotSearchChannel
import org.kazusa.matter.data.model.REALTIME_HOT_SEARCH_CONTAINER_ID

/**
 * 发现页热搜榜的状态控制器：持有当前榜单数据、可选榜单、加载态，并提供切榜 / 刷新。
 *
 * 之所以抽出来：**切榜按钮要在悬浮胶囊（底部悬浮栏）旁边**，而那份悬浮栏由
 * [org.kazusa.matter.ui.main.MainTabsScreen] 持有，热搜列表则由 [SearchScreen] 渲染 ——
 * 两边都要读写同一份「当前是哪个榜」，因此把状态提到调用方共享的这一层。
 *
 * 榜单列表（我的 / 热搜 / 文娱 / 社会 / 同城…）全部来自服务端下发的
 * `cardlist_head_cards[].channel_list[]`，客户端只认 containerid，不写死榜名。
 */
@Stable
class HotSearchBoardState(
    private val apiService: WeiboApiService,
    private val scope: CoroutineScope
) {
    /** 当前榜单内容（含服务端下发的页面标题 / 榜单列表 / 榜段 / 入口卡）。 */
    var board: HotSearchBoard by mutableStateOf(HotSearchBoard())
        private set

    /** 当前榜单的 containerid（切榜就是换它重新请求）。 */
    var selectedContainerId: String by mutableStateOf(DEFAULT_HOT_SEARCH_CONTAINER_ID)
        private set

    var isLoading: Boolean by mutableStateOf(false)
        private set

    var isRefreshing: Boolean by mutableStateOf(false)
        private set

    /** 是否至少成功加载过一次（进入页面时只拉一次，不重复请求）。 */
    var hasLoaded: Boolean by mutableStateOf(false)
        private set

    /**
     * 上次成功拿到榜单数据的时间（`System.currentTimeMillis()`），用于列表顶部「更新于 HH:mm」。
     *
     * 只在下拉刷新 / 切榜时变化：进入页面仍走 [loadIfNeeded] 的一次性闸门，不自动刷新。
     */
    var lastLoadedAt: Long by mutableStateOf(0L)
        private set

    /** 可切换的榜单（服务端下发）。 */
    val channels: List<HotSearchChannel> get() = board.channels

    /** 页面大标题（服务端 `title_top`，如「微博热搜」）。 */
    val pageTitle: String? get() = board.title

    /** 当前榜单，用于在按钮上标注 / 展开菜单时高亮。 */
    val selectedChannel: HotSearchChannel? get() = channels.firstOrNull { it.containerid == selectedContainerId }

    private var loadJob: Job? = null

    /** 上次成功切换时用的经纬度；刷新同一榜单时沿用（同城榜需要它才有数据）。 */
    private var lastLatLon: Pair<Double, Double>? = null

    /** 首次进入发现页时调用：只加载一次。 */
    fun loadIfNeeded() {
        if (!hasLoaded) load(selectedContainerId, isRefresh = false, latLon = lastLatLon)
    }

    /** 下拉刷新：重新拉当前榜单。 */
    fun refresh() {
        load(selectedContainerId, isRefresh = true, latLon = lastLatLon)
    }

    /**
     * 切换榜单：换 containerid 再拉一次，并把列表滚回顶部交给调用方处理。
     *
     * @param latLon 经纬度，仅同城榜（服务端 `filter_type=region`）需要；
     *   没有定位权限 / 拿不到位置就传 null，该榜会退回服务端那句「位置信息获取失败」。
     */
    fun select(channel: HotSearchChannel, latLon: Pair<Double, Double>? = null) {
        if (channel.containerid.isBlank()) return
        if (channel.containerid == selectedContainerId && latLon == lastLatLon) return
        lastLatLon = latLon
        selectedContainerId = channel.containerid
        load(channel.containerid, isRefresh = false, latLon = latLon)
    }

    private fun load(containerId: String, isRefresh: Boolean, latLon: Pair<Double, Double>?) {
        loadJob?.cancel()
        isLoading = !isRefresh && !hasLoaded
        isRefreshing = isRefresh
        android.util.Log.d("WeiboApi", "hotSearch load: cid=$containerId latLon=$latLon")
        loadJob = scope.launch {
            when (val result = apiService.getHotSearch(containerId = containerId, latLon = latLon)) {
                is ApiResult.Success -> {
                    val data = result.data
                    lastLoadedAt = System.currentTimeMillis()
                    if (data.isEmpty && containerId == DEFAULT_HOT_SEARCH_CONTAINER_ID && !hasLoaded) {
                        // 若首屏默认「我的」榜返回为空（例如未登录访客模式），兜底尝试加载「热搜」榜
                        val fallbackResult = apiService.getHotSearch(
                            containerId = REALTIME_HOT_SEARCH_CONTAINER_ID,
                            latLon = latLon
                        )
                        if (fallbackResult is ApiResult.Success && !fallbackResult.data.isEmpty) {
                            board = fallbackResult.data
                            selectedContainerId = REALTIME_HOT_SEARCH_CONTAINER_ID
                            hasLoaded = true
                        } else {
                            board = data
                            hasLoaded = true
                        }
                    } else {
                        board = data
                        hasLoaded = true
                        // 如果当前 selectedContainerId 不在服务端返回的 channels 列表中，且存在「我的」榜，对齐 containerId
                        if (data.channels.isNotEmpty() && data.channels.none { it.containerid == selectedContainerId }) {
                            val matchingMine = data.channels.firstOrNull { it.name == "我的" || it.containerid.contains("mineband") }
                            if (matchingMine != null) {
                                selectedContainerId = matchingMine.containerid
                            }
                        }
                    }
                }
                else -> {
                    // 若首屏拉取「我的」失败（可能需要登录或网络原因），尝试兜底「热搜」
                    if (!hasLoaded && containerId == DEFAULT_HOT_SEARCH_CONTAINER_ID) {
                        val fallbackResult = apiService.getHotSearch(
                            containerId = REALTIME_HOT_SEARCH_CONTAINER_ID,
                            latLon = latLon
                        )
                        if (fallbackResult is ApiResult.Success) {
                            board = fallbackResult.data
                            selectedContainerId = REALTIME_HOT_SEARCH_CONTAINER_ID
                            hasLoaded = true
                        } else {
                            board = HotSearchBoard()
                        }
                    } else if (!hasLoaded) {
                        // 留着上一次的榜单不覆盖，避免切榜失败变成空白页；空响应不清掉已选榜单
                        board = HotSearchBoard()
                    }
                }
            }
            isLoading = false
            isRefreshing = false
        }
    }
}
