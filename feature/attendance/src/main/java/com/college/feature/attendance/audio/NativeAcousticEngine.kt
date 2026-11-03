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

    // Evaluates acoustic chirp using C++ NEON vector intrinsics
    external fun evaluateChirpNative(
        samples: ShortArray,
        sampleRate: Int,
        targetFreqHz: Double,
        snrThresholdDb: Double,
        refPowerDb: Double
    ): DoubleArray?
}
