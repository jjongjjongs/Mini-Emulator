package com.example.minigba;

// Records what the bridge sends instead of playing it.
public final class AudioSink {
    public volatile int configuredRate;
    public volatile int configureCalls;
    public volatile long samples;
    public volatile String writeThread;

    public synchronized void configure(int i) {
        configuredRate = i;
        configureCalls++;
    }

    // Answers the speed nudge (parts per million) the real sink steers the queue with.
    public volatile int pacePpm;

    public synchronized int write(short[] s, int n) {
        if (n > s.length) throw new IllegalStateException("count larger than array");
        samples += n;
        writeThread = Thread.currentThread().getName();
        return pacePpm;
    }
}
