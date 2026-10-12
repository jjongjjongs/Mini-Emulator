package com.example.minigba;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

final class AudioSink {
    private AudioTrack track;

    AudioSink() {
    }

    synchronized void configure(int i) {
        release();
        AudioTrack build = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(14).setContentType(2).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(i).setChannelMask(12).setEncoding(2).build()).setTransferMode(1).setBufferSizeInBytes(Math.max(AudioTrack.getMinBufferSize(i, 12, 2), (i / 10) * 4)).build();
        this.track = build;
        build.play();
    }

    synchronized void write(short[] sArr, int i) {
        AudioTrack audioTrack = this.track;
        if (audioTrack != null && i > 0) {
            audioTrack.write(sArr, 0, Math.min(sArr.length, i), 1);
        }
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
