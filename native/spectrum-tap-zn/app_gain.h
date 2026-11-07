// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cstdint>
namespace tap {
constexpr uint32_t kUnityAppGain = 0xe000e000u;
// Android audio_utils gain encoding: unsigned 3-bit exponent / 13-bit
// significand. Independently expressed arithmetic, exhaustively checked
// against AOSP float_from_gain. This is not IEEE half or a system-volume curve.
constexpr float app_gain_channel(uint16_t code) {
    const unsigned exponent=code>>13, fraction=code&8191;
    return exponent ? (8192+fraction)*(1u<<exponent)/1048576.f : fraction/524288.f;
}
constexpr float app_gain_left(uint32_t packed) { return app_gain_channel(uint16_t(packed)); }
constexpr float app_gain_right(uint32_t packed) { return app_gain_channel(uint16_t(packed>>16)); }
}
