package com.seleuco.mame4droid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;
import android.view.SurfaceHolder;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class Emulator {
    private static final int AUTOSAVE = 16;
    private static final int EXIT_GAME_KEY = 2;
    private static final int EXIT_PAUSE = 9;
    private static final long INPUT_A = 8192;
    private static final long INPUT_B = 16384;
    private static final long INPUT_C = 4096;
    private static final long INPUT_COIN = 512;
    private static final long INPUT_D = 32768;
    private static final long INPUT_DOWN = 16;
    private static final long INPUT_E = 1024;
    private static final long INPUT_F = 2048;
    private static final long INPUT_LEFT = 4;
    private static final long INPUT_RIGHT = 64;
    private static final long INPUT_START = 256;
    private static final long INPUT_UP = 1;
    private static final int LOADSTATE = 18;
    private static final int NUMBTNS = 25;
    private static final int NUMWAYS = 26;
    private static final int PAUSE = 11;
    private static final int ROM_NAME = 7;
    private static final int ROM_PATH = 6;
    private static final int SAVESTATE = 17;
    private static final int SOUND_VALUE = 13;
    private static final String TAG = "MiniMAME139";
    private static final int THREADED_VIDEO = 22;
    private static final int THROTTLE = 14;
    private static final int USING_SAF = 62;
    private static final int VERSION = 8;
    private static AudioTrack audioTrack;
    private static volatile boolean nativeReady;
    private static volatile boolean running;
    private static volatile SurfaceHolder surfaceHolder;
    private static final Object VIDEO_LOCK = new Object();
    private static final Paint VIDEO_PAINT = new Paint(2);
    private static Bitmap frameBitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.RGB_565);
    private static int visibleWidth = 320;
    private static int visibleHeight = 240;

    public static native int getValue(int i, int i2);

    public static native String getValueStr(int i, int i2);

    private static native void init(String str, String str2);

    public static native int netplayInit(String str, int i, int i2);

    private static native void runT();

    private static native void runVideoT();

    static void safCloseDir(int i) {
    }

    static String safGetNextDirEntry(int i) {
        return null;
    }

    static int safOpenFile(String str, String str2) {
        return -1;
    }

    static int safReadDir(String str, int i) {
        return 0;
    }

    public static native void setAnalogData(int i, float f, float f2);

    private static native void setPadData(int i, long j);

    public static native void setValue(int i, int i2, int i3);

    public static native void setValueStr(int i, int i2, String str);

    static {
        System.loadLibrary("mame4droid-jni");
    }

    private Emulator() {
    }

    public static synchronized void start(Context context, SurfaceHolder surfaceHolder2, String str) throws IOException {
        synchronized (Emulator.class) {
            surfaceHolder = surfaceHolder2;
            if (running) {
                setValue(11, 0);
                setValue(9, 1);
                return;
            }
            final File file = new File(str);
            final File parentFile = file.getParentFile();
            if (!file.isFile() || parentFile == null) {
                throw new IOException("MAME 0.139 ROM 파일을 찾을 수 없습니다.");
            }
            File file2 = new File(context.getFilesDir(), "mame139");
            prepareData(context, file2);
            syncRegisteredBios(context, parentFile);
            final String str2 = context.getApplicationInfo().nativeLibraryDir;
            final String str3 = file2.getAbsolutePath() + File.separator;
            running = true;
            Thread thread = new Thread(new Runnable() { // from class: com.seleuco.mame4droid.Emulator$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    Emulator.lambda$start$0(str2, str3, parentFile, file);
                }
            }, "MAME139-Main");
            thread.setPriority(10);
            thread.start();
        }
    }

    static /* synthetic */ void lambda$start$0(String str, String str2, File file, File file2) {
        try {
            init(str, str2);
            nativeReady = true;
            setValueStr(8, "Mini Emulator / MAME4droid 0.139u1");
            setValue(62, 0);
            setValue(13, 1);
            setValue(14, 1);
            setValue(16, 1);
            setValue(22, 0);
            setValue(25, 6);
            setValue(26, 8);
            setValueStr(6, file.getAbsolutePath());
            setValueStr(7, file2.getName());
            Log.i("MiniMAME139", "Starting " + file2.getName() + " from " + file);
            runT();
        } finally {
            try {
            } finally {
            }
        }
    }

    public static void attachSurface(SurfaceHolder surfaceHolder2) {
        surfaceHolder = surfaceHolder2;
    }

    public static void detachSurface(SurfaceHolder surfaceHolder2) {
        if (surfaceHolder == surfaceHolder2) {
            surfaceHolder = null;
        }
    }

    public static boolean isRunning() {
        return running;
    }

    public static void pause() {
        if (running) {
            setValue(11, 1);
        }
        AudioTrack audioTrack2 = audioTrack;
        if (audioTrack2 != null) {
            try {
                audioTrack2.pause();
            } catch (IllegalStateException unused) {
            }
        }
    }

    public static void resume() {
        AudioTrack audioTrack2 = audioTrack;
        if (audioTrack2 != null) {
            try {
                audioTrack2.play();
            } catch (IllegalStateException unused) {
            }
        }
        if (running) {
            setValue(11, 0);
            setValue(9, 1);
        }
    }

    public static void saveState() {
        if (running) {
            setValue(17, 1);
        }
    }

    public static void loadState() {
        if (running) {
            setValue(18, 1);
        }
    }

    public static void stop() {
        if (running) {
            setValue(16, 1);
            setValue(2, 1);
            setValue(2, 0);
        }
    }

    public static void setMiniInput(int i) {
        if (nativeReady) {
            long j = (i & 64) != 0 ? 1L : 0L;
            if ((i & 128) != 0) {
                j |= 16;
            }
            if ((i & 32) != 0) {
                j |= 4;
            }
            if ((i & 16) != 0) {
                j |= 64;
            }
            if ((i & 1) != 0) {
                j |= 8192;
            }
            if ((i & 2) != 0) {
                j |= 16384;
            }
            if ((i & 1024) != 0) {
                j |= 4096;
            }
            if ((i & 2048) != 0) {
                j |= 32768;
            }
            if ((i & 4096) != 0) {
                j |= 1024;
            }
            if ((i & 8192) != 0) {
                j |= 2048;
            }
            if ((i & 4) != 0) {
                j |= 512;
            }
            if ((i & 8) != 0) {
                j |= 256;
            }
            setPadData(0, j);
        }
    }

    public static void bitblt(ByteBuffer byteBuffer) {
        SurfaceHolder surfaceHolder2 = surfaceHolder;
        if (surfaceHolder2 == null || byteBuffer == null || !surfaceHolder2.getSurface().isValid()) {
            return;
        }
        synchronized (VIDEO_LOCK) {
            try {
                byteBuffer.rewind();
                frameBitmap.copyPixelsFromBuffer(byteBuffer);
                Canvas lockCanvas = surfaceHolder2.lockCanvas();
                if (lockCanvas == null) {
                    return;
                }
                try {
                    lockCanvas.drawColor(-16777216);
                    int min = Math.min(visibleWidth, frameBitmap.getWidth());
                    int min2 = Math.min(visibleHeight, frameBitmap.getHeight());
                    lockCanvas.drawBitmap(frameBitmap, new Rect(0, 0, min, min2), fitRect(lockCanvas.getWidth(), lockCanvas.getHeight(), min, min2), VIDEO_PAINT);
                } finally {
                    surfaceHolder2.unlockCanvasAndPost(lockCanvas);
                }
            } catch (RuntimeException e) {
                Log.w("MiniMAME139", "Video frame rejected", e);
            }
        }
    }

    public static void changeVideo(int i, int i2, int i3, int i4) {
        synchronized (VIDEO_LOCK) {
            frameBitmap = Bitmap.createBitmap(Math.max(1, i), Math.max(1, i2), Bitmap.Config.RGB_565);
            visibleWidth = Math.max(1, i3);
            visibleHeight = Math.max(1, i4);
        }
        Log.i("MiniMAME139", "Video " + i + "x" + i2 + " visible=" + i3 + "x" + i4);
    }

    public static synchronized void initAudio(int i, boolean z) {
        synchronized (Emulator.class) {
            endAudio();
            int i2 = z ? 12 : 4;
            int minBufferSize = AudioTrack.getMinBufferSize(i, i2, 2);
            if (minBufferSize <= 0) {
                Log.e("MiniMAME139", "Invalid AudioTrack buffer size: " + minBufferSize);
                return;
            }
            AudioTrack build = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(14).setContentType(2).build()).setAudioFormat(new AudioFormat.Builder().setEncoding(2).setSampleRate(i).setChannelMask(i2).build()).setBufferSizeInBytes(minBufferSize * 2).setTransferMode(1).build();
            audioTrack = build;
            build.play();
        }
    }

    public static void writeAudio(byte[] bArr, int i) {
        AudioTrack audioTrack2 = audioTrack;
        if (audioTrack2 == null || bArr == null || i <= 0) {
            return;
        }
        audioTrack2.write(bArr, 0, Math.min(i, bArr.length), 0);
    }

    public static synchronized void endAudio() {
        synchronized (Emulator.class) {
            AudioTrack audioTrack2 = audioTrack;
            audioTrack = null;
            if (audioTrack2 != null) {
                try {
                    audioTrack2.stop();
                } catch (IllegalStateException unused) {
                }
                audioTrack2.release();
            }
        }
    }

    static void netplayWarn(String str) {
        Log.w("MiniMAME139", "Netplay: " + str);
    }

    private static Rect fitRect(int i, int i2, int i3, int i4) {
        float f = i3;
        float f2 = i4;
        float min = Math.min(i / f, i2 / f2);
        int max = Math.max(1, Math.round(f * min));
        int max2 = Math.max(1, Math.round(f2 * min));
        int i5 = (i - max) / 2;
        int i6 = (i2 - max2) / 2;
        return new Rect(i5, i6, max + i5, max2 + i6);
    }

    private static void prepareData(Context context, File file) throws IOException {
        File file2 = new File(file, ".mini-mame139-v1");
        if (file2.isFile()) {
            return;
        }
        if (!file.exists() && !file.mkdirs()) {
            throw new IOException("MAME 0.139 데이터 폴더를 만들 수 없습니다.");
        }
        String str = file.getCanonicalPath() + File.separator;
        InputStream open = context.getAssets().open("mame139/files.zip");
        try {
            ZipInputStream zipInputStream = new ZipInputStream(new BufferedInputStream(open));
            try {
                byte[] bArr = new byte[32768];
                while (true) {
                    ZipEntry nextEntry = zipInputStream.getNextEntry();
                    if (nextEntry != null) {
                        File file3 = new File(file, nextEntry.getName());
                        if (!file3.getCanonicalPath().startsWith(str)) {
                            throw new IOException("잘못된 MAME 데이터 경로입니다.");
                        }
                        if (nextEntry.isDirectory()) {
                            if (!file3.exists() && !file3.mkdirs()) {
                                throw new IOException("MAME 데이터 폴더 생성 실패: " + nextEntry.getName());
                            }
                        } else {
                            File parentFile = file3.getParentFile();
                            if (parentFile != null && !parentFile.exists() && !parentFile.mkdirs()) {
                                throw new IOException("MAME 데이터 폴더 생성 실패: " + parentFile);
                            }
                            BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(new FileOutputStream(file3));
                            while (true) {
                                try {
                                    int read = zipInputStream.read(bArr);
                                    if (read == -1) {
                                        break;
                                    } else {
                                        bufferedOutputStream.write(bArr, 0, read);
                                    }
                                } finally {
                                }
                            }
                            bufferedOutputStream.close();
                        }
                    } else {
                        zipInputStream.close();
                        if (open != null) {
                            open.close();
                        }
                        if (!file2.createNewFile() && !file2.isFile()) {
                            throw new IOException("MAME 데이터 준비 상태를 저장할 수 없습니다.");
                        }
                        return;
                    }
                }
            } finally {
            }
        } catch (Throwable th) {
            if (open != null) {
                try {
                    open.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }

    private static void syncRegisteredBios(Context context, File file) throws IOException {
        File[] listFiles = new File(new File(context.getFilesDir(), "system"), "mame/bios").listFiles(new FileFilter() { // from class: com.seleuco.mame4droid.Emulator$$ExternalSyntheticLambda1
            @Override // java.io.FileFilter
            public final boolean accept(File file2) {
                return Emulator.lambda$syncRegisteredBios$1(file2);
            }
        });
        if (listFiles == null) {
            return;
        }
        byte[] bArr = new byte[32768];
        for (File file2 : listFiles) {
            File file3 = new File(file, file2.getName());
            if (!file3.isFile() || file3.length() != file2.length() || file3.lastModified() < file2.lastModified()) {
                BufferedInputStream bufferedInputStream = new BufferedInputStream(new FileInputStream(file2));
                try {
                    BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(new FileOutputStream(file3));
                    while (true) {
                        try {
                            int read = bufferedInputStream.read(bArr);
                            if (read == -1) {
                                break;
                            } else {
                                bufferedOutputStream.write(bArr, 0, read);
                            }
                        } finally {
                        }
                    }
                    bufferedOutputStream.close();
                    bufferedInputStream.close();
                    file3.setLastModified(file2.lastModified());
                } catch (Throwable th) {
                    try {
                        bufferedInputStream.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                    throw th;
                }
            }
        }
    }

    static /* synthetic */ boolean lambda$syncRegisteredBios$1(File file) {
        return file.isFile() && file.getName().toLowerCase().endsWith(".zip");
    }

    private static void setValue(int i, int i2) {
        setValue(i, 0, i2);
    }

    private static void setValueStr(int i, String str) {
        setValueStr(i, 0, str);
    }
}
