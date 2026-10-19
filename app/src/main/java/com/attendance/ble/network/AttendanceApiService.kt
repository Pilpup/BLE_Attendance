package com.attendance.ble.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface AttendanceApiService {

    @POST("api/v1/auth/login")
    suspend fun login(
        @Body body: LoginRequest
    ): Response<TokenResponse>

    @POST("api/v1/auth/register")
    suspend fun register(
        @Body body: RegisterRequest
    ): Response<UserResponse>

    @GET("api/v1/attendance/courses")
    suspend fun listCourses(
        @Header("Authorization") token: String
    ): Response<List<CourseResponse>>

    @POST("api/v1/attendance/courses")
    suspend fun createCourse(
        @Header("Authorization") token: String,
        @Body body: CourseCreateRequest
    ): Response<CourseResponse>

    @POST("api/v1/attendance/sessions")
    suspend fun createSession(
        @Header("Authorization") token: String,
        @Body body: SessionCreateRequest
    ): Response<AttendanceSessionResponse>

    @POST("api/v1/attendance/sessions/{sessionId}/audit-and-finalize")
    suspend fun auditAndFinalize(
        @Header("Authorization") token: String,
        @Path("sessionId") sessionId: String,
        @Body body: MeshAuditRunRequest
    ): Response<AttendanceSessionResponse>

    @POST("api/v1/attendance/sessions/{sessionId}/submit-neighbors")
    suspend fun submitNeighbors(
        @Header("Authorization") token: String,
        @Path("sessionId") sessionId: String,
        @Body body: SubmitNeighborsRequest
    ): Response<Unit>

    @GET("api/v1/attendance/sessions/{sessionId}")
    suspend fun getSessionDetails(
        @Header("Authorization") token: String,
        @Path("sessionId") sessionId: String
    ): Response<AttendanceSessionResponse>
}
