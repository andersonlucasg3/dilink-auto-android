#pragma once
#include <cstdint>
#include <ctime>

namespace dilink {

// Token-bucket pacer for TCP video streaming.
// Prevents bursty sends that can overflow WiFi buffers and cause drops.
class PacingSender {
public:
    // target_bitrate_bps: target send rate
    // max_burst_bytes: maximum bytes to send at once before pacing
    PacingSender(int target_bitrate_bps, int max_burst_bytes = 32768);

    // Called before sending a frame of the given size.
    // Returns the number of microseconds to wait before sending, or 0 if ready now.
    int64_t pace(size_t frame_size);

private:
    int target_rate_bps_;
    int max_burst_;
    int64_t tokens_;         // available tokens (bytes)
    int64_t last_refill_ns_; // last token refill time
};

} // namespace dilink
