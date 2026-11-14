#include "gclan.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace gclan {
namespace {
constexpr int kPort = 27403;
constexpr uint8_t kHello = 1;
constexpr uint8_t kWelcome = 2;
constexpr uint8_t kFrame = 3;
constexpr int kMaxGuests = 7;
constexpr size_t kMaxFrame = 1600;
// A guest that has not been heard from for this long has left.
constexpr auto kGuestTimeout = std::chrono::seconds(10);

using Clock = std::chrono::steady_clock;

struct Guest {
    sockaddr_in address;
    Clock::time_point seen;
};

std::atomic<int> gMode{0};
std::string gHost;
std::string gDestination;

std::mutex gMutex;
int gListen = -1;     // loopback TCP for Dolphin
int gTap = -1;        // Dolphin's connection
int gUdp = -1;
std::thread gThread;
std::atomic<bool> gRunning{false};
sockaddr_in gHostAddress{};
std::atomic<bool> gWelcomed{false};
std::vector<Guest> gGuests;  // host only
std::vector<uint8_t> gTapBuffer;

bool sameAddress(const sockaddr_in& a, const sockaddr_in& b) {
    return a.sin_addr.s_addr == b.sin_addr.s_addr && a.sin_port == b.sin_port;
}

void sendUdp(const sockaddr_in& to, uint8_t type, const uint8_t* data, size_t len) {
    if (gUdp < 0) return;
    std::vector<uint8_t> packet(1 + len);
    packet[0] = type;
    if (len > 0) memcpy(packet.data() + 1, data, len);
    sendto(gUdp, packet.data(), packet.size(), 0, reinterpret_cast<const sockaddr*>(&to), sizeof to);
}

// A frame for this phone's Dolphin, after its length.
void toTap(const uint8_t* frame, size_t len) {
    if (gTap < 0 || len == 0 || len > kMaxFrame) return;
    uint8_t packet[2 + kMaxFrame];
    packet[0] = static_cast<uint8_t>(len & 0xFF);
    packet[1] = static_cast<uint8_t>(len >> 8);
    memcpy(packet + 2, frame, len);
    size_t sent = 0;
    while (sent < len + 2) {
        ssize_t n = send(gTap, packet + sent, len + 2 - sent, MSG_NOSIGNAL);
        if (n <= 0) return;
        sent += static_cast<size_t>(n);
    }
}

// A frame from this phone's Dolphin: the host hands it to every guest, a guest to the host.
void fromTap(const uint8_t* frame, size_t len) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gMode.load() == 1) {
        for (const Guest& guest : gGuests) sendUdp(guest.address, kFrame, frame, len);
    } else {
        sendUdp(gHostAddress, kFrame, frame, len);
    }
}

void readTap() {
    uint8_t buffer[4096];
    ssize_t n = recv(gTap, buffer, sizeof buffer, 0);
    if (n <= 0) {
        // Dolphin let go (the game stopped); wait for the next connection.
        close(gTap);
        gTap = -1;
        gTapBuffer.clear();
        return;
    }
    gTapBuffer.insert(gTapBuffer.end(), buffer, buffer + n);
    size_t offset = 0;
    while (gTapBuffer.size() - offset >= 2) {
        size_t len = gTapBuffer[offset] | (gTapBuffer[offset + 1] << 8);
        if (gTapBuffer.size() - offset - 2 < len) break;
        if (len > 0 && len <= kMaxFrame) fromTap(gTapBuffer.data() + offset + 2, len);
        offset += 2 + len;
    }
    gTapBuffer.erase(gTapBuffer.begin(), gTapBuffer.begin() + static_cast<long>(offset));
}

void readUdp() {
    uint8_t buffer[2048];
    sockaddr_in from{};
    socklen_t fromLen = sizeof from;
    ssize_t n = recvfrom(gUdp, buffer, sizeof buffer, 0, reinterpret_cast<sockaddr*>(&from), &fromLen);
    if (n < 1) return;
    uint8_t type = buffer[0];
    const uint8_t* payload = buffer + 1;
    size_t len = static_cast<size_t>(n - 1);
    if (gMode.load() == 1) {
        std::lock_guard<std::mutex> lock(gMutex);
        Guest* sender = nullptr;
        for (Guest& guest : gGuests) {
            if (sameAddress(guest.address, from)) sender = &guest;
        }
        if (type == kHello) {
            if (sender == nullptr && static_cast<int>(gGuests.size()) < kMaxGuests) {
                gGuests.push_back({from, Clock::now()});
                sender = &gGuests.back();
            }
            if (sender != nullptr) {
                sender->seen = Clock::now();
                sendUdp(from, kWelcome, nullptr, 0);
            }
            return;
        }
        if (type != kFrame || sender == nullptr || len == 0 || len > kMaxFrame) return;
        sender->seen = Clock::now();
        // The hub: to this phone's Dolphin and on to every other guest.
        toTap(payload, len);
        for (const Guest& guest : gGuests) {
            if (!sameAddress(guest.address, from)) sendUdp(guest.address, kFrame, payload, len);
        }
        return;
    }
    if (!sameAddress(from, gHostAddress)) return;
    if (type == kWelcome) {
        gWelcomed.store(true);
        return;
    }
    if (type == kFrame && len > 0 && len <= kMaxFrame) {
        std::lock_guard<std::mutex> lock(gMutex);
        toTap(payload, len);
    }
}

void run() {
    Clock::time_point lastHello{};
    while (gRunning.load()) {
        pollfd fds[3];
        int count = 0;
        fds[count++] = {gListen, POLLIN, 0};
        fds[count++] = {gUdp, POLLIN, 0};
        if (gTap >= 0) fds[count++] = {gTap, POLLIN, 0};
        int ready = poll(fds, count, 200);
        if (!gRunning.load()) break;
        if (ready > 0) {
            if (fds[0].revents & POLLIN) {
                int tap = accept(gListen, nullptr, nullptr);
                if (tap >= 0) {
                    int one = 1;
                    setsockopt(tap, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
                    std::lock_guard<std::mutex> lock(gMutex);
                    if (gTap >= 0) close(gTap);
                    gTap = tap;
                    gTapBuffer.clear();
                }
            }
            if (fds[1].revents & POLLIN) readUdp();
            if (count == 3 && (fds[2].revents & (POLLIN | POLLHUP | POLLERR))) readTap();
        }
        Clock::time_point now = Clock::now();
        if (gMode.load() == 2 && now - lastHello > std::chrono::seconds(2)) {
            // Joining, and staying known to the host.
            lastHello = now;
            sendUdp(gHostAddress, kHello, nullptr, 0);
        }
        if (gMode.load() == 1) {
            std::lock_guard<std::mutex> lock(gMutex);
            for (size_t k = gGuests.size(); k-- > 0;) {
                if (now - gGuests[k].seen > kGuestTimeout) gGuests.erase(gGuests.begin() + static_cast<long>(k));
            }
        }
    }
}

void closeAll() {
    if (gTap >= 0) close(gTap);
    if (gListen >= 0) close(gListen);
    if (gUdp >= 0) close(gUdp);
    gTap = gListen = gUdp = -1;
    gGuests.clear();
    gTapBuffer.clear();
    gWelcomed.store(false);
}
}  // namespace

void configure(int mode, const char* host) {
    gMode.store(mode == 1 || mode == 2 ? mode : 0);
    gHost = host != nullptr ? host : "";
}

int mode() {
    return gMode.load();
}

const char* begin() {
    end();
    gDestination.clear();
    int mode = gMode.load();
    if (mode == 0) return "";
    if (mode == 2) {
        gHostAddress = {};
        gHostAddress.sin_family = AF_INET;
        gHostAddress.sin_port = htons(kPort);
        if (inet_pton(AF_INET, gHost.c_str(), &gHostAddress.sin_addr) != 1) return "";
    }
    gListen = socket(AF_INET, SOCK_STREAM, 0);
    gUdp = socket(AF_INET, SOCK_DGRAM, 0);
    if (gListen < 0 || gUdp < 0) {
        closeAll();
        return "";
    }
    int one = 1;
    setsockopt(gListen, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    sockaddr_in loop{};
    loop.sin_family = AF_INET;
    loop.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    loop.sin_port = 0;
    socklen_t loopLen = sizeof loop;
    if (bind(gListen, reinterpret_cast<sockaddr*>(&loop), sizeof loop) != 0 || listen(gListen, 2) != 0 ||
        getsockname(gListen, reinterpret_cast<sockaddr*>(&loop), &loopLen) != 0) {
        closeAll();
        return "";
    }
    setsockopt(gUdp, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    sockaddr_in local{};
    local.sin_family = AF_INET;
    local.sin_addr.s_addr = htonl(INADDR_ANY);
    local.sin_port = mode == 1 ? htons(kPort) : 0;
    if (bind(gUdp, reinterpret_cast<sockaddr*>(&local), sizeof local) != 0) {
        closeAll();
        return "";
    }
    char text[32];
    snprintf(text, sizeof text, "127.0.0.1:%u", static_cast<unsigned>(ntohs(loop.sin_port)));
    gDestination = text;
    gRunning.store(true);
    gThread = std::thread(run);
    return gDestination.c_str();
}

void end() {
    if (gRunning.exchange(false) && gThread.joinable()) {
        gThread.join();
    }
    std::lock_guard<std::mutex> lock(gMutex);
    closeAll();
}

int peers() {
    if (gMode.load() == 1) {
        std::lock_guard<std::mutex> lock(gMutex);
        return static_cast<int>(gGuests.size());
    }
    return gMode.load() == 2 && gWelcomed.load() ? 1 : 0;
}
}  // namespace gclan
