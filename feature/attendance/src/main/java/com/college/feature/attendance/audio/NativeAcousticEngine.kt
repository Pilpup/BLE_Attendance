package com.college.feature.attendance.audio

// JNI bridge to native C++ ARM NEON ultrasonic DSP engine
object NativeAcousticEngine {

    // Indicates whether the compiled native C++ library was loaded successfully
    var isNativeLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("acoustic_dsp")
            isNativeLoaded = true
        } catch (_: UnsatisfiedLinkError) {
            isNativeLoaded = false
        }
    }

    // K3 (golden-gated port): zero-allocation entry — writes the six result
    // doubles into the caller-reusable [out] buffer (out[0]=is_detected,
    // out[1]=snr_db, out[2]=target_power, out[3]=power_level_db,
    // out[4]=estimated_distance_cm, out[5]=noise_power). Same C++ math core
    // as the previous allocating entry (golden replay gate: PASS-EXACT,
    // 163/163 chunks, /home/wink/Projects/golden_dsp/replay_result_*.txt).
    external fun evaluateChirpNativeInto(
        samples: ShortArray,
        sampleRate: Int,
        targetFreqHz: Double,
        snrThresholdDb: Double,
        refPowerDb: Double,
        out: DoubleArray
    ): Boolean
}
