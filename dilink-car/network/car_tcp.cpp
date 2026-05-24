#include "car_tcp.h"
#include <android/log.h>
#include <arpa/inet.h>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <netdb.h>
#include <poll.h>

#define LOG_TAG "dilink-car.CarTcp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace dilink {
namespace car {

CarTcp::CarTcp() = default;

CarTcp::~CarTcp() { close_conn(); }

static bool set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return false;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK) >= 0;
}

bool CarTcp::connect(const char* host, int port, int timeout_ms) {
    fd_ = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd_ < 0) {
        LOGE("socket() failed: %s", strerror(errno));
        return false;
    }

    set_low_latency();
    set_nonblocking(fd_);

    // Resolve hostname
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port));

    // Try numeric first, then hostname
    if (inet_pton(AF_INET, host, &addr.sin_addr) != 1) {
        addrinfo hints{};
        hints.ai_family = AF_INET;
        hints.ai_socktype = SOCK_STREAM;
        addrinfo* result = nullptr;
        int ret = getaddrinfo(host, nullptr, &hints, &result);
        if (ret != 0 || !result) {
            LOGE("getaddrinfo(%s) failed: %s", host, gai_strerror(ret));
            close_conn();
            return false;
        }
        addr.sin_addr = reinterpret_cast<sockaddr_in*>(result->ai_addr)->sin_addr;
        freeaddrinfo(result);
    }

    int ret = ::connect(fd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    if (ret < 0 && errno != EINPROGRESS) {
        LOGE("connect(%s:%d) failed: %s", host, port, strerror(errno));
        close_conn();
        return false;
    }

    // Wait for connection completion
    pollfd pfd;
    pfd.fd = fd_;
    pfd.events = POLLOUT;
    int pr = poll(&pfd, 1, timeout_ms);
    if (pr <= 0) {
        LOGE("connect(%s:%d) timeout", host, port);
        close_conn();
        return false;
    }

    int sock_err = 0;
    socklen_t err_len = sizeof(sock_err);
    getsockopt(fd_, SOL_SOCKET, SO_ERROR, &sock_err, &err_len);
    if (sock_err != 0) {
        LOGE("connect(%s:%d) error: %s", host, port, strerror(sock_err));
        close_conn();
        return false;
    }

    connected_ = true;
    LOGI("Connected to %s:%d", host, port);
    return true;
}

ssize_t CarTcp::read_some(uint8_t* buffer, size_t max_size) {
    if (fd_ < 0 || !connected_) return -1;
    ssize_t n = recv(fd_, buffer, max_size, MSG_DONTWAIT);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
        return -1;
    }
    if (n == 0) { connected_ = false; return -1; } // EOF
    return n;
}

bool CarTcp::write_all(const uint8_t* data, size_t size) {
    if (fd_ < 0 || !connected_) return false;
    size_t off = 0;
    while (off < size) {
        ssize_t n = send(fd_, data + off, size - off, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) return false;
            connected_ = false;
            return false;
        }
        off += static_cast<size_t>(n);
    }
    return true;
}

void CarTcp::close_conn() {
    if (fd_ >= 0) {
        close(fd_);
        fd_ = -1;
    }
    connected_ = false;
}

bool CarTcp::set_low_latency(int send_buf, int recv_buf) {
    if (fd_ < 0) return false;
    int opt = 1;
    setsockopt(fd_, IPPROTO_TCP, TCP_NODELAY, &opt, sizeof(opt));
    setsockopt(fd_, SOL_SOCKET, SO_SNDBUF, &send_buf, sizeof(send_buf));
    setsockopt(fd_, SOL_SOCKET, SO_RCVBUF, &recv_buf, sizeof(recv_buf));
    return true;
}

} // namespace car
} // namespace dilink
