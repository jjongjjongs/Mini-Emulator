// 3D 다듬기 (0.0.369) in GLSL, the body shared by the OpenGL ES shaders gbabridge.cpp builds
// at run time and the Vulkan ones (fxaa_frag.inc, post_frag.inc), compiled with
// glslangValidator -V from the shaders in vulkan_hw.cpp's comments with this body pasted in.
// Each shader defines vec3 fxSample(vec2 uv) (filtered, kept inside the picture) and
// vec3 fxTexel(ivec2 texel) before it.
//   fxaa: edge smoothing after Timothy Lottes' FXAA (the small console form): the luma of the
//         four diagonal neighbours gives the edge's direction, and the pixel is blurred along
//         it, unless that goes outside the neighbourhood's range.
//   cas:  sharpening after AMD FidelityFX CAS: a cross-shaped unsharp mask whose weight
//         shrinks where the neighbourhood is already near black or white.
//   grade: 0 as drawn, 1 자연스러운 색 (calmer), 2 또렷한 색 (more saturated, a little contrast).
R"GLSL(
const vec3 kLuma = vec3(0.299, 0.587, 0.114);

vec3 fxaa(vec2 p, vec2 r) {
    vec3 nw = fxSample(p + vec2(-1.0, -1.0) * r);
    vec3 ne = fxSample(p + vec2(1.0, -1.0) * r);
    vec3 sw = fxSample(p + vec2(-1.0, 1.0) * r);
    vec3 se = fxSample(p + vec2(1.0, 1.0) * r);
    vec3 m = fxSample(p);
    float lNW = dot(nw, kLuma);
    float lNE = dot(ne, kLuma);
    float lSW = dot(sw, kLuma);
    float lSE = dot(se, kLuma);
    float lM = dot(m, kLuma);
    float lMin = min(lM, min(min(lNW, lNE), min(lSW, lSE)));
    float lMax = max(lM, max(max(lNW, lNE), max(lSW, lSE)));
    if (lMax - lMin < max(0.0312, lMax * 0.125)) {
        return m;  // no edge worth smoothing
    }
    vec2 dir = vec2(-((lNW + lNE) - (lSW + lSE)), (lNW + lSW) - (lNE + lSE));
    float reduce = max((lNW + lNE + lSW + lSE) * (0.25 / 8.0), 1.0 / 128.0);
    float rcpMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);
    dir = clamp(dir * rcpMin, vec2(-8.0), vec2(8.0)) * r;
    vec3 a = 0.5 * (fxSample(p + dir * (1.0 / 3.0 - 0.5)) + fxSample(p + dir * (2.0 / 3.0 - 0.5)));
    vec3 b = a * 0.5 + 0.25 * (fxSample(p - dir * 0.5) + fxSample(p + dir * 0.5));
    float lB = dot(b, kLuma);
    return (lB < lMin || lB > lMax) ? a : b;
}

vec3 cas(vec3 c, ivec2 p, float sharp) {
    vec3 a = fxTexel(p + ivec2(0, -1));
    vec3 b = fxTexel(p + ivec2(-1, 0));
    vec3 d = fxTexel(p + ivec2(1, 0));
    vec3 e = fxTexel(p + ivec2(0, 1));
    vec3 lo = min(min(min(a, b), min(c, d)), e);
    vec3 hi = max(max(max(a, b), max(c, d)), e);
    vec3 amp = sqrt(clamp(min(lo, 2.0 - hi) / max(hi, vec3(1.0 / 1024.0)), 0.0, 1.0));
    vec3 w = amp * (-1.0 / mix(8.0, 5.0, clamp(sharp, 0.0, 1.0)));
    return clamp((c + (a + b + d + e) * w) / (1.0 + 4.0 * w), 0.0, 1.0);
}

vec3 grade(vec3 c, int mode) {
    float l = dot(c, kLuma);
    if (mode == 1) {
        c = mix(vec3(l), c, 0.82);
        c = mix(vec3(0.5), c, 0.96);
    } else if (mode == 2) {
        c = mix(vec3(l), c, 1.3);
        c = (c - 0.5) * 1.06 + 0.5;
    }
    return clamp(c, 0.0, 1.0);
}
)GLSL"
