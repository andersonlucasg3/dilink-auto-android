#include "pacing.h"
#include <algorithm>

namespace dilink {

PacingSender::PacingSender(int target_bitrate_bps, int max_burst_bytes)
    : target_rate_bps_(target_bitrate_bps)
    , max_burst_(max_burst_bytes)
    , tokens_(max_burst_bytes)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    last_refill_ns_ = ts.tv_sec * 1'000'000'000L + ts.tv_nsec;
}

int64_t PacingSender::pace(size_t frame_size) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    int64_t now_ns = ts.tv_sec * 1'000'000'000L + ts.tv_nsec;

    // Refill tokens
    int64_t elapsed_ns = now_ns - last_refill_ns_;
    if (elapsed_ns > 0) {
        // tokens += bitrate(bps) * elapsed(s) / 8 (bits to bytes)
        int64_t new_tokens = target_rate_bps_ * elapsed_ns / 8'000'000'000LL;
        tokens_ = std::min<int64_t>(max_burst_, tokens_ + new_tokens);
        last_refill_ns_ = now_ns;
    }

    int64_t frame_sz = static_cast<int64_t>(frame_size);

    if (tokens_ >= frame_sz) {
        tokens_ -= frame_sz;
        return 0; // ready to send
    }

    // Not enough tokens: calculate wait time
    int64_t deficit = frame_sz - tokens_;
    tokens_ = 0;

    // Wait time = deficit (bytes) * 8 (bits) / bitrate (bps) in microseconds
    return deficit * 8'000'000LL / target_rate_bps_;
}

} // namespace dilink
