package com.aguafriogarden.resortinc;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;

import com.google.gson.Gson;

import java.util.Objects;

/**
 * Foreground poller behind the app's "changes made on the website show up without a refresh"
 * behaviour (the backend has no push channel, so this is short polling). A cheap {@value #TICK_MS}
 * ms heartbeat runs while started, but {@code onSync} only fires when the host view is actually
 * on screen and the interval has elapsed — so a backgrounded app or a hidden tab costs nothing,
 * and coming back to the foreground catches up within one heartbeat instead of a full interval.
 */
final class LiveSync {

    private static final long TICK_MS = 2000L;
    private static final Gson GSON = new Gson();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final View host;
    private final long intervalMs;
    private final Runnable onSync;

    private boolean running = false;
    private long lastSyncAt = 0L;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = System.currentTimeMillis();
            if (isOnScreen(host) && now - lastSyncAt >= intervalMs) {
                lastSyncAt = now;
                onSync.run();
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    /**
     * @param host       the view whose visibility gates syncing (any view in the polled screen's tree)
     * @param intervalMs minimum time between two syncs
     * @param onSync     the silent re-fetch; runs on the main thread and must guard against its own
     *                   in-flight request
     */
    LiveSync(View host, long intervalMs, Runnable onSync) {
        this.host = host;
        this.intervalMs = intervalMs;
        this.onSync = onSync;
    }

    /** Idempotent. The first sync happens one interval after the last one, not immediately —
     *  callers that just loaded fresh data don't need an instant duplicate request. */
    void start() {
        if (running) {
            return;
        }
        running = true;
        lastSyncAt = System.currentTimeMillis();
        handler.postDelayed(heartbeat, TICK_MS);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(heartbeat);
    }

    boolean isRunning() {
        return running;
    }

    private static boolean isOnScreen(View view) {
        return view != null && view.isAttachedToWindow() && view.isShown()
                && view.getWindowVisibility() == View.VISIBLE;
    }

    /** True when the two payloads would render identically (compared by their JSON form), so a
     *  background refresh can skip rebuilding the screen entirely. */
    static boolean sameData(Object previous, Object latest) {
        return Objects.equals(GSON.toJson(previous), GSON.toJson(latest));
    }

    /** Whether the guest is mid-typing in a field inside {@code screen} — a background rebind
     *  would drop their focus/keyboard, so callers defer to the next sync instead. */
    static boolean isUserTyping(View screen) {
        if (screen == null) {
            return false;
        }
        View focused = screen.findFocus();
        return focused instanceof EditText;
    }
}
