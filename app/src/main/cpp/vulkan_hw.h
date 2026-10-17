// Vulkan hardware rendering for libretro cores (PPSSPP): the device the core draws with,
// the libretro Vulkan interface it talks to, and a swapchain on the game's window that
// shows the images the core hands over.
#pragma once

#include "libretro.h"

struct ANativeWindow;

namespace vkhw {

// Whether this device can run a Vulkan core: the loader, a GPU with a graphics queue and
// swapchain support. Checked once, on first use.
bool available();

class Host;

// Takes the core's SET_HW_RENDER (context_type RETRO_HW_CONTEXT_VULKAN); null if Vulkan is
// not available.
Host* create(const retro_hw_render_callback& hw);
// The core's SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE, if it sends one.
void setNegotiation(Host* host, const retro_hw_render_context_negotiation_interface* iface);
// Makes the device (through the core's create_device when it negotiates). On the
// emulation thread, before context_reset.
bool init(Host* host);
// The interface GET_HW_RENDER_INTERFACE returns; valid after init.
const retro_hw_render_interface* renderInterface(Host* host);
// Follows the window Java hands over (null while there is none). Emulation thread.
void setWindow(Host* host, ANativeWindow* window);
// Shows the image last passed to set_image, stretched over the window. Emulation thread.
void present(Host* host);
// Waits for the GPU and frees the swapchain and surfaces. The device stays: the core
// created it and the game process ends right after.
void shutdown(Host* host);

}  // namespace vkhw
