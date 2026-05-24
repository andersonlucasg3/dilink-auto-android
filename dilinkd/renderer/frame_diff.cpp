#include "frame_diff.h"
#include <cstdlib>
#include <cstring>
#include <algorithm>

namespace dilink {

FrameDiff::FrameDiff() { std::memset(prev_hist_, 0, sizeof(prev_hist_)); }

FrameDiff::~FrameDiff() = default;

void FrameDiff::reset() {
    std::memset(prev_hist_, 0, sizeof(prev_hist_));
    has_prev_ = false;
}

bool FrameDiff::has_changed(const uint8_t* buffer, int width, int height, int threshold) {
    // Build 16-bin luminance histogram from subsampled pixels.
    // Sample every 4th pixel for speed (1/16 of total pixels).
    // Each pixel: compute approximate luminance from RGB.
    uint32_t hist[16] = {};

    int step = 4;
    for (int y = 0; y < height; y += step) {
        for (int x = 0; x < width; x += step) {
            int idx = (y * width + x) * 4; // RGBA
            uint8_t r = buffer[idx];
            uint8_t g = buffer[idx + 1];
            uint8_t b = buffer[idx + 2];
            // Approximate luminance: 0.299*R + 0.587*G + 0.114*B
            // Quantize to 4 bits (16 bins)
            uint8_t luma = static_cast<uint8_t>((r * 77 + g * 150 + b * 29) >> 8);
            ++hist[luma >> 4];
        }
    }

    if (!has_prev_) {
        std::memcpy(prev_hist_, hist, sizeof(hist));
        has_prev_ = true;
        return true;
    }

    // Compare histograms: sum of absolute differences in bin counts
    uint32_t diff = 0;
    uint32_t total_samples = 0;
    for (int i = 0; i < 16; ++i) {
        diff += std::abs(static_cast<int32_t>(hist[i]) - static_cast<int32_t>(prev_hist_[i]));
        total_samples += hist[i];
    }

    std::memcpy(prev_hist_, hist, sizeof(hist));

    if (total_samples == 0) return false;

    // Changed if more than threshold% of samples moved bins
    uint32_t threshold_count = total_samples * threshold / 256;
    return diff > threshold_count;
}

} // namespace dilink
