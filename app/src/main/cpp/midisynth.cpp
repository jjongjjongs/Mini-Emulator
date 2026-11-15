// MIDI music on a SoundFont (see midisynth.h).
#define TSF_IMPLEMENTATION
#include "tsf.h"
#include "midisynth.h"

#include <mutex>

namespace midisynth {
namespace {
std::mutex gMutex;
tsf* gSynth = nullptr;
int gRate = 0;
uint8_t gStatus = 0;     // running status
uint8_t gData[2];
int gHave = 0;
bool gSysex = false;

int dataBytes(uint8_t status) {
    switch (status & 0xF0) {
        case 0xC0:
        case 0xD0:
            return 1;
        default:
            return 2;
    }
}

void resetChannels() {
    for (int ch = 0; ch < 16; ++ch) {
        tsf_channel_set_presetnumber(gSynth, ch, 0, ch == 9);
        tsf_channel_set_pitchrange(gSynth, ch, 2.0f);
    }
}

void message(uint8_t status, uint8_t a, uint8_t b) {
    int ch = status & 0x0F;
    switch (status & 0xF0) {
        case 0x80:
            tsf_channel_note_off(gSynth, ch, a);
            break;
        case 0x90:
            if (b == 0) {
                tsf_channel_note_off(gSynth, ch, a);
            } else {
                tsf_channel_note_on(gSynth, ch, a, b / 127.0f);
            }
            break;
        case 0xB0:
            tsf_channel_midi_control(gSynth, ch, a, b);
            break;
        case 0xC0:
            tsf_channel_set_presetnumber(gSynth, ch, a, ch == 9);
            break;
        case 0xE0:
            tsf_channel_set_pitchwheel(gSynth, ch, a | (b << 7));
            break;
        default:
            break;   // key / channel pressure
    }
}
}  // namespace

bool load(const char* path) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gSynth != nullptr) {
        tsf_close(gSynth);
        gSynth = nullptr;
    }
    gStatus = 0;
    gHave = 0;
    gSysex = false;
    if (path == nullptr || path[0] == '\0') return false;
    gSynth = tsf_load_filename(path);
    if (gSynth == nullptr) return false;
    tsf_set_max_voices(gSynth, 96);
    tsf_set_output(gSynth, TSF_STEREO_INTERLEAVED, gRate > 0 ? gRate : 44100, -3.0f);
    resetChannels();
    return true;
}

void close() {
    load(nullptr);
}

bool active() {
    std::lock_guard<std::mutex> lock(gMutex);
    return gSynth != nullptr;
}

void setRate(int sampleRate) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (sampleRate <= 0 || sampleRate == gRate) return;
    gRate = sampleRate;
    if (gSynth != nullptr) tsf_set_output(gSynth, TSF_STEREO_INTERLEAVED, gRate, -3.0f);
}

bool write(uint8_t byte) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gSynth == nullptr) return true;
    if (byte >= 0xF8) return true;               // real-time messages
    if (byte == 0xF0) {
        gSysex = true;
        return true;
    }
    if (byte == 0xF7) {
        gSysex = false;                          // GM / GS / XG resets: channels back to their start
        return true;
    }
    if (byte & 0x80) {
        gSysex = false;
        if (byte >= 0xF0) {
            gStatus = 0;                         // other system common messages
            return true;
        }
        gStatus = byte;
        gHave = 0;
        return true;
    }
    if (gSysex || gStatus == 0) return true;
    gData[gHave++] = byte;
    if (gHave >= dataBytes(gStatus)) {
        message(gStatus, gData[0], gHave > 1 ? gData[1] : 0);
        gHave = 0;
    }
    return true;
}

void mix(int16_t* stereo, size_t frames) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gSynth == nullptr || frames == 0) return;
    tsf_render_short(gSynth, stereo, static_cast<int>(frames), 1);
}
}  // namespace midisynth
