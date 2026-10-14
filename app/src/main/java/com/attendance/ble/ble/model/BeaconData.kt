package com.attendance.ble.ble.model

data class BeaconData(
    val currentRssi: Int? = null,
    val avgRssi: Double = 0.0,
    val minRssi: Int? = null,
    val maxRssi: Int? = null,
    val packetCount: Int = 0,
    val avgLatencyMs: Double = 0.0,
    val latestMessage: String = "No Data"
)
