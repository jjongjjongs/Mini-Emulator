#pragma once
// "DS 통신": the DS's local wireless between phones. melonDS DS hands the bridge its wireless
// frames through libretro's netpacket interface; this relays them over UDP on the Wi-Fi. One
// phone hosts (client 0) and forwards every frame between the guests; guests send theirs to
// the host. Everything the core sees happens on the emulation thread.
#include "libretro.h"

namespace dsnet {
// mode: 0 off, 1 host, 2 guest (host is the address to join). Set before the game starts.
void configure(int mode, const char* host);
int mode();
// The core's callbacks, from RETRO_ENVIRONMENT_SET_NETPACKET_INTERFACE.
void setCallback(const retro_netpacket_callback* callback);
// Opens the sockets once the core runs; start() is passed to the core from tick().
void begin();
// On the emulation thread each frame: starts the core's networking when the link is ready
// and tells it about guests that arrived.
void tick();
// Tells the core to stop and closes the sockets.
void end();
}
