#pragma once
#include <cstdint>

namespace dilink {

// Adaptive bitrate controller based on TCP write time.
// Measures how long the TCP write takes per frame.
// If write time > threshold → congestion → reduce bitrate.
// If write time stays low for 5 seconds → increase bitrate.
class BitrateController {
public:
    BitrateController(int initial_bitrate = 5'000'000,
                       int min_bitrate = 2'000'000,
                       int max_bitrate = 8'000'000);

    // Report the TCP write time for the last frame (in microseconds).
    // Returns the new bitrate if it changed, or current bitrate.
    int report_write_time(int64_t write_time_us);

    // Current bitrate in bps.
    int bitrate() const { return bitrate_; }

    // Reset state (call on session start).
    void reset();

private:
    static constexpr int64_t CONGESTION_THRESHOLD_US = 15000;  // 15ms
    static constexpr int64_t CLEAN_WINDOW_US = 5'000'000;      // 5 seconds
    static constexpr int     BITRATE_STEP = 1'000'000;          // 1Mbps

    int bitrate_;
    int min_bitrate_;
    int max_bitrate_;
    int64_t clean_since_us_ = 0;
    int64_t last_check_us_ = 0;
};

} // namespace dilink
