package org.kazusa.matter.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.repository.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _countdown = MutableStateFlow(0)
    val countdown: StateFlow<Int> = _countdown.asStateFlow()

    private var countdownJob: Job? = null

    /**
     * 发送短信验证码
     */
    fun sendSmsCode(phone: String) {
        val cleanPhone = phone.trim()
        if (cleanPhone.length != 11 || !cleanPhone.all { it.isDigit() }) {
            _uiState.value = LoginUiState.Error("请输入正确的11位手机号码")
            return
        }

        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading("正在发送验证码...")
            when (val result = authRepository.sendSmsCode(cleanPhone)) {
                is ApiResult.Success -> {
                    _uiState.value = LoginUiState.CodeSent(result.data)
                    startCountdown()
                }
                is ApiResult.Error -> {
                    _uiState.value = LoginUiState.Error(result.message)
                }
                ApiResult.Loading -> {}
            }
        }
    }

    /**
     * 短信验证码登录
     */
    fun loginWithSms(phone: String, code: String) {
        val cleanPhone = phone.trim()
        val cleanCode = code.trim()

        if (cleanPhone.length != 11) {
            _uiState.value = LoginUiState.Error("请输入完整的手机号码")
            return
        }
        if (cleanCode.length < 4) {
            _uiState.value = LoginUiState.Error("请输入有效的验证码")
            return
        }

        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading("正在验证登录...")
            when (val result = authRepository.loginWithSms(cleanPhone, cleanCode)) {
                is ApiResult.Success -> {
                    _uiState.value = LoginUiState.Success(
                        uid = result.data.uid,
                        screenName = result.data.screenName.ifBlank { "手机用户_${cleanPhone.takeLast(4)}" }
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
     * 账号密码登录
     */
    fun loginWithPassword(username: String, password: String) {
        val cleanUsername = username.trim()
        val cleanPassword = password.trim()

        if (cleanUsername.isBlank()) {
            _uiState.value = LoginUiState.Error("请输入手机号、邮箱或微博账号")
            return
        }
        if (cleanPassword.isBlank()) {
            _uiState.value = LoginUiState.Error("请输入密码")
            return
        }

        viewModelScope.launch {
            _uiState.value = LoginUiState.Loading("正在登录...")
            when (val result = authRepository.loginWithPassword(cleanUsername, cleanPassword)) {
                is ApiResult.Success -> {
                    _uiState.value = LoginUiState.Success(
                        uid = result.data.uid,
                        screenName = result.data.screenName.ifBlank { cleanUsername }
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

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            _countdown.value = 60
            while (_countdown.value > 0) {
                delay(1000)
                _countdown.value -= 1
            }
        }
    }

    fun resetState() {
        _uiState.value = LoginUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
    }

    companion object {
        fun provideFactory(authRepository: AuthRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LoginViewModel(authRepository) as T
                }
            }
    }
}
