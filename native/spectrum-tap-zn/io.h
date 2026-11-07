// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "identity.h"
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

namespace tap {
inline uint64_t monotonic_ns() {
    timespec ts{};
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return uint64_t(ts.tv_sec) * 1000000000 + uint64_t(ts.tv_nsec);
}
inline bool transfer(int fd, void* data, size_t count, bool sending) {
    auto* cursor = static_cast<unsigned char*>(data);
    const uint64_t until = monotonic_ns() + 1000000000;
    while (count) {
        if (monotonic_ns() >= until) return false;
        pollfd p{fd, short(sending ? POLLOUT : POLLIN), 0};
        int polled;
        do { polled = poll(&p, 1, 100); } while (polled < 0 && errno == EINTR);
        if (polled < 0 || (p.revents & (POLLNVAL | POLLERR))) return false;
        if (polled == 0) continue;
        const ssize_t n = sending ? send(fd, cursor, count, MSG_NOSIGNAL | MSG_DONTWAIT)
                                  : recv(fd, cursor, count, MSG_DONTWAIT);
        if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (n <= 0) return false;
        cursor += n; count -= size_t(n);
    }
    return true;
}
template <typename T> bool receive(int fd, T& value) { return transfer(fd, &value, sizeof(T), false); }
template <typename T> bool send_value(int fd, const T& value) {
    return transfer(fd, const_cast<T*>(&value), sizeof(T), true);
}
inline bool exact_file(const char* path, const char* expected) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char buf[256]{}; const ssize_t n = read(fd, buf, sizeof(buf)); close(fd);
    return n >= 0 && size_t(n) < sizeof(buf) && exact_identity(buf, size_t(n), expected);
}
}  // namespace tap
