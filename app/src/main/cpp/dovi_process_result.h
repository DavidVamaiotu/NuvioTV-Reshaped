#pragma once
#include <cstdint>

// Low 32 bits preserve the signed write length (negative means output capacity needed).
// Upper 16-bit fields report actual conversion calls/successes for low-RAM DV7 decisions.
static inline int64_t packProcessResult(int32_t written, uint16_t rpuCalls, uint16_t rpuSuccesses) {
    const uint64_t w = static_cast<uint32_t>(written);
    const uint64_t c = static_cast<uint64_t>(rpuCalls) << 32;
    const uint64_t s = static_cast<uint64_t>(rpuSuccesses) << 48;
    return static_cast<int64_t>(w | c | s);
}
