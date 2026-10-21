package com.college.feature.library.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Library REST surface. WITHOUT @Header("Authorization"): the shared
 * AuthInterceptor (:core:network, driven by SessionManager.default) attaches
 * the bearer token automatically for staff/developer mutations.
 *
 * The backend routes are not deployed yet — LibraryViewModel keeps an
 * in-memory catalogue as the offline mock fallback so the module works today;
 * cloud sync is attempted best-effort on every mutation.
 */
interface LibraryApiService {

    @GET("api/v1/library/books")
    suspend fun listBooks(
        @Query("search") search: String? = null,
        @Query("category") category: String? = null
    ): Response<List<BookDto>>

    @POST("api/v1/library/books")
    suspend fun addBook(
        @Body body: BookCreateRequest
    ): Response<BookDto>

    @PUT("api/v1/library/books/{bookId}")
    suspend fun updateBook(
        @Path("bookId") bookId: String,
        @Body body: BookUpdateRequest
    ): Response<BookDto>

    @DELETE("api/v1/library/books/{bookId}")
    suspend fun deleteBook(
        @Path("bookId") bookId: String
    ): Response<Unit>

    @POST("api/v1/library/books/{bookId}/reserve")
    suspend fun reserveBook(
        @Path("bookId") bookId: String
    ): Response<BookDto>
}

// ---- Wire format (snake_case once talking to the FastAPI backend) ----------

data class BookDto(
    val id: String,
    val title: String,
    val author: String,
    val isbn: String,
    val category: String,
    @SerializedName("total_copies") val totalCopies: Int,
    @SerializedName("available_copies") val availableCopies: Int,
    @SerializedName("shelf_location") val shelfLocation: String? = null
)

data class BookCreateRequest(
    val title: String,
    val author: String,
    val isbn: String,
    val category: String,
    @SerializedName("total_copies") val totalCopies: Int,
    @SerializedName("shelf_location") val shelfLocation: String? = null
)

data class BookUpdateRequest(
    val title: String,
    val author: String,
    val isbn: String,
    val category: String,
    @SerializedName("total_copies") val totalCopies: Int,
    @SerializedName("available_copies") val availableCopies: Int,
    @SerializedName("shelf_location") val shelfLocation: String? = null
)
