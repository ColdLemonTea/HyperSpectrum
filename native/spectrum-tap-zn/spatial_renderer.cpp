// SPDX-License-Identifier: Apache-2.0
#include "spatial_renderer.h"
#include "spatial_fir.h"
#include <algorithm>
#include <cmath>
#include <cstring>
namespace tap {
SpatialRenderer::SpatialRenderer() {
    for(size_t i=0;i<N;++i) window_[i]=std::sqrt(.5-.5*std::cos(6.2831853071795864769*i/N));
}
void SpatialRenderer::reset() {
    std::memset(history_,0,sizeof(history_));std::memset(pending_,0,sizeof(pending_));
    std::memset(convolution_,0,sizeof(convolution_));std::memset(tails_,0,sizeof(tails_));
    std::fill_n(overlap_,HOP,0);std::fill_n(output_,HOP,0);
    std::fill_n(left_,N,std::complex<double>{});std::fill_n(right_,N,std::complex<double>{});
    std::fill_n(common_,N,std::complex<double>{});filled_=0;delay_position_=0;std::fill_n(delay_,1152,0);
}
std::complex<double> SpatialRenderer::common(std::complex<double> l,std::complex<double> r) {
    const auto s=l+r;
    const double product=std::abs(l)*std::abs(r);
    // Silence, Nyquist and near-antiphase guard. This branch is deliberate;
    // never amplify a numerical residue or produce a nonfinite PCM window.
    if(product<1e-24 || std::norm(s)<1e-24) return {};
    const auto q=.25-l*r/(s*s);
    const auto root=std::sqrt(q);
    // Preserve the verified split-buffer instruction ordering of the first
    // candidate; its imaginary component is q.imag, not root.imag.
    const double weight=std::min(std::abs(std::complex<double>(.5+root.real(),q.imag())),std::abs(.5-root));
    const double coherence=std::clamp(.5+.5*std::real(l*std::conj(r))/product,0.,1.);
    const auto value=s*(weight*coherence);
    return std::isfinite(value.real())&&std::isfinite(value.imag()) ? value : std::complex<double>{};
}
bool SpatialRenderer::sample(const float* channels) {
    for(size_t c=0;c<12;++c) pending_[c][filled_]=channels[c];
    if(++filled_<HOP)return false;
    filled_=0;render();return true;
}
void SpatialRenderer::render() {
    for(size_t c=0;c<12;++c) {
        std::copy_n(history_[c]+HOP,HOP,history_[c]);
        std::copy_n(pending_[c],HOP,history_[c]+HOP);
    }
    for(size_t i=0;i<N;++i) {
        left_[i]=history_[0][i]*window_[i];right_[i]=history_[1][i]*window_[i];
    }
    fft_.transform(left_);fft_.transform(right_);
    for(size_t k=0;k<=N/2;++k) {
        common_[k]=common(left_[k],right_[k]);
        if(k && k<N/2)common_[N-k]=std::conj(common_[k]);
    }
    fft_.transform(common_,true);
    for(size_t c=0;c<13;++c) {
        std::copy_n(tails_[c],127,convolution_[c]);
        if(c<12)std::copy_n(history_[c],HOP,convolution_[c]+127);
    }
    for(size_t i=0;i<HOP;++i) {
        convolution_[12][127+i]=float(common_[i].real()*window_[i]+overlap_[i]);
        overlap_[i]=common_[i+HOP].real()*window_[i+HOP];
    }
    std::fill_n(output_,HOP,0);
    for(size_t c=0;c<13;++c) {
        for(size_t k=0;k<128;++k) {
            const float coefficient=c<12 ? kSpatialFir[c][k] :
                float(1.4142135623730951*kSpatialFir[2][k]-kSpatialFir[0][k]-kSpatialFir[1][k]);
            if(coefficient==0)continue;
            for(size_t i=0;i<HOP;++i)output_[i]+=coefficient*convolution_[c][127+i-k];
        }
        std::copy_n(convolution_[c]+HOP,127,tails_[c]);
    }
    // Match the isolated reference AU's additional hop and128-sample filter
    // delay. This is a fixed measured latency, not signal-dependent alignment.
    for(size_t i=0;i<HOP;++i) {
        const float current=output_[i];output_[i]=delay_[delay_position_];
        delay_[delay_position_]=current;delay_position_=(delay_position_+1)%1152;
    }

}
}
