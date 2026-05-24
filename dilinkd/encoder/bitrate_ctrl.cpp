#include "bitrate_ctrl.h"
#include <algorithm>
#include <ctime>

namespace dilink {

BitrateController::BitrateController(int initial_bitrate, int min_bitrate, int max_bitrate)
    : bitrate_(initial_bitrate)
    , min_bitrate_(min_bitrate)
    , max_bitrate_(max_bitrate) {}

int BitrateController::report_write_time(int64_t write_time_us) {
    int64_t now_ns = 0;
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) == 0) {
        now_ns = ts.tv_sec * 1'000'000'000L + ts.tv_nsec;
    }

    if (write_time_us > CONGESTION_THRESHOLD_US) {
        // Congestion detected: reduce 25%
        clean_since_us_ = 0;
        int new_br = std::max(min_bitrate_, bitrate_ * 3 / 4);
        if (new_br < bitrate_) {
            bitrate_ = new_br;
        }
    } else {
        // No congestion: track clean window
        if (clean_since_us_ == 0) {
            clean_since_us_ = now_ns / 1000; // convert to us
        }
    }

    // Gradual upgrade after sustained clean window
    if (clean_since_us_ > 0) {
        int64_t elapsed_us = (now_ns / 1000) - clean_since_us_;
        if (elapsed_us >= CLEAN_WINDOW_US) {
            int new_br = std::min(max_bitrate_, bitrate_ + BITRATE_STEP);
            if (new_br > bitrate_) {
                bitrate_ = new_br;
            }
            clean_since_us_ = now_ns / 1000; // reset timer
        }
    }

    return bitrate_;
}

void BitrateController::reset() {
    clean_since_us_ = 0;
    last_check_us_ = 0;
}

} // namespace dilink
