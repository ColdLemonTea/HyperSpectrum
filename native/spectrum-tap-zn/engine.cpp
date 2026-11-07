// SPDX-License-Identifier: Apache-2.0
#include "engine.h"
#include <algorithm>
#include <cmath>
#include <thread>
#include <chrono>
#include <ctime>
#include <new>

namespace tap {
void Engine::observe(const Metadata& m, const void* raw, size_t frames, uint32_t tid, uint64_t now) {
    const auto epoch = epoch_.load(std::memory_order_acquire);
    if (now >= deadline.load(std::memory_order_acquire)) return;
    in_flight_.fetch_add(1, std::memory_order_acq_rel);
    // stop/configure waits for all readers that passed this second check.
    if (epoch != epoch_.load(std::memory_order_acquire) ||
        now >= deadline.load(std::memory_order_acquire)) {
        in_flight_.fetch_sub(1, std::memory_order_release);
        return;
    }
    struct Exit {
        std::atomic<uint32_t>& counter;
        Engine& engine;
        uint64_t start;
        ~Exit() {
            timespec ts{};
            if (clock_gettime(CLOCK_MONOTONIC, &ts) == 0) {
                const auto end = uint64_t(ts.tv_sec) * 1000000000 + uint64_t(ts.tv_nsec);
                if (end >= start) engine.record_cost(end - start);
            }
            counter.fetch_sub(1, std::memory_order_release);
        }
    } exit{in_flight_, *this, now};
    entered_.fetch_add(1, std::memory_order_relaxed);
    if (m.uid != uid_.load(std::memory_order_relaxed) ||
        m.session != session_.load(std::memory_order_relaxed) ||
        (io_.load(std::memory_order_relaxed) && m.io != io_.load(std::memory_order_relaxed))) return;
    if (m.stage > 1 || (m.stage == 1 && (m.channels != 2 || m.mask != 3)) ||
        m.state != 6 || m.type != 0 || m.usage != 1 || m.stream != 3 || !m.id ||
        !tid || !raw || !frames || !m.io || m.io >= 0x7fffffff || (m.format != 1 && m.format != 5) ||
        m.channels == 0 || m.channels > kMaxChannels ||
        (m.mask & 0xc0000000u) || // Only positional masks; never guess index-mask layouts.
        m.channels != uint32_t(__builtin_popcount(m.mask)) ||
        m.frame_size != m.channels * (m.format == 5 ? 4u : 2u) ||
        m.rate < 8000 || m.rate > 192000) {
        rejected_.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    if (m.app_muted || (m.app_gain_known && m.app_gain==0)) { app_muted_.fetch_add(1, std::memory_order_relaxed); return; }
    eligible_.fetch_add(1, std::memory_order_relaxed);
    Slot* selected = nullptr;
    for (auto& slot : slots_) {
        uint32_t id = slot.id.load(std::memory_order_acquire);
        if (id == 0) {
            slot.id.compare_exchange_strong(id, m.id, std::memory_order_acq_rel);
            id = slot.id.load(std::memory_order_acquire);
        }
        if (id == m.id) { selected = &slot; break; }
    }
    if (!selected) { slots_full_.fetch_add(1, std::memory_order_relaxed); return; }
    auto& s = *selected;
    uint32_t owner = 0;
    s.owner.compare_exchange_strong(owner, tid, std::memory_order_acq_rel);
    if (s.owner.load(std::memory_order_acquire) != tid) {
        s.contention.fetch_add(1, std::memory_order_relaxed); return;
    }
    // Both callbacks must be on this slot's claimed mixer thread. A successful
    // converted block suppresses raw blocks briefly; expiry restores fallback.
    if (!m.stage && now < s.converted_until) return;
    if (m.stage) s.converted_until = now + 200000000;
    ++s.ordinal;
    if (frames > kFrames) {
        oversize_.fetch_add(1, std::memory_order_relaxed);
        s.drops.fetch_add(1, std::memory_order_relaxed); return;
    }
    const uint32_t w = s.write.load(std::memory_order_relaxed);
    if (w - s.read.load(std::memory_order_acquire) >= kBlocks) {
        s.drops.fetch_add(1, std::memory_order_relaxed); return;
    }
    Block& b = s.blocks[w % kBlocks];
    b.ns = now; b.ordinal = s.ordinal; b.frames = static_cast<uint32_t>(frames);
    b.format = m.format; b.channels = m.channels; b.rate = m.rate; b.io = m.io; b.stage = m.stage; b.mask = m.mask;
    b.app_gain = m.app_gain; b.app_gain_known = m.app_gain_known;
    std::memcpy(b.pcm, raw, frames * m.frame_size);
    s.write.store(w + 1, std::memory_order_release);
}

void Engine::stop() {
    deadline.store(0, std::memory_order_release);
    epoch_.fetch_add(1, std::memory_order_acq_rel);
}

bool Engine::configure(const Command& c, uint64_t now) {
    stop();
    // Non-RT control only. Bounded quiescence; never reset a live producer.
    for (unsigned i = 0; in_flight_.load(std::memory_order_acquire); ++i) {
        if (i == 200) return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    for (auto& s : slots_) {
        s.id.store(0); s.owner.store(0); s.write.store(0); s.read.store(0);
        s.drops.store(0); s.contention.store(0); s.ordinal = 0; s.converted_until = 0;
        for (auto& b : s.blocks) { std::memset(b.pcm, 0, sizeof(b.pcm)); b.frames = 0; }
    }
    std::memset(last_hash_, 0, sizeof(last_hash_));
    std::memset(last_ordinal_, 0, sizeof(last_ordinal_));
    std::memset(last_format_, 0, sizeof(last_format_));
    std::memset(last_rate_, 0, sizeof(last_rate_));
    std::memset(last_channels_, 0, sizeof(last_channels_));
    std::memset(last_stage_, 0, sizeof(last_stage_));
    for (auto& fft : fft_) fft.reset();
    for (auto& spatial : spatial_) if (spatial) spatial->reset();
    for (auto& mono : mono_) if (mono) mono->reset();
    std::memset(last_mask_,0,sizeof(last_mask_));
    std::memset(analysis_,0,sizeof(analysis_));
    for (auto& frame : fft_latest_) frame = SpectrumFrame{};
    std::memset(fft_gain_flags_,0,sizeof(fft_gain_flags_));
    fft_frames_ = 0;
    entered_.store(0); eligible_.store(0); rejected_.store(0); app_muted_.store(0);
    oversize_.store(0); slots_full_.store(0); cost_sum_.store(0); cost_max_.store(0);
    uid_.store(c.uid); session_.store(c.session); io_.store(c.io);
    if (c.seconds) deadline.store(now + uint64_t(c.seconds) * 1000000000, std::memory_order_release);
    return true;
}

bool Engine::renew(const Command& c, uint64_t now) {
    if (deadline.load(std::memory_order_acquire) <= now || c.uid != uid_.load() ||
        c.session != session_.load() || c.io != io_.load() || c.seconds == 0) return false;
    deadline.store(now + uint64_t(c.seconds) * 1000000000, std::memory_order_release);
    return true;
}
bool Engine::clear_signal(Report* report) {
    if (deadline.load() != 0 || in_flight_.load(std::memory_order_acquire)) return false;
    if (report) drain(*report);
    for (auto& s : slots_) {
        for (auto& b : s.blocks) std::memset(b.pcm, 0, sizeof(b.pcm));
        s.read.store(s.write.load(std::memory_order_acquire), std::memory_order_release);
    }
    for (auto& f : fft_) f.reset();
    for (auto& spatial : spatial_) if (spatial) spatial->reset();
    for (auto& mono : mono_) if (mono) mono->reset();
    for (auto& f : fft_latest_) f = SpectrumFrame{};
    std::memset(fft_gain_flags_,0,sizeof(fft_gain_flags_));
    if(report) { report->spectrum=SpectrumFrame{};report->reserved&=~241u; }
    return true;
}

void Engine::record_cost(uint64_t ns) {
    cost_sum_.fetch_add(ns, std::memory_order_relaxed);
    auto previous = cost_max_.load(std::memory_order_relaxed);
    // Best effort single CAS; telemetry must never spin on a mixer thread.
    if (ns > previous) cost_max_.compare_exchange_strong(previous, ns, std::memory_order_relaxed);
}

void Engine::drain(Report& r) {
    for (uint32_t i = 0; i < kSlots; ++i) {
        auto& s = slots_[i]; auto& t = r.tracks[i];
        t.id = s.id.load(std::memory_order_acquire); t.owner_tid = s.owner.load();
        t.drops = s.drops.load(); t.contention = s.contention.load();
        auto rd = s.read.load(std::memory_order_relaxed);
        const auto end = s.write.load(std::memory_order_acquire);
        for (; rd != end; ++rd) {
            auto& b = s.blocks[rd % kBlocks];
            if ((last_ordinal_[i] && b.ordinal != last_ordinal_[i] + 1) ||
                (t.blocks && (last_format_[i] != b.format || last_rate_[i] != b.rate ||
                              last_channels_[i] != b.channels || t.io != b.io || last_stage_[i] != b.stage || last_mask_[i] != b.mask)) ||
                (t.last_ns && b.ns > t.last_ns + 100000000)) {
                ++t.discontinuities; fft_[i].reset(); fft_latest_[i] = SpectrumFrame{}; fft_gain_flags_[i]=0;
                if(spatial_[i])spatial_[i]->reset();
                if(mono_[i])mono_[i]->reset();
            }
            last_ordinal_[i] = b.ordinal;
            last_format_[i] = b.format; last_rate_[i] = b.rate; last_channels_[i] = b.channels; last_stage_[i] = b.stage; last_mask_[i] = b.mask;
            const auto count = size_t(b.frames) * b.channels;
            const auto bytes = count * (b.format == 5 ? 4u : 2u);
            double sum = 0, peak = 0; uint64_t nonzero = 0, invalid = 0;
            uint64_t hash = 14695981039346656037ull;
            for (size_t k = 0; k < bytes; ++k) hash = (hash ^ b.pcm[k]) * 1099511628211ull;
            for (size_t k = 0; k < count; ++k) {
                double value;
                if (b.format == 5) { float f; std::memcpy(&f, b.pcm + k * 4, 4); value = f; }
                else { int16_t v; std::memcpy(&v, b.pcm + k * 2, 2); value = v / 32768.0; }
                if (!std::isfinite(value) || std::abs(value) > 32) { ++invalid; continue; }
                sum += value * value; peak = std::max(peak, std::abs(value));
                nonzero += value != 0;
            }
            const double rms = invalid == count ? 0 : std::sqrt(sum / (count - invalid));
            if (invalid) {
                fft_[i].reset(); fft_latest_[i] = SpectrumFrame{}; fft_gain_flags_[i]=0;
                if(spatial_[i])spatial_[i]->reset();
                if(mono_[i])mono_[i]->reset();
            } else {
                const bool supported=b.stage==0 && SpatialRenderer::supports(b.channels,b.mask,b.rate);
                // Worker allocation only, after an authenticated lease produced
                // a validated block. Failure retains the existing raw backend.
                if(supported && !spatial_[i]) spatial_[i].reset(new(std::nothrow) SpatialRenderer);
                const bool ordinary=(b.rate==44100 || b.rate==48000) &&
                    ((b.channels==2 && b.mask==3) || (b.channels==1 && b.mask==1));
                if(ordinary && !mono_[i])mono_[i].reset(new(std::nothrow) Mono48);
                const uint32_t mode=supported && spatial_[i] ? 2 : ordinary && mono_[i] ? 3 : b.stage;
                if(analysis_[i]!=mode) {
                    fft_[i].reset(); fft_latest_[i]=SpectrumFrame{}; fft_gain_flags_[i]=0;
                    if(spatial_[i])spatial_[i]->reset();
                    if(mono_[i])mono_[i]->reset();
                }
                if(mode==3 && (analysis_[i]!=3 || mono_[i]->rate()!=b.rate))mono_[i]->configure(b.rate);
                analysis_[i]=mode;
                // The same App gain applies to every spatial source channel
                // for SoundCheck. Asymmetric multichannel distribution has
                // not been established: retain unity and report that fallback.
                const bool gain_known=b.app_gain_known && (b.channels<=2 || uint16_t(b.app_gain)==uint16_t(b.app_gain>>16));
                const float left_gain=gain_known ? app_gain_left(b.app_gain) : 1;
                const float right_gain=gain_known ? app_gain_right(b.app_gain) : 1;
                const auto previous=fft_[i].total();bool produced=false;
                if(mode==2) {
                    // Android BL/BR precede SL/SR; Apple Atmos places side
                    // surrounds before rear surrounds. Labels, never indices
                    // guessed from channel count, define this exact mapping.
                    constexpr uint32_t map[]{0,1,2,3,6,7,4,5,8,9,10,11};
                    float channels[12];
                    for(size_t frame=0;frame<b.frames;++frame) {
                        for(size_t c=0;c<12;++c) {
                            if(b.format==5) std::memcpy(&channels[c],b.pcm+(frame*12+map[c])*4,4);
                            else {int16_t v;std::memcpy(&v,b.pcm+(frame*12+map[c])*2,2);channels[c]=v/32768.f;}
                        }
                        if(spatial_[i]->sample(channels)) produced|=fft_[i].push(
                            reinterpret_cast<const unsigned char*>(spatial_[i]->output()),
                            SpatialRenderer::HOP,5,1,b.rate,b.ns,fft_latest_[i],false,left_gain,left_gain);
                    }
                } else if(mode==3) {
                    // Convert private L after its App gain, before the mono48
                    // display FFT. Bounded worker scratch; no output-stage gain.
                    float converted[kFrames*2];size_t frames=0;
                    for(size_t frame=0;frame<b.frames;++frame) {
                        float value;
                        if(b.format==5)std::memcpy(&value,b.pcm+frame*b.channels*4,4);
                        else {int16_t v;std::memcpy(&v,b.pcm+frame*b.channels*2,2);value=v/32768.f;}
                        mono_[i]->push(value*left_gain,[&](float sample){converted[frames++]=sample;});
                    }
                    if(frames)produced=fft_[i].push(reinterpret_cast<const unsigned char*>(converted),frames,5,1,48000,b.ns,fft_latest_[i]);
                } else produced=fft_[i].push(b.pcm,b.frames,b.format,b.channels,b.rate,b.ns,fft_latest_[i],b.stage==1,left_gain,right_gain);
                if(produced) {
                    fft_frames_+=fft_[i].total()-previous;
                    fft_latest_[i].sequence=++fft_sequence_;
                    fft_latest_[i].track=t.id;fft_latest_[i].io=b.io;
                    fft_latest_[i].app_gain=gain_known ? b.app_gain : kUnityAppGain;
                    fft_gain_flags_[i]=gain_known ? 32u : 64u;
                }
            }
            if (t.blocks && hash != last_hash_[i]) ++t.changed;
            last_hash_[i] = hash;
            if (t.last_ns && b.ns >= t.last_ns) t.max_gap_ns = std::max(t.max_gap_ns, b.ns - t.last_ns);
            t.last_ns = b.ns; t.id = s.id.load(); t.format = b.format;
            t.rate = b.rate; t.channels = b.channels; t.io = b.io;
            t.rms_min = t.blocks ? std::min(t.rms_min, rms) : rms;
            t.rms_max = std::max(t.rms_max, rms); t.rms_last = rms; t.peak = std::max(t.peak, peak);
            ++t.blocks; t.frames += b.frames; t.samples += count;
            t.nonzero += nonzero; t.invalid_samples += invalid;
            std::memset(b.pcm, 0, sizeof(b.pcm));
            s.read.store(rd + 1, std::memory_order_release);
        }
    }
    uint64_t latest = 0;
    for (const auto& t : r.tracks) latest = std::max(latest, t.last_ns);
    uint32_t candidates = 0, chosen = 0;
    for (uint32_t i = 0; i < kSlots; ++i) {
        if (r.tracks[i].blocks && latest - r.tracks[i].last_ns < 250000000) { ++candidates; chosen = i; }
    }
    r.spectrum = candidates == 1 ? fft_latest_[chosen] : SpectrumFrame{};
    r.fft_frames = fft_frames_;
    r.reserved = (r.reserved & ~241u) | (candidates==1 ?
        ((analysis_[chosen]==2 ? 16u : analysis_[chosen]==3 ? 128u|last_stage_[chosen] : analysis_[chosen]) | fft_gain_flags_[chosen]) : 0);
}

void Engine::counters(Report& r) const {
    r.deadline_ns = deadline.load(); r.entered = entered_.load(); r.eligible = eligible_.load();
    r.rejected = rejected_.load(); r.app_muted = app_muted_.load(); r.oversize = oversize_.load();
    r.slots_full = slots_full_.load(); r.copy_ns_sum = cost_sum_.load(); r.copy_ns_max = cost_max_.load();
}
}  // namespace tap
