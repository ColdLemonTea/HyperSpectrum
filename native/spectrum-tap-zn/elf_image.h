// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "elf_types.h"
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <limits>
namespace tap {
struct ElfImage {
    uintptr_t bias=0;const Elf64_Phdr* phdr=nullptr;size_t count=0;
    bool contains(uintptr_t address,size_t size,uint32_t flags) const {
        if(address<bias)return false;
        const uint64_t offset=address-bias;
        for(size_t i=0;i<count;++i){const auto& p=phdr[i];
            if(p.p_type==PT_LOAD&&(p.p_flags&flags)==flags&&offset>=p.p_vaddr&&
               offset-p.p_vaddr<=p.p_memsz&&size<=p.p_memsz-(offset-p.p_vaddr))return true;}
        return false;
    }
    template<class T> bool read(uintptr_t address,T& out) const {
        if(!contains(address,sizeof(T),PF_R))return false;
        std::memcpy(&out,reinterpret_cast<const void*>(address),sizeof(T));return true;
    }
    uintptr_t pointer(uint64_t value,size_t bytes=1) const {
        if(contains(value,bytes,PF_R))return value;
        if(value>std::numeric_limits<uintptr_t>::max()-bias)return 0;
        return contains(bias+value,bytes,PF_R)?bias+value:0;
    }
    struct Dynamic { uintptr_t sym=0,str=0,rela=0;size_t strsz=0,relasz=0; };
    bool dynamic(Dynamic& d) const {
        bool found=false;
        for(size_t i=0;i<count;++i){const auto& p=phdr[i];
            if(p.p_type!=PT_DYNAMIC)continue;
            if(found||p.p_memsz>1024*1024)return false;found=true;
            bool terminated=false;uint64_t sym=0,str=0,rela=0;size_t ent=0;bool rel=false;
            for(size_t off=0;off+sizeof(Elf64_Dyn)<=p.p_memsz;off+=sizeof(Elf64_Dyn)){
                Elf64_Dyn x{};if(!read(bias+p.p_vaddr+off,x))return false;
                if(x.d_tag==DT_NULL){terminated=true;break;}
                switch(x.d_tag){case DT_SYMTAB:sym=x.d_un.d_ptr;break;case DT_STRTAB:str=x.d_un.d_ptr;break;
                    case DT_STRSZ:d.strsz=x.d_un.d_val;break;case DT_SYMENT:ent=x.d_un.d_val;break;
                    case DT_JMPREL:rela=x.d_un.d_ptr;break;case DT_PLTRELSZ:d.relasz=x.d_un.d_val;break;
                    case DT_PLTREL:rel=x.d_un.d_val==DT_RELA;break;default:break;}
            }
            if(!terminated||ent!=sizeof(Elf64_Sym)||!rel||!d.strsz||d.strsz>64*1024*1024||
               !d.relasz||d.relasz>16*1024*1024||d.relasz%sizeof(Elf64_Rela))return false;
            d.sym=pointer(sym,sizeof(Elf64_Sym));d.str=pointer(str,d.strsz);d.rela=pointer(rela,d.relasz);
            return d.sym&&d.str&&d.rela;
        }
        return false;
    }
    // Exact unique JUMP_SLOT by relocation symbol. Never assume a GOT offset.
    bool jump_slot(const char* name,uintptr_t& slot) const {
        Dynamic d;if(!dynamic(d))return false;size_t matches=0;
        for(size_t off=0;off<d.relasz;off+=sizeof(Elf64_Rela)){
            Elf64_Rela r{};if(!read(d.rela+off,r))return false;
            if(ELF64_R_TYPE(r.r_info)!=R_AARCH64_JUMP_SLOT)continue;
            const uint64_t index=ELF64_R_SYM(r.r_info);
            if(index>(std::numeric_limits<uintptr_t>::max()-d.sym)/sizeof(Elf64_Sym))return false;
            Elf64_Sym s{};if(!read(d.sym+index*sizeof(s),s)||s.st_name>=d.strsz)return false;
            const auto text=reinterpret_cast<const char*>(d.str+s.st_name);
            const size_t remaining=d.strsz-s.st_name,length=std::strlen(name);
            if(length>=remaining||std::memcmp(text,name,length)||text[length])continue;
            if(r.r_offset>std::numeric_limits<uintptr_t>::max()-bias)return false;
            const uintptr_t address=bias+r.r_offset;
            if((address&7)||!contains(address,sizeof(void*),PF_R))return false;
            slot=address;++matches;
        }
        return matches==1;
    }
};
}
