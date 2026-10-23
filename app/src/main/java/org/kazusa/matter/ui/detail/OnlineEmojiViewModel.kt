package org.kazusa.matter.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.kazusa.matter.data.repository.EmojiRepository

class OnlineEmojiViewModel(private val repository: EmojiRepository) : ViewModel() {
    val state = repository.state
    val images = repository.images
    init { viewModelScope.launch { repository.restore() } }
    fun openCatalog() { viewModelScope.launch { repository.refresh(); repository.install("com.sina.default") } }
    fun refreshCatalog() { viewModelScope.launch { repository.refresh(force = true); repository.install("com.sina.default") } }
    fun install(id: String) { viewModelScope.launch { repository.install(id) } }
}
