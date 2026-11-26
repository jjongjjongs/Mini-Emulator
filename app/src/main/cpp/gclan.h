#pragma once
// "LAN 대전" (게임큐브): the GameCube broadband adapter between phones. Dolphin's adapter in
// TAP server mode sends and takes whole Ethernet frames over a local TCP connection (each
// frame after its length, two bytes little endian); this listens for that connection on the
// loopback and relays the frames over UDP. One phone hosts and acts as the hub: it hands
// every frame to its own Dolphin and to every other guest; guests send theirs to the host.

namespace gclan {
// mode: 0 off, 1 host, 2 guest (host is the address to join). Set before the game starts.
void configure(int mode, const char* host);
int mode();
// Opens the sockets and the relay thread; returns "127.0.0.1:<port>" for Dolphin, or ""
// when off or the sockets could not be made.
const char* begin();
// Closes everything.
void end();
// Phones in the room besides this one (the host counts its guests; a guest counts 1 once
// the host answered).
int peers();
}
