// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "protocol.h"
#include "spatial_renderer.h"
#include "app_gain.h"
#include "mono48.h"
#include <memory>
#include <atomic>
#include <cstddef>
#include <cstring>

namespace tap {
struct Metadata {
    uint32_t uid, session, id, io, state, usage, stream, type, format, channels, mask, rate;
    uint64_t frame_size;
    bool app_muted;
    // Raw power-mean fallback or validated converted stereo, before track gain.
    uint32_t stage = 0;
    uint32_t app_gain = kUnityAppGain;
    bool app_gain_known = true;
};
struct Block {
    uint64_t ns = 0, ordinal = 0;
    uint32_t frames = 0, format = 0, channels = 0, rate = 0, io = 0, stage = 0, mask = 0;
    uint32_t app_gain = kUnityAppGain;
    bool app_gain_known = false;
    alignas(16) unsigned char pcm[kPcmBytes]{};
};
struct Slot {
    std::atomic<uint32_t> id{0}, owner{0}, write{0}, read{0};
    std::atomic<uint64_t> drops{0}, contention{0};
    // Only the claimed producer writes ordinal; a different TID is rejected.
    uint64_t ordinal = 0, converted_until = 0;
    Block blocks[kBlocks]{};
};
class Engine {
public:
    std::atomic<uint64_t> deadline{0};
    void observe(const Metadata&, const void*, size_t, uint32_t tid, uint64_t now);
    bool configure(const Command&, uint64_t now);
    bool renew(const Command&, uint64_t now);
    bool clear_signal(Report* report = nullptr);
    void stop();
    void drain(Report&);
    void counters(Report&) const;
    void record_cost(uint64_t);
private:
    std::atomic<uint64_t> epoch_{0};
    std::atomic<uint32_t> in_flight_{0}, uid_{0}, session_{0}, io_{0};
    Slot slots_[kSlots]{};
    uint64_t last_hash_[kSlots]{}, last_ordinal_[kSlots]{};
    uint32_t last_format_[kSlots]{}, last_rate_[kSlots]{}, last_channels_[kSlots]{}, last_stage_[kSlots]{}, last_mask_[kSlots]{}, analysis_[kSlots]{};
    SpectrumFft fft_[kSlots];
    std::unique_ptr<SpatialRenderer> spatial_[kSlots];
    std::unique_ptr<Mono48> mono_[kSlots];
    SpectrumFrame fft_latest_[kSlots]{};
    uint32_t fft_gain_flags_[kSlots]{};
    uint64_t fft_frames_ = 0, fft_sequence_ = 0;
    std::atomic<uint64_t> entered_{0}, eligible_{0}, rejected_{0}, app_muted_{0};
    std::atomic<uint64_t> oversize_{0}, slots_full_{0}, cost_sum_{0}, cost_max_{0};
};
static_assert(std::atomic<uint64_t>::is_always_lock_free);
static_assert(std::atomic<uint32_t>::is_always_lock_free);
}  // namespace tap
