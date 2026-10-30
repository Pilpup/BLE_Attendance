package com.college.feature.attendance.game

import com.college.feature.attendance.telemetry.CognitiveFocusReport
import com.college.feature.attendance.telemetry.FocusClassification
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * High-performance behavioral telemetry engine for the Stroop Match Attendance Challenge.
 * Analyzes semantic conflict resolution, reaction latency, and latency oscillations
 * grounded in human benchmark cognitive data to verify physical presence and flag dual-device proxies.
 */
object GameTelemetryEngine {

    fun analyze(
        interactions: List<GameInteractionRecord>,
        totalTrials: Int = 12
    ): CognitiveFocusReport {
        if (interactions.isEmpty()) {
            return CognitiveFocusReport(
                totalTaps = 0,
                totalSpawns = totalTrials,
                hits = 0,
                misses = totalTrials,
                accuracyPercentage = 0,
                meanLatencyMs = 0f,
                medianLatencyMs = 0f,
                latencyStdDevMs = 0f,
                coeffOfVariation = 0f,
                meanConsecutiveDiffMs = 0f,
                latencyOscillationIndex = 0f,
                maxInterTapGapMs = 0L,
                meanRadialOffset = 1f,
                spatialDispersionStdDev = 0f,
                focusIndex = 0f,
                classification = FocusClassification.INATTENTIVE,
                diagnosticReason = "No interaction recorded before timeout."
            )
        }

        val hits = interactions.count { it.isCorrect }
        val misses = totalTrials - hits
        val accuracy = if (totalTrials > 0) ((hits * 100) / totalTrials) else 0

        // Timing statistics
        val latencies = interactions.map { it.latencyMs.toFloat() }
        val meanLatency = latencies.average().toFloat()
        val sortedLatencies = latencies.sorted()
        val medianLatency = if (sortedLatencies.size % 2 == 1) {
            sortedLatencies[sortedLatencies.size / 2]
        } else {
            (sortedLatencies[sortedLatencies.size / 2 - 1] + sortedLatencies[sortedLatencies.size / 2]) / 2f
        }

        val variance = latencies.map { (it - meanLatency) * (it - meanLatency) }.average().toFloat()
        val stdDev = sqrt(variance)
        val cv = if (meanLatency > 0f) stdDev / meanLatency else 0f

        // Mean Absolute Consecutive Difference (MCD) & Oscillation Index (sawtooth proxy flag)
        var mcd = 0f
        if (latencies.size > 1) {
            var diffSum = 0f
            for (i in 0 until latencies.size - 1) {
                diffSum += abs(latencies[i + 1] - latencies[i])
            }
            mcd = diffSum / (latencies.size - 1)
        }
        val oscillationIndex = if (meanLatency > 0f) mcd / meanLatency else 0f

        // Inter-card time gaps
        var maxGap = 0L
        if (interactions.size > 1) {
            for (i in 0 until interactions.size - 1) {
                val gap = interactions[i + 1].timestampMs - interactions[i].timestampMs
                if (gap > maxGap) maxGap = gap
            }
        }

        // Composite focus index (0.0 to 1.0)
        val accuracyFactor = accuracy / 100f
        val stabilityFactor = (1f - (cv * 0.5f)).coerceIn(0.2f, 1f)
        val focusIndex = (accuracyFactor * 0.7f + stabilityFactor * 0.3f).coerceIn(0f, 1f)

        // Evaluate behavioral focus classification
        val (classification, diagnosticReason) = when {
            // Superhuman bot check
            meanLatency < 90f && stdDev < 15f && hits >= 4 -> {
                FocusClassification.AUTOMATED_SCRIPT to
                        "Superhuman swipe latency (${meanLatency.toInt()}ms) detected. Automated script flagged."
            }

            // Normal focused human on single device
            accuracy >= 62 && meanLatency <= 950f -> {
                FocusClassification.FOCUSED_SINGLE_DEVICE to
                        "Natural cognitive inhibition verified (${meanLatency.toInt()}ms latency, $accuracy% accuracy)."
            }

            // High miss rate from cross-device confusion
            accuracy < 50 -> {
                FocusClassification.DIVIDED_ATTENTION_PROXY to
                        "Semantic conflict resolution failure ($accuracy% accuracy). Divided attention flagged."
            }

            // Severe hesitation / saccade delay to 2nd phone
            meanLatency > 1150f -> {
                FocusClassification.DIVIDED_ATTENTION_PROXY to
                        "Excessive cognitive hesitation (${meanLatency.toInt()}ms). Cross-device gaze shift flagged."
            }

            // Alternating fast/slow sawtooth pattern from switching between 2 phones
            oscillationIndex > 0.55f && stdDev > 250f -> {
                FocusClassification.DIVIDED_ATTENTION_PROXY to
                        "Bimodal latency oscillation detected. Interleaved dual-device operation flagged."
            }

            else -> {
                FocusClassification.FOCUSED_SINGLE_DEVICE to
                        "Stroop match challenge verified ($accuracy% accuracy)."
            }
        }

        return CognitiveFocusReport(
            totalTaps = interactions.size,
            totalSpawns = totalTrials,
            hits = hits,
            misses = misses,
            accuracyPercentage = accuracy,
            meanLatencyMs = meanLatency,
            medianLatencyMs = medianLatency,
            latencyStdDevMs = stdDev,
            coeffOfVariation = cv,
            meanConsecutiveDiffMs = mcd,
            latencyOscillationIndex = oscillationIndex,
            maxInterTapGapMs = maxGap,
            meanRadialOffset = 0f,
            spatialDispersionStdDev = stdDev,
            focusIndex = focusIndex,
            classification = classification,
            diagnosticReason = diagnosticReason
        )
    }
}
