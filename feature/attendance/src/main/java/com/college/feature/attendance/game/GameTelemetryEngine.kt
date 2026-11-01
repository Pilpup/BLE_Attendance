package com.college.feature.attendance.game

import com.college.feature.attendance.telemetry.CognitiveFocusReport
import com.college.feature.attendance.telemetry.ExpiredTargetEvent
import com.college.feature.attendance.telemetry.FocusClassification
import com.college.feature.attendance.telemetry.TapEvent
import kotlin.math.abs
import kotlin.math.sqrt

// Analyzes tap timing and spatial accuracy to verify single-device human operation
object GameTelemetryEngine {

    data class TelemetryMetrics(
        val totalTaps: Int,
        val totalExpired: Int,
        val totalSpawned: Int,
        val accuracy: Int,
        val meanLatency: Float,
        val medianLatency: Float,
        val stdDev: Float,
        val cv: Float,
        val mcd: Float,
        val oscillationIndex: Float,
        val maxInterTapGap: Long,
        val meanRadialOffset: Float,
        val spatialDispersionStdDev: Float,
        val superhumanTapRatio: Float
    )

    fun extractMetrics(
        taps: List<TapEvent>,
        expired: List<ExpiredTargetEvent>,
        totalTargetsGoal: Int = 18
    ): TelemetryMetrics {
        val totalTaps = taps.size
        val totalExpired = expired.size
        val totalSpawned = (totalTaps + totalExpired).coerceAtLeast(totalTargetsGoal)
        val accuracy = if (totalSpawned > 0) ((totalTaps * 100) / totalSpawned) else 0

        if (taps.isEmpty()) {
            return TelemetryMetrics(
                totalTaps = 0,
                totalExpired = totalExpired,
                totalSpawned = totalSpawned,
                accuracy = 0,
                meanLatency = 0f,
                medianLatency = 0f,
                stdDev = 0f,
                cv = 0f,
                mcd = 0f,
                oscillationIndex = 0f,
                maxInterTapGap = 0L,
                meanRadialOffset = 0f,
                spatialDispersionStdDev = 0f,
                superhumanTapRatio = 0f
            )
        }

        // Calculate reaction latencies and identify superhuman tap speeds
        val latencies = ArrayList<Float>(taps.size)
        var sumLat = 0f
        var superhumanTaps = 0
        for (i in taps.indices) {
            val lat = taps[i].latencyMs.toFloat()
            latencies.add(lat)
            sumLat += lat
            if (lat < 130f) superhumanTaps++
        }
        val count = latencies.size
        val meanLatency = sumLat / count
        val superhumanTapRatio = superhumanTaps.toFloat() / count.toFloat()

        // Calculate variance and standard deviation of reaction times
        var varianceSum = 0f
        for (i in 0 until count) {
            val diff = latencies[i] - meanLatency
            varianceSum += diff * diff
        }
        val variance = if (count > 1) varianceSum / (count - 1) else 0f
        val stdDev = sqrt(variance)
        val cv = if (meanLatency > 0f) stdDev / meanLatency else 0f

        // Calculate Mean Absolute Consecutive Difference (MCD) to detect sawtooth oscillations
        var mcd = 0f
        if (count > 1) {
            var diffSum = 0f
            for (i in 0 until count - 1) {
                diffSum += abs(latencies[i + 1] - latencies[i])
            }
            mcd = diffSum / (count - 1)
        }
        val oscillationIndex = if (meanLatency > 0f) mcd / meanLatency else 0f

        // Compute median reaction latency
        latencies.sort()
        val medianLatency = when {
            count % 2 == 1 -> latencies[count / 2]
            else -> (latencies[count / 2 - 1] + latencies[count / 2]) / 2f
        }

        // Measure maximum pause between consecutive taps
        val tapTimes = ArrayList<Long>(count)
        for (i in taps.indices) {
            tapTimes.add(taps[i].tapTimeMillis)
        }
        tapTimes.sort()
        var maxInterTapGap = 0L
        for (i in 0 until tapTimes.size - 1) {
            val gap = tapTimes[i + 1] - tapTimes[i]
            if (gap > maxInterTapGap) maxInterTapGap = gap
        }

        // Calculate spatial touch dispersion relative to orb centers
        var sumRadial = 0f
        for (i in taps.indices) {
            sumRadial += taps[i].normalizedRadialOffset
        }
        val meanRadialOffset = sumRadial / count
        var radialVarSum = 0f
        for (i in taps.indices) {
            val diff = taps[i].normalizedRadialOffset - meanRadialOffset
            radialVarSum += diff * diff
        }
        val radialVariance = if (count > 1) radialVarSum / (count - 1) else 0f
        val spatialDispersionStdDev = sqrt(radialVariance)

        return TelemetryMetrics(
            totalTaps = totalTaps,
            totalExpired = totalExpired,
            totalSpawned = totalSpawned,
            accuracy = accuracy,
            meanLatency = meanLatency,
            medianLatency = medianLatency,
            stdDev = stdDev,
            cv = cv,
            mcd = mcd,
            oscillationIndex = oscillationIndex,
            maxInterTapGap = maxInterTapGap,
            meanRadialOffset = meanRadialOffset,
            spatialDispersionStdDev = spatialDispersionStdDev,
            superhumanTapRatio = superhumanTapRatio
        )
    }

    // Aggregates sub-scores across rhythm, gaze continuity, and completion
    fun computeCredibilityScore(metrics: TelemetryMetrics): Int {
        if (metrics.totalTaps == 0) return 0

        // Score rhythm stability from oscillation index
        val rhythmScore = when {
            metrics.oscillationIndex < 0.55f -> 30
            metrics.oscillationIndex < 0.75f -> 20
            metrics.oscillationIndex < 0.95f -> 10
            else -> 0
        }

        // Score gaze continuity from maximum pause between taps
        val continuityScore = when {
            metrics.maxInterTapGap < 2500L -> 25
            metrics.maxInterTapGap < 3500L -> 18
            metrics.maxInterTapGap < 4500L -> 8
            else -> 0
        }

        // Score target completion rate under decay timer
        val expirationRate = if (metrics.totalSpawned > 0) {
            metrics.totalExpired.toFloat() / metrics.totalSpawned.toFloat()
        } else 1f
        val completionScore = when {
            expirationRate <= 0.35f -> 20
            expirationRate <= 0.50f -> 15
            expirationRate <= 0.65f -> 10
            expirationRate <= 0.80f -> 4
            else -> 0
        }

        // Score mean latency within natural human reaction bounds
        val latencyScore = when {
            metrics.meanLatency in 220f..850f -> 15
            metrics.meanLatency in 850f..1050f -> 10
            metrics.meanLatency > 1050f -> 4
            else -> 0
        }

        // Score touch dispersion within human thumb contact bounds
        val dispersionScore = when {
            metrics.spatialDispersionStdDev in 0.02f..0.50f -> 10
            else -> 2
        }

        return rhythmScore + continuityScore + completionScore + latencyScore + dispersionScore
    }

    fun analyze(
        taps: List<TapEvent>,
        expired: List<ExpiredTargetEvent>,
        totalTargetsGoal: Int = 18
    ): CognitiveFocusReport {
        val totalSpawned = (taps.size + expired.size).coerceAtLeast(totalTargetsGoal)
        if (taps.isEmpty()) {
            return CognitiveFocusReport(
                totalTaps = 0,
                totalSpawns = totalSpawned,
                hits = 0,
                misses = totalSpawned,
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
                diagnosticReason = "No targets were tapped during the attendance challenge."
            )
        }

        val metrics = extractMetrics(taps, expired, totalTargetsGoal)
        val credibilityScore = computeCredibilityScore(metrics)
        val focusIndex = (credibilityScore / 100f).coerceIn(0f, 1f)

        // Detect automated scripts with near-zero latency variance or superhuman tap speed
        val isZeroVarianceBot = (metrics.totalTaps >= 6 && metrics.stdDev < 15f && metrics.spatialDispersionStdDev < 0.02f)
        val isSuperhumanBot = metrics.superhumanTapRatio > 0.25f

        // Classify behavioral patterns as human single-device or divided-attention proxy
        val (classification, diagnosticReason) = when {
            isZeroVarianceBot || isSuperhumanBot -> {
                FocusClassification.AUTOMATED_SCRIPT to
                        "Robotic timing or superhuman tap speed detected. Automated script flagged."
            }

            metrics.accuracy >= 55 -> {
                FocusClassification.FOCUSED_SINGLE_DEVICE to
                        "Single-device human verified (${metrics.totalTaps}/${metrics.totalSpawned} hits, ${metrics.accuracy}% accuracy)."
            }

            else -> {
                FocusClassification.DIVIDED_ATTENTION_PROXY to
                        "Divided attention proxy: Only ${metrics.totalTaps}/${metrics.totalSpawned} targets hit (${metrics.accuracy}% accuracy). Multi-device attention split."
            }
        }

        return CognitiveFocusReport(
            totalTaps = metrics.totalTaps,
            totalSpawns = metrics.totalSpawned,
            hits = metrics.totalTaps,
            misses = metrics.totalExpired,
            accuracyPercentage = metrics.accuracy,
            meanLatencyMs = metrics.meanLatency,
            medianLatencyMs = metrics.medianLatency,
            latencyStdDevMs = metrics.stdDev,
            coeffOfVariation = metrics.cv,
            meanConsecutiveDiffMs = metrics.mcd,
            latencyOscillationIndex = metrics.oscillationIndex,
            maxInterTapGapMs = metrics.maxInterTapGap,
            meanRadialOffset = metrics.meanRadialOffset,
            spatialDispersionStdDev = metrics.spatialDispersionStdDev,
            focusIndex = focusIndex,
            classification = classification,
            diagnosticReason = diagnosticReason
        )
    }
}
