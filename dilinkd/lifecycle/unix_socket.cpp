#include "unix_socket.h"
#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/poll.h>

#define LOG_TAG "dilinkd.Lifecycle"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace dilink {

LifecycleChannel::LifecycleChannel() = default;

LifecycleChannel::~LifecycleChannel() {
    close_channel();
}

bool LifecycleChannel::connect(const char* socket_path) {
    fd_ = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd_ < 0) {
        LOGE("Unix socket() failed: %s", strerror(errno));
        return false;
    }

    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, socket_path, sizeof(addr.sun_path) - 1);

    int flags = fcntl(fd_, F_GETFL, 0);
    fcntl(fd_, F_SETFL, flags | O_NONBLOCK);

    int ret = ::connect(fd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    if (ret < 0 && errno != EINPROGRESS) {
        LOGE("Unix connect(%s) failed: %s", socket_path, strerror(errno));
        close(fd_);
        fd_ = -1;
        return false;
    }

    // Wait for connection to complete (non-blocking connect)
    pollfd pfd;
    pfd.fd = fd_;
    pfd.events = POLLOUT;
    int pr = poll(&pfd, 1, 2000); // 2s timeout
    if (pr <= 0) {
        LOGE("Unix connect timeout: %s", socket_path);
        close(fd_);
        fd_ = -1;
        return false;
    }

    // Check for connection error
    int sock_err = 0;
    socklen_t err_len = sizeof(sock_err);
    getsockopt(fd_, SOL_SOCKET, SO_ERROR, &sock_err, &err_len);
    if (sock_err != 0) {
        LOGE("Unix connect error: %s", strerror(sock_err));
        close(fd_);
        fd_ = -1;
        return false;
    }

    fcntl(fd_, F_SETFL, flags & ~O_NONBLOCK); // back to blocking for simple reads
    LOGI("Connected to lifecycle channel: %s", socket_path);
    return true;
}

bool LifecycleChannel::send(uint8_t msg_type, const uint8_t* payload, size_t payload_size) {
    if (fd_ < 0) return false;

    // Simple protocol: 1 byte msg_type + optional 4B size + payload
    uint8_t header[5];
    header[0] = msg_type;
    if (payload && payload_size > 0) {
        header[1] = static_cast<uint8_t>((payload_size >> 24) & 0xFF);
        header[2] = static_cast<uint8_t>((payload_size >> 16) & 0xFF);
        header[3] = static_cast<uint8_t>((payload_size >> 8) & 0xFF);
        header[4] = static_cast<uint8_t>(payload_size & 0xFF);

        struct iovec iov[2] = {
            { header, 5 },
            { const_cast<uint8_t*>(payload), payload_size }
        };
        struct msghdr msg{};
        msg.msg_iov = iov;
        msg.msg_iovlen = 2;
        ssize_t n = sendmsg(fd_, &msg, MSG_NOSIGNAL);
        return n == static_cast<ssize_t>(5 + payload_size);
    } else {
        ssize_t n = ::send(fd_, header, 1, MSG_NOSIGNAL);
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

void LifecycleChannel::close_channel() {
    if (fd_ >= 0) {
        close(fd_);
        fd_ = -1;
    }
}

} // namespace dilink
