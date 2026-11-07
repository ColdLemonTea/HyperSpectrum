// SPDX-License-Identifier: Apache-2.0
// Offline ELF/layout audit. Reads shared libraries; never loads or executes them.
#include "elf_image.h"
#include "track_layout.h"
#include <fstream>
#include <iostream>
#include <vector>
#include <stdexcept>
#include <string>
#include <iterator>
using namespace tap;
namespace {
struct Fixture {
    std::vector<unsigned char> file,memory;Elf64_Ehdr header{};std::vector<Elf64_Phdr> phdr;
    const Elf64_Shdr* sections=nullptr;const Elf64_Sym* symbols=nullptr;size_t count=0;
    const char* names=nullptr;size_t name_size=0;ElfImage image;
    template<class T> const T* at(size_t offset,size_t bytes)const {
        if(offset>file.size()||bytes>file.size()-offset)throw std::runtime_error("ELF range");
        return reinterpret_cast<const T*>(file.data()+offset);
    }
    explicit Fixture(const char* path,size_t padding){
        std::ifstream in(path,std::ios::binary);if(!in)throw std::runtime_error("open input");
        file.assign(std::istreambuf_iterator<char>(in),{});
        std::memcpy(&header,at<unsigned char>(0,sizeof(header)),sizeof(header));
        if(std::memcmp(header.e_ident,ELFMAG,4)||header.e_machine!=EM_AARCH64||header.e_ident[EI_CLASS]!=ELFCLASS64||
           header.e_ident[EI_DATA]!=ELFDATA2LSB||header.e_phentsize!=sizeof(Elf64_Phdr)||header.e_shentsize!=sizeof(Elf64_Shdr))throw std::runtime_error("ELF ABI");
        auto p=at<Elf64_Phdr>(header.e_phoff,size_t(header.e_phnum)*sizeof(Elf64_Phdr));phdr.assign(p,p+header.e_phnum);
        size_t size=0;for(const auto& v:phdr)if(v.p_type==PT_LOAD){if(v.p_vaddr>128*1024*1024||v.p_memsz>128*1024*1024-v.p_vaddr)throw std::runtime_error("ELF size");size=std::max(size,size_t(v.p_vaddr+v.p_memsz));}
        memory.resize(size+padding);image={reinterpret_cast<uintptr_t>(memory.data()+padding),phdr.data(),phdr.size()};
        for(const auto& v:phdr)if(v.p_type==PT_LOAD){if(v.p_filesz>v.p_memsz)throw std::runtime_error("segment");std::memcpy(reinterpret_cast<void*>(image.bias+v.p_vaddr),at<unsigned char>(v.p_offset,v.p_filesz),v.p_filesz);}
        sections=at<Elf64_Shdr>(header.e_shoff,size_t(header.e_shnum)*sizeof(Elf64_Shdr));
        size_t sym_section=0;
        for(size_t i=0;i<header.e_shnum;++i)if(sections[i].sh_type==SHT_DYNSYM){
            if(symbols||sections[i].sh_entsize!=sizeof(Elf64_Sym)||sections[i].sh_link>=header.e_shnum)throw std::runtime_error("symtab");
            symbols=at<Elf64_Sym>(sections[i].sh_offset,sections[i].sh_size);count=sections[i].sh_size/sizeof(Elf64_Sym);sym_section=i;
            const auto& strings=sections[sections[i].sh_link];names=at<char>(strings.sh_offset,strings.sh_size);name_size=strings.sh_size;
        }
        if(!symbols)throw std::runtime_error("no symbols");
        auto apply_relative=[&](uint64_t offset,uint64_t value){
            if(!image.contains(image.bias+offset,8,PF_R))throw std::runtime_error("relocation bounds");
            value+=image.bias;std::memcpy(reinterpret_cast<void*>(image.bias+offset),&value,8);
        };
        for(size_t i=0;i<header.e_shnum;++i)if(sections[i].sh_type==0x6fffff00u){
            const auto& section=sections[i];const auto* rel=at<uint64_t>(section.sh_offset,section.sh_size);uint64_t cursor=0;
            for(size_t j=0;j<section.sh_size/8;++j){uint64_t x=rel[j];
                if(!(x&1)){cursor=x;uint64_t v=0;if(!image.read(image.bias+cursor,v))throw std::runtime_error("RELR read");apply_relative(cursor,v);cursor+=8;}
                else {for(unsigned k=1;k<64;++k)if((x>>k)&1){uint64_t v=0;if(!image.read(image.bias+cursor+(k-1)*8,v))throw std::runtime_error("RELR bitmap");apply_relative(cursor+(k-1)*8,v);}cursor+=63*8;}
            }
        }
        for(size_t i=0;i<header.e_shnum;++i)if(sections[i].sh_type==0x60000002u){
            const auto& section=sections[i];const auto* bytes=at<unsigned char>(section.sh_offset,section.sh_size);size_t cursor=4;
            if(section.sh_size<4||std::memcmp(bytes,"APS2",4))throw std::runtime_error("packed RELA header");
            auto sleb=[&](){uint64_t v=0;unsigned shift=0;unsigned char x;
                do{if(cursor>=section.sh_size||shift>=64)throw std::runtime_error("packed RELA integer");x=bytes[cursor++];v|=uint64_t(x&127)<<shift;shift+=7;}while(x&128);
                if(shift<64&&(x&64))v|=~uint64_t(0)<<shift;
                return int64_t(v);
            };
            const int64_t total=sleb();int64_t offset=sleb(),addend=0,done=0;
            while(done<total){const auto group=sleb(),flags=sleb();if(group<=0||group>total-done||flags<0||flags>15)throw std::runtime_error("packed RELA group");
                const auto delta=(flags&2)?sleb():0,info=(flags&1)?sleb():0;
                if((flags&12)==12)addend+=sleb();else if(!(flags&8))addend=0;
                for(int64_t j=0;j<group;++j){offset+=(flags&2)?delta:sleb();const uint64_t rinfo=(flags&1)?info:sleb();if((flags&8)&&!(flags&4))addend+=sleb();
                    if(ELF64_R_TYPE(rinfo)==R_AARCH64_RELATIVE)apply_relative(offset,addend);
                    else if(ELF64_R_TYPE(rinfo)==R_AARCH64_ABS64||ELF64_R_TYPE(rinfo)==R_AARCH64_GLOB_DAT){size_t index=ELF64_R_SYM(rinfo);if(index>=count)throw std::runtime_error("packed symbol");if(symbols[index].st_shndx!=SHN_UNDEF)apply_relative(offset,symbols[index].st_value+addend);}
                }done+=group;
            }
        }
        for(size_t i=0;i<header.e_shnum;++i)if(sections[i].sh_type==SHT_RELA){
            const auto& section=sections[i];if(section.sh_entsize!=sizeof(Elf64_Rela)||section.sh_link!=sym_section)throw std::runtime_error("relocation table");
            const auto* rel=at<Elf64_Rela>(section.sh_offset,section.sh_size);
            for(size_t j=0;j<section.sh_size/sizeof(*rel);++j){const auto& r=rel[j];uint64_t value=0;bool apply=false;
                if(ELF64_R_TYPE(r.r_info)==R_AARCH64_RELATIVE){value=image.bias+r.r_addend;apply=true;}
                else if(ELF64_R_TYPE(r.r_info)==R_AARCH64_ABS64||ELF64_R_TYPE(r.r_info)==R_AARCH64_GLOB_DAT||ELF64_R_TYPE(r.r_info)==R_AARCH64_JUMP_SLOT){
                    size_t n=ELF64_R_SYM(r.r_info);if(n>=count)throw std::runtime_error("relocation symbol");
                    if(symbols[n].st_shndx!=SHN_UNDEF){value=image.bias+symbols[n].st_value+r.r_addend;apply=true;}
                }
                if(apply){if(!image.contains(image.bias+r.r_offset,8,PF_R))throw std::runtime_error("relocation bounds");std::memcpy(reinterpret_cast<void*>(image.bias+r.r_offset),&value,8);}
            }
        }
    }
    uintptr_t symbol(const char* name,size_t* size=nullptr)const {
        uintptr_t value=0;unsigned matches=0;
        for(size_t i=0;i<count;++i){const auto& s=symbols[i];if(s.st_name>=name_size)throw std::runtime_error("symbol name");
            if(!std::memchr(names+s.st_name,0,name_size-s.st_name))throw std::runtime_error("string bounds");
            if(s.st_shndx!=SHN_UNDEF&&!std::strcmp(name,names+s.st_name)){value=image.bias+s.st_value;if(size)*size=s.st_size;++matches;}}
        return matches==1?value:0;
    }
};
}
int main(int argc,char** argv){
    try{
        if(argc!=2)throw std::runtime_error("usage: layout_audit libaudioflinger.so");
        for(size_t padding:{size_t(0),size_t(4096)}){
            Fixture f(argv[1],padding);size_t table_size=0,ctor_size=0;
            auto table_symbol=f.symbol("_ZTVN7android5TrackE",&table_size);
            auto d=f.symbol("_ZN7android5TrackD1Ev"),n=f.symbol("_ZN7android5Track13getNextBufferEPNS_19AudioBufferProvider6BufferE"),r=f.symbol("_ZN7android5Track13releaseBufferEPNS_19AudioBufferProvider6BufferE");
            uintptr_t table=0;unsigned matches=0;
            for(size_t off=0;off+104*8<=table_size;off+=8){uintptr_t a=0,b=0,c=0;
                if(!f.image.read(table_symbol+off,a)||!f.image.read(table_symbol+off+16,b)||!f.image.read(table_symbol+off+24,c))throw std::runtime_error("table read");
                if(a==d&&b==n&&c==r){table=table_symbol+off;++matches;}}
            if(matches!=1)throw std::runtime_error("vtable anchors");
            auto ctor=f.symbol("_ZN7android9TrackBaseC2EPNS_13IAfThreadBaseERKNS_2spINS_6ClientEEERK18audio_attributes_tj14audio_format_t20audio_channel_mask_tmPvm15audio_session_tijbNS_12IAfTrackBase10alloc_typeENSF_10track_typeEiNSt3__112basic_stringIcNSI_11char_traitsIcEENSI_9allocatorIcEEEERKSO_",&ctor_size);
            TrackLayout l;auto read=[&](uintptr_t address,auto& out){return f.image.read(address,out);};
            if(!discover_track_layout(read,table,f.symbol("_ZNK7android5Track10sampleRateEv"),f.symbol("_ZNK7android5Track11getVolumeLREv"),ctor,ctor_size,l))throw std::runtime_error("layout unsupported");
            uintptr_t slot=0;if(!f.image.jump_slot("_ZN7android5Track15interceptBufferERKNS_19AudioBufferProvider6BufferE",slot))throw std::runtime_error("PLT not unique");
            std::cout<<"relocated="<<padding<<" uid="<<l.uid<<" session="<<l.session<<" state="<<l.state<<" format="<<l.format<<" mask="<<l.mask<<" io="<<l.io<<" proxy="<<l.proxy<<" gain="<<l.gain<<" plt="<<slot-f.image.bias<<"\n";
            // Corrupt a getter: the same library must stop qualifying, even
            // with unchanged filename, SONAME, ELF Build ID and all symbols.
            uintptr_t getter=0;f.image.read(table+14*8,getter);uint32_t bad=0;std::memcpy(reinterpret_cast<void*>(getter),&bad,4);
            if(discover_track_layout(read,table,f.symbol("_ZNK7android5Track10sampleRateEv"),f.symbol("_ZNK7android5Track11getVolumeLREv"),ctor,ctor_size,l))throw std::runtime_error("corrupt getter accepted");
        }
        return 0;
    }catch(const std::exception& e){std::cerr<<e.what()<<"\n";return 1;}
}
