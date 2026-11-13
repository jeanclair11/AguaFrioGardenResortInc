package com.aguafriogarden.resortinc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.MyBookingsResponse;
import com.aguafriogarden.resortinc.network.MyStatusesResponse;
import com.aguafriogarden.resortinc.network.PaymentVerificationSummary;
import com.aguafriogarden.resortinc.network.ReservationSummary;
import com.aguafriogarden.resortinc.network.StatusDetail;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * The guest's reservations and bookings, kept current for every screen that shows them. The
 * backend has no push channel, so while at least one such screen is visible (and the app is in the
 * foreground) this polls GET /booking/my-statuses every {@value #POLL_MS} ms and patches each
 * record's status in place — the screens re-bind their badges without a spinner or a reload. When
 * the set of records changes (a request was paid and became a booking, a new one appeared) or a
 * status changed, the full list is silently refetched from GET /booking/my-bookings so the other
 * fields (cancel reason, payments, totals) follow.
 *
 * <p>Screens call {@link #watch} when they are shown. No request is made while none is visible;
 * everything stops when the app is backgrounded ({@link #setForeground}) or after {@link #clear} on
 * logout, and resumes with an immediate refresh when a screen is shown again or the app returns.
 */
final class ReservationFeed {

    private static final long POLL_MS = 5000L;
    /** A screen that re-binds itself (watch() on every bind) must not trigger a refetch each time. */
    private static final long WATCH_REFRESH_GAP_MS = 2000L;

    private static final class Watcher {
        final Object owner;
        final BooleanSupplier visible;
        final Runnable onChange;

        Watcher(Object owner, BooleanSupplier visible, Runnable onChange) {
            this.owner = owner;
            this.visible = visible;
            this.onChange = onChange;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Watcher> WATCHERS = new ArrayList<>();

    private static Context appContext;
    private static List<ReservationSummary> rows = new ArrayList<>();
    private static boolean loaded = false;
    private static boolean foreground = true;
    private static boolean refreshing = false;
    private static boolean refreshQueued = false;
    private static boolean pollingStatuses = false;
    private static boolean tickScheduled = false;
    private static long lastRefreshAt = 0L;
    /** Bumped on logout so a response that was in flight for the previous guest is discarded. */
    private static int generation = 0;

    private static final Runnable TICK = ReservationFeed::tick;

    private ReservationFeed() {
    }

    static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    /** The guest's records as last fetched, newest first (the server's order). Treat as read-only. */
    static List<ReservationSummary> rows() {
        return rows;
    }

    static boolean isLoaded() {
        return loaded;
    }

    static ReservationSummary find(String kind, int id) {
        return FeedDiff.find(rows, kind, id);
    }

    /**
     * Registers a screen that shows reservations or bookings. {@code visible} is asked on every
     * tick; {@code onChange} runs on the main thread whenever a status or the list changed.
     * Idempotent per {@code owner} (a repeat call replaces the previous registration).
     */
    static void watch(Object owner, BooleanSupplier visible, Runnable onChange) {
        for (int i = WATCHERS.size() - 1; i >= 0; i--) {
            if (WATCHERS.get(i).owner == owner) {
                WATCHERS.remove(i);
            }
        }
        WATCHERS.add(new Watcher(owner, visible, onChange));
        if (!loaded || System.currentTimeMillis() - lastRefreshAt >= WATCH_REFRESH_GAP_MS) {
            refresh();
        }
        scheduleTick();
    }

    static void unwatch(Object owner) {
        for (int i = WATCHERS.size() - 1; i >= 0; i--) {
            if (WATCHERS.get(i).owner == owner) {
                WATCHERS.remove(i);
            }
        }
    }

    static void unwatchAll() {
        WATCHERS.clear();
        MAIN.removeCallbacks(TICK);
        tickScheduled = false;
    }

    /** False while the app is in the background: no polling then; true again refreshes at once. */
    static void setForeground(boolean inForeground) {
        foreground = inForeground;
        if (!inForeground) {
            MAIN.removeCallbacks(TICK);
            tickScheduled = false;
            return;
        }
        if (anyVisible()) {
            refresh();
            scheduleTick();
        }
    }

    /** Logout: forget everything, drop in-flight responses, stop polling. */
    static void clear() {
        generation++;
        rows = new ArrayList<>();
        loaded = false;
        refreshing = false;
        refreshQueued = false;
        pollingStatuses = false;
        lastRefreshAt = 0L;
        unwatchAll();
    }

    /** Silent full refetch of GET /booking/my-bookings: no spinner, watchers just re-bind. Used on
     *  open, after create/pay/cancel, and whenever the poll sees the list or a status change. */
    static void refresh() {
        String token = token();
        if (token.isEmpty()) {
            return;
        }
        if (refreshing) {
            refreshQueued = true;
            return;
        }
        refreshing = true;
        final int startedIn = generation;
        ApiClient.bookingApi().getMyBookings("Bearer " + token).enqueue(new Callback<MyBookingsResponse>() {
            @Override
            public void onResponse(Call<MyBookingsResponse> call, Response<MyBookingsResponse> response) {
                if (startedIn != generation) {
                    return;
                }
                refreshing = false;
                lastRefreshAt = System.currentTimeMillis();
                if (response.isSuccessful() && response.body() != null && response.body().reservations != null) {
                    List<ReservationSummary> fresh = response.body().reservations;
                    boolean changed = !loaded || !LiveSync.sameData(rows, fresh);
                    rows = fresh;
                    loaded = true;
                    if (changed) {
                        notifyWatchers();
                    }
                }
                runQueuedRefresh();
            }

            @Override
            public void onFailure(Call<MyBookingsResponse> call, Throwable t) {
                if (startedIn != generation) {
                    return;
                }
                // Keeps whatever was last loaded; the next poll or visit retries.
                refreshing = false;
                runQueuedRefresh();
            }
        });
    }

    /** After a cancel (or any response carrying the record's new status): show that status on the
     *  matching row immediately, then refetch for everything else that changed with it. */
    static void applyStatus(String kind, int id, StatusDetail status) {
        ReservationSummary row = find(kind, id);
        if (row != null && status != null && !status.equals(row.status)) {
            row.status = status;
            notifyWatchers();
        }
        refresh();
    }

    /** After the guest resubmitted a rejected payment: show the booking's new payment verification
     *  (Pending Reverification, with the corrected amount, reference and proof) on its row at once,
     *  then refetch for everything else that came with it. The booking itself is untouched. */
    static void applyPaymentVerification(String kind, int id, PaymentVerificationSummary verification,
            StatusDetail status) {
        ReservationSummary row = find(kind, id);
        if (row != null) {
            if (verification != null) {
                row.payment_verification = verification;
            }
            if (status != null) {
                row.status = status;
            }
            notifyWatchers();
        }
        refresh();
    }

    private static void runQueuedRefresh() {
        if (refreshQueued) {
            refreshQueued = false;
            refresh();
        }
    }

    private static void scheduleTick() {
        if (tickScheduled || !foreground || WATCHERS.isEmpty()) {
            return;
        }
        tickScheduled = true;
        MAIN.postDelayed(TICK, POLL_MS);
    }

    private static void tick() {
        tickScheduled = false;
        if (!foreground || token().isEmpty()) {
            // Stops here; watch() / setForeground(true) start it again.
            return;
        }
        // No request is made while no reservations/bookings screen is on view; the timer itself
        // keeps running so a screen that becomes visible again is picked up within one interval.
        if (anyVisible()) {
            pollStatuses();
        }
        scheduleTick();
    }

    private static boolean anyVisible() {
        for (Watcher watcher : new ArrayList<>(WATCHERS)) {
            if (watcher.visible.getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    private static void pollStatuses() {
        String token = token();
        if (token.isEmpty() || pollingStatuses) {
            return;
        }
        if (!loaded) {
            refresh();
            return;
        }
        pollingStatuses = true;
        final int startedIn = generation;
        ApiClient.bookingApi().getMyStatuses("Bearer " + token).enqueue(new Callback<MyStatusesResponse>() {
            @Override
            public void onResponse(Call<MyStatusesResponse> call, Response<MyStatusesResponse> response) {
                if (startedIn != generation) {
                    return;
                }
                pollingStatuses = false;
                if (response.isSuccessful() && response.body() != null && response.body().statuses != null) {
                    merge(response.body().statuses);
                }
            }

            @Override
            public void onFailure(Call<MyStatusesResponse> call, Throwable t) {
                if (startedIn != generation) {
                    return;
                }
                pollingStatuses = false;
            }
        });
    }

    /** Patches each known record's status in place; refetches the full list when anything beyond a
     *  re-worded status changed (see {@link FeedDiff}). */
    private static void merge(List<MyStatusesResponse.Row> feed) {
        FeedDiff diff = FeedDiff.apply(rows, feed);
        if (diff.displayChanged) {
            notifyWatchers();
        }
        if (diff.needsRefetch) {
            refresh();
        }
    }

    private static void notifyWatchers() {
        for (Watcher watcher : new ArrayList<>(WATCHERS)) {
            watcher.onChange.run();
        }
    }

    private static String token() {
        return appContext == null ? "" : ProfileStore.getAuthToken(appContext);
    }
}
