// DS screen layout (0.0.359): melonDS DS draws its two 256x192 screens one above the other
// (256x384, no gap); the presenter copies each screen to where the app placed it in a
// canvas the size of the view (or smaller), on black. Kept apart so it can be tested on
// the host.
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace dslayout {

struct Blit {
    int source;               // 0 the upper screen, 1 the lower one
    int left, top, right, bottom;  // in the canvas
};

// Copies the screens of a top-bottom DS frame (srcW x srcH, the two screens srcH / 2 tall
// each) into dst (canvasW x canvasH, dstPitch bytes a row), nearest neighbour; the rest is
// black. bpp is 2 or 4. Blits later in the list are drawn over earlier ones.
inline void compose(const uint8_t* src, unsigned srcW, unsigned srcH, size_t srcPitch, size_t bpp,
                    uint8_t* dst, unsigned canvasW, unsigned canvasH, size_t dstPitch,
                    const std::vector<Blit>& blits) {
    for (unsigned y = 0; y < canvasH; ++y) {
        std::memset(dst + y * dstPitch, 0, canvasW * bpp);
        if (bpp == 4) {
            // opaque black (the alpha byte of RGBX is ignored, but keep it set)
            auto* row = reinterpret_cast<uint32_t*>(dst + y * dstPitch);
            for (unsigned x = 0; x < canvasW; ++x) row[x] = 0xFF000000u;
        }
    }
    if (srcW == 0 || srcH < 2) return;
    const unsigned screenH = srcH / 2;
    std::vector<unsigned> xs;
    for (const Blit& b : blits) {
        int w = b.right - b.left;
        int h = b.bottom - b.top;
        if (w <= 0 || h <= 0 || (b.source != 0 && b.source != 1)) continue;
        int x0 = b.left < 0 ? 0 : b.left;
        int y0 = b.top < 0 ? 0 : b.top;
        int x1 = b.right > static_cast<int>(canvasW) ? static_cast<int>(canvasW) : b.right;
        int y1 = b.bottom > static_cast<int>(canvasH) ? static_cast<int>(canvasH) : b.bottom;
        if (x0 >= x1 || y0 >= y1) continue;
        xs.resize(static_cast<size_t>(x1 - x0));
        for (int x = x0; x < x1; ++x) {
            unsigned sx = static_cast<unsigned>((static_cast<long long>(x - b.left) * srcW) / w);
            xs[static_cast<size_t>(x - x0)] = sx < srcW ? sx : srcW - 1;
        }
        const unsigned base = b.source == 1 ? screenH : 0;
        for (int y = y0; y < y1; ++y) {
            unsigned sy = static_cast<unsigned>((static_cast<long long>(y - b.top) * screenH) / h);
            if (sy >= screenH) sy = screenH - 1;
            const uint8_t* in = src + (base + sy) * srcPitch;
            uint8_t* out = dst + static_cast<size_t>(y) * dstPitch + static_cast<size_t>(x0) * bpp;
            if (bpp == 4) {
                const auto* in32 = reinterpret_cast<const uint32_t*>(in);
                auto* out32 = reinterpret_cast<uint32_t*>(out);
                for (size_t k = 0; k < xs.size(); ++k) out32[k] = in32[xs[k]];
            } else {
                const auto* in16 = reinterpret_cast<const uint16_t*>(in);
                auto* out16 = reinterpret_cast<uint16_t*>(out);
                for (size_t k = 0; k < xs.size(); ++k) out16[k] = in16[xs[k]];
            }
        }
    }
}

// The canvas for a W x H view: the view's size, made smaller (by a whole factor) to about
// 0.7 million pixels at most, so copying stays cheap; Android enlarges it the rest of the
// way. Returns the factor.
inline unsigned canvasDivisor(unsigned viewW, unsigned viewH) {
    unsigned k = 1;
    while (k < 8 && static_cast<unsigned long long>(viewW / k) * (viewH / k) > 700000ull) ++k;
    return k;
}

}  // namespace dslayout
