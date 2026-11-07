// SPDX-License-Identifier: Apache-2.0
#include "track_layout.h"
#include "elf_image.h"
#include <array>
#include <cassert>
#include <cstring>
#include <limits>
using namespace tap;
namespace {
struct Code {
    std::array<unsigned char,32768> bytes{};
    uintptr_t base=0x100000,table=base+0x100,ctor=base+0x1000,rate=base+0x1200,volume=base+0x1400;
    template<class T> bool read(uintptr_t address,T& value)const {
        if(address<base||address-base>bytes.size()||sizeof(T)>bytes.size()-(address-base))return false;
        std::memcpy(&value,bytes.data()+address-base,sizeof(T));return true;
    }
    template<class T> void put(uintptr_t address,T value) {
        assert(address>=base&&address-base+sizeof(T)<=bytes.size());std::memcpy(bytes.data()+address-base,&value,sizeof(T));
    }
    uintptr_t fn(size_t slot)const{return base+0x2000+slot*32;}
    static uint32_t load(unsigned bytes,unsigned base,unsigned dest,size_t off){
        return (bytes==8?0xf9400000u:0xb9400000u)|uint32_t(off/bytes)<<10|base<<5|dest;
    }
    Code(size_t delta,bool bti){
        auto start=[&](size_t slot){auto f=fn(slot);put(table+slot*8,f);if(bti){put(f,0xd503245fu);f+=4;}return f;};
        auto leaf=[&](size_t slot,size_t off,unsigned size=4){auto f=start(slot);put(f,load(size,0,0,off+delta));put(f+4,0xd65f03c0u);};
        leaf(14,448);leaf(15,452);leaf(23,104);leaf(47,396);leaf(48,496);leaf(55,400);leaf(61,432,8);leaf(81,1524);leaf(21,512);
        for(unsigned k=0;k<2;++k){auto f=start(28+k);put(f,load(4,0,8,504+delta));put(f+4,0x7100011fu|((k+1)<<10));put(f+8,0x1a9f17e0u);put(f+12,0xd65f03c0u);}
        auto attr=start(36);put(attr,0x91000001u|uint32_t(120+delta)<<10);put(attr+4,0xaa0803e0u);put(attr+8,0x52802202u);
        auto flags=start(82);put(flags,load(4,0,8,1844+delta));put(flags+4,0x12000100u);put(flags+8,0xd65f03c0u);
        put(table+62*8,rate);put(table+101*8,volume);
        for(auto f:{rate,volume}){put(f,load(8,0,19,1832+delta));put(f+4,load(8,19,8,16));put(f+8,load(4,8,f==rate?0:19,f==rate?20:16));put(f+12,0xd65f03c0u);}
        put(ctor,load(8,27,8,0x48));put(ctor+4,0xd63f0100u);put(ctor+8,0x6f00e400u);put(ctor+12,0xb9000260u|uint32_t((508+delta)/4)<<10);
    }
    bool discover(TrackLayout& l){return discover_track_layout([&](uintptr_t a,auto& v){return read(a,v);},table,rate,volume,ctor,16,l);}
};
void relocated_layouts(){
    for(size_t delta:{size_t(0),size_t(128),size_t(256)})for(bool bti:{false,true}){
        Code c(delta,bti);TrackLayout l;assert(c.discover(l));
        assert(l.session==448+delta&&l.uid==452+delta&&l.io==508+delta&&l.proxy==1832+delta);
        assert(l.usage==124+delta&&l.rate==20&&l.gain==16&&l.cblk==16);
    }
}
void incompatible_layouts(){
    for(unsigned failure=0;failure<8;++failure){Code c(0,true);TrackLayout l;
        switch(failure){
            case 0:c.put(c.fn(14),0u);break;
            case 1:c.put(c.table+14*8,c.fn(15));break;
            case 2:c.put(c.table+62*8,c.volume);break;
            case 3:c.put(c.volume,Code::load(8,0,19,1840));break;
            case 4:c.put(c.fn(29)+4,Code::load(4,0,8,508));break;
            case 5:c.put(c.ctor+4,0u);break;
            case 6:c.put(c.fn(36)+12,0u);c.put(c.fn(36)+8,0u);break;
            case 7:c.put(c.table+14*8,std::numeric_limits<uintptr_t>::max());break;
        }
        assert(!c.discover(l));
    }
}
struct ElfFixture {
    alignas(16) std::array<unsigned char,4096> memory{};
    std::array<Elf64_Phdr,2> ph{};ElfImage image;
    template<class T> void put(size_t off,const T& x){assert(off+sizeof(x)<=memory.size());std::memcpy(memory.data()+off,&x,sizeof(x));}
    ElfFixture(bool absolute){
        const auto bias=reinterpret_cast<uintptr_t>(memory.data());image={bias,ph.data(),ph.size()};
        ph[0].p_type=PT_LOAD;ph[0].p_flags=PF_R;ph[0].p_memsz=memory.size();
        ph[1].p_type=PT_DYNAMIC;ph[1].p_vaddr=0x100;ph[1].p_memsz=8*sizeof(Elf64_Dyn);
        auto d=[&](unsigned i,int64_t tag,uint64_t value){Elf64_Dyn x{};x.d_tag=tag;x.d_un.d_val=value;put(0x100+i*sizeof(x),x);};
        d(0,DT_SYMTAB,(absolute?bias:0)+0x300);d(1,DT_STRTAB,(absolute?bias:0)+0x400);d(2,DT_STRSZ,128);d(3,DT_SYMENT,sizeof(Elf64_Sym));
        d(4,DT_JMPREL,(absolute?bias:0)+0x500);d(5,DT_PLTRELSZ,sizeof(Elf64_Rela));d(6,DT_PLTREL,DT_RELA);d(7,DT_NULL,0);
        Elf64_Sym s{};s.st_name=1;put(0x300+sizeof(s),s);std::memcpy(memory.data()+0x401,"target",7);
        Elf64_Rela r{};r.r_offset=0x600;r.r_info=(uint64_t(1)<<32)|R_AARCH64_JUMP_SLOT;put(0x500,r);
    }
};
void relocation_guards(){
    for(bool absolute:{false,true}){
        ElfFixture f(absolute);uintptr_t slot=0;assert(f.image.jump_slot("target",slot));assert(slot==f.image.bias+0x600);
        assert(!f.image.jump_slot("missing",slot));
        Elf64_Dyn d{};d.d_tag=DT_PLTRELSZ;d.d_un.d_val=2*sizeof(Elf64_Rela);f.put(0x100+5*sizeof(d),d);
        Elf64_Rela r{};std::memcpy(&r,f.memory.data()+0x500,sizeof(r));f.put(0x500+sizeof(r),r);
        assert(!f.image.jump_slot("target",slot)); // ambiguous imports fail closed
    }
    ElfFixture f(false);uintptr_t slot=0;Elf64_Rela r{};r.r_offset=std::numeric_limits<uint64_t>::max();r.r_info=(uint64_t(1)<<32)|R_AARCH64_JUMP_SLOT;f.put(0x500,r);
    assert(!f.image.jump_slot("target",slot));
    assert(!f.image.contains(f.image.bias-1,1,PF_R));assert(!f.image.contains(f.image.bias+4090,16,PF_R));
}
}
int main(){relocated_layouts();incompatible_layouts();relocation_guards();}
