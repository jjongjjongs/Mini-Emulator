// MIDI music for cores that send MIDI bytes out (libretro's MIDI interface): played on a
// SoundFont with TinySoundFont and mixed into the core's sound. PC-98 (NP2kai) since 0.0.318.
#pragma once
#include <cstddef>
#include <cstdint>

namespace midisynth {
// Loads the SoundFont (an empty path turns MIDI off). Before the game starts.
bool load(const char* path);
void close();
bool active();
// The sample rate the core's sound runs at.
void setRate(int sampleRate);
// One MIDI byte from the core (running status and system exclusive handled here).
bool write(uint8_t byte);
// Adds the synthesizer's sound to frames stereo frames of the core's sound.
void mix(int16_t* stereo, size_t frames);
}  // namespace midisynth
