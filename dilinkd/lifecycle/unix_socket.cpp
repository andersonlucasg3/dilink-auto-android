#include "unix_socket.h"
#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/poll.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <ctime>

#define LOG_TAG "dilinkd.Lifecycle"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace dilink {

LifecycleChannel::LifecycleChannel() = default;

LifecycleChannel::~LifecycleChannel() {
    close_channel();
}

bool LifecycleChannel::connect(const char* host, int port) {
    fd_ = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd_ < 0) {
        LOGE("TCP socket() failed: %s", strerror(errno));
        return false;
    }

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET, host, &addr.sin_addr) != 1) {
        LOGE("inet_pton(%s) failed: %s", host, strerror(errno));
        close(fd_);
        fd_ = -1;
        return false;
    }

    int flags = fcntl(fd_, F_GETFL, 0);
    fcntl(fd_, F_SETFL, flags | O_NONBLOCK);

    int ret = ::connect(fd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    if (ret < 0 && errno != EINPROGRESS) {
        LOGE("TCP connect(%s:%d) failed: %s", host, port, strerror(errno));
        close(fd_);
        fd_ = -1;
        return false;
    }

    // Wait for non-blocking connect to complete
    pollfd pfd;
    pfd.fd = fd_;
    pfd.events = POLLOUT;
    int pr = poll(&pfd, 1, 2000); // 2s timeout
    if (pr <= 0) {
        LOGE("TCP connect timeout: %s:%d", host, port);
        close(fd_);
        fd_ = -1;
        return false;
    }

    int sock_err = 0;
    socklen_t err_len = sizeof(sock_err);
    getsockopt(fd_, SOL_SOCKET, SO_ERROR, &sock_err, &err_len);
    if (sock_err != 0) {
        LOGE("TCP connect error: %s", strerror(sock_err));
        close(fd_);
        fd_ = -1;
        return false;
    }

    fcntl(fd_, F_SETFL, flags); // restore original flags (blocking for reads)
    LOGI("Connected to lifecycle channel: %s:%d", host, port);
    return true;
}

bool LifecycleChannel::send(uint8_t msg_type, const uint8_t* payload, size_t payload_size) {
    if (fd_ < 0) return false;

    if (payload && payload_size > 0) {
        uint8_t header = msg_type;
        struct iovec iov[2] = {
            { &header, 1 },
            { const_cast<uint8_t*>(payload), payload_size }
        };
        struct msghdr msg{};
        msg.msg_iov = iov;
        msg.msg_iovlen = 2;
        ssize_t n = sendmsg(fd_, &msg, MSG_NOSIGNAL);
        return n == static_cast<ssize_t>(1 + payload_size);
    } else {
        ssize_t n = ::send(fd_, &msg_type, 1, MSG_NOSIGNAL);
        return n == 1;
    }
}

int LifecycleChannel::read_command(int timeout_ms) {
    if (fd_ < 0) return -1;

    pollfd pfd;
    pfd.fd = fd_;
    pfd.events = POLLIN;
    int pr = poll(&pfd, 1, timeout_ms);
    if (pr <= 0) return -1;

    uint8_t cmd;
    ssize_t n = recv(fd_, &cmd, 1, 0);
    if (n <= 0) return -1;

    return static_cast<int>(cmd);
}

bool LifecycleChannel::read_bytes(uint8_t* buffer, size_t n, int timeout_ms) {
    if (fd_ < 0) return false;
    int64_t deadline_ns = 0;
    if (timeout_ms > 0) {
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        deadline_ns = ts.tv_sec * 1'000'000'000L + ts.tv_nsec + timeout_ms * 1'000'000L;
    }
    size_t off = 0;
    while (off < n) {
        pollfd pfd;
        pfd.fd = fd_;
        pfd.events = POLLIN;
        int pr = poll(&pfd, 1, 100);
        if (pr < 0) return false;
        if (pr == 0) {
            if (timeout_ms > 0) {
                struct timespec ts;
                clock_gettime(CLOCK_MONOTONIC, &ts);
                if (ts.tv_sec * 1'000'000'000L + ts.tv_nsec >= deadline_ns) return false;
            }
            continue;
        }
        ssize_t r = recv(fd_, buffer + off, n - off, 0);
        if (r <= 0) return false;
        off += static_cast<size_t>(r);
    }
    return true;
}

void LifecycleChannel::close_channel() {
    if (fd_ >= 0) {
        close(fd_);
        fd_ = -1;
    }
}

} // namespace dilink
