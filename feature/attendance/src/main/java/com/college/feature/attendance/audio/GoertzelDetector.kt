package com.college.feature.attendance.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max

// Detects isolated ultrasonic spectral energy and evaluates Signal-to-Noise Ratio
object GoertzelDetector {

    @Volatile
    private var cachedHannWindow: DoubleArray? = null

    // Returns a Hann window scaled by 2.0 to preserve calibrated peak signal amplitude
    private fun getOrCreateHannWindow(size: Int): DoubleArray {
        val existing = cachedHannWindow
        if (existing != null && existing.size == size) return existing
        val win = DoubleArray(size) { i ->
            val hann = 0.5 * (1.0 - cos(2.0 * PI * i / (size - 1)))
            2.0 * hann
        }
        cachedHannWindow = win
        return win
    }

    // Computes energy at a specific target frequency using the Goertzel algorithm (O(N) operations)
    fun computeFrequencyPower(
        samples: ShortArray,
        targetFreqHz: Double,
        sampleRate: Int,
        applyWindow: Boolean = false
    ): Double {
        val normalizedFreq = 2.0 * PI * targetFreqHz / sampleRate
        val coeff = 2.0 * cos(normalizedFreq)
        val window = if (applyWindow && samples.isNotEmpty()) getOrCreateHannWindow(samples.size) else null

        var sPrev = 0.0
        var sPrev2 = 0.0

        for (i in samples.indices) {
            val sampleVal = if (window != null) samples[i] * window[i] else samples[i].toDouble()
            val s0 = sampleVal + (coeff * sPrev) - sPrev2
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

    // Evaluates ultrasonic chirp and SNR in a single pass over audio samples using Hann windowing
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

        // Hann window suppresses sidelobe leakage into adjacent guard bands
        val window = getOrCreateHannWindow(samples.size)

        // Precompute filter coefficients for target and 4 guard bands (18.4 - 20.0 kHz)
        val f0Coeff = 2.0 * cos(2.0 * PI * targetFreqHz / sampleRate)
        val g1Coeff = 2.0 * cos(2.0 * PI * 18400.0 / sampleRate)
        val g2Coeff = 2.0 * cos(2.0 * PI * 18800.0 / sampleRate)
        val g3Coeff = 2.0 * cos(2.0 * PI * 19600.0 / sampleRate)
        val g4Coeff = 2.0 * cos(2.0 * PI * 20000.0 / sampleRate)

        var s0Prev = 0.0; var s0Prev2 = 0.0
        var g1Prev = 0.0; var g1Prev2 = 0.0
        var g2Prev = 0.0; var g2Prev2 = 0.0
        var g3Prev = 0.0; var g3Prev2 = 0.0
        var g4Prev = 0.0; var g4Prev2 = 0.0

        // Single pass over memory: updates all 5 frequencies simultaneously
        for (i in samples.indices) {
            val x = samples[i] * window[i]

            val s0 = x + (f0Coeff * s0Prev) - s0Prev2
            s0Prev2 = s0Prev
            s0Prev = s0

            val s1 = x + (g1Coeff * g1Prev) - g1Prev2
            g1Prev2 = g1Prev
            g1Prev = s1

            val s2 = x + (g2Coeff * g2Prev) - g2Prev2
            g2Prev2 = g2Prev
            g2Prev = s2

            val s3 = x + (g3Coeff * g3Prev) - g3Prev2
            g3Prev2 = g3Prev
            g3Prev = s3

            val s4 = x + (g4Coeff * g4Prev) - g4Prev2
            g4Prev2 = g4Prev
            g4Prev = s4
        }

        val targetPower = (s0Prev * s0Prev) + (s0Prev2 * s0Prev2) - (f0Coeff * s0Prev * s0Prev2)
        val g1 = (g1Prev * g1Prev) + (g1Prev2 * g1Prev2) - (g1Coeff * g1Prev * g1Prev2)
        val g2 = (g2Prev * g2Prev) + (g2Prev2 * g2Prev2) - (g2Coeff * g2Prev * g2Prev2)
        val g3 = (g3Prev * g3Prev) + (g3Prev2 * g3Prev2) - (g3Coeff * g3Prev * g3Prev2)
        val g4 = (g4Prev * g4Prev) + (g4Prev2 * g4Prev2) - (g4Coeff * g4Prev * g4Prev2)

        val powerLevelDb = 10.0 * log10(max(targetPower, 1.0))
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
