// SPDX-License-Identifier: Apache-2.0
#include "spectrum_fft.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace tap {
SpectrumFft::SpectrumFft() {
    constexpr double pi = 3.14159265358979323846;
    for (size_t i = 0; i < N; ++i) {
        uint32_t reverse = 0, value = uint32_t(i);
        for (size_t b = N; b > 1; b >>= 1) { reverse = (reverse << 1) | (value & 1); value >>= 1; }
        reversed_[i] = reverse;
        window_[i] = i < INPUT ? 0.5 - 0.5 * std::cos(2 * pi * i / INPUT) : 0;
    }
    // MediaCoreUI applies2/inputCount to Accelerate's real FFT, which itself
    // returns twice the conventional DFT. Our complex FFT has no such implicit
    // factor. Match the combined scaling, not just the explicit vsmul constant.
    // The recovered inputCount is half the power-of-two tap block. ctoz fills
    // only inputCount/2 complex pairs; the rest of the FFT input stays zero.
    scale_ = 4.0 / INPUT;
    for (size_t i = 0; i < N / 2; ++i) {
        cosine_[i] = std::cos(-2 * pi * i / N); sine_[i] = std::sin(-2 * pi * i / N);
    }
}
void SpectrumFft::reset() {
    std::memset(ring_, 0, sizeof(ring_)); std::memset(re_, 0, sizeof(re_));
    std::memset(im_, 0, sizeof(im_)); std::memset(power_, 0, sizeof(power_));
    cursor_ = filled_ = since_ = 0;
    format_ = channels_ = rate_ = 0;
}
bool SpectrumFft::push(const unsigned char* pcm, size_t frames, uint32_t format,
                       uint32_t channels, uint32_t rate, uint64_t ns, SpectrumFrame& out, bool first_channel,
                       float left_gain, float right_gain) {
    if (!pcm || (format != 1 && format != 5) || channels == 0 || channels > kMaxChannels || rate < 8000 ||
        !std::isfinite(left_gain) || !std::isfinite(right_gain) || left_gain<0 || right_gain<0 ||
        left_gain>=2 || right_gain>=2 || (channels>2 && left_gain!=right_gain)) {
        reset(); return false;
    }
    bool produced = false;
    if (rate_ && (format_ != format || channels_ != channels || rate_ != rate || first_channel_ != first_channel)) reset();
    format_ = format; channels_ = channels; rate_ = rate; first_channel_ = first_channel;
    const size_t bytes = format == 5 ? 4 : 2;
    for (size_t f = 0; f < frames; ++f) {
        for (uint32_t c = 0; c < channels; ++c) {
            double value;
            if (format == 5) { float sample; std::memcpy(&sample, pcm + (f * channels + c) * bytes, bytes); value = sample; }
            else { int16_t sample; std::memcpy(&sample, pcm + (f * channels + c) * bytes, bytes); value = sample / 32768.0; }
            if (!std::isfinite(value) || std::abs(value) > 32) { reset(); return false; }
            // Private analysis history only. Keep captured PCM untouched and
            // retain App normalization, independently of stream/port volume.
            ring_[c][cursor_] = value * (c==1 ? right_gain : left_gain);
        }
        cursor_ = (cursor_ + 1) % N;
        if (filled_ < N) {
            if (++filled_ == N) { produced |= analyze(first_channel ? 1 : channels, rate, ns, out); since_ = 0; }
        } else if (++since_ >= HOP) { produced |= analyze(first_channel ? 1 : channels, rate, ns, out); since_ = 0; }
    }
    return produced;
}
bool SpectrumFft::analyze(uint32_t channels, uint32_t rate, uint64_t ns, SpectrumFrame& out) {
    std::fill_n(power_, N / 2, 0);
    double square = 0;
    for (uint32_t c = 0; c < channels; ++c) {
        std::fill_n(im_, N, 0);
        for (size_t i = 0; i < N; ++i) {
            const double sample = ring_[c][(cursor_ + i) % N];
            if (i < INPUT) square += sample * sample;
            re_[reversed_[i]] = sample * window_[i];
        }
        for (size_t length = 2; length <= N; length *= 2) {
            const size_t half = length / 2, step = N / length;
            for (size_t start = 0; start < N; start += length) for (size_t j = 0; j < half; ++j) {
                const auto a = start + j, b = a + half, twiddle = j * step;
                const double re = re_[b] * cosine_[twiddle] - im_[b] * sine_[twiddle];
                const double im = re_[b] * sine_[twiddle] + im_[b] * cosine_[twiddle];
                re_[b] = re_[a] - re; im_[b] = im_[a] - im; re_[a] += re; im_[a] += im;
            }
        }
        for (size_t i = 0; i < N / 2; ++i) power_[i] += (re_[i] * re_[i] + im_[i] * im_[i]) / channels;
        // Accelerate packs Nyquist into imagp[0] beside DC in realp[0]. The
        // reference's zvmags includes both in its first magnitude element.
        power_[0] += (re_[N / 2] * re_[N / 2] + im_[N / 2] * im_[N / 2]) / channels;
    }
    // MediaCoreUI six-bar table and magnitude sums. No per-band AGC, strongest
    // quartile selection, bass accent or output-volume compensation.
    constexpr double edges[]{20,81,170,527,1500,4500,10000};
    out = SpectrumFrame{}; out.sequence = ++sequence_; out.ns = ns;
    out.rate = rate; out.channels = channels; out.rms = float(std::sqrt(square / (INPUT * channels)));
    size_t bin = 0;
    float frequency = float(edges[0]);
    const float step = float(rate) / float(N);
    for (size_t band = 0; band < 6; ++band) {
        // Match the recovered accumulator, including its20Hz initial offset.
        // It consumes bins continuously from zero, not ceil(edge/binHz) slices.
        double sum = 0;
        while (bin < N / 2 && frequency < edges[band + 1]) {
            sum += std::sqrt(power_[bin++]) * scale_;
            frequency += step;
        }
        out.bands[band] = float(sum);
    }
    return true;
}
}  // namespace tap
