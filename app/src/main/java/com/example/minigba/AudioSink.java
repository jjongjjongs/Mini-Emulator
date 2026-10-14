package com.example.minigba;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

// Plays the core's stereo 16-bit samples, one write per emulated frame. When the game runs
// slower than real time the queue drains and the gaps crackle, so a frame that arrives
// with little left queued is stretched (up to 1.5x, pitch drops a little) to fill time.
final class AudioSink {
    // Room in the track and the amount kept queued, in milliseconds.
    private static final int BUFFER_MS = 160;
    private static final int TARGET_MS = 60;

    private AudioTrack track;
    private int sampleRate;
    private long framesWritten;
    private short[] stretched = new short[0];

    AudioSink() {
    }

    synchronized void configure(int i) {
        release();
        this.sampleRate = i;
        this.framesWritten = 0;
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
        long target = (long) this.sampleRate * TARGET_MS / 1000;
        short[] out = sArr;
        int outFrames = frames;
        if (queued < target) {
            outFrames = frames + (int) Math.min(target - queued, frames / 2);
            out = stretch(sArr, frames, outFrames);
        }
        int written = audioTrack.write(out, 0, outFrames * 2, AudioTrack.WRITE_NON_BLOCKING);
        if (written > 0) {
            this.framesWritten += written / 2;
        }
    }

    // Linear interpolation of stereo frames from `frames` to `outFrames`.
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
