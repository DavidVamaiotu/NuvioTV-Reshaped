#pragma once
#include <cstdint>

namespace thumb {
// Match the admitted thumbnail tier; encoded resolution cannot silently grow past its memory budget.
inline int64_t preview_max_pixels(int width, int height) {
    if (width <= 0 || width > 4096 || height <= 0 || height > 2304) return 0;
    return static_cast<int64_t>((width + 63) & ~63) * ((height + 63) & ~63);
}
constexpr int PREVIEW_MAX_PACKET = 8 * 1024 * 1024 + 64 * 1024;
constexpr int PREVIEW_MAX_EXTRA = 1024 * 1024;
}
