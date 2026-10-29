package com.college.feature.attendance.game

/**
 * High-performance interaction event record for Stroop match behavioral telemetry.
 * Captures swipe decision latency, correctness, and stimulus metadata with 0 GC pressure.
 */
data class GameInteractionRecord(
    val trialIndex: Int,
    val latencyMs: Long,
    val isCorrect: Boolean,
    val hesitationMs: Long = 0L,
    val touchDistancePx: Float = 0f,
    val vectorDeviationDeg: Float = 0f,
    val swipeVelocityPxPerMs: Float = 0f,
    val stimulusKey: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)
