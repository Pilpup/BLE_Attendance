#ifndef ACOUSTIC_DSP_H
#define ACOUSTIC_DSP_H

#include <cstdint>
#include <cstddef>

#ifdef __cplusplus
extern "C" {
#endif

// Acoustic chirp detection output structure
struct AcousticDetectionResult {
    double is_detected;
    double snr_db;
    double target_power;
    double power_level_db;
    double estimated_distance_cm;
    double noise_power;
};

// Computes 5-bin Goertzel recurrence with SIMD acceleration
AcousticDetectionResult evaluate_chirp_native(
    const int16_t* samples,
    size_t num_samples,
    int sample_rate,
    double target_freq_hz,
    double snr_threshold_db,
    double ref_power_db
);

#ifdef __cplusplus
}
#endif

#endif // ACOUSTIC_DSP_H
