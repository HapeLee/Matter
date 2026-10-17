package org.kazusa.matter.core.network

sealed interface ApiResult<out T> {
    data class Success<out T>(val data: T) : ApiResult<T>
    data class Error(val code: Int? = null, val message: String, val throwable: Throwable? = null) : ApiResult<Nothing>
    data object Loading : ApiResult<Nothing>
}
