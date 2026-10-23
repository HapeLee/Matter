package org.kazusa.matter.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /**
     * Cookie / 凭据登录
     */
    fun loginWithCookie(cookieText: String) {
        if (cookieText.isBlank()) {
            _uiState.value = LoginUiState.Error("请输入有效的 Cookie 或 SUB 凭据")
            return
        }

        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading("正在验证 Cookie...")
            when (val result = authRepository.loginWithCookie(cookieText.trim())) {
                is ApiResult.Success -> {
                    _uiState.value = LoginUiState.Success(
                        uid = result.data.uid,
                        screenName = result.data.screenName
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = LoginUiState.Error(result.message)
                }
                ApiResult.Loading -> {}
            }
        }
    }

    /**
     * 访客体验模式
     */
    fun loginAsGuest() {
        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading("进入访客模式...")
            authRepository.saveGuestSession()
            _uiState.value = LoginUiState.Success(uid = "guest", screenName = "访客体验用户")
        }
    }

    fun resetState() {
        _uiState.value = LoginUiState.Idle
    }
}
