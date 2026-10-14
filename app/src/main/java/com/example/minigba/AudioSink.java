package com.example.minigba;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

// Plays the core's stereo 16-bit samples, one write per emulated frame. The emulation and
// the audio hardware run on different clocks, so the queue drifts: drained, the gaps
// crackle; full, writes are cut short and crackle too. Each frame is resampled a little to
// steer the (smoothed) queue back to TARGET_MS: up to 1.5x longer when the game runs slower
// than real time, up to 5% shorter when it runs ahead.
final class AudioSink {
    // Room in the track and the amount kept queued, in milliseconds.
    private static final int BUFFER_MS = 200;
    private static final int TARGET_MS = 80;

    private AudioTrack track;
    private int sampleRate;
    private long framesWritten;
    private short[] stretched = new short[0];
    // Queued audio in frames, smoothed so one late frame does not swing the pitch.
    private double smoothedQueued = -1;

    AudioSink() {
    }

    synchronized void configure(int i) {
        release();
        this.sampleRate = i;
        this.framesWritten = 0;
        this.smoothedQueued = -1;
        int bytes = Math.max(AudioTrack.getMinBufferSize(i, 12, 2), i * 4 * BUFFER_MS / 1000);
        AudioTrack build = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(14).setContentType(2).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(i).setChannelMask(12).setEncoding(2).build()).setTransferMode(1).setBufferSizeInBytes(bytes).build();
        this.track = build;
        build.play();
    }

    synchronized void write(short[] sArr, int i) {
        AudioTrack audioTrack = this.track;
        int frames = Math.min(sArr.length, i) / 2;
        if (audioTrack == null || frames <= 0) {
            return;
        }
        long played = audioTrack.getPlaybackHeadPosition() & 0xffffffffL;
        long queued = Math.max(0, this.framesWritten - played);
        double target = (double) this.sampleRate * TARGET_MS / 1000;
        double room = (double) this.sampleRate * BUFFER_MS / 1000 - target;
        // The playback position moves in bursts, so the queue is smoothed; a nearly empty
        // queue is taken at once, as a gap is about to be heard.
        this.smoothedQueued = this.smoothedQueued < 0 || queued < target / 4
                ? queued : this.smoothedQueued * 0.8 + queued * 0.2;
        double ratio;
        if (this.smoothedQueued < target) {
            ratio = 1.0 + 0.5 * (target - this.smoothedQueued) / target;
        } else {
            ratio = 1.0 - 0.05 * Math.min(1.0, (this.smoothedQueued - target) / room);
        }
        int outFrames = Math.max(1, (int) Math.round(frames * ratio));
        short[] out = outFrames == frames ? sArr : stretch(sArr, frames, outFrames);
        int written = audioTrack.write(out, 0, outFrames * 2, AudioTrack.WRITE_NON_BLOCKING);
        if (written > 0) {
            this.framesWritten += written / 2;
        }
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
