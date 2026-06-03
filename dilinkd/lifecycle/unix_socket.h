#pragma once
#include <cstdint>
#include <cstddef>

namespace dilink {

// TCP lifecycle channel for phone app ↔ daemon lifecycle communication.
// Daemon connects to phone's VirtualDisplayClient on host:port (e.g. 127.0.0.1:19647).
// Phone sends CMD_STOP (0xFF), daemon sends MSG_DISPLAY_READY, etc.
class LifecycleChannel {
public:
    LifecycleChannel();
    ~LifecycleChannel();

    LifecycleChannel(const LifecycleChannel&) = delete;
    LifecycleChannel& operator=(const LifecycleChannel&) = delete;

    // Connect to the phone app's lifecycle listener via TCP.
    bool connect(const char* host, int port);

    // Send a message to the phone app.
    bool send(uint8_t msg_type, const uint8_t* payload = nullptr, size_t payload_size = 0);

    // Read one byte command (blocking with timeout). Returns -1 on error/timeout.
    int read_command(int timeout_ms);

    // Check if connected.
    bool is_connected() const { return fd_ >= 0; }

    // Close the channel.
    void close_channel();

    int fd() const { return fd_; }

private:
    int fd_ = -1;
};

} // namespace dilink
