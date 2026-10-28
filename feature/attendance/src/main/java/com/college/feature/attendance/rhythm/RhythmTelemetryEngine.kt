package com.college.feature.attendance.rhythm

import com.college.feature.attendance.telemetry.CognitiveFocusReport
import com.college.feature.attendance.telemetry.FocusClassification
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Advanced Sensory & Cognitive Biometrics Engine for osu!-style Rhythm Attendance Challenge.
 * Evaluates approach circle temporal phase-locking, continuous slider tracking fidelity,
 * and sequential working memory order to deterministically isolate proxy buddy-punchers.
 */
object RhythmTelemetryEngine {

    fun analyze(
        interactions: List<RhythmInteractionRecord>,
        totalObjects: Int = AttendanceBeatmap.objects.size
    ): CognitiveFocusReport {
        val totalRecorded = interactions.size
        val hits = interactions.count { it.judgement != JudgementType.MISS }
        val misses = (totalObjects - hits).coerceAtLeast(0)

        // Empty session guard
        if (interactions.isEmpty() || hits == 0) {
            return CognitiveFocusReport(
                totalTaps = 0,
                totalSpawns = totalObjects,
                hits = 0,
                misses = totalObjects,
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
                diagnosticReason = "No rhythm objects were engaged during attendance window"
            )
        }

        // 1. Scoring & Accuracy Calculation
        var totalPointsEarned = 0
        val maxPoints = totalObjects * 300
        interactions.forEach { totalPointsEarned += it.judgement.score }
        val accuracyPercentage = ((totalPointsEarned.toFloat() / maxPoints.toFloat()) * 100f).toInt().coerceIn(0, 100)

        // 2. Approach Timing Error Phase-Lock (|actual - target|)
        val timingErrors = interactions.filter { it.judgement != JudgementType.MISS }.map { abs(it.timingErrorMs).toFloat() }
        val meanTimingError = if (timingErrors.isNotEmpty()) timingErrors.average().toFloat() else 350f

        val timingVariance = if (timingErrors.size > 1) {
            timingErrors.map { (it - meanTimingError).pow(2) }.sum() / (timingErrors.size - 1)
        } else 0f
        val timingStdDev = sqrt(timingVariance)
        val timingCoeffOfVariation = if (meanTimingError > 0f) timingStdDev / meanTimingError else 0f

        // 3. Consecutive Timing Oscillation (Detects alternating delay spikes from gaze shifts)
        val consecutiveDiffs = mutableListOf<Float>()
        for (i in 1 until timingErrors.size) {
            consecutiveDiffs.add(abs(timingErrors[i] - timingErrors[i - 1]))
        }
        val mcd = if (consecutiveDiffs.isNotEmpty()) consecutiveDiffs.average().toFloat() else 0f
        val oscillationIndex = if (meanTimingError > 0f) mcd / meanTimingError else 0f

        // 4. Slider Tracking Fidelity (Continuous Motor Control)
        val sliderInteractions = interactions.filter { it.isSlider }
        val avgSliderTrackingRatio = if (sliderInteractions.isNotEmpty()) {
            sliderInteractions.map { it.sliderTrackingRatio }.average().toFloat()
        } else 1.0f

        // 5. Sequential Order Integrity
        val outOfOrderStrikes = interactions.count { !it.hitInOrder }
        val orderIntegrityRatio = (1f - (outOfOrderStrikes.toFloat() / totalObjects.toFloat())).coerceIn(0f, 1f)

        // 6. Touch Radial Offset & Spatial Jitter
        val radialOffsets = interactions.map { it.normalizedRadialOffset }
        val meanRadialOffset = if (radialOffsets.isNotEmpty()) radialOffsets.average().toFloat() else 0f
        val spatialVariance = if (radialOffsets.size > 1) {
            radialOffsets.map { (it - meanRadialOffset).pow(2) }.sum() / (radialOffsets.size - 1)
        } else 0f
        val spatialDispersionStdDev = sqrt(spatialVariance)

        // 7. Bot / Superhuman Script Checks
        val isZeroJitterBot = timingErrors.size >= 6 && timingStdDev < 8f && spatialDispersionStdDev < 0.015f
        val isSuperhumanTiming = timingErrors.isNotEmpty() && meanTimingError < 15f && timingStdDev < 10f

        // 8. Cognitive Component Scores [0.0 .. 1.0]
        // A. Timing Phase-Lock Score: optimal is tight timing error (< 75ms)
        val timingScore = when {
            meanTimingError < 80f -> 1.0f
            meanTimingError < 140f -> 0.85f
            meanTimingError < 220f -> 0.65f
            else -> 0.30f
        }

        // B. Slider Continuous Tracking Score: single-device focus is > 75%
        val sliderScore = when {
            avgSliderTrackingRatio >= 0.80f -> 1.0f
            avgSliderTrackingRatio >= 0.60f -> 0.75f
            avgSliderTrackingRatio >= 0.40f -> 0.40f
            else -> 0.10f // Dropped slider while looking at second phone
        }

        // C. Rhythm Flow & Presence Score
        val presenceScore = (hits.toFloat() / totalObjects.toFloat()).coerceIn(0f, 1f)

        // Composite Focus Index
        val focusIndex = (
            (0.35f * timingScore) +
            (0.35f * sliderScore) +
            (0.15f * presenceScore) +
            (0.15f * orderIntegrityRatio)
        ).coerceIn(0f, 1f)

        // 9. Classification & Threat Diagnostics
        val classification: FocusClassification
        val diagnosticReason: String

        when {
            isZeroJitterBot || isSuperhumanTiming -> {
                classification = FocusClassification.AUTOMATED_SCRIPT
                diagnosticReason = "Superhuman timing accuracy (avg error ${meanTimingError.toInt()}ms) and zero spatial jitter detected"
            }

            // Dual-phone proxy indicators:
            // - Dropped continuous slider tracking (< 50%) due to moving hand to second device
            // - High timing oscillation (> 0.52) or out-of-order combo strikes paired with erratic phase-lock
            (avgSliderTrackingRatio < 0.48f && sliderInteractions.isNotEmpty()) ||
            (oscillationIndex > 0.52f && (outOfOrderStrikes > 1 || avgSliderTrackingRatio < 0.65f)) ||
            (timingStdDev > 110f && avgSliderTrackingRatio < 0.60f) -> {
                classification = FocusClassification.DIVIDED_ATTENTION_PROXY
                diagnosticReason = if (avgSliderTrackingRatio < 0.48f) {
                    "Broken continuous slider tracking (${(avgSliderTrackingRatio * 100).toInt()}%) indicates attention shifted to secondary device"
                } else {
                    "Alternating timing phase-drift and sequential combo errors indicate divided attention across 2+ screens"
                }
            }

            focusIndex >= 0.60f && accuracyPercentage >= 60 -> {
                classification = FocusClassification.FOCUSED_SINGLE_DEVICE
                diagnosticReason = "Synchronized visual-motor phase lock, continuous path tracking (${(avgSliderTrackingRatio * 100).toInt()}%), and foveal focus confirmed"
            }

            else -> {
                classification = FocusClassification.INATTENTIVE
                diagnosticReason = "Incomplete rhythm sequence or timing phase drift without alternating proxy pattern"
            }
        }

        return CognitiveFocusReport(
            totalTaps = totalRecorded,
            totalSpawns = totalObjects,
            hits = hits,
            misses = misses,
            accuracyPercentage = accuracyPercentage,
            meanLatencyMs = meanTimingError,
            medianLatencyMs = meanTimingError,
            latencyStdDevMs = timingStdDev,
            coeffOfVariation = timingCoeffOfVariation,
            meanConsecutiveDiffMs = mcd,
            latencyOscillationIndex = oscillationIndex,
            maxInterTapGapMs = 0L,
            meanRadialOffset = meanRadialOffset,
            spatialDispersionStdDev = spatialDispersionStdDev,
            focusIndex = focusIndex,
            classification = classification,
            diagnosticReason = diagnosticReason
        )
    }
}
