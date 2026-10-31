import android.view.Surface;
import com.example.minigba.AudioSink;
import com.example.minigba.NativeGba;
import java.io.File;
import java.nio.file.Files;

public class HostTest {
    static native int frames();
    static native int liveWindows();
    static native int[] lastFrame();

    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static String expectError(Runnable r) {
        try {
            r.run();
            return null;
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }

    public static void main(String[] args) throws Exception {
        System.loadLibrary("gbabridge");
        String rom = args[0];
        File work = new File(args[1]);
        File saves = new File(work, "saves");
        File system = new File(work, "system");
        saves.mkdirs();
        system.mkdirs();
        Surface surface = new Surface();
        AudioSink sink = new AudioSink();

        long h = NativeGba.create(surface, rom, saves.getPath(), system.getPath(), "", "gba", sink);
        check(h != 0, "create returns a handle");
        check(sink.configureCalls == 1 && sink.configuredRate > 0, "audio configured once, rate=" + sink.configuredRate);
        int baseRate = sink.configuredRate;

        Thread.sleep(1000);
        int f1 = frames();
        int[] lf = lastFrame();
        check(f1 > 30, "frames rendered in 1s: " + f1);
        check(lf[1] == 240 && lf[2] == 160, "frame size " + lf[1] + "x" + lf[2]);
        check(lf[0] == 0xF800, String.format("center pixel is red RGB565 (0x%04X)", lf[0]));
        check(sink.samples > 0, "audio samples written: " + sink.samples);
        check(sink.writeThread != null && !sink.writeThread.equals("main"), "audio written from emulation thread: " + sink.writeThread);

        NativeGba.setInput(h, 1); // A
        Thread.sleep(200);
        check(NativeGba.saveSramNow(h), "saveSramNow with A held");
        byte[] sav = Files.readAllBytes(new File(saves, "test.sav").toPath());
        check(sav.length > 2 && sav[0] == 0x42, "SRAM marker written by ROM, save size " + sav.length);
        check((sav[1] & 0xFF) == 0xFE, String.format("KEYINPUT shows A pressed (0x%02X)", sav[1] & 0xFF));
        NativeGba.setInput(h, 0);
        Thread.sleep(200);
        NativeGba.saveSramNow(h);
        sav = Files.readAllBytes(new File(saves, "test.sav").toPath());
        check((sav[1] & 0xFF) == 0xFF, String.format("KEYINPUT released (0x%02X)", sav[1] & 0xFF));
        NativeGba.setInput(h, 512 | 2); // L + B
        Thread.sleep(200);
        NativeGba.saveSramNow(h);
        sav = Files.readAllBytes(new File(saves, "test.sav").toPath());
        check((sav[1] & 0xFF) == 0xFD, String.format("B maps to GBA B (0x%02X)", sav[1] & 0xFF));
        NativeGba.setInput(h, 0);

        check(NativeGba.saveStateNow(h), "saveStateNow");
        File state = new File(saves, "test.state");
        check(state.length() > 0, "state file written: " + state.length() + " bytes");
        check(NativeGba.loadStateNow(h), "loadStateNow");
        check(NativeGba.saveNow(h), "saveNow");

        NativeGba.setSpeedPercent(h, 500);
        check(sink.configureCalls == 2 && sink.configuredRate == baseRate * 2, "fast-forward clamps to 2x and reconfigures audio");
        NativeGba.setSpeedPercent(h, 200);
        check(sink.configureCalls == 2, "same speed does not reconfigure audio");
        int before = frames();
        Thread.sleep(1000);
        int fast = frames() - before;
        check(fast > 100, "2x speed frames/s: " + fast);
        NativeGba.setSpeedPercent(h, 50);
        check(sink.configuredRate == baseRate / 2, "0.5x audio rate");
        before = frames();
        Thread.sleep(1000);
        int slow = frames() - before;
        check(slow > 20 && slow < 40, "0.5x speed frames/s: " + slow);
        NativeGba.setSpeedPercent(h, 100);
        check(sink.configuredRate == baseRate, "back to 1x audio rate");

        NativeGba.pause(h, true);
        Thread.sleep(100);
        before = frames();
        Thread.sleep(300);
        check(frames() == before, "paused: no frames");
        NativeGba.pause(h, false);
        Thread.sleep(300);
        check(frames() > before, "resumed: frames again");

        NativeGba.clearSurface(h);
        check(liveWindows() == 0, "clearSurface releases the window");
        Thread.sleep(100);
        before = frames();
        Thread.sleep(300);
        check(frames() == before, "no drawing without a surface");
        NativeGba.setSurface(h, surface);
        Thread.sleep(300);
        check(frames() > before && liveWindows() == 1, "setSurface resumes drawing");
        NativeGba.setSurface(h, surface);
        check(liveWindows() == 1, "replacing the surface releases the old window");

        NativeGba.reset(h);
        Thread.sleep(200);
        check(frames() > before, "still running after reset");

        NativeGba.setRewindEnabled(h, true);
        Thread.sleep(1500);
        NativeGba.setRewinding(h, true);
        before = frames();
        Thread.sleep(500);
        float back = NativeGba.rewoundSeconds(h);
        check(back > 0.2f && back < 1.0f, "rewinding goes back in time: " + back + "s");
        check(frames() > before, "frames keep coming while rewinding");
        Thread.sleep(1500);
        float most = NativeGba.rewoundSeconds(h);
        check(most >= 1.2f && most <= 1.8f, "rewind stops at the oldest state: " + most + "s");
        NativeGba.setRewinding(h, false);
        before = frames();
        Thread.sleep(300);
        check(frames() > before, "plays on after rewinding");
        check(NativeGba.setCheats(h, new String[]{"D8BAE4D9+4864DCE5", "320375D4+0000", "82025D7C 423F\n82025D7E 000F"}), "cheats accepted");
        before = frames();
        Thread.sleep(300);
        check(frames() > before, "runs with cheats on");
        // CodeBreaker 16-bit write of green into the centre pixel of the mode 3 screen.
        check(NativeGba.setCheats(h, new String[]{"860096F0+03E0"}), "pixel cheat accepted");
        Thread.sleep(300);
        int[] cf = lastFrame();
        check(cf[0] == 0x07C0, String.format("cheat changes the game: centre pixel 0x%04X", cf[0]));
        // Address cheats (RetroArch's handler 1): the bridge writes them into system RAM,
        // and the test ROM draws WRAM[0] into the centre pixel.
        check(NativeGba.setCheats(h, new String[]{"@0:31744:2"}), "address cheat accepted");
        Thread.sleep(300);
        cf = lastFrame();
        check(cf[0] == 0x001F, String.format("address cheat writes system RAM: centre pixel 0x%04X", cf[0]));
        check(NativeGba.setCheats(h, new String[]{"@0:31744:2:1:3:0:1"}), "repeated address cheat accepted");
        Thread.sleep(300);
        cf = lastFrame();
        check(cf[0] == 0x101F, String.format("repeated address cheat steps the value: centre pixel 0x%04X", cf[0]));
        check(NativeGba.setCheats(h, new String[]{"@0:2:1:4", "@0:992:2"}), "conditional address cheat accepted");
        Thread.sleep(300);
        cf = lastFrame();
        check(cf[0] == 0x07C0, String.format("run-next-if-equal runs the next cheat: centre pixel 0x%04X", cf[0]));
        check(NativeGba.setCheats(h, new String[]{"@0:3:1:4", "@0:31:2"}), "failing condition accepted");
        Thread.sleep(300);
        cf = lastFrame();
        check(cf[0] == 0x07C0, String.format("a failing condition skips the next cheat: centre pixel 0x%04X", cf[0]));
        check(NativeGba.setCheats(h, new String[0]), "cheats cleared");
        NativeGba.setRewindEnabled(h, false);
        NativeGba.setRewinding(h, true);
        Thread.sleep(200);
        check(NativeGba.rewoundSeconds(h) == 0.0f, "no rewinding with the setting off");
        NativeGba.setRewinding(h, false);

        state.delete();
        new File(saves, "test.sav").delete();
        NativeGba.destroy(h);
        check(liveWindows() == 0, "destroy releases the window");
        check(state.exists() && new File(saves, "test.sav").exists(), "destroy writes state and SRAM");

        long h2 = NativeGba.create(surface, rom, saves.getPath(), system.getPath(), "my/game", "gba", sink);
        check(h2 != 0, "create with explicit save name");
        NativeGba.saveNow(h2);
        check(new File(saves, "my_game.sav").exists() && new File(saves, "my_game.state").exists(), "save name sanitized to my_game");
        NativeGba.destroy(h2);

        String err = expectError(() -> NativeGba.create(null, rom, saves.getPath(), system.getPath(), "", "gba", sink));
        check("ROM load failure: invalid render surface".equals(err), "null surface error: " + err);
        err = expectError(() -> NativeGba.create(surface, rom, saves.getPath(), system.getPath(), "", "nes", sink));
        check("Core load failure: selected libretro core was not found or is incomplete".equals(err), "unknown core error: " + err);
        err = expectError(() -> NativeGba.create(surface, "/nonexistent.gba", saves.getPath(), system.getPath(), "", "gba", sink));
        check("ROM load failure: selected file could not be loaded by the core".equals(err), "missing ROM error: " + err);
        check(liveWindows() == 0, "failed creates release their windows");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
