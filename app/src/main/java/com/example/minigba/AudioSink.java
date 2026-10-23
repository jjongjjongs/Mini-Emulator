package com.example.minigba;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

// Plays the core's stereo 16-bit samples, one write per emulated frame. The emulation and
// the audio hardware run on different clocks, so the queue drifts: drained, the gaps
// crackle; full, writes are cut short and crackle too. The emulation follows the speaker's
// clock instead: each write answers how much faster or slower (at most PACE_PPM) the game
// should run to steer the (smoothed) queue back to TARGET_MS, so the music keeps its own
// speed and pitch. Only when the game cannot keep up (a heavy scene) is a frame's audio
// stretched, by at most MAX_STRETCH, which is hard to hear.
// A frame that takes much longer than usual (PSP games such as Monster Hunter now and then)
// empties the queue, and the gap is heard as a crack; each one raises the amount kept
// queued, up to MAX_TARGET_MS, so the next hitch is covered. It eases back down after a
// quiet while, as it adds that much delay to the sound.
final class AudioSink {
    // Room in the track and the amount kept queued, in milliseconds.
    private static final int BUFFER_MS = 400;
    private static final int TARGET_MS = 80;
    private static final int MAX_TARGET_MS = 200;
    private static final int TARGET_STEP_MS = 30;
    private static final long CALM_NS = 60_000_000_000L;
    // A longer pause than this between writes is the game standing still (the menu, a
    // quick save), not a hitch.
    private static final long PAUSE_NS = 500_000_000L;
    // Longest a frame's audio is stretched; more than this drags the music audibly, so a
    // game that slow gets short gaps instead.
    private static final double MAX_STRETCH = 1.05;
    // Largest speed nudge for following the speaker's clock, in parts per million (0.5%).
    private static final double PACE_PPM = 5000;

    private AudioTrack track;
    private int sampleRate;
    private long framesWritten;
    private short[] stretched = new short[0];
    // Queued audio in frames, smoothed so one late frame does not swing the pitch.
    private double smoothedQueued = -1;
    // After a stall (rotation, the menu, a quick save) the queue has run (nearly) dry;
    // stretching the music to refill it would drag it for a moment, so silence refills it
    // instead. Stretching stays for the slow drift of a game a little behind real time.
    private static final long RESTART_GAP_NS = 50_000_000L;
    private long lastWriteNs;
    private short[] silence = new short[0];
    private int targetMs = TARGET_MS;
    private long lastGapNs;
    // The least kept queued; raised for engines whose speed wobbles (PS2), where the
    // default's first few hitches would each be heard before the target grew.
    private int floorMs = TARGET_MS;

    AudioSink() {
    }

    // Sets the least kept queued, in milliseconds (TARGET_MS..MAX_TARGET_MS); applies from
    // the next configure().
    synchronized void setFloorMs(int ms) {
        this.floorMs = Math.max(TARGET_MS, Math.min(MAX_TARGET_MS, ms));
    }

    synchronized void configure(int i) {
        release();
        this.sampleRate = i;
        this.framesWritten = 0;
        this.smoothedQueued = -1;
        this.lastWriteNs = 0;
        this.targetMs = this.floorMs;
        this.lastGapNs = 0;
        // AudioTrack wants whole stereo 16-bit frames (4 bytes); a rate such as the DS's
        // 32768 Hz gives a byte count that is not.
        int bytes = Math.max(AudioTrack.getMinBufferSize(i, 12, 2), i * 4 * BUFFER_MS / 1000);
        bytes = (bytes + 3) & ~3;
        AudioTrack build = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(14).setContentType(2).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(i).setChannelMask(12).setEncoding(2).build()).setTransferMode(1).setBufferSizeInBytes(bytes).build();
        this.track = build;
        build.play();
    }

    // Returns the speed nudge for the emulation, in parts per million.
    synchronized int write(short[] sArr, int i) {
        AudioTrack audioTrack = this.track;
        int frames = Math.min(sArr.length, i) / 2;
        if (audioTrack == null || frames <= 0) {
            return 0;
        }
        long played = audioTrack.getPlaybackHeadPosition() & 0xffffffffL;
        long queued = Math.max(0, this.framesWritten - played);
        long now = System.nanoTime();
        long sinceWrite = this.lastWriteNs == 0 ? 0 : now - this.lastWriteNs;
        double target = (double) this.sampleRate * this.targetMs / 1000;
        boolean restarted = queued < target / 4 || sinceWrite > RESTART_GAP_NS;
        if (restarted && this.lastWriteNs != 0 && sinceWrite < PAUSE_NS) {
            // The queue ran dry while the game was running: keep more queued from now on.
            this.targetMs = Math.min(MAX_TARGET_MS, this.targetMs + TARGET_STEP_MS);
            this.lastGapNs = now;
            target = (double) this.sampleRate * this.targetMs / 1000;
        } else if (this.targetMs > this.floorMs && now - this.lastGapNs > CALM_NS) {
            this.targetMs = Math.max(this.floorMs, this.targetMs - 10);
            this.lastGapNs = now;
            target = (double) this.sampleRate * this.targetMs / 1000;
        }
        double room = (double) this.sampleRate * BUFFER_MS / 1000 - target;
        this.lastWriteNs = now;
        if (restarted && queued < target) {
            int fill = (int) Math.round(target - queued);
            if (this.silence.length < fill * 2) {
                this.silence = new short[fill * 2];
            }
            int filled = audioTrack.write(this.silence, 0, fill * 2, AudioTrack.WRITE_NON_BLOCKING);
            if (filled > 0) {
                this.framesWritten += filled / 2;
                queued += filled / 2;
            }
            this.smoothedQueued = queued;
        }
        // The playback position moves in bursts, so the queue is smoothed; a nearly empty
        // queue is taken at once, as a gap is about to be heard.
        this.smoothedQueued = this.smoothedQueued < 0 || queued < target / 4
                ? queued : this.smoothedQueued * 0.8 + queued * 0.2;
        // Queue below target: run a little faster; above: a little slower.
        double error = Math.max(-1.0, Math.min(1.0, (target - this.smoothedQueued) / target));
        int pace = (int) Math.round(PACE_PPM * error);
        // Stretching is only for a queue the nudge could not hold: below half the target
        // the game is falling behind, above target + most of the room it races ahead.
        double low = target / 2;
        double high = target + room * 0.75;
        double ratio = 1.0;
        if (this.smoothedQueued < low) {
            ratio = Math.min(MAX_STRETCH, 1.0 + (MAX_STRETCH - 1.0) * (low - this.smoothedQueued) / low);
        } else if (this.smoothedQueued > high) {
            ratio = 1.0 - 0.05 * Math.min(1.0, (this.smoothedQueued - high) / (target + room - high));
        }
        int outFrames = Math.max(1, (int) Math.round(frames * ratio));
        short[] out = outFrames == frames ? sArr : stretch(sArr, frames, outFrames);
        int written = audioTrack.write(out, 0, outFrames * 2, AudioTrack.WRITE_NON_BLOCKING);
        if (written > 0) {
            this.framesWritten += written / 2;
        }
        return pace;
    }

    // Linear interpolation of stereo frames from `frames` to `outFrames` (longer or shorter).
    private short[] stretch(short[] in, int frames, int outFrames) {
        if (this.stretched.length < outFrames * 2) {
            this.stretched = new short[outFrames * 2];
        }
        short[] out = this.stretched;
        double step = outFrames > 1 ? (frames - 1) / (double) (outFrames - 1) : 0;
        for (int k = 0; k < outFrames; k++) {
            double position = k * step;
            int index = (int) position;
            int next = Math.min(index + 1, frames - 1);
            double t = position - index;
            out[k * 2] = (short) Math.round(in[index * 2] + (in[next * 2] - in[index * 2]) * t);
            out[k * 2 + 1] = (short) Math.round(in[index * 2 + 1] + (in[next * 2 + 1] - in[index * 2 + 1]) * t);
        }
        return out;
    }

    synchronized void release() {
        AudioTrack audioTrack = this.track;
        if (audioTrack == null) {
            return;
        }
        audioTrack.pause();
        this.track.flush();
        this.track.release();
        this.track = null;
    }
}
