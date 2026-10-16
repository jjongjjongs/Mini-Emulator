// Vulkan hardware rendering for libretro cores; see vulkan_hw.h.
//
// The core (PPSSPP) makes its device through the negotiation interface on an instance made
// here, renders each frame into images of its own and hands one over with set_image. Each
// video refresh draws that image over the window's swapchain with a full-screen triangle.
//
// Synchronisation follows the libretro Vulkan interface: there are kSlots sync indices, the
// index moves on after every frame shown, and wait_sync_index returns once the frontend has
// finished every read of the images handed over at the current index, so the core can draw
// into them again. All queue use, the core's included, goes through one lock.
#include "vulkan_hw.h"

// Vulkan structs are written as {sType} with the rest zeroed.
#pragma clang diagnostic ignored "-Wmissing-field-initializers"

#define VK_NO_PROTOTYPES
#ifdef __ANDROID__
#define VK_USE_PLATFORM_ANDROID_KHR
#endif
#include <vulkan/vulkan.h>

#include "libretro_vulkan.h"

// Older NDK headers stop at Vulkan 1.3.
#ifndef VK_API_VERSION_1_4
#define VK_API_VERSION_1_4 VK_MAKE_API_VERSION(0, 1, 4, 0)
#endif

#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#define VLOGI(...) __android_log_print(ANDROID_LOG_INFO, "MiniVulkan", __VA_ARGS__)
#define VLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MiniVulkan", __VA_ARGS__)

namespace vkhw {
namespace {

// Full-screen triangle sampling the core's image (blit.vert / blit.frag):
//   #version 450
//   layout(location = 0) out vec2 uv;
//   void main() {
//       uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
//       gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
//   }
//
//   #version 450
//   layout(location = 0) in vec2 uv;
//   layout(location = 0) out vec4 color;
//   layout(set = 0, binding = 0) uniform sampler2D frame;
//   void main() { color = vec4(texture(frame, uv).rgb, 1.0); }
// compiled with glslangValidator -V.
const uint32_t kBlitVert[] = {
#include "blit_vert.inc"
};
const uint32_t kBlitFrag[] = {
#include "blit_frag.inc"
};

constexpr uint32_t kSlots = 3;

#define VK_GLOBAL(X) X(vkCreateInstance) X(vkEnumerateInstanceExtensionProperties)
#define VK_INSTANCE(X)                                                                            \
    X(vkDestroyInstance) X(vkEnumeratePhysicalDevices) X(vkGetPhysicalDeviceProperties)           \
    X(vkGetPhysicalDeviceQueueFamilyProperties) X(vkEnumerateDeviceExtensionProperties)           \
    X(vkGetPhysicalDeviceSurfaceSupportKHR) X(vkGetPhysicalDeviceSurfaceCapabilitiesKHR)          \
    X(vkGetPhysicalDeviceSurfaceFormatsKHR) X(vkGetPhysicalDeviceSurfacePresentModesKHR)          \
    X(vkGetPhysicalDeviceMemoryProperties) X(vkDestroySurfaceKHR) X(vkCreateDevice)               \
    X(vkGetDeviceProcAddr)
#define VK_DEVICE(X)                                                                              \
    X(vkGetDeviceQueue) X(vkDeviceWaitIdle) X(vkCreateSwapchainKHR) X(vkDestroySwapchainKHR)      \
    X(vkGetSwapchainImagesKHR) X(vkAcquireNextImageKHR) X(vkQueuePresentKHR) X(vkQueueSubmit)     \
    X(vkCreateImageView) X(vkDestroyImageView) X(vkCreateRenderPass) X(vkDestroyRenderPass)       \
    X(vkCreateFramebuffer) X(vkDestroyFramebuffer) X(vkCreateShaderModule)                        \
    X(vkDestroyShaderModule) X(vkCreatePipelineLayout) X(vkCreateGraphicsPipelines)               \
    X(vkDestroyPipeline) X(vkCreateDescriptorSetLayout) X(vkCreateDescriptorPool)                 \
    X(vkAllocateDescriptorSets) X(vkUpdateDescriptorSets) X(vkCreateSampler) X(vkCreateCommandPool) \
    X(vkAllocateCommandBuffers) X(vkBeginCommandBuffer) X(vkEndCommandBuffer)                     \
    X(vkResetCommandBuffer) X(vkCmdBeginRenderPass) X(vkCmdEndRenderPass) X(vkCmdBindPipeline)    \
    X(vkCmdBindDescriptorSets) X(vkCmdSetViewport) X(vkCmdSetScissor) X(vkCmdDraw)                \
    X(vkCmdPipelineBarrier) X(vkCreateFence) X(vkWaitForFences) X(vkResetFences)                  \
    X(vkCreateSemaphore) X(vkDestroySemaphore) X(vkCreateBuffer) X(vkGetBufferMemoryRequirements)  \
    X(vkAllocateMemory) X(vkBindBufferMemory) X(vkMapMemory) X(vkCmdCopyImageToBuffer)

#define VK_DECLARE(name) PFN_##name name = nullptr;
VK_GLOBAL(VK_DECLARE)
VK_INSTANCE(VK_DECLARE)
VK_DEVICE(VK_DECLARE)
#ifdef VKHW_HEADLESS
PFN_vkCreateHeadlessSurfaceEXT vkCreateHeadlessSurfaceEXT = nullptr;
#else
PFN_vkCreateAndroidSurfaceKHR vkCreateAndroidSurfaceKHR = nullptr;
#endif
PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr = nullptr;
PFN_vkEnumerateInstanceVersion vkEnumerateInstanceVersion = nullptr;

// The instance, made once by available() with what the core's Vulkan code expects of one.
VkInstance gInstance = VK_NULL_HANDLE;
VkPhysicalDevice gGpu = VK_NULL_HANDLE;
int gAvailable = -1;
std::mutex gAvailableMutex;

#ifndef VKHW_HEADLESS
// A window of our own that never shows anything (an image reader's), for the surface the
// core is created against: it outlives the game's windows, which come and go with rotation.
struct MediaNdk {
    int (*newReader)(int32_t, int32_t, int32_t, int32_t, void**) = nullptr;
    int (*getWindow)(void*, ANativeWindow**) = nullptr;
};
void* gImageReader = nullptr;

ANativeWindow* placeholderWindow() {
    static ANativeWindow* window = nullptr;
    if (window != nullptr) {
        return window;
    }
    void* lib = dlopen("libmediandk.so", RTLD_NOW);
    MediaNdk ndk;
    if (lib != nullptr) {
        ndk.newReader = reinterpret_cast<decltype(ndk.newReader)>(dlsym(lib, "AImageReader_new"));
        ndk.getWindow = reinterpret_cast<decltype(ndk.getWindow)>(dlsym(lib, "AImageReader_getWindow"));
    }
    // 64x64 AIMAGE_FORMAT_RGBA_8888 (1), two images.
    if (ndk.newReader == nullptr || ndk.getWindow == nullptr || ndk.newReader(64, 64, 1, 2, &gImageReader) != 0 ||
        ndk.getWindow(gImageReader, &window) != 0) {
        window = nullptr;
    }
    return window;
}
#endif

VkSurfaceKHR makeSurface(ANativeWindow* window) {
    VkSurfaceKHR surface = VK_NULL_HANDLE;
#ifdef VKHW_HEADLESS
    (void)window;
    VkHeadlessSurfaceCreateInfoEXT info{VK_STRUCTURE_TYPE_HEADLESS_SURFACE_CREATE_INFO_EXT};
    if (vkCreateHeadlessSurfaceEXT(gInstance, &info, nullptr, &surface) != VK_SUCCESS) {
        return VK_NULL_HANDLE;
    }
#else
    if (window == nullptr) {
        return VK_NULL_HANDLE;
    }
    VkAndroidSurfaceCreateInfoKHR info{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    info.window = window;
    if (vkCreateAndroidSurfaceKHR(gInstance, &info, nullptr, &surface) != VK_SUCCESS) {
        return VK_NULL_HANDLE;
    }
#endif
    return surface;
}

bool hasExtension(const std::vector<VkExtensionProperties>& list, const char* name) {
    for (const auto& e : list) {
        if (strcmp(e.extensionName, name) == 0) {
            return true;
        }
    }
    return false;
}

int graphicsFamily(VkPhysicalDevice gpu) {
    uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &count, nullptr);
    std::vector<VkQueueFamilyProperties> families(count);
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &count, families.data());
    for (uint32_t i = 0; i < count; ++i) {
        if ((families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

bool probe() {
    void* lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (lib == nullptr) {
        lib = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    }
    if (lib == nullptr) {
        VLOGI("no Vulkan loader");
        return false;
    }
    vkGetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
    if (vkGetInstanceProcAddr == nullptr) {
        return false;
    }
#define VK_LOAD_GLOBAL(name) name = reinterpret_cast<PFN_##name>(vkGetInstanceProcAddr(VK_NULL_HANDLE, #name));
    VK_GLOBAL(VK_LOAD_GLOBAL)
    vkEnumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
            vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    if (vkCreateInstance == nullptr || vkEnumerateInstanceExtensionProperties == nullptr) {
        return false;
    }
    // The version PPSSPP's own instance code would ask for: what the loader offers, up to 1.4.
    uint32_t apiVersion = VK_API_VERSION_1_0;
    if (vkEnumerateInstanceVersion != nullptr) {
        vkEnumerateInstanceVersion(&apiVersion);
        apiVersion &= 0xFFFFF000u;
        apiVersion = std::min<uint32_t>(apiVersion, VK_API_VERSION_1_4);
    }
    uint32_t count = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> available(count);
    vkEnumerateInstanceExtensionProperties(nullptr, &count, available.data());
#ifdef VKHW_HEADLESS
    const char* platformSurface = VK_EXT_HEADLESS_SURFACE_EXTENSION_NAME;
#else
    const char* platformSurface = VK_KHR_ANDROID_SURFACE_EXTENSION_NAME;
#endif
    if (!hasExtension(available, VK_KHR_SURFACE_EXTENSION_NAME) || !hasExtension(available, platformSurface)) {
        VLOGI("no Vulkan surface support");
        return false;
    }
    std::vector<const char*> extensions = {VK_KHR_SURFACE_EXTENSION_NAME, platformSurface};
    for (const char* optional : {VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME, VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME}) {
        if (hasExtension(available, optional)) {
            extensions.push_back(optional);
        }
    }
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Mini Emulator";
    app.pEngineName = "Mini Emulator";
    app.apiVersion = apiVersion;
    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    info.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    info.ppEnabledExtensionNames = extensions.data();
    if (vkCreateInstance(&info, nullptr, &gInstance) != VK_SUCCESS) {
        VLOGI("vkCreateInstance failed");
        gInstance = VK_NULL_HANDLE;
        return false;
    }
#define VK_LOAD_INSTANCE(name) name = reinterpret_cast<PFN_##name>(vkGetInstanceProcAddr(gInstance, #name));
    VK_INSTANCE(VK_LOAD_INSTANCE)
#ifdef VKHW_HEADLESS
    VK_LOAD_INSTANCE(vkCreateHeadlessSurfaceEXT)
    bool surfaceLoaded = vkCreateHeadlessSurfaceEXT != nullptr;
#else
    VK_LOAD_INSTANCE(vkCreateAndroidSurfaceKHR)
    bool surfaceLoaded = vkCreateAndroidSurfaceKHR != nullptr;
#endif
    if (!surfaceLoaded || vkEnumeratePhysicalDevices == nullptr || vkCreateDevice == nullptr || vkGetDeviceProcAddr == nullptr) {
        return false;
    }
    count = 0;
    vkEnumeratePhysicalDevices(gInstance, &count, nullptr);
    std::vector<VkPhysicalDevice> gpus(count);
    vkEnumeratePhysicalDevices(gInstance, &count, gpus.data());
    for (VkPhysicalDevice gpu : gpus) {
        uint32_t extCount = 0;
        vkEnumerateDeviceExtensionProperties(gpu, nullptr, &extCount, nullptr);
        std::vector<VkExtensionProperties> deviceExtensions(extCount);
        vkEnumerateDeviceExtensionProperties(gpu, nullptr, &extCount, deviceExtensions.data());
        if (graphicsFamily(gpu) >= 0 && hasExtension(deviceExtensions, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
            gGpu = gpu;
            break;
        }
    }
    if (gGpu == VK_NULL_HANDLE) {
        VLOGI("no Vulkan GPU with a graphics queue and swapchains");
        return false;
    }
#ifndef VKHW_HEADLESS
    if (placeholderWindow() == nullptr) {
        VLOGI("no placeholder window for Vulkan");
        return false;
    }
#endif
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(gGpu, &props);
    VLOGI("Vulkan %u.%u on %s", VK_API_VERSION_MAJOR(props.apiVersion), VK_API_VERSION_MINOR(props.apiVersion), props.deviceName);
    return true;
}

}  // namespace

bool available() {
    std::lock_guard<std::mutex> lock(gAvailableMutex);
    if (gAvailable < 0) {
        gAvailable = probe() ? 1 : 0;
    }
    return gAvailable == 1;
}

class Host {
public:
    retro_hw_render_callback hw{};
    const retro_hw_render_context_negotiation_interface_vulkan* negotiation = nullptr;
    retro_hw_render_interface_vulkan iface{};

    VkPhysicalDevice gpu = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t family = 0;
    VkSurfaceKHR placeholder = VK_NULL_HANDLE;
    std::mutex queueMutex;

    // What the core handed over, and which of our frames read what (state mutex).
    std::mutex state;
    bool haveImage = false;
    retro_vulkan_image image{};
    uint32_t imageIndex = 0;  // sync index it was handed over at
    std::vector<VkSemaphore> imageWaits;
    uint32_t syncIndex = 0;
    uint32_t lastGivenIndex = 0;
    uint32_t readSlots[kSlots] = {};  // per sync index: slots whose frames read its images
    std::vector<VkCommandBuffer> coreCommands;
    VkSemaphore coreSignal = VK_NULL_HANDLE;
    // A fence is reset and submitted only while no other thread waits on it (Vulkan wants
    // that access to itself); the core's waits are counted here.
    std::mutex fenceMutex;
    std::condition_variable fenceIdle;
    int fenceWaiters = 0;

    // Presentation (emulation thread only).
    ANativeWindow* window = nullptr;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkExtent2D extent{};
    std::vector<VkImage> chainImages;
    std::vector<VkImageView> chainViews;
    std::vector<VkFramebuffer> framebuffers;
    std::vector<VkSemaphore> rendered;  // per swapchain image
    bool rebuild = false;
    VkRenderPass renderPass = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkFormat pipelineFormat = VK_FORMAT_UNDEFINED;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkDescriptorPool pool = VK_NULL_HANDLE;
    VkDescriptorSet sets[kSlots] = {};
    VkSampler sampler = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commands[kSlots] = {};
    VkFence fences[kSlots] = {};
    VkSemaphore acquired[kSlots] = {};
#ifdef VKHW_HEADLESS
    VkBuffer readback = VK_NULL_HANDLE;
    void* readbackData = nullptr;
#endif
};

#ifdef VKHW_HEADLESS
// Host tests: the centre pixel (R, G, B, A bytes) of the last frame shown.
extern "C" uint32_t vkhw_test_last_pixel();
std::atomic<uint32_t> gTestPixel{0};
extern "C" uint32_t vkhw_test_last_pixel() { return gTestPixel.load(); }
#endif

namespace {

void setImage(void* handle, const retro_vulkan_image* image, uint32_t numSemaphores, const VkSemaphore* semaphores,
              uint32_t /*srcQueueFamily*/) {
    auto* host = static_cast<Host*>(handle);
    std::lock_guard<std::mutex> lock(host->state);
    if (image == nullptr) {
        host->haveImage = false;
        return;
    }
    host->image = *image;
    host->image.create_info.pNext = nullptr;
    host->imageIndex = host->lastGivenIndex;
    host->imageWaits.assign(semaphores, semaphores + numSemaphores);
    host->haveImage = true;
}

uint32_t getSyncIndex(void* handle) {
    auto* host = static_cast<Host*>(handle);
    std::lock_guard<std::mutex> lock(host->state);
    host->lastGivenIndex = host->syncIndex;
    return host->syncIndex;
}

uint32_t getSyncIndexMask(void* /*handle*/) {
    return (1u << kSlots) - 1;
}

void setCommandBuffers(void* handle, uint32_t count, const VkCommandBuffer* commands) {
    auto* host = static_cast<Host*>(handle);
    std::lock_guard<std::mutex> lock(host->state);
    host->coreCommands.assign(commands, commands + count);
}

void waitSyncIndex(void* handle) {
    auto* host = static_cast<Host*>(handle);
    VkFence wait[kSlots + 1];
    uint32_t count = 0;
    {
        std::lock_guard<std::mutex> lock(host->state);
        uint32_t index = host->syncIndex;
        uint32_t slots = host->readSlots[index] | (1u << index);
        host->readSlots[index] = 0;
        for (uint32_t s = 0; s < kSlots; ++s) {
            if ((slots & (1u << s)) != 0) {
                wait[count++] = host->fences[s];
            }
        }
    }
    if (count == 0) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(host->fenceMutex);
        host->fenceWaiters++;
    }
    vkWaitForFences(host->device, count, wait, VK_TRUE, UINT64_MAX);
    {
        std::lock_guard<std::mutex> lock(host->fenceMutex);
        host->fenceWaiters--;
    }
    host->fenceIdle.notify_all();
}

void lockQueue(void* handle) {
    static_cast<Host*>(handle)->queueMutex.lock();
}

void unlockQueue(void* handle) {
    static_cast<Host*>(handle)->queueMutex.unlock();
}

void setSignalSemaphore(void* handle, VkSemaphore semaphore) {
    auto* host = static_cast<Host*>(handle);
    std::lock_guard<std::mutex> lock(host->state);
    host->coreSignal = semaphore;
}

VkShaderModule shaderModule(VkDevice device, const uint32_t* code, size_t bytes) {
    VkShaderModuleCreateInfo info{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    info.codeSize = bytes;
    info.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    vkCreateShaderModule(device, &info, nullptr, &module);
    return module;
}

// Render pass and pipeline for the swapchain's format.
bool makePipeline(Host* h) {
    if (h->pipeline != VK_NULL_HANDLE && h->pipelineFormat == h->format) {
        return true;
    }
    if (h->pipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(h->device, h->pipeline, nullptr);
        vkDestroyRenderPass(h->device, h->renderPass, nullptr);
        h->pipeline = VK_NULL_HANDLE;
        h->renderPass = VK_NULL_HANDLE;
    }
    VkAttachmentDescription color{};
    color.format = h->format;
    color.samples = VK_SAMPLE_COUNT_1_BIT;
    color.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
#ifdef VKHW_HEADLESS
    color.finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
#else
    color.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
#endif
    VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &ref;
    // The acquire semaphore is waited at colour output; the layout change must follow it.
    VkSubpassDependency dependency{};
    dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
    dependency.dstSubpass = 0;
    dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.srcAccessMask = 0;
    dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo rp{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    rp.attachmentCount = 1;
    rp.pAttachments = &color;
    rp.subpassCount = 1;
    rp.pSubpasses = &subpass;
    rp.dependencyCount = 1;
    rp.pDependencies = &dependency;
    if (vkCreateRenderPass(h->device, &rp, nullptr, &h->renderPass) != VK_SUCCESS) {
        return false;
    }
    VkShaderModule vert = shaderModule(h->device, kBlitVert, sizeof(kBlitVert));
    VkShaderModule frag = shaderModule(h->device, kBlitFrag, sizeof(kBlitFrag));
    VkPipelineShaderStageCreateInfo stages[2] = {{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO},
                                                 {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}};
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vert;
    stages[0].pName = "main";
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = frag;
    stages[1].pName = "main";
    VkPipelineVertexInputStateCreateInfo vertexInput{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo assembly{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo viewport{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    viewport.viewportCount = 1;
    viewport.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo raster{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo multisample{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState blendAttachment{};
    blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo blend{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    blend.attachmentCount = 1;
    blend.pAttachments = &blendAttachment;
    VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dynamic.dynamicStateCount = 2;
    dynamic.pDynamicStates = dynamicStates;
    VkGraphicsPipelineCreateInfo info{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    info.stageCount = 2;
    info.pStages = stages;
    info.pVertexInputState = &vertexInput;
    info.pInputAssemblyState = &assembly;
    info.pViewportState = &viewport;
    info.pRasterizationState = &raster;
    info.pMultisampleState = &multisample;
    info.pColorBlendState = &blend;
    info.pDynamicState = &dynamic;
    info.layout = h->layout;
    info.renderPass = h->renderPass;
    VkResult result = vert != VK_NULL_HANDLE && frag != VK_NULL_HANDLE
            ? vkCreateGraphicsPipelines(h->device, VK_NULL_HANDLE, 1, &info, nullptr, &h->pipeline)
            : VK_ERROR_INITIALIZATION_FAILED;
    if (vert != VK_NULL_HANDLE) vkDestroyShaderModule(h->device, vert, nullptr);
    if (frag != VK_NULL_HANDLE) vkDestroyShaderModule(h->device, frag, nullptr);
    if (result != VK_SUCCESS) {
        h->pipeline = VK_NULL_HANDLE;
        return false;
    }
    h->pipelineFormat = h->format;
    return true;
}

void destroySwapchain(Host* h) {
    if (h->swapchain == VK_NULL_HANDLE) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(h->queueMutex);
        vkDeviceWaitIdle(h->device);
    }
    for (VkFramebuffer fb : h->framebuffers) vkDestroyFramebuffer(h->device, fb, nullptr);
    for (VkImageView view : h->chainViews) vkDestroyImageView(h->device, view, nullptr);
    for (VkSemaphore s : h->rendered) vkDestroySemaphore(h->device, s, nullptr);
    h->framebuffers.clear();
    h->chainViews.clear();
    h->chainImages.clear();
    h->rendered.clear();
    vkDestroySwapchainKHR(h->device, h->swapchain, nullptr);
    h->swapchain = VK_NULL_HANDLE;
}

bool makeSwapchain(Host* h) {
    destroySwapchain(h);
    h->rebuild = false;
    if (h->surface == VK_NULL_HANDLE) {
        return false;
    }
    VkBool32 supported = VK_FALSE;
    vkGetPhysicalDeviceSurfaceSupportKHR(h->gpu, h->family, h->surface, &supported);
    if (!supported) {
        VLOGE("the queue cannot present to the window");
        return false;
    }
    VkSurfaceCapabilitiesKHR caps;
    if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(h->gpu, h->surface, &caps) != VK_SUCCESS) {
        return false;
    }
    uint32_t count = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(h->gpu, h->surface, &count, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(count);
    vkGetPhysicalDeviceSurfaceFormatsKHR(h->gpu, h->surface, &count, formats.data());
    if (formats.empty()) {
        return false;
    }
    VkSurfaceFormatKHR chosen = formats[0];
    for (const auto& f : formats) {
        if ((f.format == VK_FORMAT_R8G8B8A8_UNORM || f.format == VK_FORMAT_B8G8R8A8_UNORM) &&
            f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            chosen = f;
            break;
        }
    }
    count = 0;
    vkGetPhysicalDeviceSurfacePresentModesKHR(h->gpu, h->surface, &count, nullptr);
    std::vector<VkPresentModeKHR> modes(count);
    vkGetPhysicalDeviceSurfacePresentModesKHR(h->gpu, h->surface, &count, modes.data());
    // The emulation thread paces frames itself, as with OpenGL's swap interval 0: mailbox
    // shows the newest frame without waiting for the display; FIFO is always there.
    VkPresentModeKHR mode = VK_PRESENT_MODE_FIFO_KHR;
    if (std::find(modes.begin(), modes.end(), VK_PRESENT_MODE_MAILBOX_KHR) != modes.end()) {
        mode = VK_PRESENT_MODE_MAILBOX_KHR;
    }
    VkExtent2D extent = caps.currentExtent;
    if (extent.width == 0xFFFFFFFFu) {
        extent = {640, 360};
    }
    if (extent.width == 0 || extent.height == 0) {
        return false;
    }
    uint32_t images = std::max<uint32_t>(caps.minImageCount + 1, 3);
    if (caps.maxImageCount > 0) {
        images = std::min(images, caps.maxImageCount);
    }
    VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    for (VkCompositeAlphaFlagBitsKHR a : {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
                                          VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR}) {
        if ((caps.supportedCompositeAlpha & a) != 0) {
            alpha = a;
            break;
        }
    }
    VkSwapchainCreateInfoKHR info{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    info.surface = h->surface;
    info.minImageCount = images;
    info.imageFormat = chosen.format;
    info.imageColorSpace = chosen.colorSpace;
    info.imageExtent = extent;
    info.imageArrayLayers = 1;
    info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
#ifdef VKHW_HEADLESS
    info.imageUsage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
#endif
    info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    // The compositor turns the picture with the display; the app does not pre-rotate.
    info.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) != 0
            ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
    info.compositeAlpha = alpha;
    info.presentMode = mode;
    info.clipped = VK_TRUE;
    if (vkCreateSwapchainKHR(h->device, &info, nullptr, &h->swapchain) != VK_SUCCESS) {
        VLOGE("vkCreateSwapchainKHR failed");
        h->swapchain = VK_NULL_HANDLE;
        return false;
    }
    h->format = chosen.format;
    h->extent = extent;
    if (!makePipeline(h)) {
        VLOGE("could not make the presentation pipeline");
        destroySwapchain(h);
        return false;
    }
    count = 0;
    vkGetSwapchainImagesKHR(h->device, h->swapchain, &count, nullptr);
    h->chainImages.resize(count);
    vkGetSwapchainImagesKHR(h->device, h->swapchain, &count, h->chainImages.data());
    for (VkImage image : h->chainImages) {
        VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        view.image = image;
        view.viewType = VK_IMAGE_VIEW_TYPE_2D;
        view.format = h->format;
        view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VkImageView imageView = VK_NULL_HANDLE;
        vkCreateImageView(h->device, &view, nullptr, &imageView);
        h->chainViews.push_back(imageView);
        VkFramebufferCreateInfo fb{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        fb.renderPass = h->renderPass;
        fb.attachmentCount = 1;
        fb.pAttachments = &imageView;
        fb.width = extent.width;
        fb.height = extent.height;
        fb.layers = 1;
        VkFramebuffer framebuffer = VK_NULL_HANDLE;
        vkCreateFramebuffer(h->device, &fb, nullptr, &framebuffer);
        h->framebuffers.push_back(framebuffer);
        VkSemaphoreCreateInfo sem{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VkSemaphore semaphore = VK_NULL_HANDLE;
        vkCreateSemaphore(h->device, &sem, nullptr, &semaphore);
        h->rendered.push_back(semaphore);
    }
    VLOGI("swapchain %ux%u, %u images, format %d, %s", extent.width, extent.height, count, h->format,
          mode == VK_PRESENT_MODE_MAILBOX_KHR ? "mailbox" : "fifo");
    return true;
}

bool makeDevice(Host* h) {
    h->placeholder = makeSurface(
#ifdef VKHW_HEADLESS
            nullptr
#else
            placeholderWindow()
#endif
    );
    if (h->placeholder == VK_NULL_HANDLE) {
        VLOGE("no placeholder surface");
        return false;
    }
    const char* required[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    if (h->negotiation != nullptr && h->negotiation->interface_type == RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN &&
        h->negotiation->create_device != nullptr) {
        retro_vulkan_context context{};
        VkPhysicalDeviceFeatures features{};
        if (!h->negotiation->create_device(&context, gInstance, gGpu, h->placeholder, vkGetInstanceProcAddr, required, 1,
                                           nullptr, 0, &features) ||
            context.device == VK_NULL_HANDLE) {
            VLOGE("the core could not create its Vulkan device");
            return false;
        }
        h->gpu = context.gpu;
        h->device = context.device;
        h->queue = context.queue;
        h->family = context.queue_family_index;
    } else {
        int family = graphicsFamily(gGpu);
        float priority = 1.0f;
        VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        queueInfo.queueFamilyIndex = static_cast<uint32_t>(family);
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;
        VkDeviceCreateInfo info{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        info.queueCreateInfoCount = 1;
        info.pQueueCreateInfos = &queueInfo;
        info.enabledExtensionCount = 1;
        info.ppEnabledExtensionNames = required;
        if (vkCreateDevice(gGpu, &info, nullptr, &h->device) != VK_SUCCESS) {
            return false;
        }
        h->gpu = gGpu;
        h->family = static_cast<uint32_t>(family);
        h->queue = VK_NULL_HANDLE;
    }
#define VK_LOAD_DEVICE(name) name = reinterpret_cast<PFN_##name>(vkGetDeviceProcAddr(h->device, #name));
    VK_DEVICE(VK_LOAD_DEVICE)
    if (h->queue == VK_NULL_HANDLE) {
        vkGetDeviceQueue(h->device, h->family, 0, &h->queue);
    }
    return true;
}

bool makeResources(Host* h) {
    VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.maxLod = 0.25f;
    if (vkCreateSampler(h->device, &samplerInfo, nullptr, &h->sampler) != VK_SUCCESS) {
        return false;
    }
    VkDescriptorSetLayoutBinding binding{};
    binding.binding = 0;
    binding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    binding.descriptorCount = 1;
    binding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    setInfo.bindingCount = 1;
    setInfo.pBindings = &binding;
    if (vkCreateDescriptorSetLayout(h->device, &setInfo, nullptr, &h->setLayout) != VK_SUCCESS) {
        return false;
    }
    VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layoutInfo.setLayoutCount = 1;
    layoutInfo.pSetLayouts = &h->setLayout;
    if (vkCreatePipelineLayout(h->device, &layoutInfo, nullptr, &h->layout) != VK_SUCCESS) {
        return false;
    }
    VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, kSlots};
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = kSlots;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &size;
    if (vkCreateDescriptorPool(h->device, &poolInfo, nullptr, &h->pool) != VK_SUCCESS) {
        return false;
    }
    VkDescriptorSetLayout layouts[kSlots];
    std::fill(layouts, layouts + kSlots, h->setLayout);
    VkDescriptorSetAllocateInfo allocInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocInfo.descriptorPool = h->pool;
    allocInfo.descriptorSetCount = kSlots;
    allocInfo.pSetLayouts = layouts;
    if (vkAllocateDescriptorSets(h->device, &allocInfo, h->sets) != VK_SUCCESS) {
        return false;
    }
    VkCommandPoolCreateInfo poolCreate{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolCreate.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolCreate.queueFamilyIndex = h->family;
    if (vkCreateCommandPool(h->device, &poolCreate, nullptr, &h->commandPool) != VK_SUCCESS) {
        return false;
    }
    VkCommandBufferAllocateInfo cmdInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cmdInfo.commandPool = h->commandPool;
    cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdInfo.commandBufferCount = kSlots;
    if (vkAllocateCommandBuffers(h->device, &cmdInfo, h->commands) != VK_SUCCESS) {
        return false;
    }
    for (uint32_t i = 0; i < kSlots; ++i) {
        VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        VkSemaphoreCreateInfo semInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        if (vkCreateFence(h->device, &fenceInfo, nullptr, &h->fences[i]) != VK_SUCCESS ||
            vkCreateSemaphore(h->device, &semInfo, nullptr, &h->acquired[i]) != VK_SUCCESS) {
            return false;
        }
    }
#ifdef VKHW_HEADLESS
    VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufferInfo.size = 4;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    vkCreateBuffer(h->device, &bufferInfo, nullptr, &h->readback);
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(h->device, h->readback, &req);
    VkPhysicalDeviceMemoryProperties mem;
    vkGetPhysicalDeviceMemoryProperties(h->gpu, &mem);
    VkMemoryAllocateInfo alloc{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    alloc.allocationSize = req.size;
    for (uint32_t i = 0; i < mem.memoryTypeCount; ++i) {
        VkMemoryPropertyFlags want = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        if ((req.memoryTypeBits & (1u << i)) != 0 && (mem.memoryTypes[i].propertyFlags & want) == want) {
            alloc.memoryTypeIndex = i;
            break;
        }
    }
    VkDeviceMemory memory = VK_NULL_HANDLE;
    vkAllocateMemory(h->device, &alloc, nullptr, &memory);
    vkBindBufferMemory(h->device, h->readback, memory, 0);
    vkMapMemory(h->device, memory, 0, 4, 0, &h->readbackData);
#endif
    return true;
}

}  // namespace

Host* create(const retro_hw_render_callback& hw) {
    if (!available()) {
        return nullptr;
    }
    auto* host = new Host();
    host->hw = hw;
    return host;
}

void setNegotiation(Host* host, const retro_hw_render_context_negotiation_interface* iface) {
    host->negotiation = reinterpret_cast<const retro_hw_render_context_negotiation_interface_vulkan*>(iface);
}

bool init(Host* h) {
    if (!makeDevice(h) || !makeResources(h)) {
        return false;
    }
    retro_hw_render_interface_vulkan& i = h->iface;
    i.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
    i.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
    i.handle = h;
    i.instance = gInstance;
    i.gpu = h->gpu;
    i.device = h->device;
    i.get_device_proc_addr = vkGetDeviceProcAddr;
    i.get_instance_proc_addr = vkGetInstanceProcAddr;
    i.queue = h->queue;
    i.queue_index = h->family;
    i.set_image = setImage;
    i.get_sync_index = getSyncIndex;
    i.get_sync_index_mask = getSyncIndexMask;
    i.set_command_buffers = setCommandBuffers;
    i.wait_sync_index = waitSyncIndex;
    i.lock_queue = lockQueue;
    i.unlock_queue = unlockQueue;
    i.set_signal_semaphore = setSignalSemaphore;
    return true;
}

const retro_hw_render_interface* renderInterface(Host* host) {
    return reinterpret_cast<const retro_hw_render_interface*>(&host->iface);
}

void setWindow(Host* h, ANativeWindow* window) {
    if (window == h->window || h->device == VK_NULL_HANDLE) {
        return;
    }
    destroySwapchain(h);
    if (h->surface != VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(gInstance, h->surface, nullptr);
        h->surface = VK_NULL_HANDLE;
    }
    if (h->window != nullptr) {
        ANativeWindow_release(h->window);
    }
    h->window = window;
    if (window == nullptr) {
        return;
    }
    ANativeWindow_acquire(window);
    h->surface = makeSurface(window);
    if (h->surface == VK_NULL_HANDLE) {
        VLOGE("could not make a surface for the window");
        return;
    }
    makeSwapchain(h);
    // Show the last frame straight away, e.g. while paused after a rotation.
    present(h);
}

void present(Host* h) {
    if (h->device == VK_NULL_HANDLE || h->surface == VK_NULL_HANDLE) {
        return;
    }
    if ((h->swapchain == VK_NULL_HANDLE || h->rebuild) && !makeSwapchain(h)) {
        return;
    }
    uint32_t slot;
    {
        std::lock_guard<std::mutex> lock(h->state);
        slot = h->syncIndex;
    }
    vkWaitForFences(h->device, 1, &h->fences[slot], VK_TRUE, UINT64_MAX);
    uint32_t chainIndex = 0;
    // Bounded: a window on its way out must not hold the emulation thread.
    VkResult acquired = vkAcquireNextImageKHR(h->device, h->swapchain, 200000000ull, h->acquired[slot], VK_NULL_HANDLE, &chainIndex);
    if (acquired == VK_ERROR_OUT_OF_DATE_KHR) {
        h->rebuild = true;
        return;
    }
    if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) {
        VLOGE("vkAcquireNextImageKHR: %d", acquired);
        return;
    }

    bool haveImage;
    retro_vulkan_image image;
    uint32_t imageIndex;
    std::vector<VkSemaphore> waits;
    std::vector<VkCommandBuffer> coreCommands;
    VkSemaphore coreSignal;
    {
        std::lock_guard<std::mutex> lock(h->state);
        haveImage = h->haveImage;
        image = h->image;
        imageIndex = h->imageIndex;
        waits.swap(h->imageWaits);
        coreCommands.swap(h->coreCommands);
        coreSignal = h->coreSignal;
        h->coreSignal = VK_NULL_HANDLE;
    }
    VkCommandBuffer cmd = h->commands[slot];
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(cmd, &begin);
    if (haveImage) {
        VkDescriptorImageInfo imageInfo{h->sampler, image.image_view, image.image_layout};
        VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        write.dstSet = h->sets[slot];
        write.dstBinding = 0;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &imageInfo;
        vkUpdateDescriptorSets(h->device, 1, &write, 0, nullptr);
        // The core's writes to the image (earlier on this queue) become visible to sampling.
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        barrier.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.oldLayout = image.image_layout;
        barrier.newLayout = image.image_layout;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = image.create_info.image;
        barrier.subresourceRange = image.create_info.subresourceRange;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0,
                             nullptr, 1, &barrier);
    }
    VkClearValue clear{};
    clear.color = {{0.0f, 0.0f, 0.0f, 1.0f}};
    VkRenderPassBeginInfo pass{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    pass.renderPass = h->renderPass;
    pass.framebuffer = h->framebuffers[chainIndex];
    pass.renderArea = {{0, 0}, h->extent};
    pass.clearValueCount = 1;
    pass.pClearValues = &clear;
    vkCmdBeginRenderPass(cmd, &pass, VK_SUBPASS_CONTENTS_INLINE);
    if (haveImage) {
        VkViewport viewport{0.0f, 0.0f, static_cast<float>(h->extent.width), static_cast<float>(h->extent.height), 0.0f, 1.0f};
        VkRect2D scissor{{0, 0}, h->extent};
        vkCmdSetViewport(cmd, 0, 1, &viewport);
        vkCmdSetScissor(cmd, 0, 1, &scissor);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, h->pipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, h->layout, 0, 1, &h->sets[slot], 0, nullptr);
        vkCmdDraw(cmd, 3, 1, 0, 0);
    }
    vkCmdEndRenderPass(cmd);
#ifdef VKHW_HEADLESS
    VkBufferImageCopy copy{};
    copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.imageOffset = {static_cast<int32_t>(h->extent.width / 2), static_cast<int32_t>(h->extent.height / 2), 0};
    copy.imageExtent = {1, 1, 1};
    vkCmdCopyImageToBuffer(cmd, h->chainImages[chainIndex], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, h->readback, 1, &copy);
    VkImageMemoryBarrier toPresent{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    toPresent.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    toPresent.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    toPresent.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    toPresent.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toPresent.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toPresent.image = h->chainImages[chainIndex];
    toPresent.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr,
                         1, &toPresent);
#endif
    vkEndCommandBuffer(cmd);

    std::vector<VkSemaphore> waitSemaphores = {h->acquired[slot]};
    std::vector<VkPipelineStageFlags> waitStages = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
    for (VkSemaphore s : waits) {
        waitSemaphores.push_back(s);
        waitStages.push_back(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    }
    std::vector<VkCommandBuffer> buffers = coreCommands;
    buffers.push_back(cmd);
    std::vector<VkSemaphore> signals = {h->rendered[chainIndex]};
    if (coreSignal != VK_NULL_HANDLE) {
        signals.push_back(coreSignal);
    }
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = static_cast<uint32_t>(waitSemaphores.size());
    submit.pWaitSemaphores = waitSemaphores.data();
    submit.pWaitDstStageMask = waitStages.data();
    submit.commandBufferCount = static_cast<uint32_t>(buffers.size());
    submit.pCommandBuffers = buffers.data();
    submit.signalSemaphoreCount = static_cast<uint32_t>(signals.size());
    submit.pSignalSemaphores = signals.data();
    VkResult presented;
    {
        std::unique_lock<std::mutex> fenceLock(h->fenceMutex);
        h->fenceIdle.wait(fenceLock, [h] { return h->fenceWaiters == 0; });
        vkResetFences(h->device, 1, &h->fences[slot]);
        if (haveImage) {
            // This frame reads the image: the core waits for it before drawing into it again.
            std::lock_guard<std::mutex> stateLock(h->state);
            h->readSlots[imageIndex] |= 1u << slot;
        }
        std::lock_guard<std::mutex> lock(h->queueMutex);
        VkResult submitted = vkQueueSubmit(h->queue, 1, &submit, h->fences[slot]);
        if (submitted != VK_SUCCESS) {
            VLOGE("vkQueueSubmit: %d", submitted);
            // Signal the fence anyway, or the next wait on this slot never returns.
            vkQueueSubmit(h->queue, 0, nullptr, h->fences[slot]);
            presented = VK_ERROR_OUT_OF_DATE_KHR;
        } else {
            VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
            present.waitSemaphoreCount = 1;
            present.pWaitSemaphores = &h->rendered[chainIndex];
            present.swapchainCount = 1;
            present.pSwapchains = &h->swapchain;
            present.pImageIndices = &chainIndex;
            presented = vkQueuePresentKHR(h->queue, &present);
        }
    }
    if (presented == VK_ERROR_OUT_OF_DATE_KHR || presented == VK_SUBOPTIMAL_KHR || acquired == VK_SUBOPTIMAL_KHR) {
        h->rebuild = true;
    }
#ifdef VKHW_HEADLESS
    vkWaitForFences(h->device, 1, &h->fences[slot], VK_TRUE, UINT64_MAX);
    uint32_t pixel;
    memcpy(&pixel, h->readbackData, 4);
    if (h->format == VK_FORMAT_B8G8R8A8_UNORM) {
        pixel = (pixel & 0xFF00FF00u) | ((pixel & 0xFFu) << 16) | ((pixel >> 16) & 0xFFu);
    }
    gTestPixel.store(pixel);
#endif
    std::lock_guard<std::mutex> lock(h->state);
    h->syncIndex = (h->syncIndex + 1) % kSlots;
}

void shutdown(Host* h) {
    if (h->device == VK_NULL_HANDLE) {
        return;
    }
    destroySwapchain(h);
    if (h->surface != VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(gInstance, h->surface, nullptr);
        h->surface = VK_NULL_HANDLE;
    }
    if (h->window != nullptr) {
        ANativeWindow_release(h->window);
        h->window = nullptr;
    }
}

}  // namespace vkhw
