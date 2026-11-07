// SPDX-License-Identifier: Apache-2.0
#include "ui_bridge.h"
#include "io.h"
#include <cstdio>
#include <sys/stat.h>
#include <sys/un.h>

namespace tap {
UiBridge::~UiBridge() { disconnect(); }
void UiBridge::disconnect() { if (fd_ >= 0) close(fd_); fd_ = -1; demanded_ = false; generation_ = 0; }
void UiBridge::connect_if_needed(const Report& r, uint64_t now) {
    if (fd_ >= 0 || now < next_connect_ || r.health != uint32_t(Health::Ready)) return;
    next_connect_ = now + 1000000000;
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) { error_ = errno; return; }
    sockaddr_un addr{}; addr.sun_family = AF_UNIX;
    std::memcpy(addr.sun_path + 1, kUiSocket, std::strlen(kUiSocket));
    if (connect(fd, reinterpret_cast<sockaddr*>(&addr), socklen_t(offsetof(sockaddr_un, sun_path) + 1 + std::strlen(kUiSocket)))) {
        error_ = errno; close(fd); return;
    }
    ucred cred{}; socklen_t len = sizeof(cred); struct stat owner{};
    char context[128]{}, path[128]{};
    bool ok = stat("/data/user_de/0/com.android.systemui", &owner) == 0;
    if (!ok) ok = stat("/data/user/0/com.android.systemui", &owner) == 0;
    ok = ok && getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &len) == 0 &&
         len == sizeof(cred) && cred.uid == owner.st_uid && cred.pid > 1;
    std::snprintf(path, sizeof(path), "/proc/%d/cmdline", cred.pid);
    len = sizeof(context);
    ok = ok && exact_file(path, "com.android.systemui") &&
         getsockopt(fd, SOL_SOCKET, SO_PEERSEC, context, &len) == 0 && len < sizeof(context);
    const char* process_label = ok ? systemui_process_label(owner.st_uid, cred.uid, context) : nullptr;
    // Check the process label separately: platform_app sockets omit the MLS
    // categories, while system_app uses the same label on socket and process.
    std::snprintf(path, sizeof(path), "/proc/%d/attr/current", cred.pid);
    ok = process_label && exact_file(path, process_label);
    if (!ok) { error_ = EACCES; close(fd); return; }
    UiPacket hello; hello.type = 1; hello.instance = r.instance;
    std::memcpy(reinterpret_cast<unsigned char*>(&hello) + 32, r.source_build, 64);
    hello.track = r.health; hello.channels = r.pid;
    if (!send_value(fd, hello)) { error_ = EPIPE; close(fd); return; }
    fd_ = fd; error_ = 0; last_request_ = now; last_sequence_ = generation_ = 0; demanded_ = false;
}
bool UiBridge::expired(uint64_t now) const { return fd_ >= 0 && now - last_request_ > 2000000000; }
bool UiBridge::request(const Report& r, Command& c, uint64_t sequence) {
    UiPacket p;
    if (!receive(fd_, p) || p.magic != kMagic || p.version != kVersion || p.type != 2 ||
        p.size != sizeof(p) || p.instance != r.instance || !p.generation ||
        p.sequence <= last_sequence_ || p.flags > 1 ||
        (p.flags && (p.uid < 10000 || p.uid >= 1000000 || !p.session || p.session >= 0x7fffffff || p.io >= 0x7fffffff))) return false;
    last_sequence_ = p.sequence; last_request_ = monotonic_ns(); generation_ = p.generation;
    demanded_ = p.flags != 0;
    c = Command{}; c.instance = r.instance; c.sequence = sequence;
    c.reserved = 1; c.client_generation = p.generation;
    c.not_after_ns = last_request_ + 5000000000;
    if (demanded_) { c.uid = p.uid; c.session = p.session; c.io = p.io; c.seconds = 2; }
    return true;
}
bool UiBridge::publish(const Report& r) {
    if (fd_ < 0) return true;
    UiPacket p; p.type = 3; p.instance = r.instance; p.generation = r.client_generation;
    p.sequence = r.spectrum.sequence; p.ns = r.spectrum.ns;
    p.uid = r.uid; p.session = r.session; p.io = r.spectrum.io;
    p.flags = demanded_ && generation_ == r.client_generation && r.enabled &&
              r.spectrum.sequence && r.now_ns >= r.spectrum.ns && r.now_ns - r.spectrum.ns < 250000000;
    std::memcpy(p.bands, r.spectrum.bands, sizeof(p.bands)); p.rms = r.spectrum.rms;
    p.rate = r.spectrum.rate; p.track = r.spectrum.track; p.channels = r.spectrum.channels;
    p.callbacks = r.eligible; p.fft_frames = r.fft_frames;
    for (const auto& t : r.tracks) p.drops += t.drops;
    return send_value(fd_, p);
}
}
