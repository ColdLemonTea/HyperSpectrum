// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
namespace tap {
// Bind the supported SELinux identity to the package directory's actual owner.
// Shared system UID alone does not authenticate SystemUI: the caller must also
// verify its exact process name and the returned process label through /proc.
inline const char* systemui_process_label(uint32_t owner, uint32_t peer,
                                         const char* socket_label) {
    if (owner != peer) return nullptr;
    if (owner == 1000 && std::strcmp(socket_label, "u:r:system_app:s0") == 0)
        return "u:r:system_app:s0";
    if (owner >= 10000 &&
        std::strcmp(socket_label, "u:r:platform_app:s0") == 0)
        return "u:r:platform_app:s0:c512,c768";
    return nullptr;
}
inline bool exact_identity(const char* bytes, size_t size, const char* expected) {
    const size_t length = std::strlen(expected);
    if (size < length || std::memcmp(bytes, expected, length) != 0) return false;
    if (size == length || (size == length + 1 && bytes[length] == '\n')) return true;
    // Zygote-specialized argv[0] retains its original argument area's NUL
    // padding. Accept only empty trailing bytes, never an additional argument.
    for (size_t i = length; i < size; ++i) if (bytes[i] != 0) return false;
    return true;
}
}
