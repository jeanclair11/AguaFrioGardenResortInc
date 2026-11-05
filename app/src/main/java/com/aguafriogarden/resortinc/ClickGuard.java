package com.aguafriogarden.resortinc;

import android.os.SystemClock;
import android.view.View;

/**
 * Drops taps that arrive right after another guarded tap, so mashing a button can't run its action
 * twice (double navigation, two pickers or dialogs, a second submit). The window is shared by every
 * guarded button, not kept per button: Next swaps the screen synchronously, and the new screen's
 * own Next/Complete button sits under the same finger, so a per-button debounce would let that
 * second tap straight through.
 * <p>
 * Only for actions that navigate, submit or open something. Leave steppers, toggles and selection
 * cards unguarded, since tapping those quickly in a row is legitimate. Main thread only.
 */
final class ClickGuard {

    private static final long WINDOW_MS = 500;

    private static long lastAcceptedAt = -WINDOW_MS;

    private ClickGuard() {
    }

    /** True if this tap should run, and starts a new window; false if it is a repeat to ignore. */
    static boolean allow() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastAcceptedAt < WINDOW_MS) {
            return false;
        }
        lastAcceptedAt = now;
        return true;
    }

    static View.OnClickListener wrap(View.OnClickListener listener) {
        return v -> {
            if (allow()) {
                listener.onClick(v);
            }
        };
    }
}
