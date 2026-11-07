// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace tap {
struct TrackLayout {
    size_t uid=0, session=0, id=0, io=0, state=0, usage=0, stream=0;
    size_t type=0, format=0, mask=0, frame_size=0, flags=0, proxy=0;
    size_t cblk=0, rate=0, gain=0;
    uintptr_t vtable=0;
};
namespace arm64 {
inline bool landing(uint32_t x) { return x==0xd503245f || x==0xd503201f; }
inline bool load(uint32_t x, unsigned bytes, unsigned base, unsigned dest, size_t& offset) {
    const uint32_t op=bytes==8?0xf9400000u:bytes==4?0xb9400000u:0x39400000u;
    if((x&0xffc003ffu)!=(op|(base<<5)|dest))return false;
    offset=((x>>10)&4095)*bytes;return true;
}
template<class Read> bool leaf(Read read,uintptr_t function,unsigned bytes,size_t& offset) {
    uint32_t x=0;if(!read(function,x))return false;
    if(landing(x)){function+=4;if(!read(function,x))return false;}
    uint32_t ret=0;
    return load(x,bytes,0,0,offset)&&read(function+4,ret)&&ret==0xd65f03c0;
}
template<class Read> bool equals(Read read,uintptr_t function,unsigned expected,size_t& offset) {
    uint32_t x=0;if(!read(function,x))return false;
    if(landing(x)){function+=4;if(!read(function,x))return false;}
    uint32_t cmp=0,set=0,ret=0;
    return load(x,4,0,8,offset)&&read(function+4,cmp)&&cmp==(0x7100011fu|(expected<<10))&&
        read(function+8,set)&&set==0x1a9f17e0&&read(function+12,ret)&&ret==0xd65f03c0;
}
// Recognize only validated load chains, not arbitrary instruction matches. The
// getter is inspected at initialization and is never called on the audio thread.
template<class Read> bool proxy_chain(Read read,uintptr_t function,unsigned dest,
                                      size_t& proxy,size_t& cblk,size_t& value) {
    bool found=false;
    for(size_t i=0;i<192;i+=4) {
        uint32_t x=0;if(!read(function+i,x))return false;
        if(x==0xd65f03c0)break;
        size_t candidate=0;
        if(!load(x,8,0,19,candidate))continue;
        bool chain=false;
        for(size_t j=i+4;j<i+64&&j<192;j+=4) {
            uint32_t a=0,b=0;size_t c=0,v=0;
            if(!read(function+j,a)||!read(function+j+4,b))return false;
            if(load(a,8,19,8,c)&&load(b,4,8,dest,v)) {
                if(found)return false;
                proxy=candidate;cblk=c;value=v;found=chain=true;break;
            }
        }
        if(!chain)return false;
    }
    return found;
}
}

// This is a recognized virtual-interface ABI, not a ROM/build allowlist. Named
// anchors, getter shapes, paired accessors and field bounds must all agree.
// Added/reordered virtual methods or other compilers fail closed.
template<class Read> bool discover_track_layout(Read read,uintptr_t table,
        uintptr_t sample_rate,uintptr_t get_volume,uintptr_t ctor,size_t ctor_size,TrackLayout& out) {
    auto entry=[&](size_t index){uintptr_t p=0;return read(table+index*sizeof(p),p)?p:0;};
    TrackLayout l;l.vtable=table;
    if(entry(62)!=sample_rate||entry(101)!=get_volume)return false;
    if(!arm64::leaf(read,entry(14),4,l.session)||!arm64::leaf(read,entry(15),4,l.uid)||
       !arm64::leaf(read,entry(23),4,l.state)||!arm64::leaf(read,entry(47),4,l.format)||
       !arm64::leaf(read,entry(48),4,l.id)||!arm64::leaf(read,entry(55),4,l.mask)||
       !arm64::leaf(read,entry(61),8,l.frame_size)||!arm64::leaf(read,entry(81),4,l.stream))return false;
    size_t type2=0;
    if(!arm64::equals(read,entry(28),1,l.type)||!arm64::equals(read,entry(29),2,type2)||type2!=l.type)return false;
    // attributes() copies audio_attributes_t; usage immediately follows content_type.
    uintptr_t attributes=entry(36);uint32_t x=0,a=0,b=0;
    if(!read(attributes,x))return false;
    if(arm64::landing(x))attributes+=4;
    if(!read(attributes,x)||(x&0xffc003ffu)!=0x91000001u||
        !read(attributes+4,a)||a!=0xaa0803e0||!read(attributes+8,b)||b!=0x52802202)return false;
    l.usage=((x>>10)&4095)+4;
    uintptr_t direct=entry(82);if(!read(direct,x))return false;
    if(arm64::landing(x))direct+=4;
    if(!read(direct,x)||!arm64::load(x,4,0,8,l.flags)||!read(direct+4,a)||a!=0x12000100||
       !read(direct+8,b)||b!=0xd65f03c0)return false;
    size_t p=0,c=0;
    if(!arm64::proxy_chain(read,sample_rate,0,l.proxy,l.cblk,l.rate)||
       !arm64::proxy_chain(read,get_volume,19,p,c,l.gain)||p!=l.proxy||c!=l.cblk||l.rate!=l.gain+4)return false;
    // The I/O handle constructor store must follow the thread ioHandle virtual
    // call, and a separate portId accessor must corroborate its position.
    size_t port=0;if(!arm64::leaf(read,entry(21),4,port))return false;
    unsigned matches=0;
    for(size_t i=0;i+16<=ctor_size;i+=4) {
        uint32_t ins[4]{};for(unsigned k=0;k<4;++k)if(!read(ctor+i+k*4,ins[k]))return false;
        size_t slot=0,offset=0;
        if(arm64::load(ins[0],8,27,8,slot)&&slot==0x48&&ins[1]==0xd63f0100&&ins[2]==0x6f00e400&&
           (ins[3]&0xffc003ffu)==0xb9000260u) {
            offset=((ins[3]>>10)&4095)*4;
            if(offset+4!=port)return false;
            l.io=offset;++matches;
        }
    }
    if(matches!=1)return false;
    const size_t offsets[]{l.uid,l.session,l.id,l.io,l.state,l.usage,l.stream,l.type,l.format,l.mask,l.frame_size,l.flags,l.proxy};
    for(size_t v:offsets)if(v<8||v>4096||(v&3))return false;
    for(size_t i=0;i<sizeof(offsets)/sizeof(offsets[0]);++i)
        for(size_t j=0;j<i;++j)if(offsets[i]==offsets[j])return false;
    if((l.proxy&7)||(l.frame_size&7)||l.cblk<8||l.cblk>128||l.rate>256||l.gain>256)return false;
    out=l;return true;
}
}
