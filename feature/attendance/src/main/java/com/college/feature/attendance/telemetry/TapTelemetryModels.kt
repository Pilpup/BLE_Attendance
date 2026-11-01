package com.college.feature.attendance.telemetry

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

// Telemetry record captured on each physical screen tap
@Immutable
data class TapEvent(
    val targetId: Int,
    val tapTimeMillis: Long,
    val spawnTimeMillis: Long,
    val latencyMs: Long = (tapTimeMillis - spawnTimeMillis).coerceAtLeast(0L),
    val targetRelX: Float,
    val targetRelY: Float,
    val touchOffsetX: Float = 0f,
    val touchOffsetY: Float = 0f,
    val normalizedRadialOffset: Float = 0f
)

// Record of a target that expired without being tapped
@Immutable
data class ExpiredTargetEvent(
    val targetId: Int,
    val spawnTimeMillis: Long,
    val expiredTimeMillis: Long,
    val targetRelX: Float,
    val targetRelY: Float
)

// Behavioral focus and anti-proxy classification
enum class FocusClassification {
    @SerializedName("FOCUSED_SINGLE_DEVICE")
    FOCUSED_SINGLE_DEVICE,

    @SerializedName("DIVIDED_ATTENTION_PROXY")
    DIVIDED_ATTENTION_PROXY,

    @SerializedName("AUTOMATED_SCRIPT")
    AUTOMATED_SCRIPT,

    @SerializedName("INATTENTIVE")
    INATTENTIVE;

    val isPassing: Boolean get() = this == FOCUSED_SINGLE_DEVICE
    val isMaliciousProxy: Boolean get() = this == DIVIDED_ATTENTION_PROXY || this == AUTOMATED_SCRIPT
}

// Cognitive telemetry report summarizing response metrics and classification
@Immutable
data class CognitiveFocusReport(
    val totalTaps: Int,
    val totalSpawns: Int,
    val hits: Int,
    val misses: Int,
    val accuracyPercentage: Int,
    val meanLatencyMs: Float,
    val medianLatencyMs: Float,
    val latencyStdDevMs: Float,
    val coeffOfVariation: Float,
    val meanConsecutiveDiffMs: Float,
    val latencyOscillationIndex: Float,
    val maxInterTapGapMs: Long,
    val meanRadialOffset: Float,
    val spatialDispersionStdDev: Float,
    val focusIndex: Float,
    val classification: FocusClassification,
    val diagnosticReason: String
) {
    val isPassing: Boolean get() = classification.isPassing
    val isMaliciousProxy: Boolean get() = classification.isMaliciousProxy
}
