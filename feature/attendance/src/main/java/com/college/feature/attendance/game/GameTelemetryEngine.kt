package com.college.feature.attendance.game

import com.college.feature.attendance.telemetry.CognitiveFocusReport
import com.college.feature.attendance.telemetry.FocusClassification
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * High-performance behavioral telemetry engine for the 6 cognitive attendance games.
 * Analyzes interaction metrics, latency oscillations, and task-specific error modes
 * grounded in human benchmark cognitive data to separate legitimate students from
 * dual-phone proxy cheaters.
 */
object GameTelemetryEngine {

    fun analyze(
        gameType: AttendanceGameType,
        interactions: List<GameInteractionRecord>,
        totalTrials: Int
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

        // Mean Absolute Consecutive Difference (MCD) & Oscillation Index
        var mcd = 0f
        if (latencies.size > 1) {
            var diffSum = 0f
            for (i in 0 until latencies.size - 1) {
                diffSum += abs(latencies[i + 1] - latencies[i])
            }
            mcd = diffSum / (latencies.size - 1)
        }
        val oscillationIndex = if (meanLatency > 0f) mcd / meanLatency else 0f

        // Inter-tap gaps
        var maxGap = 0L
        if (interactions.size > 1) {
            for (i in 0 until interactions.size - 1) {
                val gap = interactions[i + 1].timestampMs - interactions[i].timestampMs
                if (gap > maxGap) maxGap = gap
            }
        }

        // Radial offset / spatial precision
        val meanRadialOffset = interactions.map { it.touchDistancePx }.average().toFloat().coerceIn(0f, 100f) / 100f

        // Composite focus index (0.0 to 1.0)
        val accuracyFactor = accuracy / 100f
        val stabilityFactor = (1f - (cv * 0.5f)).coerceIn(0.2f, 1f)
        val focusIndex = (accuracyFactor * 0.7f + stabilityFactor * 0.3f).coerceIn(0f, 1f)

        // Specialized game-specific behavioral diagnostics
        val (classification, diagnosticReason) = evaluateGameBehavior(
            gameType = gameType,
            interactions = interactions,
            hits = hits,
            totalTrials = totalTrials,
            accuracy = accuracy,
            meanLatency = meanLatency,
            stdDev = stdDev,
            oscillationIndex = oscillationIndex,
            maxGap = maxGap
        )

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
            meanRadialOffset = meanRadialOffset,
            spatialDispersionStdDev = stdDev,
            focusIndex = focusIndex,
            classification = classification,
            diagnosticReason = diagnosticReason
        )
    }

    private fun evaluateGameBehavior(
        gameType: AttendanceGameType,
        interactions: List<GameInteractionRecord>,
        hits: Int,
        totalTrials: Int,
        accuracy: Int,
        meanLatency: Float,
        stdDev: Float,
        oscillationIndex: Float,
        maxGap: Long
    ): Pair<FocusClassification, String> {

        // Check for superhuman automated bot script (< 90ms reaction time with near zero variance)
        if (meanLatency < 90f && stdDev < 15f && hits >= 2) {
            return FocusClassification.AUTOMATED_SCRIPT to
                    "Superhuman latency (${meanLatency.toInt()}ms) detected. Automated script flagged."
        }

        // Evaluate by game archetype
        return when (gameType) {
            AttendanceGameType.CHIMP_MEMORY -> {
                // Single student taps sequentially with steady rhythm.
                // Proxy cheater looking back and forth has huge pause (gap > 1200ms) or misordered taps.
                when {
                    accuracy >= 70 && maxGap <= 1100L -> {
                        FocusClassification.FOCUSED_SINGLE_DEVICE to
                                "Fluid sequential recall (${meanLatency.toInt()}ms latency). Single-device verified."
                    }
                    accuracy < 55 -> {
                        FocusClassification.DIVIDED_ATTENTION_PROXY to
                                "Memory trace wiped out during blank phase. Divided attention flagged."
                    }
                    maxGap > 1300L -> {
                        FocusClassification.DIVIDED_ATTENTION_PROXY to
                                "Severe gaze stall between numbers (${maxGap}ms gap). Dual-phone proxy flagged."
                    }
                    else -> {
                        FocusClassification.FOCUSED_SINGLE_DEVICE to
                                "Sequence completed (${accuracy}% accuracy)."
                    }
                }
            }

            AttendanceGameType.STROOP_MATCH -> {
                // Single student resolves Stroop cards in 300-650ms.
                // Dual-phone proxy suffers cross-device semantic interference (latency > 1150ms or wrong swipes).
                when {
                    accuracy >= 62 && meanLatency <= 900f -> {
                        FocusClassification.FOCUSED_SINGLE_DEVICE to
                                "Strong cognitive inhibition (${meanLatency.toInt()}ms). Single-device verified."
                    }
                    accuracy < 50 -> {
                        FocusClassification.DIVIDED_ATTENTION_PROXY to
                                "Semantic conflict resolution failure under divided attention."
                    }
                    meanLatency > 1150f -> {
                        FocusClassification.DIVIDED_ATTENTION_PROXY to
                                "Excessive cognitive hesitation (${meanLatency.toInt()}ms). Saccadic delay flagged."
                    }
                    else -> {
                        FocusClassification.FOCUSED_SINGLE_DEVICE to
                                "Stroop swipe completed (${accuracy}% accuracy)."
                    }
                }
            }

            AttendanceGameType.SUBITIZING_RUSH -> {
                // Subitizing happens in < 400ms in foveal focus.
                // Cheater in peripheral vision experiences crowding and fails or takes > 1000ms.
                when {
                    accuracy >= 66 && meanLatency <= 900f -> {
                        FocusClassification.FOCUSED_SINGLE_DEVICE to
                                "Instant visual number sense (${meanLatency.toInt()}ms). Single-device verified."
                    }
                    accuracy < 50 -> {
                        FocusClassification.DIVIDED_ATTENTION_PROXY to
                                "Peripheral crowding failure. Gaze was not on device."
                    }
                    meanLatency > 1050f -> {
                        FocusClassification.DIVIDED_ATTENTION_PROXY to
                                "Subitizing window exceeded (${meanLatency.toInt()}ms). Serial counting delay."
                    }
                    else -> {
                        FocusClassification.FOCUSED_SINGLE_DEVICE to
                                "Quantity estimation passed (${accuracy}% accuracy)."
                    }
                }
            }

            AttendanceGameType.ORB_VECTOR_FLICK -> {
                // Single student touches orb and executes clean vector flick (< 35 deg error, latency < 800ms).
                // Proxy cheater taps statically without flicking, or has massive angle error (> 50 deg).
                val avgAngleError = interactions.map { it.vectorDeviationDeg }.average().toFloat()
                when {
                    hits >= 4 && avgAngleError <= 38f && meanLatency <= 900f -> {
                        FocusClassification.FOCUSED_SINGLE_DEVICE to
                                "Precise vector execution (${avgAngleError.toInt()}° err, ${meanLatency.toInt()}ms). Single-device verified."
                    }
                    avgAngleError > 50f -> {
                        FocusClassification.DIVIDED_ATTENTION_PROXY to
                                "Erratic flick vector (${avgAngleError.toInt()}° deviation). Cross-device interference."
                    }
                    hits <= 2 || meanLatency > 1150f -> {
                        FocusClassification.DIVIDED_ATTENTION_PROXY to
                                "Failed directional vector lock. Static tap or proxy hesitation flagged."
                    }
                    else -> {
                        FocusClassification.FOCUSED_SINGLE_DEVICE to
                                "Orb vector flick verified (${accuracy}% accuracy)."
                    }
                }
            }

            AttendanceGameType.CHUBB_CONTRAST -> {
                // Foveal contrast perception is fast (300-650ms).
                // Peripheral vision flattens contrast into muddy wash.
                when {
                    accuracy >= 66 && meanLatency <= 900f -> {
                        FocusClassification.FOCUSED_SINGLE_DEVICE to
                                "Foveal contrast illusion perceived (${meanLatency.toInt()}ms). Single-device verified."
                    }
                    accuracy < 50 -> {
                        FocusClassification.DIVIDED_ATTENTION_PROXY to
                                "Contrast illusion collapsed in peripheral vision. Dual-phone proxy flagged."
                    }
                    meanLatency > 1100f -> {
                        FocusClassification.DIVIDED_ATTENTION_PROXY to
                                "Prolonged saccadic latency (${meanLatency.toInt()}ms) on visual contrast."
                    }
                    else -> {
                        FocusClassification.FOCUSED_SINGLE_DEVICE to
                                "Contrast discrimination passed (${accuracy}% accuracy)."
                    }
                }
            }

            AttendanceGameType.POPOUT_ANOMALY -> {
                // Pre-attentive parallel pop-out happens in < 500ms.
                // Cheater looking across screens forced into serial scan (> 1100ms).
                when {
                    hits >= 3 && meanLatency <= 750f -> {
                        FocusClassification.FOCUSED_SINGLE_DEVICE to
                                "Pre-attentive pop-out triggered (${meanLatency.toInt()}ms). Single-device verified."
                    }
                    hits <= 1 -> {
                        FocusClassification.DIVIDED_ATTENTION_PROXY to
                                "Failed to acquire feature pop-out anomaly within attentional window."
                    }
                    meanLatency > 1200f -> {
                        FocusClassification.DIVIDED_ATTENTION_PROXY to
                                "Serial search latency (${meanLatency.toInt()}ms). Divided attention flagged."
                    }
                    else -> {
                        FocusClassification.FOCUSED_SINGLE_DEVICE to
                                "Anomaly pop-out acquired (${accuracy}% accuracy)."
                    }
                }
            }
        }
    }
}
