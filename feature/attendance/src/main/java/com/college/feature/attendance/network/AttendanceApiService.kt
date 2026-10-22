package com.college.feature.attendance.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Attendance REST surface.
 *
 * Deliberately WITHOUT @Header("Authorization") parameters: the shared
 * AuthInterceptor (from :core:network, driven by SessionManager.default)
 * attaches the bearer token automatically, and clears it on 401.
 * Guest/anonymous callables (like submit-neighbors) simply work without one.
 */
interface AttendanceApiService {

    @POST("api/v1/auth/login")
    suspend fun login(
        @Body body: LoginRequest
    ): Response<LoginResponse>

    @POST("api/v1/auth/login/verify-otp")
    suspend fun verifyLoginOtp(
        @Body body: VerifyOtpRequest
    ): Response<TokenResponse>

    @POST("api/v1/auth/register")
    suspend fun register(
        @Body body: RegisterRequest
    ): Response<OtpSentResponse>

    @POST("api/v1/auth/register/verify-otp")
    suspend fun verifyRegisterOtp(
        @Body body: VerifyOtpRequest
    ): Response<TokenResponse>

    @POST("api/v1/auth/otp/resend")
    suspend fun resendOtp(
        @Body body: ResendOtpRequest
    ): Response<OtpSentResponse>

    @GET("api/v1/attendance/courses")
    suspend fun listCourses(
    ): Response<List<CourseResponse>>

    @POST("api/v1/attendance/courses")
    suspend fun createCourse(
        @Body body: CourseCreateRequest
    ): Response<CourseResponse>

    @POST("api/v1/attendance/sessions")
    suspend fun createSession(
        @Body body: SessionCreateRequest
    ): Response<AttendanceSessionResponse>

    @POST("api/v1/attendance/sessions/{sessionId}/cancel")
    suspend fun cancelSession(
        @Path("sessionId") sessionId: String
    ): Response<AttendanceSessionResponse>

    @POST("api/v1/attendance/sessions/{sessionId}/audit-and-finalize")
    suspend fun auditAndFinalize(
        @Path("sessionId") sessionId: String,
        @Body body: MeshAuditRunRequest
    ): Response<AttendanceSessionResponse>

    @POST("api/v1/attendance/sessions/{sessionId}/submit-neighbors")
    suspend fun submitNeighbors(
        @Path("sessionId") sessionId: String,
        @Body body: SubmitNeighborsRequest
    ): Response<Unit>

    @GET("api/v1/attendance/sessions/history")
    suspend fun getSessionHistory(): Response<List<AttendanceSessionResponse>>

    @GET("api/v1/attendance/sessions/{sessionId}")
    suspend fun getSessionDetails(
        @Path("sessionId") sessionId: String
    ): Response<AttendanceSessionResponse>
}
