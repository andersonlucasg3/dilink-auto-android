#pragma once
#include <cstdint>
#include <cstddef>

namespace dilink {

// Unix domain socket for phone app ↔ daemon lifecycle communication.
// The phone app connects and sends control commands (CMD_STOP, etc.).
// The daemon sends responses (MSG_DISPLAY_READY, MSG_STACK_EMPTY, etc.).
class LifecycleChannel {
public:
    LifecycleChannel();
    ~LifecycleChannel();

    LifecycleChannel(const LifecycleChannel&) = delete;
    LifecycleChannel& operator=(const LifecycleChannel&) = delete;

    // Connect to the phone app's lifecycle listener.
    // phone_host: phone IP (for TCP) or path for Unix socket.
    bool connect(const char* socket_path);

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
