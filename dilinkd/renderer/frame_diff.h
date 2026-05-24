#pragma once
#include <cstdint>
#include <cstddef>

namespace dilink {

// Detects whether a frame has changed significantly from the previous one.
// Uses a fast luminance histogram comparison. Frame dimensions must be even
// (guaranteed by H.264 alignment).
class FrameDiff {
public:
    FrameDiff();
    ~FrameDiff();

    // Returns true if the frame differs significantly from the previous frame.
    // buffer: raw RGBA/GL-readable pixel data
    // width, height: frame dimensions in pixels
    // threshold: 0-255, higher = more sensitive. Default 8 (1.5% change triggers).
    bool has_changed(const uint8_t* buffer, int width, int height, int threshold = 8);

    // Reset history (call after keyframe or scene change).
    void reset();

private:
    // 16-bin luminance histogram (4 bits per channel average)
    uint32_t prev_hist_[16] = {};
    bool     has_prev_ = false;
};

} // namespace dilink
