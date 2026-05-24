#pragma once
#include <cstdint>
#include <cstddef>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>

namespace dilink {

// Non-blocking TCP server using epoll.
// Binds to 0.0.0.0:port, accepts exactly one connection, then sends/receives.
class TcpStream {
public:
    TcpStream();
    ~TcpStream();

    TcpStream(const TcpStream&) = delete;
    TcpStream& operator=(const TcpStream&) = delete;

    // Bind and listen on the given port. Non-blocking.
    bool listen(int port);

    // Accept a single connection with timeout (milliseconds).
    // Returns true when connected.
    bool accept(int timeout_ms);

    // Get the file descriptor for epoll integration.
    int fd() const { return client_fd_; }

    // Get the epoll handle for this stream's server socket (during accept phase).
    int server_fd() const { return server_fd_; }

    // Write all bytes. Non-blocking. Returns true on success.
    // Uses edge-triggered epoll for completion notification.
    bool write_all(const uint8_t* data, size_t size);

    // Read available bytes into buffer. Returns bytes read, -1 on error, 0 on EOF.
    // Non-blocking — only reads what's available without blocking.
    ssize_t read_some(uint8_t* buffer, size_t max_size);

    // Close the connection but keep the server socket alive for re-accept.
    void close_client();

    // Full close (server + client).
    void close_all();

    // Set TCP_NODELAY + send/receive buffer sizes.
    bool set_socket_options(int send_buf = 262144, int recv_buf = 262144);

    bool is_connected() const { return client_fd_ >= 0; }

private:
    int server_fd_ = -1;
    int client_fd_ = -1;
    int port_ = 0;
};

} // namespace dilink
