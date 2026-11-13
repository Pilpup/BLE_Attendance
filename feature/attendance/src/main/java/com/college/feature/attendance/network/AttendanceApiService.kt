package com.college.feature.attendance.network

import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface AttendanceApiService {

    @GET("api/v1/attendance/courses")
    suspend fun listCourses(
    ): Response<List<CourseResponse>>

    @GET("api/v1/attendance/courses/{courseId}/syllabus")
    suspend fun getCourseSyllabus(
        @Path("courseId") courseId: String
    ): Response<List<CourseUnitResponse>>

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

    // R8: server-side logout (chunk 7 route) — passes the bearer token
    // explicitly because the ViewModel calls this AFTER clearing the shared
    // session, so the AuthInterceptor's lazy token read would be empty.
    // Best-effort revocation; a dead token here just returns 401 harmlessly.
    @POST("api/v1/auth/logout")
    suspend fun logout(
        @Header("Authorization") authorization: String
    ): Response<Unit>

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

    // Submits attendance report with raw binary JPEG payload bypassing Base64 overhead
    @POST("api/v1/attendance/sessions/{sessionId}/submit-neighbors")
    suspend fun submitNeighborsBinary(
        @Path("sessionId") sessionId: String,
        @Header("Content-Type") contentType: String = "image/jpeg",
        @Header("X-Session-Salt") sessionSalt: String?,
        @Header("X-Student-Id") studentId: String?,
        @Header("X-Neighbors") neighbors: String?,
        @Header("X-Close-Neighbors") closeNeighbors: String?,
        // [ULTRASONIC-DISABLED] Layer 4 headers removed (restore with the
        // audio-stack call sites in AttendanceViewModel).
        // @Header("X-Co-Located-Peer-Id") coLocatedPeerId: String?,
        // @Header("X-Ultrasonic-Proximity-Detected") ultrasonicProximityDetected: Boolean?,
        @Header("X-Game-Score") gameScore: Int? = null,
        @Header("X-Relayed-Reports") relayedReportsJson: String?,
        @Body imageBytes: RequestBody
    ): Response<Unit>

    @GET("api/v1/attendance/sessions/history")
    suspend fun getSessionHistory(): Response<List<AttendanceSessionResponse>>

    @GET("api/v1/attendance/sessions/{sessionId}")
    suspend fun getSessionDetails(
        @Path("sessionId") sessionId: String
    ): Response<AttendanceSessionResponse>

    @PATCH("api/v1/attendance/records/{recordId}/override")
    suspend fun overrideRecord(
        @Path("recordId") recordId: String,
        @Body body: RecordOverrideRequest
    ): Response<AttendanceRecordItem>

    @POST("api/v1/attendance/lab/face-check")
    suspend fun checkLabFace(
        @Body body: FaceLabCheckRequest
    ): Response<FaceLabCheckResponse>

    @POST("api/v1/attendance/lab/face-compare")
    suspend fun compareLabFaces(
        @Body body: FaceLabCompareRequest
    ): Response<FaceLabCompareResponse>

    // Leave: student submits and views own applications
    @POST("api/v1/leave/applications")
    suspend fun submitLeaveApplication(
        @Body body: LeaveCreateRequest
    ): Response<LeaveApplicationResponse>

    @GET("api/v1/leave/applications/mine")
    suspend fun getMyLeaveApplications(): Response<List<LeaveApplicationResponse>>

    // Leave: department queue (teacher/HOD) and HOD review
    @GET("api/v1/leave/applications")
    suspend fun getDepartmentLeaveApplications(): Response<List<LeaveApplicationResponse>>

    @POST("api/v1/leave/applications/{applicationId}/review")
    suspend fun reviewLeaveApplication(
        @Path("applicationId") applicationId: String,
        @Body body: LeaveReviewRequest
    ): Response<LeaveApplicationResponse>
}
