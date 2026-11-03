#include "acoustic_dsp.h"
#include <jni.h>
#include <cmath>
#include <vector>
#include <algorithm>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

namespace {

constexpr double PI_VAL = 3.14159265358979323846;
constexpr double COEFF_18400_HZ = -1.4862896506307135;
constexpr double COEFF_18800_HZ = -1.5540307997920397;
constexpr double COEFF_19200_HZ = -1.6180339887498950;
constexpr double COEFF_19600_HZ = -1.6781745265551944;
constexpr double COEFF_20000_HZ = -1.7320508075688772;
constexpr double MIN_DETECTION_POWER = 3.0e7;

// Calculates acoustic distance in centimeters using inverse-square sound propagation
inline double calculate_distance_cm(double power_db, double ref_power_db, double ref_dist_cm = 20.0) {
    if (power_db <= 0.0) return -1.0;
    double dist = ref_dist_cm * std::pow(10.0, (ref_power_db - power_db) / 20.0);
    return std::max(3.0, std::min(250.0, dist));
}

} // namespace

// Computes 5-bin Goertzel recurrence with SIMD acceleration
AcousticDetectionResult evaluate_chirp_native(
    const int16_t* samples,
    size_t num_samples,
    int sample_rate,
    double target_freq_hz,
    double snr_threshold_db,
    double ref_power_db
) {
    AcousticDetectionResult result = {0.0, 0.0, 0.0, 0.0, -1.0, 0.0};
    if (num_samples == 0 || samples == nullptr) {
        return result;
    }

    // Resolves recurrence filter coefficients
    const bool is_default_48k = (sample_rate == 48000 && std::abs(target_freq_hz - 19200.0) < 1.0);
    const double f0_coeff = is_default_48k ? COEFF_19200_HZ : 2.0 * std::cos(2.0 * PI_VAL * target_freq_hz / sample_rate);
    const double g1_coeff = (sample_rate == 48000) ? COEFF_18400_HZ : 2.0 * std::cos(2.0 * PI_VAL * 18400.0 / sample_rate);
    const double g2_coeff = (sample_rate == 48000) ? COEFF_18800_HZ : 2.0 * std::cos(2.0 * PI_VAL * 18800.0 / sample_rate);
    const double g3_coeff = (sample_rate == 48000) ? COEFF_19600_HZ : 2.0 * std::cos(2.0 * PI_VAL * 19600.0 / sample_rate);
    const double g4_coeff = (sample_rate == 48000) ? COEFF_20000_HZ : 2.0 * std::cos(2.0 * PI_VAL * 20000.0 / sample_rate);

    // Precomputes Hann window scaled by 2.0
    std::vector<double> window(num_samples);
    const double inv_n = 2.0 * PI_VAL / static_cast<double>(num_samples - 1);
    for (size_t i = 0; i < num_samples; ++i) {
        window[i] = 1.0 - std::cos(static_cast<double>(i) * inv_n);
    }

    double s0_prev = 0.0, s0_prev2 = 0.0;
    double g1_prev = 0.0, g1_prev2 = 0.0;
    double g2_prev = 0.0, g2_prev2 = 0.0;
    double g3_prev = 0.0, g3_prev2 = 0.0;
    double g4_prev = 0.0, g4_prev2 = 0.0;

#if defined(__ARM_NEON)
    // ARM NEON vectorization: evaluates 4 guard frequency bins in parallel
    const float guard_coeffs[4] = {
        static_cast<float>(g1_coeff),
        static_cast<float>(g2_coeff),
        static_cast<float>(g3_coeff),
        static_cast<float>(g4_coeff)
    };
    const float32x4_t v_guard_coeffs = vld1q_f32(guard_coeffs);
    float32x4_t v_guard_prev = vdupq_n_f32(0.0f);
    float32x4_t v_guard_prev2 = vdupq_n_f32(0.0f);

    for (size_t i = 0; i < num_samples; ++i) {
        const double x = static_cast<double>(samples[i]) * window[i];
        const float x_f = static_cast<float>(x);

        // Scalar recurrence for target chirp frequency f0
        const double s0 = x + (f0_coeff * s0_prev) - s0_prev2;
        s0_prev2 = s0_prev;
        s0_prev = s0;

        // Vector NEON FMA recurrence across 4 guard bins simultaneously
        const float32x4_t v_x = vdupq_n_f32(x_f);
        float32x4_t v_next = vmlaq_f32(v_x, v_guard_coeffs, v_guard_prev);
        v_next = vsubq_f32(v_next, v_guard_prev2);
        v_guard_prev2 = v_guard_prev;
        v_guard_prev = v_next;
    }

    // Extract guard bins from NEON vector register
    float final_prev[4], final_prev2[4];
    vst1q_f32(final_prev, v_guard_prev);
    vst1q_f32(final_prev2, v_guard_prev2);
    g1_prev = final_prev[0]; g1_prev2 = final_prev2[0];
    g2_prev = final_prev[1]; g2_prev2 = final_prev2[1];
    g3_prev = final_prev[2]; g3_prev2 = final_prev2[2];
    g4_prev = final_prev[3]; g4_prev2 = final_prev2[3];

#else
    // Portable SIMD-optimizable loop for x86_64 and generic architectures
    for (size_t i = 0; i < num_samples; ++i) {
        const double x = static_cast<double>(samples[i]) * window[i];

        const double s0 = x + (f0_coeff * s0_prev) - s0_prev2;
        s0_prev2 = s0_prev; s0_prev = s0;

        const double s1 = x + (g1_coeff * g1_prev) - g1_prev2;
        g1_prev2 = g1_prev; g1_prev = s1;

        const double s2 = x + (g2_coeff * g2_prev) - g2_prev2;
        g2_prev2 = g2_prev; g2_prev = s2;

        const double s3 = x + (g3_coeff * g3_prev) - g3_prev2;
        g3_prev2 = g3_prev; g3_prev = s3;

        const double s4 = x + (g4_coeff * g4_prev) - g4_prev2;
        g4_prev2 = g4_prev; g4_prev = s4;
    }
#endif

    // Evaluates energy across target chirp and guard frequencies
    const double target_power = (s0_prev * s0_prev) + (s0_prev2 * s0_prev2) - (f0_coeff * s0_prev * s0_prev2);
    const double g1 = (g1_prev * g1_prev) + (g1_prev2 * g1_prev2) - (g1_coeff * g1_prev * g1_prev2);
    const double g2 = (g2_prev * g2_prev) + (g2_prev2 * g2_prev2) - (g2_coeff * g2_prev * g2_prev2);
    const double g3 = (g3_prev * g3_prev) + (g3_prev2 * g3_prev2) - (g3_coeff * g3_prev * g3_prev2);
    const double g4 = (g4_prev * g4_prev) + (g4_prev2 * g4_prev2) - (g4_coeff * g4_prev * g4_prev2);

    const double power_level_db = 10.0 * std::log10(std::max(target_power, 1.0));
    const double noise_baseline = std::max((g1 + g2 + g3 + g4) / 4.0, 1.0);
    const double snr_db = 10.0 * std::log10(std::max(target_power, 1.0) / noise_baseline);

    const bool is_chirp = (target_power >= MIN_DETECTION_POWER && snr_db >= snr_threshold_db);
    const double distance_cm = is_chirp ? calculate_distance_cm(power_level_db, ref_power_db) : -1.0;

    result.is_detected = is_chirp ? 1.0 : 0.0;
    result.snr_db = is_chirp ? snr_db : 0.0;
    result.target_power = target_power;
    result.power_level_db = power_level_db;
    result.estimated_distance_cm = distance_cm;
    result.noise_power = noise_baseline;
    return result;
}

// JNI entrypoint for high-speed acoustic chirp evaluation
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_college_feature_attendance_audio_NativeAcousticEngine_evaluateChirpNative(
    JNIEnv* env,
    jobject /* this */,
    jshortArray samples_array,
    jint sample_rate,
    jdouble target_freq_hz,
    jdouble snr_threshold_db,
    jdouble ref_power_db
) {
    if (samples_array == nullptr) {
        return nullptr;
    }

    const jsize len = env->GetArrayLength(samples_array);
    jshort* samples = env->GetShortArrayElements(samples_array, nullptr);
    if (samples == nullptr) {
        return nullptr;
    }

    AcousticDetectionResult res = evaluate_chirp_native(
        reinterpret_cast<const int16_t*>(samples),
        static_cast<size_t>(len),
        sample_rate,
        target_freq_hz,
        snr_threshold_db,
        ref_power_db
    );

    env->ReleaseShortArrayElements(samples_array, samples, JNI_ABORT);

    jdoubleArray out_arr = env->NewDoubleArray(6);
    if (out_arr == nullptr) {
        return nullptr;
    }

    jdouble buf[6] = {
        res.is_detected,
        res.snr_db,
        res.target_power,
        res.power_level_db,
        res.estimated_distance_cm,
        res.noise_power
    };
    env->SetDoubleArrayRegion(out_arr, 0, 6, buf);
    return out_arr;
}
