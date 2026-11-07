// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cstdint>
#include <cstddef>
#include <cstring>
namespace tap {
template<class T> T converted_field(const void* object, size_t offset) {
    T value;
    std::memcpy(&value, static_cast<const unsigned char*>(object) + offset, sizeof(T));
    return value;
}
// Caller must establish that provider is executing on its owning mixer thread.
// Only known Passthru/Copy provider ABIs may be followed. readable(offset,size)
// validates the pinned libaudioflinger's mapped read-only vtable region.
template<class Readable>
void* converted_source_track(const void* provider, uintptr_t processing, uintptr_t flinger, Readable readable) {
    const void* upstream = converted_field<const void*>(provider, 8);
    for (unsigned depth = 0; upstream && depth < 8; ++depth) {
        const auto vptr = converted_field<uintptr_t>(upstream, 0);
        const uintptr_t wrappers[]{0xb91f0,0xb92b0,0xb9370,0xb93d0,0xb9430,0xb9490,0xb94f0};
        bool wrapper = false;
        for (auto offset : wrappers) wrapper |= vptr == processing + offset;
        if (wrapper) { upstream = converted_field<const void*>(upstream, 8); continue; }
        if (vptr < flinger + 0x170 || !readable(vptr - flinger - 0x170, 0x188) ||
            converted_field<uintptr_t>(reinterpret_cast<const void*>(vptr), 0x10) != flinger + 0x211ea0) return nullptr;
        const auto adjustment = converted_field<int64_t>(reinterpret_cast<const void*>(vptr - 0x170), 0);
        if (adjustment > 0 || adjustment < -0x800 || (adjustment & 7)) return nullptr;
        return reinterpret_cast<void*>(reinterpret_cast<uintptr_t>(upstream) + adjustment);
    }
    return nullptr;
}
} // namespace tap
