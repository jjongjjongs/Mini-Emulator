package com.example.minigba;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

// An invisible touch area between two face buttons that presses both, for games that want
// two buttons at once under one thumb. It sits above the buttons, so where it overlaps
// their edges ("넓게") it takes those touches too.
final class ComboZoneView extends View {
    private final TouchButton first;
    private final TouchButton second;
    private final TouchButton.Listener listener;
    private boolean held;

    ComboZoneView(Context context, TouchButton first, TouchButton second, TouchButton.Listener listener) {
        super(context);
        this.first = first;
        this.second = second;
        this.listener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            setHeld(true);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            setHeld(false);
        }
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        setHeld(false);
        super.onDetachedFromWindow();
    }

    private void setHeld(boolean held) {
        if (this.held == held) {
            return;
        }
        this.held = held;
        if (held) {
            Haptics.tap();
        }
        this.first.setComboHeld(held);
        this.second.setComboHeld(held);
        this.listener.onButtonChanged(this.first.bit() | this.second.bit(), held);
    }
}
