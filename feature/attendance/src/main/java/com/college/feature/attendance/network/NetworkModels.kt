package com.college.feature.attendance.network

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

// Re-export core auth models for feature-level compatibility
typealias LoginRequest = com.college.core.network.model.LoginRequest
typealias LoginResponse = com.college.core.network.model.LoginResponse
typealias VerifyOtpRequest = com.college.core.network.model.VerifyOtpRequest
typealias ResendOtpRequest = com.college.core.network.model.ResendOtpRequest
typealias OtpSentResponse = com.college.core.network.model.OtpSentResponse
typealias RegisterRequest = com.college.core.network.model.RegisterRequest
typealias UserResponse = com.college.core.network.model.UserResponse
typealias TokenResponse = com.college.core.network.model.TokenResponse

// Course & Session models
data class CourseCreateRequest(
    @SerializedName("course_code") val courseCode: String,
    @SerializedName("course_name") val courseName: String
)

@Immutable
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

@Immutable
data class AttendanceRecordItem(
    val id: String? = null,
    @SerializedName("ble_device_id") val bleDeviceId: String,
    val status: String,
    @SerializedName("verification_method") val verificationMethod: String,
    @SerializedName("affinity_score") val affinityScore: Double,
    val remarks: String? = null,
    @SerializedName("roll_number") val rollNumber: String? = null,
    @SerializedName("student_name") val studentName: String? = null,
    @SerializedName("student_id") val studentId: String? = null
)

data class RecordOverrideRequest(
    val status: String = "VERIFIED_PRESENT",
    val remarks: String? = "Verified by instructor"
)

@Immutable
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
    @SerializedName("topic_covered") val topicCovered: String? = null,
    @SerializedName("subject_name") val subjectName: String? = null
)

// Payload when posting BLE mesh graph
data class MeshAuditRunRequest(
    val reports: Map<String, List<String>>
)

// Payload when student submits their top neighbors directly
data class SubmitNeighborsRequest(
    @SerializedName("student_id") val studentId: String? = null,
    val neighbors: List<String>,
    @SerializedName("close_neighbors") val closeNeighbors: List<String>? = null,
    @SerializedName("session_salt") val sessionSalt: String? = null,
    @SerializedName("relayed_reports") val relayedReports: Map<String, List<String>>? = null,
    @SerializedName("face_image_base64") val faceImageBase64: String? = null,
    @SerializedName("ultrasonic_proximity_detected") val ultrasonicProximityDetected: Boolean? = null,
    @SerializedName("co_located_peer_id") val coLocatedPeerId: String? = null
)

// Face Biometric Diagnostic Lab Models
data class FaceLabCheckRequest(
    @SerializedName("face_image_base64") val faceImageBase64: String
)

data class FaceLabCheckResponse(
    @SerializedName("has_face") val hasFace: Boolean,
    val remarks: String
)

data class FaceLabCompareRequest(
    @SerializedName("image_a_base64") val imageABase64: String,
    @SerializedName("image_b_base64") val imageBBase64: String
)

data class FaceLabCompareResponse(
    @SerializedName("has_face_a") val hasFaceA: Boolean,
    @SerializedName("has_face_b") val hasFaceB: Boolean,
    @SerializedName("similarity_score") val similarityScore: Double,
    @SerializedName("is_duplicate") val isDuplicate: Boolean,
    val remarks: String
)
