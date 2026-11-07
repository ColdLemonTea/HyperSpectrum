// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <array>
#include <cmath>
#include <complex>
#include <cstddef>
namespace tap {
// Worker-owned fixed storage. Construct only with the lazy capture engine.
template<size_t N> class RadixFft {
public:
    RadixFft() {
        for (size_t i=0;i<N;++i) {
            size_t x=i,r=0;
            for (size_t b=N;b>1;b>>=1) {r=(r<<1)|(x&1);x>>=1;}
            reverse_[i]=r;
        }
        for (size_t i=0;i<N/2;++i) {
            const double p=-6.2831853071795864769*i/N;
            roots_[i]={std::cos(p),std::sin(p)};
        }
    }
    void transform(std::complex<double>* a,bool inverse=false) const {
        for (size_t i=0;i<N;++i) if (reverse_[i]>i) std::swap(a[i],a[reverse_[i]]);
        for(size_t len=2;len<=N;len*=2) for(size_t s=0;s<N;s+=len)
            for(size_t j=0;j<len/2;++j) {
                auto w=roots_[j*N/len];if(inverse)w=std::conj(w);
                const auto u=a[s+j],v=a[s+j+len/2]*w;
                a[s+j]=u+v;a[s+j+len/2]=u-v;
            }
        if(inverse)for(size_t i=0;i<N;++i)a[i]/=N;
    }
private:
    std::array<size_t,N> reverse_{};
    std::array<std::complex<double>,N/2> roots_{};
};
}
