package com.college.feature.leave.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface LeaveApiService {

    // Student submits and views own applications
    @POST("api/v1/leave/applications")
    suspend fun submitLeaveApplication(
        @Body body: LeaveCreateRequest
    ): Response<LeaveApplicationResponse>

    @GET("api/v1/leave/applications/mine")
    suspend fun getMyLeaveApplications(): Response<List<LeaveApplicationResponse>>

    // Student withdraws their own still-pending application
    @POST("api/v1/leave/applications/{applicationId}/cancel")
    suspend fun cancelLeaveApplication(
        @Path("applicationId") applicationId: String
    ): Response<LeaveApplicationResponse>

    // Department queue (HOD) and HOD review
    @GET("api/v1/leave/applications")
    suspend fun getDepartmentLeaveApplications(): Response<List<LeaveApplicationResponse>>

    @POST("api/v1/leave/applications/{applicationId}/review")
    suspend fun reviewLeaveApplication(
        @Path("applicationId") applicationId: String,
        @Body body: LeaveReviewRequest
    ): Response<LeaveApplicationResponse>
}
