// SPDX-License-Identifier: Apache-2.0
#pragma once
#if __has_include(<elf.h>)
#include <elf.h>
#else
// Fixed ELF64 little-endian wire structures for the offline macOS auditor.
#include <cstdint>
struct Elf64_Ehdr { unsigned char e_ident[16];uint16_t e_type,e_machine;uint32_t e_version;uint64_t e_entry,e_phoff,e_shoff;uint32_t e_flags;uint16_t e_ehsize,e_phentsize,e_phnum,e_shentsize,e_shnum,e_shstrndx; };
struct Elf64_Phdr { uint32_t p_type,p_flags;uint64_t p_offset,p_vaddr,p_paddr,p_filesz,p_memsz,p_align; };
struct Elf64_Shdr { uint32_t sh_name,sh_type;uint64_t sh_flags,sh_addr,sh_offset,sh_size;uint32_t sh_link,sh_info;uint64_t sh_addralign,sh_entsize; };
struct Elf64_Sym { uint32_t st_name;unsigned char st_info,st_other;uint16_t st_shndx;uint64_t st_value,st_size; };
struct Elf64_Dyn { int64_t d_tag;union {uint64_t d_val,d_ptr;} d_un; };
struct Elf64_Rela { uint64_t r_offset,r_info;int64_t r_addend; };
constexpr unsigned PT_LOAD=1,PT_DYNAMIC=2,PF_X=1,PF_R=4;
constexpr int64_t DT_NULL=0,DT_PLTRELSZ=2,DT_STRTAB=5,DT_SYMTAB=6,DT_RELA=7,DT_STRSZ=10,DT_SYMENT=11,DT_PLTREL=20,DT_JMPREL=23;
constexpr unsigned R_AARCH64_ABS64=257,R_AARCH64_GLOB_DAT=1025,R_AARCH64_JUMP_SLOT=1026,R_AARCH64_RELATIVE=1027;
constexpr unsigned SHT_RELA=4,SHT_DYNSYM=11,SHN_UNDEF=0,EM_AARCH64=183,EI_CLASS=4,EI_DATA=5,ELFCLASS64=2,ELFDATA2LSB=1;
constexpr char ELFMAG[]="\177ELF";
inline uint32_t ELF64_R_TYPE(uint64_t x){return uint32_t(x);}
inline uint32_t ELF64_R_SYM(uint64_t x){return uint32_t(x>>32);}
static_assert(sizeof(Elf64_Ehdr)==64&&sizeof(Elf64_Phdr)==56&&sizeof(Elf64_Shdr)==64&&sizeof(Elf64_Sym)==24&&sizeof(Elf64_Rela)==24);
#endif
