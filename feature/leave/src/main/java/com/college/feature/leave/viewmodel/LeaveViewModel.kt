package com.college.feature.leave.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.college.core.network.SessionManager
import com.college.feature.leave.network.LeaveApiClient
import com.college.feature.leave.network.LeaveApplicationResponse
import com.college.feature.leave.network.LeaveCreateRequest
import com.college.feature.leave.network.LeaveReviewRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LeaveViewModel : ViewModel() {

    private val api = LeaveApiClient.service

    // --- Student: my applications -------------------------------------------
    var myLeaveApplications by mutableStateOf<List<LeaveApplicationResponse>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isSubmitting by mutableStateOf(false)
        private set
    var isCancelling by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    // --- HOD: department queue -----------------------------------------------
    var departmentLeaveApplications by mutableStateOf<List<LeaveApplicationResponse>>(emptyList())
        private set
    var isLoadingDept by mutableStateOf(false)
        private set
    var isReviewing by mutableStateOf(false)
        private set
    var deptMessage by mutableStateOf<String?>(null)
        private set

    private fun detailOrFallback(errorBody: String?, fallback: String): String {
        val detail = try {
            JSONObject(errorBody.orEmpty()).optString("detail")
        } catch (_: Exception) {
            ""
        }
        return detail.ifBlank { fallback }
    }

    private suspend fun handleUnauthorized() {
        SessionManager.default.endSession()
        withContext(Dispatchers.Main) {
            message = "Session expired. Please sign in again."
            deptMessage = "Session expired. Please sign in again."
        }
    }

    fun loadMyLeaveApplications() {
        if (!SessionManager.default.isLoggedIn) return
        isLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = api.getMyLeaveApplications()
                if (res.code() == 401) {
                    handleUnauthorized()
                    withContext(Dispatchers.Main) { isLoading = false }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    isLoading = false
                    if (res.isSuccessful) {
                        myLeaveApplications = res.body().orEmpty()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    fun submitLeaveApplication(fromDate: String, toDate: String, reason: String) {
        if (!SessionManager.default.isLoggedIn) return
        isSubmitting = true
        message = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = api.submitLeaveApplication(
                    LeaveCreateRequest(
                        fromDate = fromDate,
                        toDate = toDate,
                        reason = reason.trim(),
                        applicantReason = reason.trim()
                    )
                )
                if (res.code() == 401) {
                    handleUnauthorized()
                    withContext(Dispatchers.Main) { isSubmitting = false }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    isSubmitting = false
                    if (res.isSuccessful) {
                        message = "Leave application submitted"
                        loadMyLeaveApplications()
                    } else {
                        message = detailOrFallback(res.errorBody()?.string(), "Could not submit application")
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    isSubmitting = false
                    message = "Could not submit application"
                }
            }
        }
    }

    fun cancelLeaveApplication(applicationId: String) {
        if (!SessionManager.default.isLoggedIn) return
        isCancelling = true
        message = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = api.cancelLeaveApplication(applicationId)
                if (res.code() == 401) {
                    handleUnauthorized()
                    withContext(Dispatchers.Main) { isCancelling = false }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    isCancelling = false
                    if (res.isSuccessful) {
                        message = "Leave application cancelled"
                        loadMyLeaveApplications()
                    } else {
                        message = detailOrFallback(res.errorBody()?.string(), "Could not cancel application")
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    isCancelling = false
                    message = "Could not cancel application"
                }
            }
        }
    }

    fun loadDepartmentLeaveApplications() {
        if (!SessionManager.default.isLoggedIn) return
        isLoadingDept = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = api.getDepartmentLeaveApplications()
                if (res.code() == 401) {
                    handleUnauthorized()
                    withContext(Dispatchers.Main) { isLoadingDept = false }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    isLoadingDept = false
                    if (res.isSuccessful) {
                        departmentLeaveApplications = res.body().orEmpty()
                    } else {
                        deptMessage = detailOrFallback(res.errorBody()?.string(), "Could not load leave requests")
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    isLoadingDept = false
                    deptMessage = "Could not load leave requests"
                }
            }
        }
    }

    fun reviewLeaveApplication(applicationId: String, decision: String, comment: String? = null) {
        if (!SessionManager.default.isLoggedIn) return
        isReviewing = true
        deptMessage = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = api.reviewLeaveApplication(
                    applicationId,
                    LeaveReviewRequest(
                        decision = decision,
                        comment = comment?.trim()?.takeIf { it.isNotBlank() }
                    )
                )
                if (res.code() == 401) {
                    handleUnauthorized()
                    withContext(Dispatchers.Main) { isReviewing = false }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    isReviewing = false
                    if (res.isSuccessful) {
                        deptMessage = if (decision == "APPROVED") "Leave approved" else "Leave rejected"
                        loadDepartmentLeaveApplications()
                    } else {
                        deptMessage = detailOrFallback(res.errorBody()?.string(), "Could not review application")
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    isReviewing = false
                    deptMessage = "Could not review application"
                }
            }
        }
    }
}
