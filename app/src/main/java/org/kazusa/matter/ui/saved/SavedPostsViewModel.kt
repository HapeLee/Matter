package org.kazusa.matter.ui.saved

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.model.UserPostCollection
import org.kazusa.matter.data.model.UserPostsPage
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.FavoritesRepository
import org.kazusa.matter.data.repository.FavoriteState
import org.kazusa.matter.data.repository.favoriteId

data class SavedPostsListState(
    val posts: List<WeiboStatusDto> = emptyList(),
    val initialized: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val nextPage: Int? = null,
    val error: String? = null,
    val refreshFailed: Boolean = false
)

data class SavedPostsUiState(
    val selected: UserPostCollection = UserPostCollection.LIKES,
    val lists: Map<UserPostCollection, SavedPostsListState> =
        UserPostCollection.entries.associateWith { SavedPostsListState() }
)

class SavedPostsViewModel internal constructor(
    private val loadPosts: suspend (UserPostCollection, Int) -> ApiResult<UserPostsPage>,
    private val favoriteState: StateFlow<FavoriteState>,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {
    constructor(repository: FavoritesRepository, savedStateHandle: SavedStateHandle = SavedStateHandle()) :
        this(repository::getPosts, repository.state, savedStateHandle)

    private val initialSelection = savedStateHandle.get<String>("collection")?.let { name ->
        UserPostCollection.entries.firstOrNull { it.name == name }
    } ?: UserPostCollection.LIKES
    private val mutableState = MutableStateFlow(SavedPostsUiState(selected = initialSelection))
    val uiState = mutableState.asStateFlow()
    private val loadJobs = mutableMapOf<UserPostCollection, Job>()

    init {
        refresh(initialSelection)
        viewModelScope.launch {
            favoriteState.map { it.revision }.distinctUntilChanged().drop(1).collect {
                val favorites = UserPostCollection.FAVORITES
                if (uiState.value.lists.getValue(favorites).initialized) {
                    updateList(favorites) { state -> state.copy(posts = state.posts.filter {
                        favoriteState.value.overrides[it.favoriteId] != false
                    }) }
                    // 删除收藏会改变服务端分页偏移，只校准收藏列表。
                    refresh(favorites)
                }
            }
        }
    }

    fun select(collection: UserPostCollection) {
        savedStateHandle["collection"] = collection.name
        mutableState.update { it.copy(selected = collection) }
        if (!uiState.value.lists.getValue(collection).initialized) refresh(collection)
    }

    fun refresh(collection: UserPostCollection = uiState.value.selected) = load(collection, refresh = true)

    fun loadMore(collection: UserPostCollection = uiState.value.selected) {
        val state = uiState.value.lists.getValue(collection)
        if (!state.refreshing && !state.loadingMore && state.nextPage != null) load(collection, refresh = false)
    }

    fun retry() {
        val collection = uiState.value.selected
        if (uiState.value.lists.getValue(collection).refreshFailed) refresh(collection) else loadMore(collection)
    }

    private fun load(collection: UserPostCollection, refresh: Boolean) {
        val page = if (refresh) 1 else uiState.value.lists.getValue(collection).nextPage ?: return
        loadJobs[collection]?.cancel()
        updateList(collection) { it.copy(initialized = true, refreshing = refresh, loadingMore = !refresh,
            error = null, refreshFailed = false) }
        loadJobs[collection] = viewModelScope.launch {
            try {
                val result = loadPosts(collection, page)
                currentCoroutineContext().ensureActive()
                when (result) {
                    is ApiResult.Success -> updateList(collection) { it.copy(
                        posts = (if (refresh) result.data.posts else it.posts + result.data.posts)
                            .distinctBy(WeiboStatusDto::listKey),
                        nextPage = result.data.nextPage
                    ) }
                    is ApiResult.Error -> updateList(collection) { it.copy(error = result.message, refreshFailed = refresh) }
                    ApiResult.Loading -> Unit
                }
            } finally {
                if (currentCoroutineContext().isActive) updateList(collection) {
                    it.copy(refreshing = false, loadingMore = false)
                }
            }
        }
    }

    private fun updateList(collection: UserPostCollection, update: (SavedPostsListState) -> SavedPostsListState) {
        mutableState.update { it.copy(lists = it.lists + (collection to update(it.lists.getValue(collection)))) }
    }
}
