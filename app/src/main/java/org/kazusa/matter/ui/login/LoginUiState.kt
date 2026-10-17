package org.kazusa.matter.ui.login

sealed interface LoginUiState {
    data object Idle : LoginUiState
    data class Loading(val message: String = "正在处理...") : LoginUiState
    data class CodeSent(val message: String = "验证码已发送") : LoginUiState
    data class Success(val uid: String, val screenName: String) : LoginUiState
    data class Error(val message: String) : LoginUiState
}
