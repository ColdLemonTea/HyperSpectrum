// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "radix_fft.h"
#include <cstdint>
#include <complex>
namespace tap {
// Analysis-only reference renderer. Never connected to playback output.
// Exact supported source layout is positional Android7.1.4,48kHz.
class SpatialRenderer {
public:
    static constexpr size_t N=2048,HOP=1024;
    SpatialRenderer();
    void reset();
    static bool supports(uint32_t channels,uint32_t mask,uint32_t rate) {
        return channels==12 && mask==0x2d63f && rate==48000;
    }
    // Apple-order interleaved floating inputs; one output hop when true.
    bool sample(const float* channels);
    const float* output() const {return output_;}
    static std::complex<double> common(std::complex<double>,std::complex<double>);
private:
    RadixFft<N> fft_;
    float history_[12][N]{}, pending_[12][HOP]{};
    float convolution_[13][HOP+127]{}, tails_[13][127]{};
    double window_[N]{}, overlap_[HOP]{};
    std::complex<double> left_[N]{},right_[N]{},common_[N]{};
    float output_[HOP]{};
    float delay_[1152]{};
    size_t filled_=0,delay_position_=0;
    void render();
};
}
