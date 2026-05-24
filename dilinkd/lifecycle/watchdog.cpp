#include "watchdog.h"
#include <android/log.h>
#include <ctime>

#define LOG_TAG "dilinkd.Watchdog"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace dilink {

static int64_t now_ns() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1'000'000'000L + ts.tv_nsec;
}

Watchdog::Watchdog(int timeout_ms)
    : timeout_ms_(timeout_ms) {}

bool Watchdog::start(void (*on_timeout)(void*), void* user_data) {
    on_timeout_ = on_timeout;
    user_data_ = user_data;
    last_heartbeat_ns_ = now_ns();
    running_ = true;

    int ret = pthread_create(&thread_, nullptr, thread_func, this);
    if (ret != 0) {
        LOGE("pthread_create failed: %d", ret);
        running_ = false;
        return false;
    }
    return true;
}

void Watchdog::heartbeat() {
    last_heartbeat_ns_ = now_ns();
}

void Watchdog::stop() {
    running_ = false;
    if (thread_) {
        pthread_join(thread_, nullptr);
        thread_ = 0;
    }
}

void* Watchdog::thread_func(void* arg) {
    auto* wd = static_cast<Watchdog*>(arg);
    int64_t timeout_ns = static_cast<int64_t>(wd->timeout_ms_) * 1'000'000L;

    while (wd->running_) {
        struct timespec ts = {1, 0}; // check every 1 second
        nanosleep(&ts, nullptr);

        int64_t elapsed = now_ns() - wd->last_heartbeat_ns_;
        if (elapsed > timeout_ns && wd->on_timeout_) {
            LOGE("Watchdog timeout: %lld ms since last heartbeat",
                 (long long)(elapsed / 1'000'000));
            wd->on_timeout_(wd->user_data_);
            break;
        }
    }
    return nullptr;
}

} // namespace dilink
