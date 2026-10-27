#include "dsnet.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace dsnet {
namespace {
constexpr int kPort = 27402;
constexpr uint8_t kHello = 1;
constexpr uint8_t kWelcome = 2;
constexpr uint8_t kData = 3;
constexpr int kMaxClients = 16;

// Wire format: [type][from][dest lo][dest hi][payload]. dest 0xFFFF is a broadcast.
struct Header {
    uint8_t type;
    uint8_t from;
    uint8_t destLo;
    uint8_t destHi;
};

struct Pending {
    uint16_t from;
    std::vector<uint8_t> data;
};

std::atomic<int> gMode{0};
std::string gHost;
retro_netpacket_callback gCallback{};
bool gHaveCallback = false;

std::mutex gMutex;
int gSocket = -1;
std::thread gThread;
std::atomic<bool> gRunning{false};
std::atomic<bool> gStarted{false};
std::atomic<bool> gStartPending{false};
uint16_t gClientId = 0;
sockaddr_in gHostAddress{};
std::vector<sockaddr_in> gClients;  // host: index + 1 is the client id
std::deque<Pending> gInbox;
std::deque<uint16_t> gArrived;

bool sameAddress(const sockaddr_in& a, const sockaddr_in& b) {
    return a.sin_addr.s_addr == b.sin_addr.s_addr && a.sin_port == b.sin_port;
}

void sendRaw(const sockaddr_in& to, uint8_t type, uint16_t from, uint16_t dest, const void* buf, size_t len) {
    if (gSocket < 0) return;
    std::vector<uint8_t> packet(sizeof(Header) + len);
    Header header{type, static_cast<uint8_t>(from), static_cast<uint8_t>(dest & 0xFF), static_cast<uint8_t>(dest >> 8)};
    memcpy(packet.data(), &header, sizeof header);
    if (len > 0) memcpy(packet.data() + sizeof header, buf, len);
    sendto(gSocket, packet.data(), packet.size(), 0, reinterpret_cast<const sockaddr*>(&to), sizeof to);
}

// The core's send: host fans out, guests go through the host.
void RETRO_CALLCONV sendFn(int /*flags*/, const void* buf, size_t len, uint16_t dest) {
    if (len == 0 || buf == nullptr) return;  // flush hints
    std::lock_guard<std::mutex> lock(gMutex);
    if (gMode.load() == 1) {
        for (size_t k = 0; k < gClients.size(); k++) {
            uint16_t id = static_cast<uint16_t>(k + 1);
            if (dest == RETRO_NETPACKET_BROADCAST || dest == id) {
                sendRaw(gClients[k], kData, 0, dest, buf, len);
            }
        }
    } else {
        sendRaw(gHostAddress, kData, gClientId, dest, buf, len);
    }
}

// The core's poll: hand over what the network thread queued.
void RETRO_CALLCONV pollFn() {
    std::deque<Pending> ready;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        ready.swap(gInbox);
    }
    for (const Pending& packet : ready) {
        if (gCallback.receive) gCallback.receive(packet.data.data(), packet.data.size(), packet.from);
    }
}

void handle(const uint8_t* data, size_t len, const sockaddr_in& from) {
    if (len < sizeof(Header)) return;
    Header header{};
    memcpy(&header, data, sizeof header);
    uint16_t dest = static_cast<uint16_t>(header.destLo | (header.destHi << 8));
    const uint8_t* payload = data + sizeof header;
    size_t payloadLen = len - sizeof header;
    std::lock_guard<std::mutex> lock(gMutex);
    if (gMode.load() == 1) {
        if (header.type == kHello) {
            // A guest asking for a seat; the same guest asking again gets the same seat.
            int id = 0;
            for (size_t k = 0; k < gClients.size(); k++) {
                if (sameAddress(gClients[k], from)) id = static_cast<int>(k + 1);
            }
            if (id == 0 && gClients.size() < kMaxClients - 1) {
                gClients.push_back(from);
                id = static_cast<int>(gClients.size());
                gArrived.push_back(static_cast<uint16_t>(id));
            }
            if (id != 0) sendRaw(from, kWelcome, 0, static_cast<uint16_t>(id), nullptr, 0);
            return;
        }
        if (header.type != kData) return;
        uint16_t sender = header.from;
        if (sender == 0 || sender > gClients.size() || !sameAddress(gClients[sender - 1], from)) return;
        // To the host's own core, and on to the other guests.
        if (dest == RETRO_NETPACKET_BROADCAST || dest == 0) {
            gInbox.push_back({sender, std::vector<uint8_t>(payload, payload + payloadLen)});
        }
        for (size_t k = 0; k < gClients.size(); k++) {
            uint16_t id = static_cast<uint16_t>(k + 1);
            if (id == sender) continue;
            if (dest == RETRO_NETPACKET_BROADCAST || dest == id) {
                sendRaw(gClients[k], kData, sender, dest, payload, payloadLen);
            }
        }
    } else {
        if (!sameAddress(from, gHostAddress)) return;
        if (header.type == kWelcome) {
            if (!gStarted.load() && !gStartPending.load()) {
                gClientId = dest;
                gStartPending.store(true);
            }
            return;
        }
        if (header.type != kData) return;
        if (dest == RETRO_NETPACKET_BROADCAST || dest == gClientId) {
            gInbox.push_back({header.from, std::vector<uint8_t>(payload, payload + payloadLen)});
        }
    }
}

void networkThread() {
    std::vector<uint8_t> buffer(4096);
    auto lastHello = std::chrono::steady_clock::now() - std::chrono::seconds(1);
    while (gRunning.load()) {
        if (gMode.load() == 2 && !gStarted.load() && !gStartPending.load()) {
            auto now = std::chrono::steady_clock::now();
            if (now - lastHello >= std::chrono::milliseconds(500)) {
                std::lock_guard<std::mutex> lock(gMutex);
                sendRaw(gHostAddress, kHello, 0, 0, nullptr, 0);
                lastHello = now;
            }
        }
        pollfd waiting{gSocket, POLLIN, 0};
        if (poll(&waiting, 1, 100) <= 0 || !(waiting.revents & POLLIN)) continue;
        sockaddr_in from{};
        socklen_t fromLen = sizeof from;
        ssize_t got = recvfrom(gSocket, buffer.data(), buffer.size(), 0, reinterpret_cast<sockaddr*>(&from), &fromLen);
        if (got > 0) handle(buffer.data(), static_cast<size_t>(got), from);
    }
}
}  // namespace

void configure(int mode, const char* host) {
    gMode.store(mode >= 0 && mode <= 2 ? mode : 0);
    gHost = host ? host : "";
}

int mode() {
    return gMode.load();
}

void setCallback(const retro_netpacket_callback* callback) {
    if (callback == nullptr) {
        gHaveCallback = false;
        return;
    }
    gCallback = *callback;
    gHaveCallback = true;
}

void begin() {
    if (gMode.load() == 0 || !gHaveCallback || gRunning.load()) return;
    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) return;
    int yes = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof yes);
    sockaddr_in local{};
    local.sin_family = AF_INET;
    local.sin_addr.s_addr = htonl(INADDR_ANY);
    local.sin_port = htons(gMode.load() == 1 ? kPort : 0);
    if (bind(sock, reinterpret_cast<sockaddr*>(&local), sizeof local) != 0) {
        close(sock);
        return;
    }
    {
        std::lock_guard<std::mutex> lock(gMutex);
        gSocket = sock;
        gClients.clear();
        gInbox.clear();
        gArrived.clear();
        gClientId = 0;
        gHostAddress = sockaddr_in{};
        gHostAddress.sin_family = AF_INET;
        gHostAddress.sin_port = htons(kPort);
        inet_pton(AF_INET, gHost.c_str(), &gHostAddress.sin_addr);
    }
    gStarted.store(false);
    // The host's core starts at once; a guest's once the host has seated it.
    gStartPending.store(gMode.load() == 1);
    gRunning.store(true);
    gThread = std::thread(networkThread);
}

void tick() {
    if (!gRunning.load()) return;
    if (gStartPending.exchange(false) && !gStarted.load()) {
        gStarted.store(true);
        if (gCallback.start) gCallback.start(gClientId, sendFn, pollFn);
    }
    if (!gStarted.load()) return;
    std::deque<uint16_t> arrived;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        arrived.swap(gArrived);
    }
    for (uint16_t id : arrived) {
        if (gCallback.connected) gCallback.connected(id);
    }
    if (gCallback.poll) gCallback.poll();
}

void end() {
    if (gStarted.exchange(false) && gCallback.stop) gCallback.stop();
    gStartPending.store(false);
    if (gRunning.exchange(false)) {
        if (gThread.joinable()) gThread.join();
    }
    std::lock_guard<std::mutex> lock(gMutex);
    if (gSocket >= 0) {
        close(gSocket);
        gSocket = -1;
    }
    gClients.clear();
    gInbox.clear();
    gArrived.clear();
}
}  // namespace dsnet
