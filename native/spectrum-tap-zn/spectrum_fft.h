// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cstddef>
#include <cstdint>

namespace tap {
constexpr uint32_t kMaxChannels = 12;
struct SpectrumFrame {
    uint64_t sequence = 0, ns = 0;
    uint32_t track = 0, rate = 0, channels = 0, io = 0;
    float bands[6]{}, rms = 0;
    uint32_t app_gain = 0;
};
class SpectrumFft {
public:
    static constexpr size_t N = 4096, HOP = 4096;
    static constexpr size_t INPUT = N / 2;
    SpectrumFft();
    void reset();
    uint64_t total() const { return sequence_; }
    bool push(const unsigned char*, size_t frames, uint32_t format, uint32_t channels,
              uint32_t rate, uint64_t ns, SpectrumFrame&, bool first_channel = false,
              float left_gain = 1, float right_gain = 1);
private:
    double ring_[kMaxChannels][N]{}, re_[N]{}, im_[N]{}, power_[N / 2]{};
    double window_[N]{}, cosine_[N / 2]{}, sine_[N / 2]{}, scale_ = 0;
    uint32_t reversed_[N]{};
    size_t cursor_ = 0, filled_ = 0, since_ = 0;
    uint64_t sequence_ = 0;
    uint32_t format_ = 0, channels_ = 0, rate_ = 0;
    bool first_channel_ = false;
    bool analyze(uint32_t channels, uint32_t rate, uint64_t ns, SpectrumFrame&);
};
}  // namespace tap
