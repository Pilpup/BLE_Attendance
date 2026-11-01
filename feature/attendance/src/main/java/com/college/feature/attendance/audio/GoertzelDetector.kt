package com.college.feature.attendance.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max

// Detects isolated ultrasonic spectral energy and evaluates Signal-to-Noise Ratio
object GoertzelDetector {

    // Computes energy at a specific target frequency using the Goertzel algorithm (O(N) operations)
    fun computeFrequencyPower(samples: ShortArray, targetFreqHz: Double, sampleRate: Int): Double {
        val normalizedFreq = 2.0 * PI * targetFreqHz / sampleRate
        val coeff = 2.0 * cos(normalizedFreq)

        var sPrev = 0.0
        var sPrev2 = 0.0

        for (sample in samples) {
            val s0 = sample.toDouble() + (coeff * sPrev) - sPrev2
            sPrev2 = sPrev
            sPrev = s0
        }

        return (sPrev * sPrev) + (sPrev2 * sPrev2) - (coeff * sPrev * sPrev2)
    }

    // Minimum absolute power threshold to reject ambient room noise, whispers, and rustling
    const val MIN_DETECTION_POWER = 3.0e7

    // Calibrated acoustic reference: power level at 20 cm reference separation on mobile hardware
    const val CALIBRATED_REF_POWER_DB = 98.0
    const val CALIBRATED_REF_DISTANCE_CM = 20.0

    // Calculates acoustic distance in centimeters using inverse-square sound propagation
    fun calculateDistanceCm(
        powerDb: Double,
        refPowerDb: Double = CALIBRATED_REF_POWER_DB,
        refDistCm: Double = CALIBRATED_REF_DISTANCE_CM
    ): Double {
        if (powerDb <= 0.0) return -1.0
        val dist = refDistCm * Math.pow(10.0, (refPowerDb - powerDb) / 20.0)
        return dist.coerceIn(3.0, 250.0)
    }

    // Evaluates whether a candidate ultrasonic chirp exceeds ambient background noise and calculates distance
    fun evaluateChirp(
        samples: ShortArray,
        targetFreqHz: Double = UltrasonicToneSynthesizer.DEFAULT_FREQUENCY_HZ,
        sampleRate: Int = UltrasonicToneSynthesizer.DEFAULT_SAMPLE_RATE,
        snrThresholdDb: Double = 6.0,
        refPowerDb: Double = CALIBRATED_REF_POWER_DB
    ): UltrasonicDetectionResult {
        if (samples.isEmpty()) {
            return UltrasonicDetectionResult(
                isDetected = false,
                snrDb = 0.0,
                targetPower = 0.0,
                powerLevelDb = 0.0,
                estimatedDistanceCm = -1.0
            )
        }

        val targetPower = computeFrequencyPower(samples, targetFreqHz, sampleRate)
        val powerLevelDb = 10.0 * log10(max(targetPower, 1.0))

        // In-band guard frequencies strictly within standard mobile microphone active response (18.4 - 20.0 kHz)
        val g1 = computeFrequencyPower(samples, 18400.0, sampleRate)
        val g2 = computeFrequencyPower(samples, 18800.0, sampleRate)
        val g3 = computeFrequencyPower(samples, 19600.0, sampleRate)
        val g4 = computeFrequencyPower(samples, 20000.0, sampleRate)

        val noiseBaseline = ((g1 + g2 + g3 + g4) / 4.0).coerceAtLeast(1.0)
        val snrDb = 10.0 * log10(max(targetPower, 1.0) / noiseBaseline)

        // Must exceed both the relative SNR threshold AND the absolute energy floor
        val isChirp = targetPower >= MIN_DETECTION_POWER && snrDb >= snrThresholdDb

        // Calibrated acoustic distance in centimeters using inverse-square sound propagation
        val distanceCm = if (isChirp) {
            calculateDistanceCm(powerLevelDb, refPowerDb)
        } else {
            -1.0
        }

        return UltrasonicDetectionResult(
            isDetected = isChirp,
            snrDb = if (isChirp) snrDb else 0.0,
            targetPower = targetPower,
            powerLevelDb = powerLevelDb,
            estimatedDistanceCm = distanceCm
        )
    }

    // Computes outlier-rejected trimmed mean statistics from a buffer of acoustic burst powers (in dB)
    fun computeTrimmedMeanStats(
        burstPowersDb: List<Double>,
        refPowerDb: Double = CALIBRATED_REF_POWER_DB,
        trimRatio: Double = 0.15
    ): AcousticStatisticalSummary {
        if (burstPowersDb.isEmpty()) {
            return AcousticStatisticalSummary(
                sampleCount = 0,
                trimmedCount = 0,
                rawMinPowerDb = 0.0,
                rawMaxPowerDb = 0.0,
                medianPowerDb = 0.0,
                trimmedMeanPowerDb = 0.0,
                estimatedDistanceCm = -1.0,
                greenCount = 0,
                blueCount = 0,
                greenPercentage = 0,
                bluePercentage = 0,
                verdict = EnsembleVerdict.COLLECTING_SAMPLES
            )
        }

        val sorted = burstPowersDb.sorted()
        val total = sorted.size
        val trimCount = if (total >= 6) (total * trimRatio).toInt() else 0

        val trimmed = if (trimCount > 0 && total > trimCount * 2) {
            sorted.subList(trimCount, total - trimCount)
        } else {
            sorted
        }

        val trimmedMeanPowerDb = trimmed.average()
        val medianPowerDb = sorted[total / 2]
        val estimatedDist = calculateDistanceCm(trimmedMeanPowerDb, refPowerDb)

        var greenCount = 0
        var blueCount = 0
        for (powerDb in sorted) {
            val dist = calculateDistanceCm(powerDb, refPowerDb)
            if (dist <= 25.0) greenCount++ else blueCount++
        }

        val greenPct = (greenCount * 100) / total
        val bluePct = (blueCount * 100) / total

        val verdict = when {
            total < 8 -> EnsembleVerdict.COLLECTING_SAMPLES
            greenPct >= 65 -> EnsembleVerdict.CONFIRMED_SAME_DESK
            bluePct >= 65 -> EnsembleVerdict.CONFIRMED_NEXT_BENCH
            else -> EnsembleVerdict.AMBIGUOUS_BOUNDARY
        }

        return AcousticStatisticalSummary(
            sampleCount = total,
            trimmedCount = trimmed.size,
            rawMinPowerDb = sorted.first(),
            rawMaxPowerDb = sorted.last(),
            medianPowerDb = medianPowerDb,
            trimmedMeanPowerDb = trimmedMeanPowerDb,
            estimatedDistanceCm = estimatedDist,
            greenCount = greenCount,
            blueCount = blueCount,
            greenPercentage = greenPct,
            bluePercentage = bluePct,
            verdict = verdict
        )
    }
}

// Data class holding statistical distribution of multi-burst acoustic sampling
data class AcousticStatisticalSummary(
    val sampleCount: Int,
    val trimmedCount: Int,
    val rawMinPowerDb: Double,
    val rawMaxPowerDb: Double,
    val medianPowerDb: Double,
    val trimmedMeanPowerDb: Double,
    val estimatedDistanceCm: Double,
    val greenCount: Int,
    val blueCount: Int,
    val greenPercentage: Int,
    val bluePercentage: Int,
    val verdict: EnsembleVerdict
)

// Classification verdict based on statistical consensus
enum class EnsembleVerdict {
    COLLECTING_SAMPLES,
    CONFIRMED_SAME_DESK,
    CONFIRMED_NEXT_BENCH,
    AMBIGUOUS_BOUNDARY
}

// Result container for ultrasonic signal detection
data class UltrasonicDetectionResult(
    val isDetected: Boolean,
    val snrDb: Double,
    val targetPower: Double,
    val powerLevelDb: Double = 0.0,
    val estimatedDistanceCm: Double = -1.0
)
