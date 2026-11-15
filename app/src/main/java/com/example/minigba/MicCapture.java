package com.example.minigba;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * The phone's microphone for DS games: records 16-bit mono at 44.1 kHz only while the
 * game's microphone is on (MIC button) and hands the samples to the engine. Nothing is
 * kept or sent anywhere. Also measures how loud the input is, for the "듣는 중" meter.
 */
final class MicCapture {
    static final int RATE = 44100;

    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    private volatile float level;

    boolean isRunning() {
        return this.running;
    }

    /** 0 (silence) to 1 (loud), smoothed. */
    float level() {
        return this.level;
    }

    /** Starts recording; false when the microphone cannot be opened (no permission, busy). */
    synchronized boolean start() {
        if (this.running) {
            return true;
        }
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            return false;
        }
        AudioRecord created;
        try {
            created = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE / 5 * 2));
        } catch (RuntimeException e) {
            return false;
        }
        if (created.getState() != AudioRecord.STATE_INITIALIZED) {
            created.release();
            return false;
        }
        try {
            created.startRecording();
        } catch (RuntimeException e) {
            created.release();
            return false;
        }
        this.record = created;
        this.running = true;
        final AudioRecord source = created;
        this.thread = new Thread(() -> {
            // About 10 ms at a time, so the game hears the voice with little delay.
            short[] buffer = new short[RATE / 100];
            while (this.running) {
                int read = source.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    if (read < 0) break;
                    continue;
                }
                NativeGba.pushMic(buffer, read);
                long sum = 0;
                for (int k = 0; k < read; k++) {
                    sum += (long) buffer[k] * buffer[k];
                }
                float rms = (float) Math.sqrt(sum / (double) read) / 32768f;
                float loud = Math.min(1f, rms * 6f);
                this.level = loud > this.level ? loud : this.level * 0.85f + loud * 0.15f;
            }
        }, "ds-mic");
        this.thread.start();
        return true;
    }

    synchronized void stop() {
        if (!this.running) {
            return;
        }
        this.running = false;
        AudioRecord stopping = this.record;
        this.record = null;
        try {
            stopping.stop();
        } catch (RuntimeException ignored) {
            // Already stopped.
        }
        Thread reader = this.thread;
        this.thread = null;
        if (reader != null) {
            try {
                reader.join(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        stopping.release();
        this.level = 0f;
    }
}
