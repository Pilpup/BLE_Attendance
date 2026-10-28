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

        // 1. Fair Weighted Rhythm Accuracy Calculation:
        // Perfect = 100%, Great = 85%, Good = 70%, Miss = 0%
        var weightedHitScore = 0f
        interactions.forEach {
            weightedHitScore += when (it.judgement) {
                JudgementType.PERFECT -> 1.0f
                JudgementType.GREAT -> 0.85f
                JudgementType.GOOD -> 0.70f
                JudgementType.MISS -> 0.0f
            }
        }
        val accuracyPercentage = ((weightedHitScore / totalObjects.toFloat()) * 100f).toInt().coerceIn(0, 100)

        // 2. Approach Timing Error Phase-Lock (|actual - target|)
        // Filter only valid non-miss hits with realistic touch timing
        val validTimingInteractions = interactions.filter {
            it.judgement != JudgementType.MISS && (it.timingErrorMs != 0L || !it.isSlider)
        }
        val timingErrors = validTimingInteractions.map { abs(it.timingErrorMs).toFloat() }
        val meanTimingError = if (timingErrors.isNotEmpty()) timingErrors.average().toFloat() else 180f

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
        val outOfOrderStrikes = interactions.count { !it.hitInOrder && it.judgement != JudgementType.MISS }
        val orderIntegrityRatio = (1f - (outOfOrderStrikes.toFloat() / totalObjects.toFloat())).coerceIn(0f, 1f)

        // 6. Touch Radial Offset & Spatial Jitter
        val radialOffsets = interactions.filter { it.judgement != JudgementType.MISS }.map { it.normalizedRadialOffset }
        val meanRadialOffset = if (radialOffsets.isNotEmpty()) radialOffsets.average().toFloat() else 0f
        val spatialVariance = if (radialOffsets.size > 1) {
            radialOffsets.map { (it - meanRadialOffset).pow(2) }.sum() / (radialOffsets.size - 1)
        } else 0f
        val spatialDispersionStdDev = sqrt(spatialVariance)

        // 7. Bot / Superhuman Script Checks
        val isZeroJitterBot = timingErrors.size >= 8 && timingStdDev < 6f && spatialDispersionStdDev < 0.010f
        val isSuperhumanTiming = timingErrors.size >= 6 && meanTimingError < 12f && timingStdDev < 8f

        // 8. Cognitive Component Scores [0.0 .. 1.0]
        val timingScore = when {
            meanTimingError < 140f -> 1.0f
            meanTimingError < 240f -> 0.85f
            meanTimingError < 360f -> 0.65f
            else -> 0.40f
        }

        val sliderScore = when {
            avgSliderTrackingRatio >= 0.60f -> 1.0f
            avgSliderTrackingRatio >= 0.35f -> 0.80f
            avgSliderTrackingRatio >= 0.15f -> 0.55f
            else -> 0.25f
        }

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
                diagnosticReason = "Superhuman timing precision (< 12ms error) and zero coordinate jitter detected"
            }

            // Dual-phone proxy indicators:
            // Must exhibit severe bimodal timing oscillation (LOI > 0.65)
            // paired with high timing variance (CV > 0.60) and broken tracking/order
            (oscillationIndex > 0.65f && timingCoeffOfVariation > 0.60f && (outOfOrderStrikes >= 3 || avgSliderTrackingRatio < 0.20f)) -> {
                classification = FocusClassification.DIVIDED_ATTENTION_PROXY
                diagnosticReason = "Periodic bimodal timing swings and gaze-shift pauses indicate divided attention across 2+ devices"
            }

            hits >= (totalObjects / 2) || accuracyPercentage >= 40 -> {
                classification = FocusClassification.FOCUSED_SINGLE_DEVICE
                diagnosticReason = "Human visual rhythm verified ($hits/$totalObjects hits, ${accuracyPercentage}% accuracy)"
            }

            else -> {
                classification = FocusClassification.INATTENTIVE
                diagnosticReason = "Incomplete rhythm sequence ($hits/$totalObjects hits); please focus and try again"
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
