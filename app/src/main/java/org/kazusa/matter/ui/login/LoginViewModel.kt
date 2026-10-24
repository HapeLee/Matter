package org.kazusa.matter.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.repository.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel internal constructor(
    private val loginByCookie: suspend (String) -> ApiResult<UserSession>,
    private val enterGuestSession: suspend () -> Unit
) : ViewModel() {

    constructor(authRepository: AuthRepository) : this(
        loginByCookie = authRepository::loginWithCookie,
        enterGuestSession = authRepository::saveGuestSession
    )

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /**
     * 在途登录任务。登录不可重入：验证未结束前重复触发一律忽略，
     * 否则两次登录的协程会交错写状态（Loading / Success / Error 互相覆盖）。
     */
    private var loginJob: Job? = null

    /**
     * Cookie / 凭据登录
     */
    fun loginWithCookie(cookieText: String) {
        if (cookieText.isBlank()) {
            _uiState.value = LoginUiState.Error("请输入有效的 Cookie 或 SUB 凭据")
            return
        }
        if (loginJob?.isActive == true) return

        loginJob = viewModelScope.launch {
            _uiState.value = LoginUiState.Loading("正在验证 Cookie...")
            when (val result = loginByCookie(cookieText.trim())) {
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
        if (loginJob?.isActive == true) return

        loginJob = viewModelScope.launch {
            _uiState.value = LoginUiState.Loading("进入访客模式...")
            enterGuestSession()
            _uiState.value = LoginUiState.Success(uid = "guest", screenName = "访客体验用户")
        }
    }

    fun resetState() {
        // 重置即放弃在途结果：取消后晚到的成功 / 失败不能再写回状态
        loginJob?.cancel()
        _uiState.value = LoginUiState.Idle
    }
}
