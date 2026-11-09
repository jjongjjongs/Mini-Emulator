package com.example.minigba;

import android.view.Surface;

final class NativeGba {
    static native void clearSurface(long j);

    static native long create(Surface surface, String str, String str2, String str3, String str4, String str5, AudioSink audioSink);

    static native void destroy(long j);

    static native boolean loadStateNow(long j);

    static native void pause(long j, boolean z);

    static native void reset(long j);

    static native boolean saveNow(long j);

    static native boolean saveSramNow(long j);

    static native boolean saveStateNow(long j);

    static native void setInput(long j, int i);

    static native void setAnalog(long j, int x, int y);

    static native void setAnalogRight(long j, int x, int y);

    // Players 2-4 (player 1-3): their pad's buttons and sticks.
    static native void setPlayerInput(long handle, int player, int mask);

    static native void setPlayerAnalog(long handle, int player, int lx, int ly, int rx, int ry);

    // How many players have a pad, and the 멀티탭 (0 off, 1 on, 2 Team Player, 3 EA 4-Way Play).
    // Handle 0 sets the 멀티탭 for the next game.
    static native void setPlayers(long handle, int players, int multitap);

    // Touch on the game screen (DS): x and y in -32767..32767 across the screen view.
    static native void setPointer(long handle, int x, int y, boolean pressed);

    // 50, 100 or 200 (0.5x, 1x, 2x).
    static native void setSpeedPercent(long j, int percent);

    // PSP system language ("Korean", "English", "Japanese"). With handle 0 it applies to the
    // next game created; with a running game it applies and restarts the game.
    static native void setPspLanguage(long handle, String language);

    // PSP rendering scale (1x, 2x or 3x the PSP's 480x272) for the next game created.
    static native void setPspScale(int scale);
    // PSP 텍스처 다듬기: texture shader, upscale level, upscale type, deposterize, texture filter,
    // smart 2D filter, anisotropic (indices; 0 is the engine default). Before the game starts.
    static native void setPspTexture(int[] choices);
    // VMU 화면 (Dreamcast): VMU A1's 48x32 dots (1 lit) into dots; how many are lit, -1 if none.
    static native int vmuScreen(long handle, byte[] dots);
    // VMU beeps on or off; before the game starts.
    static native void setVmuSound(boolean on);

    static native void setN3dsScale(int scale);

    // 3DS CIA install through the Citra core; see the bridge for the status codes.
    interface CiaProgress {
        void onProgress(long done, long total);
    }

    static native int installCia(String userDir, String ciaPath, CiaProgress progress);

    // Wii 시스템 업데이트 through the Dolphin core; see the bridge for the result codes.
    interface WiiUpdateProgress {
        /** Returns false to stop. */
        boolean onProgress(int done, int total, long titleId);
    }

    static native int wiiSystemUpdate(String userDir, String sysDir, String region, WiiUpdateProgress progress);

    interface NandProgress {
        /** step 0 reading, 1 extracting. Returns false to stop. */
        boolean onProgress(int step, int done, int total);
    }

    static native int wiiImportNand(String userDir, String sysDir, String nandPath, String keysPath, NandProgress progress);

    // PSP ad-hoc over the LAN: 0 off, 1 host (built-in server), 2 guest; server is the address to join.
    static native void setPspAdhoc(int mode, String server);

    // DS 통신: 0 off, 1 local host, 2 local guest (host is its address), 3 Wiimmfi, 4 Kaeru WFC.
    static native void setDsNet(int mode, String host);

    // "속도 우선" (speed hacks) for the next PSP or PS2 (PCSX2) game created.
    static native void setSpeedFirst(boolean fast);

    // "속도 우선 · 강하게" for the next PS2 (PCSX2) game created: the EE at 60% with moderate
    // cycle skipping, on top of setSpeedFirst(true).
    static native void setSpeedFirstStrong(boolean strong);

    // "속도 우선 · 그림만 가볍게" for the next PS2 (PCSX2) game created: the drawing shortcuts of
    // setSpeedFirst(true) with the EE left at full speed.
    static native void setSpeedFirstLight(boolean light);

    // "FSR 업스케일" screen filter: 3D engines enlarge the picture with AMD FSR 1.0. Takes effect
    // at once.
    static native void setFsr(boolean on);
    // 3D 다듬기: FXAA, sharpening 0..100 (0 off), colour 0..2 (as drawn, 자연스러운 색, 또렷한 색).
    static native void setFx3d(boolean fxaa, int sharp, int grade);

    // "전원 모드" for the next game created: 0 성능, 1 균형, 2 절전.
    static native void setPowerMode(int mode);

    // DS microphone: what the game's mic hears (0 the phone's mic, 1 the built-in blowing
    // sound, 2 silence) and how the MIC button works (0 hold, 1 toggle, 2 always).
    static native void setDsMic(int input, int button);

    // Whether the DS game's microphone is on (the core opened it and the MIC button allows).
    static native boolean isDsMicOn();

    // Microphone samples (16-bit mono, 44.1 kHz) for the DS game.
    static native void pushMic(short[] samples, int count);

    // N64 rendering scale for the next game created: 1 (320x240) or 2 (640x480).
    static native void setN64Scale(int scale);

    // For the next game created: no quick save written on leaving or restored on starting.
    static native void setNoAutoState(boolean off);

    // Wii U: where the TV and GamePad pictures go, as fractions of the output (x, y, width,
    // height; a zero width hides one); stack 2 makes the output two TV heights tall.
    static native void setWiiuLayout(int stack, float[] tv, float[] pad);

    // Wii U internal resolution for the next game ("0.5x", "0.75x", "native", "1.5x").
    static native void setWiiuResolution(String value);

    // {frames run, pictures shown, the frame rate the game is paced at}, for the FPS readout.
    static native double[] frameStats(long handle);

    // Vulkan (where the device has it) for the next PSP, Dreamcast or PS2 game created;
    // false keeps OpenGL ES.
    static native void setVulkan(boolean allowed);

    // DS screen layout: 0 stacked with a 4-row gap, 1 stacked and touching, 2 side by side.
    // With handle 0 it applies to the next game created; a running game changes layout.
    static native void setNdsLayout(long handle, int layout);

    // Quick save slots: write the game's state to a file of the app's choosing, or load one.
    static native boolean saveStateFile(long handle, String path);

    static native boolean loadStateFile(long handle, String path);

    // "선명하게" screen filter: software frames scaled up with nearest neighbour first.
    static native void setSharpScaling(boolean sharp);

    // The game picture's width and height (0, 0 before the first frame).
    static native int[] frameSize(long handle);

    static native void setSurface(long j, Surface surface);

    static {
        System.loadLibrary("gbabridge");
    }

    private NativeGba() {
    }

    static native void setRewindEnabled(long handle, boolean enabled);

    static native void setRewinding(long handle, boolean held);

    static native float rewoundSeconds(long handle);

    static native boolean setCheats(long handle, String[] codes);

    static native void setWonderSwanTall(boolean tall);

    static native void setWiiControl(int type);

    // GameCube / Wii: resolution (1-3) and picture shape (0 the game's, 1 4:3, 2 16:9
    // stretched, 3 widescreen hack) for the next game.
    static native void setDolphinVideo(int scale, int aspect);

    // Wii: the remote's pointer from the right stick instead of a finger on the screen.
    static native void setWiiPointer(boolean stick);

    // 게임큐브 LAN 대전 for the next game: 0 off, 1 host, 2 guest of host.
    static native void setGcLan(int mode, String host);

    // Phones in the LAN room besides this one.
    static native int gcLanPeers();

    // Wii 인터넷 (Wiimmfi) for the next game.
    static native void setWiimmfi(boolean on);

    // WiiLink WFC for the next game: Dolphin's cheats on, so the code in the game's settings file runs.
    static native void setWiiLink(boolean on);

    // Wii 친구끼리: "<host ip>:<http port>" of the room for the next game (Dolphin sends the Wi-Fi
    // Connection names there), or null for none.
    static native void setWiiRoom(String address);

    // 모션 (자이로): offered to the next game created; readings in the screen's axes,
    // the accelerometer in g and the gyroscope in rad/s.
    static native void setMotionEnabled(boolean on);

    static native void setMotion(float ax, float ay, float az, float gx, float gy, float gz);

    // DOS (DOSBox Pure): the game's core options and the file to start ("" = the core's menu).
    static native void setDosOptions(String[] keys, String[] values, String start);

    // Disk swapping (multi-disk games, .m3u): the next disk at the next frame; {current, count}.
    static native void diskNext(long handle);

    static native int[] diskInfo(long handle);

    // A disk of a multi-disk game by number (from 0), at the next frame.
    static native void diskSet(long handle, int which);

    // The disk the next game starts on (from 0; -1 for its first), before create().
    static native void setInitialDisk(int which);

    // Options set by name, answered before the core's own ("#n" is the core's n-th choice);
    // clear drops the earlier ones. FBNeo .ini cheats are options "fbneo-cheat-...".
    static native void setCoreOptions(String[] keys, String[] values, boolean clear);

    // 3DS 통신: open (host) or join a Citra room — blocks while connecting. -1 unsupported, 0 failed, 1 joining.
    static native int n3dsNetStart(long handle, boolean host, String address, int port, String nickname, String console, int maxMembers, String game);

    // 3DS 통신: {state | (error + 1) << 8, names one per line}; state 1 idle, 2 joining, 3 joined, 4 host.
    static native String[] n3dsNetStatus(long handle);

    static native void n3dsNetStop(long handle);

    // 3DS: draw with Vulkan (and build shaders in the background) for the next game.
    static native void setCitraVulkan(boolean on);

    static native void setCitraAsync(boolean on);

    static native void setPs2Patches(boolean widescreen, boolean noInterlacing);

    static native void setN3dsLayout(long handle, String spec, boolean swap);

    static native void setDsLayout(long handle, int[] spec);

    static native void setScreenFx(long handle, int mode, int strength);

    // Vertical arcade games: 0 upright, 1 as the core draws it, 2 upright reversed (any time).
    static native void setRotationMode(int mode);

    // The quarter turn the running arcade core asked for, -1 none.
    static native int arcadeRotation(long handle);

    // 연사: these button bits fire `rate` times a second while held (0: off).
    static native void setTurbo(int mask, int rate);

    static native void setFixedTurbo(boolean on);

    // PS Vita: text the game asks for — {kind | maxLength << 8, title, initial text} — or null.
    static native String[] vitaTextRequest(long handle);

    // PS Vita: the text typed for that request (ok), or cancelled.
    static native void vitaTextAnswer(long handle, String text, boolean ok);

    // PC-98 MIDI music on this SoundFont (empty: off), before create(); false when it can't be read.
    static native boolean setMidiSoundfont(String path);

    // The running core's options starting with prefix: {key, description, "a|b|c", value}...
    static native String[] coreOptions(long handle, String prefix);

    // DOS: an on-screen key (libretro RETROK_ code) going down or up.
    static native void dosKey(long handle, int code, boolean down, int character);

    // DOS: mouse movement since the last call.
    static native void dosMouseMove(long handle, int dx, int dy);

    // DOS: held mouse buttons (1 left, 2 right, 4 middle, 8 wheel up, 16 wheel down).
    static native void dosMouseButtons(long handle, int buttons);
}
