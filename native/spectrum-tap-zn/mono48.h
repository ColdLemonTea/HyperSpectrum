// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
// Worker-only mono48 analysis conversion. Source buffers remain untouched.
#pragma once
#include <array>
#include <cmath>
#include <cstdint>

namespace tap {
class Mono48 {
public:
    // Deliberately bounded to the reference rates under investigation.
    bool configure(unsigned rate) {
        reset();rate_=rate;
        if(rate!=44100 && rate!=48000){rate_=0;return false;}
        if(rate==48000)return true;
        phases_=rate==44100?160:1;
        constexpr double cutoff=1.;
        constexpr double pi=3.14159265358979323846;
        for(unsigned p=0;p<phases_;++p) {
            const double fraction=double(p)/phases_;double sum=0;
            for(int k=-radius+1;k<=radius;++k) {
                const double x=k-fraction,z=pi*cutoff*x;
                const double window=.42+.5*std::cos(pi*x/radius)+.08*std::cos(2*pi*x/radius);
                const double coefficient=(std::abs(z)<1e-12?cutoff:cutoff*std::sin(z)/z)*window;
                coefficients_[p][k+radius-1]=coefficient;sum+=coefficient;
            }
            for(auto& coefficient:coefficients_[p])coefficient/=sum;
        }
        return true;
    }
    void reset() { received_=whole_=0;fraction_=0;history_.fill(0); }
    unsigned rate() const { return rate_; }
    template<class Sink> void push(float left,Sink&& sink) {
        if(!rate_)return;
        if(rate_==48000){sink(left);return;}
        history_[received_%history_.size()]=left;++received_;
        while(whole_+radius<received_) {
            const unsigned phase=unsigned(uint64_t(fraction_)*phases_/48000);
            double value=0;
            for(int k=-radius+1;k<=radius;++k) {
                const int64_t index=int64_t(whole_)+k;
                if(index>=0)value+=history_[uint64_t(index)%history_.size()]*coefficients_[phase][k+radius-1];
            }
            sink(float(value));
            fraction_+=rate_;whole_+=fraction_/48000;fraction_%=48000;
        }
    }
private:
    static constexpr int radius=32;
    unsigned rate_=0,phases_=0,fraction_=0;
    uint64_t received_=0,whole_=0;
    std::array<float,128> history_{};
    std::array<std::array<double,radius*2>,160> coefficients_{};
};
}
