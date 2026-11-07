// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cstddef>
#include <cstring>
namespace tap {
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
