// AMD FSR 1.0 EASU in GLSL, the body shared by both FSR shaders: gbabridge.cpp builds the
// OpenGL ES one from it at run time; fsr_frag.inc is the Vulkan one, compiled with
// glslangValidator -V from the shader in vulkan_hw.cpp's comment with this body pasted in.
// Each shader defines vec3 fetch(ivec2 texel) before it.
R"GLSL(
// AMD FidelityFX Super Resolution 1.0, EASU (edge-adaptive spatial upsampling), after
// ffx_fsr1.h (MIT): 12 texels around the output pixel's place in the source, an edge
// direction and length from their luma, then a direction-stretched Lanczos-like filter,
// clamped to the four nearest texels so it can't ring.
float easuLuma(vec3 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }

void easuSet(inout vec2 dir, inout float len, vec2 pp, float w,
             float lA, float lB, float lC, float lD, float lE) {
    float dc = lD - lC;
    float cb = lC - lB;
    float lenX = max(max(abs(dc), abs(cb)), 1.0 / 65536.0);
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) / lenX, 0.0, 1.0);
    len += lenX * lenX * w;
    float ec = lE - lC;
    float ca = lC - lA;
    float lenY = max(max(abs(ec), abs(ca)), 1.0 / 65536.0);
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) / lenY, 0.0, 1.0);
    len += lenY * lenY * w;
}

void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len2, float lob, float clp, vec3 c) {
    vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * (-dir.y) + off.y * dir.x) * len2;
    float d2 = min(v.x * v.x + v.y * v.y, clp);
    float wB = 2.0 / 5.0 * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

// src: where the output pixel's centre falls in the source, in texels (0.5 = first centre).
vec3 easu(vec2 src) {
    vec2 pp = src - 0.5;
    vec2 fp = floor(pp);
    pp -= fp;
    ivec2 p = ivec2(fp);
    vec3 b = fetch(p + ivec2(0, -1)), c = fetch(p + ivec2(1, -1));
    vec3 e = fetch(p + ivec2(-1, 0)), f = fetch(p), g = fetch(p + ivec2(1, 0)), h = fetch(p + ivec2(2, 0));
    vec3 i = fetch(p + ivec2(-1, 1)), j = fetch(p + ivec2(0, 1)), k = fetch(p + ivec2(1, 1)), l = fetch(p + ivec2(2, 1));
    vec3 n = fetch(p + ivec2(0, 2)), o = fetch(p + ivec2(1, 2));
    float bL = easuLuma(b), cL = easuLuma(c), eL = easuLuma(e), fL = easuLuma(f), gL = easuLuma(g), hL = easuLuma(h);
    float iL = easuLuma(i), jL = easuLuma(j), kL = easuLuma(k), lL = easuLuma(l), nL = easuLuma(n), oL = easuLuma(o);
    vec2 dir = vec2(0.0);
    float len = 0.0;
    easuSet(dir, len, pp, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
    easuSet(dir, len, pp, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
    easuSet(dir, len, pp, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
    easuSet(dir, len, pp, pp.x * pp.y, gL, jL, kL, lL, oL);
    float dirR = dir.x * dir.x + dir.y * dir.y;
    bool zero = dirR < 1.0 / 32768.0;
    dirR = zero ? 1.0 : inversesqrt(dirR);
    dir.x = zero ? 1.0 : dir.x;
    dir *= dirR;
    len = len * 0.5;
    len *= len;
    float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = 1.0 / lob;
    vec3 aC = vec3(0.0);
    float aW = 0.0;
    easuTap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
    easuTap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
    easuTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
    easuTap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
    easuTap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
    easuTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
    easuTap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
    easuTap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
    easuTap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
    easuTap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
    easuTap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
    easuTap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);
    vec3 lo = min(min(f, g), min(j, k));
    vec3 hi = max(max(f, g), max(j, k));
    return clamp(aC / aW, lo, hi);
}
)GLSL"
