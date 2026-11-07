// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cstdint>
#include <type_traits>
#include "spectrum_fft.h"

namespace tap {
constexpr uint32_t kMagic = 0x48535450;
constexpr uint32_t kVersion = 2;
constexpr uint32_t kSlots = 4, kBlocks = 4, kFrames = 2048;
constexpr uint32_t kPcmBytes = kFrames * kMaxChannels * sizeof(float);
constexpr const char* kDirectory = "/data/adb/hyperspectrum-tap";
constexpr const char* kSymbol = "_ZN7android5Track15interceptBufferERKNS_19AudioBufferProvider6BufferE";
constexpr const char* kLibrary = "/system/lib64/libaudioflinger.so";
constexpr uint64_t kOffset = 0x2166f0;

// Fixed little endian protocol. All actors here are arm64 or the Python host.
struct Command {
    uint32_t magic = kMagic, version = kVersion, size = sizeof(Command), reserved = 0;
    uint64_t instance = 0, sequence = 0, not_after_ns = 0;
    uint32_t uid = 0, session = 0, io = 0, seconds = 0;
    uint64_t client_generation = 0;
};
static_assert(sizeof(Command) == 64);

enum class Health : uint32_t { Starting, WrongProcess, BinaryRejected, HookFailed, Ready };
struct TrackStats {
    uint32_t id = 0, owner_tid = 0, format = 0, rate = 0, channels = 0, io = 0;
    uint64_t blocks = 0, frames = 0, samples = 0, nonzero = 0, changed = 0;
    uint64_t drops = 0, contention = 0, discontinuities = 0, invalid_samples = 0;
    uint64_t last_ns = 0, max_gap_ns = 0;
    double rms_min = 0, rms_max = 0, rms_last = 0, peak = 0;
};
struct Report {
    uint32_t magic = kMagic, version = kVersion, size = sizeof(Report), pid = 0;
    uint64_t instance = 0, report_sequence = 0, now_ns = 0, command_sequence = 0;
    char source_build[64]{};
    // reserved bit0: latest block converted; bit1: optional hooks ready;
    // bit2/3: at least one provider registration/identity-matched commit seen.
    // bit4: independent48k/7.1.4 analysis renderer on pre-volume source.
    // bit5/6: latest FFT used App gain / unavailable-or-unsupported gain fallback.
    // bit7: ordinary mono48 analysis (source metadata keeps its original format).
    uint32_t health = 0, enabled = 0, uid = 0, session = 0, io = 0, reserved = 0;
    uint64_t deadline_ns = 0, entered = 0, eligible = 0, rejected = 0, app_muted = 0;
    uint64_t oversize = 0, slots_full = 0, copy_ns_sum = 0, copy_ns_max = 0;
    TrackStats tracks[kSlots]{};
    uint64_t client_generation = 0, fft_frames = 0;
    SpectrumFrame spectrum{};
};
static_assert(std::is_trivially_copyable_v<Report>);

inline bool valid(const Command& c, uint64_t instance, uint64_t previous, uint64_t now) {
    return c.magic == kMagic && c.version == kVersion && c.size == sizeof(Command) &&
           c.reserved <= 1 && c.instance == instance && c.sequence > previous &&
           (!c.reserved || (c.client_generation != 0 && c.seconds <= 2)) &&
           (c.seconds == 0 || (c.not_after_ns > now && c.not_after_ns - now <= 5000000000ull &&
                              c.seconds <= 60 && c.uid >= 10000 && c.uid < 1000000 &&
                              c.session > 0 && c.session < 0x7fffffff && c.io < 0x7fffffff));
}
constexpr const char* kUiSocket = "hyperspectrum.tap.v2";
struct UiPacket {
    uint32_t magic = kMagic, version = kVersion, type = 0, size = sizeof(UiPacket);
    uint64_t instance = 0, generation = 0, sequence = 0, ns = 0;
    uint32_t uid = 0, session = 0, io = 0, flags = 0;
    float bands[6]{}, rms = 0;
    uint32_t rate = 0, track = 0, channels = 0;
    uint64_t callbacks = 0, fft_frames = 0, drops = 0;
};
static_assert(sizeof(UiPacket) == 128 && offsetof(UiPacket, sequence) == 32 && offsetof(UiPacket, track) == 96);
}  // namespace tap
