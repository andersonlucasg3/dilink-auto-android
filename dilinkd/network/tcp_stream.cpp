#include "tcp_stream.h"
#include <android/log.h>
#include <arpa/inet.h>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <poll.h>

#define LOG_TAG "dilinkd.TcpStream"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace dilink {

static bool set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return false;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK) >= 0;
}

TcpStream::TcpStream() = default;

TcpStream::~TcpStream() {
    close_all();
}

bool TcpStream::listen(int port) {
    server_fd_ = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server_fd_ < 0) {
        LOGE("socket() failed: %s", strerror(errno));
        return false;
    }

    int reuse = 1;
    setsockopt(server_fd_, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(static_cast<uint16_t>(port));

    if (bind(server_fd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        LOGE("bind(:%d) failed: %s", port, strerror(errno));
        close(server_fd_);
        server_fd_ = -1;
        return false;
    }

    if (::listen(server_fd_, 1) < 0) {
        LOGE("listen() failed: %s", strerror(errno));
        close(server_fd_);
        server_fd_ = -1;
        return false;
    }

    set_nonblocking(server_fd_);
    port_ = port;
    LOGI("Listening on :%d", port);
    return true;
}

bool TcpStream::connect(const char* host, int port, int timeout_ms) {
    client_fd_ = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (client_fd_ < 0) { LOGE("socket() failed: %s", strerror(errno)); return false; }

    set_nonblocking(client_fd_);
    set_socket_options();

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET, host, &addr.sin_addr) != 1) {
        LOGE("inet_pton(%s) failed", host); close_client(); return false;
    }

    int ret = ::connect(client_fd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
    if (ret < 0 && errno != EINPROGRESS) {
        LOGE("connect(%s:%d) failed: %s", host, port, strerror(errno));
        close_client(); return false;
    }

    pollfd pfd{client_fd_, POLLOUT, 0};
    int pr = poll(&pfd, 1, timeout_ms);
    if (pr <= 0) { LOGE("connect(%s:%d) timeout", host, port); close_client(); return false; }

    int sock_err = 0; socklen_t err_len = sizeof(sock_err);
    getsockopt(client_fd_, SOL_SOCKET, SO_ERROR, &sock_err, &err_len);
    if (sock_err != 0) { close_client(); return false; }

    LOGI("Connected to %s:%d", host, port);
    return true;
}

bool TcpStream::accept(int timeout_ms) {
    if (server_fd_ < 0) return false;

    // epoll for timeout
    int epfd = epoll_create1(EPOLL_CLOEXEC);
    epoll_event ev{};
    ev.events = EPOLLIN;
    ev.data.fd = server_fd_;
    epoll_ctl(epfd, EPOLL_CTL_ADD, server_fd_, &ev);

    epoll_event events[1];
    int n = epoll_wait(epfd, events, 1, timeout_ms);
    close(epfd);

    if (n <= 0) return false;

    client_fd_ = ::accept4(server_fd_, nullptr, nullptr, SOCK_CLOEXEC);
    if (client_fd_ < 0) {
        LOGE("accept() failed: %s", strerror(errno));
        return false;
    }

    set_nonblocking(client_fd_);
    set_socket_options();

    // Get peer info for logging
    sockaddr_in peer{};
    socklen_t peer_len = sizeof(peer);
    if (getpeername(client_fd_, reinterpret_cast<sockaddr*>(&peer), &peer_len) == 0) {
        char ip[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &peer.sin_addr, ip, sizeof(ip));
        LOGI("Accepted connection from %s:%d", ip, ntohs(peer.sin_port));
    }

    return true;
}

bool TcpStream::write_all(const uint8_t* data, size_t size) {
    if (client_fd_ < 0) return false;

    size_t offset = 0;
    while (offset < size) {
        ssize_t n = send(client_fd_, data + offset, size - offset,
                         MSG_NOSIGNAL | MSG_DONTWAIT);
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // Would block — caller should retry after epoll signals writable
                return false;
            }
            LOGE("send() failed: %s", strerror(errno));
            return false;
        }
        offset += static_cast<size_t>(n);
    }
    return true;
}

ssize_t TcpStream::read_some(uint8_t* buffer, size_t max_size) {
    if (client_fd_ < 0) return -1;

    ssize_t n = recv(client_fd_, buffer, max_size, MSG_DONTWAIT);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return 0; // no data available
        }
        LOGE("recv() failed: %s", strerror(errno));
        return -1;
    }
    return n; // 0 = EOF (client disconnected)
}

void TcpStream::close_client() {
    if (client_fd_ >= 0) {
        close(client_fd_);
        client_fd_ = -1;
    }
}

void TcpStream::close_all() {
    close_client();
    if (server_fd_ >= 0) {
        close(server_fd_);
        server_fd_ = -1;
    }
}

bool TcpStream::set_socket_options(int send_buf, int recv_buf) {
    if (client_fd_ < 0) return false;

    int opt = 1;
    setsockopt(client_fd_, IPPROTO_TCP, TCP_NODELAY, &opt, sizeof(opt));

    setsockopt(client_fd_, SOL_SOCKET, SO_SNDBUF, &send_buf, sizeof(send_buf));
    setsockopt(client_fd_, SOL_SOCKET, SO_RCVBUF, &recv_buf, sizeof(recv_buf));

    // Enable keepalive
    int ka = 1;
    setsockopt(client_fd_, SOL_SOCKET, SO_KEEPALIVE, &ka, sizeof(ka));

    int ka_idle = 10;
    setsockopt(client_fd_, IPPROTO_TCP, TCP_KEEPIDLE, &ka_idle, sizeof(ka_idle));

    int ka_intvl = 5;
    setsockopt(client_fd_, IPPROTO_TCP, TCP_KEEPINTVL, &ka_intvl, sizeof(ka_intvl));

    int ka_cnt = 3;
    setsockopt(client_fd_, IPPROTO_TCP, TCP_KEEPCNT, &ka_cnt, sizeof(ka_cnt));

    return true;
}

} // namespace dilink
