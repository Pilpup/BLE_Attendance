package com.college.feature.attendance.telemetry

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Mathematical behavioral biometrics engine.
 * Analyzes tap latency, inter-tap intervals, latency oscillations, and touch dispersion
 * to distinguish between:
 * 1) A single dedicated human playing with full attention.
 * 2) A student dividing attention across 2+ devices simultaneously (proxy buddy-punching).
 * 3) An automated macro / script or tap emulator.
 */
object BehavioralTelemetryEngine {

    fun analyze(
        taps: List<TapEvent>,
        expired: List<ExpiredTargetEvent>,
        totalTargetsGoal: Int
    ): CognitiveFocusReport {
        val totalTaps = taps.size
        val totalExpired = expired.size
        val totalSpawned = totalTaps + totalExpired
        val accuracy = if (totalSpawned > 0) (totalTaps * 100) / totalSpawned else 0

        // Handle edge case of no taps
        if (taps.isEmpty()) {
            return CognitiveFocusReport(
                totalTaps = 0,
                totalSpawns = totalSpawned,
                hits = 0,
                misses = totalExpired,
                accuracyPercentage = 0,
                meanLatencyMs = 0f,
                medianLatencyMs = 0f,
                latencyStdDevMs = 0f,
                coeffOfVariation = 0f,
                meanConsecutiveDiffMs = 0f,
                latencyOscillationIndex = 0f,
                maxInterTapGapMs = 0L,
                meanRadialOffset = 0f,
                spatialDispersionStdDev = 0f,
                focusIndex = 0f,
                classification = FocusClassification.INATTENTIVE,
                diagnosticReason = "No targets were tapped during the attendance window"
            )
        }

        val latencies = taps.map { it.latencyMs.toFloat() }
        val meanLatency = latencies.average().toFloat()

        val sortedLatencies = latencies.sorted()
        val medianLatency = if (sortedLatencies.size % 2 == 0) {
            (sortedLatencies[sortedLatencies.size / 2 - 1] + sortedLatencies[sortedLatencies.size / 2]) / 2f
        } else {
            sortedLatencies[sortedLatencies.size / 2]
        }

        // Standard Deviation & Coefficient of Variation (CV = sigma / mean)
        val variance = if (latencies.size > 1) {
            latencies.map { (it - meanLatency).pow(2) }.sum() / (latencies.size - 1)
        } else 0f
        val stdDev = sqrt(variance)
        val coeffOfVariation = if (meanLatency > 0f) stdDev / meanLatency else 0f

        // Mean Absolute Consecutive Difference (MCD):
        // Measures how wildly reaction time swings between consecutive taps
        val consecutiveDiffs = mutableListOf<Float>()
        for (i in 1 until latencies.size) {
            consecutiveDiffs.add(abs(latencies[i] - latencies[i - 1]))
        }
        val mcd = if (consecutiveDiffs.isNotEmpty()) consecutiveDiffs.average().toFloat() else 0f
        val latencyOscillationIndex = if (meanLatency > 0f) mcd / meanLatency else 0f

        // Inter-Tap Intervals (gaze stall detection)
        val tapTimes = taps.map { it.tapTimeMillis }.sorted()
        var maxInterTapGap = 0L
        for (i in 1 until tapTimes.size) {
            val gap = tapTimes[i] - tapTimes[i - 1]
            if (gap > maxInterTapGap) maxInterTapGap = gap
        }

        // Spatial Touch Dispersion (bot vs human finger radius)
        val radialOffsets = taps.map { it.normalizedRadialOffset }
        val meanRadialOffset = radialOffsets.average().toFloat()
        val radialVariance = if (radialOffsets.size > 1) {
            radialOffsets.map { (it - meanRadialOffset).pow(2) }.sum() / (radialOffsets.size - 1)
        } else 0f
        val spatialDispersionStdDev = sqrt(radialVariance)

        // Superhuman / Bot checks
        val superhumanTapRatio = taps.count { it.latencyMs < 130L }.toFloat() / totalTaps.toFloat()
        val isZeroVarianceBot = (totalTaps >= 6 && stdDev < 18f && spatialDispersionStdDev < 0.02f)
        val isSuperhumanBot = superhumanTapRatio > 0.25f

        // Compute sub-scores [0.0 - 1.0] where 1.0 is healthy focused human
        // 1. Latency health: Human sweet spot is 250ms - 550ms
        val latencyScore = when {
            meanLatency in 260f..560f -> 1.0f
            meanLatency in 200f..750f -> 0.85f
            meanLatency < 180f -> 0.20f // suspiciously fast
            else -> (1.0f - ((meanLatency - 750f) / 450f)).coerceIn(0.1f, 0.7f)
        }

        // 2. Rhythm consistency: Lower oscillation index indicates single-device foveal focus
        val rhythmScore = when {
            latencyOscillationIndex < 0.32f -> 1.0f
            latencyOscillationIndex < 0.45f -> 0.80f
            latencyOscillationIndex < 0.60f -> 0.55f
            else -> 0.20f // severe sawtooth alternating delays
        }

        // 3. Target completion & expiration penalty
        val expirationRate = if (totalSpawned > 0) totalExpired.toFloat() / totalSpawned.toFloat() else 1f
        val presenceScore = (1.0f - (expirationRate * 1.5f)).coerceIn(0.0f, 1.0f)

        // 4. Spatial entropy score: Human fingers naturally scatter across a 2D Gaussian
        val entropyScore = when {
            spatialDispersionStdDev in 0.05f..0.35f -> 1.0f
            spatialDispersionStdDev < 0.02f -> 0.15f // robotic dead center clicks
            else -> 0.75f
        }

        val focusIndex = (
            (0.35f * rhythmScore) +
            (0.30f * latencyScore) +
            (0.20f * presenceScore) +
            (0.15f * entropyScore)
        ).coerceIn(0.0f, 1.0f)

        // Diagnosis & Classification
        val classification: FocusClassification
        val diagnosticReason: String

        when {
            isZeroVarianceBot || isSuperhumanBot -> {
                classification = FocusClassification.AUTOMATED_SCRIPT
                diagnosticReason = if (isSuperhumanBot) {
                    "Inhuman reaction speed detected (${(superhumanTapRatio * 100).toInt()}% taps under 130ms)"
                } else {
                    "Robotic timing and identical coordinate taps detected (zero natural jitter)"
                }
            }

            // Dual-device split-brain detection criteria:
            // High oscillation index (>0.52) meaning alternating lag bursts from gaze switching,
            // OR high variance (CV > 0.48) paired with prolonged inter-tap gaze pauses (>1800ms)
            // or high expiration rate (>30%) under 1.2s pressure.
            (latencyOscillationIndex > 0.50f && (expirationRate > 0.25f || maxInterTapGap > 1800L)) ||
            (coeffOfVariation > 0.48f && latencyOscillationIndex > 0.48f && totalTaps >= 8) -> {
                classification = FocusClassification.DIVIDED_ATTENTION_PROXY
                diagnosticReason = "Bimodal reaction times and gaze-shift pauses indicate divided attention across multiple devices"
            }

            focusIndex >= 0.65f && accuracy >= 65 -> {
                classification = FocusClassification.FOCUSED_SINGLE_DEVICE
                diagnosticReason = "Healthy human reaction cadence, steady visual rhythm, and natural motor touch dispersion"
            }

            else -> {
                classification = FocusClassification.INATTENTIVE
                diagnosticReason = "Erratic reaction timing or multiple missed targets without alternating proxy pattern"
            }
        }

        return CognitiveFocusReport(
            totalTaps = totalTaps,
            totalSpawns = totalSpawned,
            hits = totalTaps,
            misses = totalExpired,
            accuracyPercentage = accuracy,
            meanLatencyMs = meanLatency,
            medianLatencyMs = medianLatency,
            latencyStdDevMs = stdDev,
            coeffOfVariation = coeffOfVariation,
            meanConsecutiveDiffMs = mcd,
            latencyOscillationIndex = latencyOscillationIndex,
            maxInterTapGapMs = maxInterTapGap,
            meanRadialOffset = meanRadialOffset,
            spatialDispersionStdDev = spatialDispersionStdDev,
            focusIndex = focusIndex,
            classification = classification,
            diagnosticReason = diagnosticReason
        )
    }
}
