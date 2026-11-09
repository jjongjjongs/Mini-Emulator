// Screen filter presets done on the frame itself (0.0.360): what the lines and masks drawn
// over the picture can't do — brighten for the CRT look, a handheld LCD's colours and
// smear, the Game Boy's green, and Scale2x for smooth pixel art. Software frames only;
// kept apart so it can be tested on the host.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace screenfx {

enum Mode { NONE = 0, BRIGHTEN = 1, LCD = 2, DOT_MATRIX = 3, SCALE2X = 4 };

inline void unpack(uint32_t p, bool wide, int& r, int& g, int& b) {
    if (wide) {  // bytes R, G, B, X
        r = static_cast<int>(p & 0xFFu);
        g = static_cast<int>((p >> 8) & 0xFFu);
        b = static_cast<int>((p >> 16) & 0xFFu);
    } else {     // RGB565
        r = static_cast<int>((p >> 11) & 0x1Fu) * 255 / 31;
        g = static_cast<int>((p >> 5) & 0x3Fu) * 255 / 63;
        b = static_cast<int>(p & 0x1Fu) * 255 / 31;
    }
}

inline uint32_t pack(int r, int g, int b, bool wide) {
    r = r < 0 ? 0 : r > 255 ? 255 : r;
    g = g < 0 ? 0 : g > 255 ? 255 : g;
    b = b < 0 ? 0 : b > 255 ? 255 : b;
    if (wide) return 0xFF000000u | (static_cast<uint32_t>(b) << 16) | (static_cast<uint32_t>(g) << 8) | static_cast<uint32_t>(r);
    return static_cast<uint32_t>(((r * 31 + 127) / 255) << 11 | ((g * 63 + 127) / 255) << 5 | ((b * 31 + 127) / 255));
}

inline uint32_t at(const uint8_t* data, size_t index, bool wide) {
    return wide ? reinterpret_cast<const uint32_t*>(data)[index] : reinterpret_cast<const uint16_t*>(data)[index];
}

inline void put(uint8_t* data, size_t index, uint32_t value, bool wide) {
    if (wide) reinterpret_cast<uint32_t*>(data)[index] = value;
    else reinterpret_cast<uint16_t*>(data)[index] = static_cast<uint16_t>(value);
}

// Processes w x h pixels (tightly packed; wide: 4 bytes RGBX, else RGB565) into out, which
// becomes outW x outH (twice the size for SCALE2X). strength 0..100. prev holds the last
// output for the LCD smear (same size; it is updated).
inline void apply(int mode, int strength, const uint8_t* in, unsigned w, unsigned h, bool wide,
                  std::vector<uint8_t>& out, unsigned& outW, unsigned& outH, std::vector<uint8_t>& prev) {
    const size_t bpp = wide ? 4 : 2;
    const size_t count = static_cast<size_t>(w) * h;
    float k = (strength < 0 ? 0 : strength > 100 ? 100 : strength) / 100.0f;
    if (mode == SCALE2X) {
        outW = w * 2;
        outH = h * 2;
        out.resize(static_cast<size_t>(outW) * outH * bpp);
        for (unsigned y = 0; y < h; ++y) {
            for (unsigned x = 0; x < w; ++x) {
                uint32_t P = at(in, static_cast<size_t>(y) * w + x, wide);
                uint32_t A = at(in, static_cast<size_t>(y > 0 ? y - 1 : y) * w + x, wide);
                uint32_t D = at(in, static_cast<size_t>(y + 1 < h ? y + 1 : y) * w + x, wide);
                uint32_t C = at(in, static_cast<size_t>(y) * w + (x > 0 ? x - 1 : x), wide);
                uint32_t B = at(in, static_cast<size_t>(y) * w + (x + 1 < w ? x + 1 : x), wide);
                // EPX / Scale2x
                uint32_t e0 = (C == A && C != D && A != B) ? A : P;
                uint32_t e1 = (A == B && A != C && B != D) ? B : P;
                uint32_t e2 = (D == C && D != B && C != A) ? C : P;
                uint32_t e3 = (B == D && B != A && D != C) ? D : P;
                size_t o = static_cast<size_t>(y * 2) * outW + x * 2;
                put(out.data(), o, e0, wide);
                put(out.data(), o + 1, e1, wide);
                put(out.data(), o + outW, e2, wide);
                put(out.data(), o + outW + 1, e3, wide);
            }
        }
        return;
    }
    outW = w;
    outH = h;
    out.resize(count * bpp);
    if (mode == LCD && prev.size() != count * bpp) {
        prev.assign(in, in + count * bpp);
    }
    // Game Boy DMG shades, dark to light
    static const int greens[4][3] = {{15, 56, 15}, {48, 98, 48}, {139, 172, 15}, {155, 188, 15}};
    for (size_t i = 0; i < count; ++i) {
        int r, g, b;
        unpack(at(in, i, wide), wide, r, g, b);
        switch (mode) {
            case BRIGHTEN: {
                // gives back what the lines over the picture take away
                float gain = 1.0f + 0.45f * k;
                r = static_cast<int>(r * gain);
                g = static_cast<int>(g * gain);
                b = static_cast<int>(b * gain);
                break;
            }
            case LCD: {
                // a little less saturated and lighter blacks, like the GBA's screen, and the
                // slow pixels leaving a short trail
                int l = (r * 299 + g * 587 + b * 114) / 1000;
                float sat = 1.0f - 0.25f * k;
                r = static_cast<int>(l + (r - l) * sat) * 15 / 16 + 12;
                g = static_cast<int>(l + (g - l) * sat) * 15 / 16 + 12;
                b = static_cast<int>(l + (b - l) * sat) * 15 / 16 + 12;
                int pr, pg, pb;
                unpack(at(prev.data(), i, wide), wide, pr, pg, pb);
                float smear = 0.45f * k;
                r = static_cast<int>(r * (1.0f - smear) + pr * smear);
                g = static_cast<int>(g * (1.0f - smear) + pg * smear);
                b = static_cast<int>(b * (1.0f - smear) + pb * smear);
                break;
            }
            case DOT_MATRIX: {
                int l = (r * 299 + g * 587 + b * 114) / 1000;
                int shade = l < 64 ? 0 : l < 128 ? 1 : l < 192 ? 2 : 3;
                r = greens[shade][0];
                g = greens[shade][1];
                b = greens[shade][2];
                break;
            }
            default:
                break;
        }
        put(out.data(), i, pack(r, g, b, wide), wide);
    }
    if (mode == LCD) {
        prev.assign(out.begin(), out.end());
    }
}

}  // namespace screenfx
