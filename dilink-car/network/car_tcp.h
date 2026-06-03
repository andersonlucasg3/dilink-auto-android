#pragma once
#include <cstdint>
#include <cstddef>
#include <sys/socket.h>

namespace dilink {
namespace car {

// Non-blocking TCP client for car → daemon communication.
// Connects to phone daemon's video (9638) or input (9639) ports.
// Uses sendmsg/recv with epoll integration.
class CarTcp {
public:
    CarTcp();
    ~CarTcp();

    CarTcp(const CarTcp&) = delete;
    CarTcp& operator=(const CarTcp&) = delete;

    // Connect to host:port. Non-blocking with timeout.
    bool connect(const char* host, int port, int timeout_ms = 5000);

    // Listen on port (server mode for reversed connection).
    bool listen(int port);

    // Accept a single connection with timeout (milliseconds).
    bool accept(int timeout_ms);

    // Server fd for accept.
    int server_fd() const { return server_fd_; }
    bool is_listening() const { return server_fd_ >= 0; }

    // Close only the client connection, keep server fd.
    void close_client();

    // Close both client and server fds.
    void close_all();

    // Read available data. Returns bytes read, 0 if none, -1 on error/EOF.
    ssize_t read_some(uint8_t* buffer, size_t max_size);

    // Write all bytes. Non-blocking. Returns true on success.
    bool write_all(const uint8_t* data, size_t size);

    // Get fd for epoll.
    int fd() const { return fd_; }
    bool is_connected() const { return fd_ >= 0 && connected_; }

    // Close connection.
    void close_conn();

    // Set socket options for low latency.
    bool set_low_latency(int send_buf = 262144, int recv_buf = 262144);

private:
    int fd_ = -1;
    int server_fd_ = -1;
    bool connected_ = false;
};

} // namespace car
} // namespace dilink
