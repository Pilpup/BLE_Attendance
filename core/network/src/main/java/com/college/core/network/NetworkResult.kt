package com.college.core.network

import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import retrofit2.Response

data class ApiErrorResponse(
    @SerializedName("detail") val detail: String? = null
)

sealed interface NetworkResult<out T> {
    data class Success<out T>(val data: T) : NetworkResult<T>
    data class Error(
        val code: Int?,
        val message: String,
        val errorPayload: ApiErrorResponse? = null
    ) : NetworkResult<Nothing>
    data class Exception(val throwable: Throwable) : NetworkResult<Nothing>

    val isSuccess: Boolean get() = this is Success
}

inline fun <T, R> NetworkResult<T>.map(transform: (T) -> R): NetworkResult<R> {
    return when (this) {
        is NetworkResult.Success -> NetworkResult.Success(transform(data))
        is NetworkResult.Error -> this
        is NetworkResult.Exception -> this
    }
}

inline fun <T> NetworkResult<T>.onSuccess(action: (T) -> Unit): NetworkResult<T> {
    if (this is NetworkResult.Success) action(data)
    return this
}

inline fun <T> NetworkResult<T>.onError(action: (code: Int?, message: String) -> Unit): NetworkResult<T> {
    if (this is NetworkResult.Error) action(code, message)
    return this
}

suspend fun <T> safeApiCall(apiCall: suspend () -> Response<T>): NetworkResult<T> {
    return try {
        val response = apiCall()
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) {
                NetworkResult.Success(body)
            } else {
                NetworkResult.Error(response.code(), "Empty response body")
            }
        } else {
            val errorBodyStr = try { response.errorBody()?.string() } catch (_: java.lang.Exception) { null }
            val errorPayload = if (!errorBodyStr.isNullOrBlank()) {
                parseErrorPayload(errorBodyStr)
            } else {
                null
            }
            val message = errorPayload?.detail ?: "HTTP ${response.code()}"
            NetworkResult.Error(response.code(), message, errorPayload)
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        NetworkResult.Exception(e)
    }
}

private fun parseErrorPayload(errorBodyStr: String): ApiErrorResponse? {
    return try {
        val jsonElement = JsonParser.parseString(errorBodyStr)
        if (jsonElement.isJsonObject) {
            val obj = jsonElement.asJsonObject
            val detailElem = obj.get("detail")
            val detail = when {
                detailElem == null || detailElem.isJsonNull -> null
                detailElem.isJsonPrimitive -> detailElem.asString
                detailElem.isJsonArray -> {
                    val arr = detailElem.asJsonArray
                    val first = arr.firstOrNull()
                    if (first != null && first.isJsonObject) {
                        first.asJsonObject.get("msg")?.asString ?: first.toString()
                    } else {
                        arr.toString()
                    }
                }
                else -> detailElem.toString()
            }
            ApiErrorResponse(detail)
        } else {
            null
        }
    } catch (_: java.lang.Exception) {
        null
    }
}
