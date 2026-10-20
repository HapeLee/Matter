package org.kazusa.matter.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.DEFAULT_HOT_SEARCH_CONTAINER_ID
import org.kazusa.matter.data.model.HotSearchBoard
import org.kazusa.matter.data.model.HotSearchChannel
import org.kazusa.matter.data.model.REALTIME_HOT_SEARCH_CONTAINER_ID
import org.kazusa.matter.data.repository.HotSearchRepository

/**
 * 当前榜单内容，以及它属于哪个 containerid。
 *
 * 两者必须一起更新：界面据此区分「换榜」（交叉淡化、新列表从顶部开始）与
 * 「同榜刷新」（原地换数据，滚动位置不能丢）。容器切换在 [HotSearchBoardUiState.selectedContainerId]
 * 上是先落的，不能拿它做内容身份。
 */
@Immutable
data class HotSearchContent(
    val containerId: String,
    val board: HotSearchBoard
)

/**
 * 热搜榜页面状态；只读暴露，界面不再持有可变副本。
 *
 * - [board] 是当前榜单内容（含服务端下发的页面标题 / 榜单列表 / 榜段 / 入口卡）；
 * - [selectedContainerId] 决定切榜后再拉哪个榜；
 * - [lastLoadedAt] 供列表顶部「更新于 HH:mm」，只在下拉刷新 / 切榜时变化。
 */
data class HotSearchBoardUiState(
    val content: HotSearchContent = HotSearchContent(
        DEFAULT_HOT_SEARCH_CONTAINER_ID,
        HotSearchBoard()
    ),
    val selectedContainerId: String = DEFAULT_HOT_SEARCH_CONTAINER_ID,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val lastLoadedAt: Long = 0L
) {
    /** 当前展示的榜单内容。 */
    val board: HotSearchBoard get() = content.board

    /** 可切换的榜单（服务端下发）。 */
    val channels: List<HotSearchChannel> get() = board.channels

    /** 页面大标题（服务端 `title_top`，如「微博热搜」）。 */
    val pageTitle: String? get() = board.title

    /** 当前榜单，用于在按钮上标注 / 展开菜单时高亮。 */
    val selectedChannel: HotSearchChannel?
        get() = channels.firstOrNull { it.containerid == selectedContainerId }
}

/**
 * 发现页热搜榜的状态：当前榜单数据、可选榜单、加载态，以及切榜 / 刷新。
 *
 * 挂在主框架的 ViewModelStore 上，菜单展开与列表滚动仍留在 UI：
 * - 切榜按钮在悬浮胶囊（底部悬浮栏），热搜列表由 [HotSearchScreen] 渲染，
 *   两边通过同一个 ViewModel 读取「当前是哪个榜」；
 * - 榜单列表（我的 / 热搜 / 文娱 / 社会 / 同城…）全部来自服务端下发的
 *   `cardlist_head_cards[].channel_list[]`，客户端只认 containerid，不写死榜名；
 * - 默认「我的」榜为空或请求失败时回退「热搜」榜，这条加载策略也在这里。
 */
class HotSearchBoardViewModel(
    private val repository: HotSearchRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HotSearchBoardUiState())
    val uiState: StateFlow<HotSearchBoardUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /** 请求身份：切榜 / 刷新会推进它，晚到的旧响应不再写回。 */
    private var loadToken = 0

    /** 上次成功切换时用的经纬度；刷新同一榜单时沿用（同城榜需要它才有数据）。 */
    private var lastLatLon: Pair<Double, Double>? = null

    /** 首次进入发现页时调用：只加载一次。 */
    fun loadIfNeeded() {
        val current = _uiState.value
        if (!current.hasLoaded) load(current.selectedContainerId, isRefresh = false, latLon = lastLatLon)
    }

    /** 下拉刷新：重新拉当前榜单。 */
    fun refresh() {
        load(_uiState.value.selectedContainerId, isRefresh = true, latLon = lastLatLon)
    }

    /**
     * 切换榜单：换 containerid 再拉一次，并把列表滚回顶部交给调用方处理。
     *
     * @param latLon 经纬度，仅同城榜（服务端 `filter_type=region`）需要；
     *   没有定位权限 / 拿不到位置就传 null，该榜会退回服务端那句「位置信息获取失败」。
     */
    fun select(channel: HotSearchChannel, latLon: Pair<Double, Double>? = null) {
        if (channel.containerid.isBlank()) return
        val current = _uiState.value
        if (channel.containerid == current.selectedContainerId && latLon == lastLatLon) return
        lastLatLon = latLon
        _uiState.update { it.copy(selectedContainerId = channel.containerid) }
        load(channel.containerid, isRefresh = false, latLon = latLon)
    }

    private fun load(containerId: String, isRefresh: Boolean, latLon: Pair<Double, Double>?) {
        loadJob?.cancel()
        val token = ++loadToken
        _uiState.update {
            it.copy(
                isLoading = !isRefresh && !it.hasLoaded,
                isRefreshing = isRefresh
            )
        }
        loadJob = viewModelScope.launch {
            when (val result = repository.board(containerId, latLon)) {
                is ApiResult.Success -> {
                    if (token != loadToken) return@launch
                    val data = result.data
                    val hasLoaded = _uiState.value.hasLoaded
                    _uiState.update { it.copy(lastLoadedAt = System.currentTimeMillis()) }
                    if (data.isEmpty && containerId == DEFAULT_HOT_SEARCH_CONTAINER_ID && !hasLoaded) {
                        // 首屏默认「我的」榜为空（例如未登录访客模式）时兜底「热搜」榜
                        loadFallback(
                            token,
                            containerId = containerId,
                            latLon = latLon,
                            preferContent = true,
                            emptyBoard = data,
                            markLoadedWhenUnavailable = true
                        )
                    } else {
                        _uiState.update {
                            it.copy(content = HotSearchContent(containerId, data), hasLoaded = true)
                        }
                        // 当前 selectedContainerId 不在服务端榜单列表里时，对齐到「我的」榜
                        if (data.channels.isNotEmpty() &&
                            data.channels.none { it.containerid == _uiState.value.selectedContainerId }
                        ) {
                            val matchingMine = data.channels.firstOrNull {
                                it.name == "我的" || it.containerid.contains("mineband")
                            }
                            if (matchingMine != null) {
                                _uiState.update { it.copy(selectedContainerId = matchingMine.containerid) }
                            }
                        }
                    }
                }

                is ApiResult.Error -> {
                    if (token != loadToken) return@launch
                    // 首屏拉取「我的」失败（可能需要登录或网络原因）时兜底「热搜」
                    if (!_uiState.value.hasLoaded && containerId == DEFAULT_HOT_SEARCH_CONTAINER_ID) {
                        loadFallback(
                            token,
                            containerId = containerId,
                            latLon = latLon,
                            preferContent = false,
                            emptyBoard = HotSearchBoard(),
                            markLoadedWhenUnavailable = false
                        )
                    } else if (!_uiState.value.hasLoaded) {
                        // 空响应 / 失败不清已选榜单，避免切榜失败变成空白页
                        _uiState.update { it.copy(content = HotSearchContent(containerId, HotSearchBoard())) }
                    }
                }

                ApiResult.Loading -> Unit
            }
            if (token == loadToken) {
                _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
            }
        }
    }

    /**
     * 兜底拉「热搜」榜。
     *
     * @param preferContent 首屏默认榜为空时，只有兜底榜**有内容**才替换；
     *   默认榜请求失败时只要兜底成功就采纳（可能是空榜，但至少布局正常）。
     * @param emptyBoard 兜底也失败时的落点（首屏保留原始空榜 / 失败时用空榜单）。
     * @param markLoadedWhenUnavailable 兜底同样不可用时是否算「已加载」。默认榜请求失败时不算，
     *   这样下次进入页面（`loadIfNeeded`）仍会重试。
     */
    private suspend fun loadFallback(
        token: Int,
        containerId: String,
        latLon: Pair<Double, Double>?,
        preferContent: Boolean,
        emptyBoard: HotSearchBoard,
        markLoadedWhenUnavailable: Boolean
    ) {
        val fallback = repository.board(REALTIME_HOT_SEARCH_CONTAINER_ID, latLon)
        if (token != loadToken) return
        if (fallback is ApiResult.Success && (!preferContent || !fallback.data.isEmpty)) {
            _uiState.update {
                it.copy(
                    content = HotSearchContent(REALTIME_HOT_SEARCH_CONTAINER_ID, fallback.data),
                    selectedContainerId = REALTIME_HOT_SEARCH_CONTAINER_ID,
                    hasLoaded = true
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    content = HotSearchContent(containerId, emptyBoard),
                    hasLoaded = it.hasLoaded || markLoadedWhenUnavailable
                )
            }
        }
    }
}
