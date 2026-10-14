package com.example.minigba;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.io.File;

// Restarts a game in a new game process. Runs in the app's main process, waits until
// the old game process has gone (GameActivity ends its process when it closes), then
// opens the game again from the start. Used for engines that cannot start a game over
// inside the process that already ran it (MAME 2010).
public final class RestartActivity extends Activity {
    static final String EXTRA_OLD_PID = "com.example.minigba.OLD_PID";

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        final int oldPid = getIntent().getIntExtra(EXTRA_OLD_PID, 0);
        final Intent game = new Intent(getIntent());
        game.setClass(this, GameActivity.class);
        game.removeExtra(EXTRA_OLD_PID);
        game.putExtra(GameActivity.EXTRA_FRESH_START, true);
        new Thread(() -> {
            // GameActivity's exit watchdog ends the process within 5 seconds.
            long deadline = System.currentTimeMillis() + 6000;
            while (oldPid > 0 && new File("/proc/" + oldPid).exists() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    break;
                }
            }
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    startActivity(game);
                    overridePendingTransition(0, 0);
                    finish();
                    overridePendingTransition(0, 0);
                }
            });
        }, "restart").start();
    }

    @Override
    public void onBackPressed() {
        // The game is about to open again; nothing to go back to here.
    }
}
