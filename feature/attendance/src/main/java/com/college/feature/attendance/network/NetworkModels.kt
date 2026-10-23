package com.college.feature.attendance.network

import com.google.gson.annotations.SerializedName

// Auth models
data class LoginRequest(
    val email: String,
    val password: String
)

data class LoginResponse(
    @SerializedName("requires_otp") val requiresOtp: Boolean = true,
    val message: String,
    val email: String,
    @SerializedName("access_token") val accessToken: String? = null,
    @SerializedName("token_type") val tokenType: String? = "bearer",
    val user: UserResponse? = null
)

data class VerifyOtpRequest(
    val email: String,
    @SerializedName("otp_code") val otpCode: String
)

data class ResendOtpRequest(
    val email: String,
    val purpose: String = "LOGIN"
)

data class OtpSentResponse(
    val message: String,
    val email: String,
    @SerializedName("expires_in_seconds") val expiresInSeconds: Int = 600,
    @SerializedName("requires_otp") val requiresOtp: Boolean = true
)

data class RegisterRequest(
    val name: String,
    val email: String,
    val password: String,
    val role: String,
    @SerializedName("roll_number") val rollNumber: String,
    val department: String,
    val program: String? = null,
    val semester: Int? = null,
    val section: String? = null,
    @SerializedName("faculty_code") val facultyCode: String? = null
)

data class UserResponse(
    val id: String,
    val name: String,
    val email: String,
    val role: String,
    @SerializedName("roll_number") val rollNumber: String? = null,
    val department: String? = null,
    val program: String? = null,
    val semester: Int? = null,
    val section: String? = null
)

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    val user: UserResponse
)

// Course & Session models
data class CourseCreateRequest(
    @SerializedName("course_code") val courseCode: String,
    @SerializedName("course_name") val courseName: String
)

data class CourseResponse(
    val id: String,
    @SerializedName("course_code") val courseCode: String,
    @SerializedName("course_name") val courseName: String
)

data class SessionCreateRequest(
    @SerializedName("course_id") val courseId: String? = null,
    @SerializedName("session_token") val sessionToken: String,
    @SerializedName("session_salt") val sessionSalt: String? = null,
    @SerializedName("total_enrolled") val totalEnrolled: Int = 0,
    @SerializedName("course_code") val courseCode: String? = null,
    @SerializedName("course_name") val courseName: String? = null
)

data class AttendanceRecordItem(
    @SerializedName("ble_device_id") val bleDeviceId: String,
    val status: String,
    @SerializedName("verification_method") val verificationMethod: String,
    @SerializedName("affinity_score") val affinityScore: Double,
    val remarks: String?,
    @SerializedName("roll_number") val rollNumber: String? = null,
    @SerializedName("student_name") val studentName: String? = null,
    @SerializedName("student_id") val studentId: String? = null
)

data class AttendanceSessionResponse(
    val id: String,
    @SerializedName("course_id") val courseId: String,
    @SerializedName("session_token") val sessionToken: String,
    val status: String,
    @SerializedName("total_verified") val totalVerified: Int,
    @SerializedName("total_spoofers") val totalSpoofers: Int,
    @SerializedName("started_at") val startedAt: String? = null,
    @SerializedName("ended_at") val endedAt: String? = null,
    val records: List<AttendanceRecordItem>? = null,
    @SerializedName("course_code") val courseCode: String? = null,
    @SerializedName("course_name") val courseName: String? = null,
    @SerializedName("class_type") val classType: String? = null,
    @SerializedName("course_program") val courseProgram: String? = null,
    @SerializedName("semester") val semester: String? = null,
    @SerializedName("batch_type") val batchType: String? = null,
    @SerializedName("unit") val unit: String? = null,
    @SerializedName("topic_covered") val topicCovered: String? = null
)

// Payload when posting BLE mesh graph
data class MeshAuditRunRequest(
    val reports: Map<String, List<String>>
)

// Payload when student submits their top neighbors directly
data class SubmitNeighborsRequest(
    @SerializedName("student_id") val studentId: String? = null,
    val neighbors: List<String>,
    @SerializedName("session_salt") val sessionSalt: String? = null,
    @SerializedName("relayed_reports") val relayedReports: Map<String, List<String>>? = null
)
