#pragma once
#include <cstdint>
#include <pthread.h>

namespace dilink {

// Watchdog thread: monitors pipeline health.
// If the pipeline hasn't sent a frame in timeout_ms, triggers restart.
class Watchdog {
public:
    Watchdog(int timeout_ms = 5000);

    // Start the watchdog thread. Calls on_timeout() if pipeline stalls.
    bool start(void (*on_timeout)(void*), void* user_data);

    // Call from pipeline thread each time a frame is sent successfully.
    void heartbeat();

    // Stop the watchdog.
    void stop();

    bool is_running() const { return running_; }

private:
    static void* thread_func(void* arg);

    int timeout_ms_;
    volatile int64_t last_heartbeat_ns_ = 0;
    volatile bool running_ = false;
    pthread_t thread_ = 0;

    void (*on_timeout_)(void*) = nullptr;
    void* user_data_ = nullptr;
};

} // namespace dilink
