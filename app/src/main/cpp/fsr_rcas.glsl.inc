// AMD FSR 1.0 RCAS in GLSL, the body shared by both sharpening shaders: gbabridge.cpp builds
// the OpenGL ES one from it at run time; rcas_frag.inc is the Vulkan one, compiled with
// glslangValidator -V from the shader in vulkan_hw.cpp's comment with this body pasted in.
// Each shader defines vec3 at(ivec2 pixel), clamped to the picture, before it.
R"GLSL(
// AMD FidelityFX Super Resolution 1.0, RCAS (robust contrast-adaptive sharpening), after
// ffx_fsr1.h (MIT), run on EASU's output: each pixel is pushed away from its four
// neighbours by as much as it can be without going past their lightest or darkest value.
// Sharpness 0.5 stops ("약하게"); AMD's default is 0.2.
vec3 rcas(ivec2 p) {
    vec3 b = at(p + ivec2(0, -1));
    vec3 d = at(p + ivec2(-1, 0));
    vec3 e = at(p);
    vec3 f = at(p + ivec2(1, 0));
    vec3 h = at(p + ivec2(0, 1));
    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));
    vec3 hitMin = mn4 / (4.0 * mx4 + 1.0 / 65536.0);
    vec3 hitMax = (1.0 - mx4) / (4.0 * mn4 - 4.0 - 1.0 / 65536.0);
    vec3 lobeRGB = max(-hitMin, hitMax);
    float lobe = max(-(0.25 - 1.0 / 16.0), min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * 0.70710678;
    return (lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0);
}
)GLSL"
