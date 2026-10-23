package org.kazusa.matter.data.repository

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.OnlineEmojiSource
import org.kazusa.matter.data.model.*

/** 公共表情资源跨账号共享；目录、下载和安装各自只提交完整成功结果。 */
class EmojiRepository(private val source: OnlineEmojiSource, private val store: EmojiPackageStore) {
    private val _state = MutableStateFlow(OnlineEmojiState())
    val state: StateFlow<OnlineEmojiState> = _state.asStateFlow()
    private val _images = MutableStateFlow<Map<String, String>>(emptyMap())
    val images: StateFlow<Map<String, String>> = _images.asStateFlow()
    private val restoreMutex = Mutex()
    private val refreshMutex = Mutex()
    private val downloadMutex = Mutex()
    private val publishMutex = Mutex()
    private val activeDownloads = mutableSetOf<String>()
    private var restored = false
    private var loaded = false

    suspend fun restore() = restoreMutex.withLock {
        if (restored) return@withLock
        val installed = store.restore()
        val catalog = store.restoreCatalog()
        _state.update { it.copy(installed = installed, catalog = catalog) }
        updateImages(catalog + installed)
        restored = true
    }

    suspend fun refresh(force: Boolean = false) {
        restore()
        if ((!force && loaded) || !refreshMutex.tryLock()) return
        _state.update { it.copy(refreshing = true, error = null) }
        try {
            when (val catalog = source.catalog()) {
                is ApiResult.Success -> {
                    val cacheError = try {
                        store.saveCatalog(catalog.data)
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        "表情目录已加载，但缓存保存失败，请稍后刷新重试"
                    }
                    publishMutex.withLock {
                        _state.update { it.copy(catalog = catalog.data, error = cacheError) }
                        updateImages(catalog.data + _state.value.installed)
                    }
                }
                is ApiResult.Error -> _state.update { it.copy(error = catalog.message) }
                ApiResult.Loading -> Unit
            }
            when (val result = source.packages()) {
                is ApiResult.Success -> {
                    _state.update { it.copy(packages = result.data) }
                    loaded = _state.value.error == null
                }
                is ApiResult.Error -> _state.update { it.copy(error = result.message) }
                ApiResult.Loading -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _state.update { it.copy(error = failure.message ?: "表情目录保存失败，请重试") }
        } finally {
            _state.update { it.copy(refreshing = false) }
            refreshMutex.unlock()
        }
    }

    suspend fun install(id: String) {
        restore()
        val started = downloadMutex.withLock {
            val pack = _state.value.packages.firstOrNull { it.id == id } ?: return@withLock false
            if (_state.value.installed.any { it.id == id && it.version == pack.version } || !activeDownloads.add(id)) {
                return@withLock false
            }
            _state.update { it.copy(downloading = it.downloading + id, packageErrors = it.packageErrors - id) }
            true
        }
        if (!started) return
        try {
            while (true) {
                val pack = _state.value.packages.firstOrNull { it.id == id } ?: return
                if (_state.value.installed.any { it.id == id && it.version == pack.version }) return
                val download = source.download(pack)
                currentCoroutineContext().ensureActive()
                // 目录刷新可能发生在下载或解包期间；旧版本结果不再覆盖当前目录。
                if (_state.value.packages.firstOrNull { it.id == id } != pack) continue
                when (download) {
                    is ApiResult.Success -> {
                        val installed = store.install(pack, download.data)
                        currentCoroutineContext().ensureActive()
                        val published = publishMutex.withLock {
                            if (_state.value.packages.firstOrNull { it.id == id } != pack) return@withLock false
                            _state.update { it.copy(installed = it.installed.filterNot { old -> old.id == id } + installed) }
                            updateImages(_state.value.catalog + _state.value.installed)
                            true
                        }
                        if (published) return
                    }
                    is ApiResult.Error -> {
                        _state.update { it.copy(packageErrors = it.packageErrors + (id to download.message)) }
                        return
                    }
                    ApiResult.Loading -> return
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _state.update { it.copy(packageErrors = it.packageErrors + (id to (failure.message ?: "安装失败，请重试"))) }
        } finally {
            withContext(NonCancellable) {
                downloadMutex.withLock {
                    activeDownloads.remove(id)
                    _state.update { it.copy(downloading = it.downloading - id) }
                }
            }
        }
    }

    private fun updateImages(installed: List<InstalledEmojiPackage>) {
        // 热门包优先，其次当前基础目录，再按扩展包的新旧次序补齐代码和别名。
        val images = linkedMapOf<String, String>()
        installed.sortedWith(compareBy<InstalledEmojiPackage> { if (it.id == "com.sina.default") 0 else if (it.id.startsWith("catalog.")) 1 else 2 }.thenByDescending { it.id }).forEach { pack ->
            pack.emojis.forEach { emoji ->
                (listOf(emoji.code) + emoji.aliases).forEach { code -> if (code !in images) images[code] = emoji.image }
            }
        }
        _images.value = images
    }
}
