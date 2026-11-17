package com.college.feature.leave.network

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

// Leave application (student -> HOD). Duration is the explicit date range
// of whole calendar days (inclusive).
data class LeaveCreateRequest(
    @SerializedName("from_date") val fromDate: String,
    @SerializedName("to_date") val toDate: String,
    @SerializedName("reason") val reason: String,
    // v2 contract name for `reason`; server accepts either
    @SerializedName("applicant_reason") val applicantReason: String? = null
)

@Immutable
data class LeaveApplicationResponse(
    @SerializedName("id") val id: String = "",
    // v2 contract name — mirrors studentId
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("student_id") val studentId: String? = null,
    @SerializedName("student_name") val studentName: String? = null,
    @SerializedName("student_roll") val studentRoll: String? = null,
    @SerializedName("department") val department: String = "",
    @SerializedName("from_date") val fromDate: String = "",
    @SerializedName("to_date") val toDate: String = "",
    // v2 contract name — mirrors reason
    @SerializedName("applicant_reason") val applicantReason: String? = null,
    @SerializedName("reason") val reason: String = "",
    @SerializedName("status") val status: String = "PENDING",
    // v2 contract name — mirrors reviewComment
    @SerializedName("reviewer_feedback") val reviewerFeedback: String? = null,
    @SerializedName("review_comment") val reviewComment: String? = null,
    @SerializedName("reviewer_name") val reviewerName: String? = null,
    @SerializedName("reviewed_at") val reviewedAt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("can_review") val canReview: Boolean = false
)

data class LeaveReviewRequest(
    @SerializedName("decision") val decision: String,
    @SerializedName("comment") val comment: String? = null
)
