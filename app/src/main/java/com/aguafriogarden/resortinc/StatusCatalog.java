package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.StatusCatalogResponse;
import com.aguafriogarden.resortinc.network.StatusDetail;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * The backend's catalog of reservation and booking statuses (GET /booking/statuses), fetched when
 * the app starts, cached on the device so the first screen after a cold start already has it, and
 * refreshed again whenever the app comes back to the foreground. Every filter, tab and progress
 * list is built from here; the app carries no status wording or colour of its own.
 *
 * <p>A badge never depends on this catalog being loaded — every record already carries its own
 * {@link StatusDetail} — the catalog only supplies the full ordered lists (all statuses, even ones
 * the guest has no record in yet).
 */
final class StatusCatalog {

    private static final String PREFS = "status_catalog";
    private static final String KEY_JSON = "catalog_json";
    /** Don't re-download more often than this when the app is merely resumed. */
    private static final long MIN_REFETCH_MS = 60_000L;
    private static final Gson GSON = new Gson();

    private static Handler main;
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    private static Context appContext;
    private static StatusCatalogResponse catalog = normalise(new StatusCatalogResponse());
    private static boolean fetching = false;
    private static long lastFetchAt = 0L;

    private StatusCatalog() {
    }

    /** Call once from the Application: loads the cached catalog, then refreshes it. */
    static void init(Context context) {
        appContext = context.getApplicationContext();
        String cached = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JSON, "");
        if (!cached.isEmpty()) {
            try {
                StatusCatalogResponse parsed = GSON.fromJson(cached, StatusCatalogResponse.class);
                if (parsed != null) {
                    catalog = normalise(parsed);
                }
            } catch (RuntimeException ignored) {
                // A corrupt cache is simply refetched below.
            }
        }
        refresh();
    }

    /** Fetches the catalog now (no-op while a fetch is already running). */
    static void refresh() {
        if (fetching || appContext == null) {
            return;
        }
        fetching = true;
        ApiClient.bookingApi().getStatusCatalog().enqueue(new Callback<StatusCatalogResponse>() {
            @Override
            public void onResponse(Call<StatusCatalogResponse> call, Response<StatusCatalogResponse> response) {
                fetching = false;
                if (!response.isSuccessful() || response.body() == null) {
                    return;
                }
                StatusCatalogResponse fresh = normalise(response.body());
                if (fresh.reservation.isEmpty() && fresh.booking.isEmpty()) {
                    return;
                }
                lastFetchAt = System.currentTimeMillis();
                boolean changed = !GSON.toJson(fresh).equals(GSON.toJson(catalog));
                catalog = fresh;
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY_JSON, GSON.toJson(fresh)).apply();
                if (changed) {
                    notifyListeners();
                }
            }

            @Override
            public void onFailure(Call<StatusCatalogResponse> call, Throwable t) {
                // Keeps the cached (or previous) catalog; the next resume retries.
                fetching = false;
            }
        });
    }

    /** Re-fetches on resume, but not on every rapid foreground/background flip. */
    static void refreshIfStale() {
        if (System.currentTimeMillis() - lastFetchAt >= MIN_REFETCH_MS) {
            refresh();
        }
    }

    /** Reservation-request statuses, in the backend's order. Empty until first loaded. */
    static List<StatusDetail> reservation() {
        return catalog.reservation;
    }

    /** Booking statuses, in the backend's (lifecycle) order. Empty until first loaded. */
    static List<StatusDetail> booking() {
        return catalog.booking;
    }

    /** The statuses to filter a section by: requests' or bookings'. */
    static List<StatusDetail> forSection(boolean bookings) {
        return bookings ? catalog.booking : catalog.reservation;
    }

    /** The booking lifecycle, derived from the backend's booking order with cancelled left out —
     *  a cancelled booking is shown separately, not as a step on the way to checked-out. */
    static List<StatusDetail> bookingProgress() {
        List<StatusDetail> steps = new ArrayList<>();
        for (StatusDetail status : catalog.booking) {
            if (!status.hasKey(StatusDetail.KEY_CANCELLED)) {
                steps.add(status);
            }
        }
        return steps;
    }

    /** The catalog entry with this kind and key, or null when the catalog doesn't have it. */
    static StatusDetail find(String kind, String key) {
        List<StatusDetail> list = StatusDetail.KIND_BOOKING.equals(kind) ? catalog.booking : catalog.reservation;
        for (StatusDetail status : list) {
            if (status.hasKey(key)) {
                return status;
            }
        }
        return null;
    }

    /** The status's own colour. A missing or unparseable colour falls back to the theme's muted
     *  text colour (a neutral, not a status colour). */
    static int color(Activity activity, StatusDetail status) {
        if (status != null && status.color != null) {
            try {
                return Color.parseColor(status.color.trim());
            } catch (IllegalArgumentException ignored) {
                // falls through to the neutral below
            }
        }
        return ThemeManager.color(activity, R.attr.textMuted);
    }

    static void addListener(Runnable listener) {
        if (!LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void notifyListeners() {
        if (main == null) {
            main = new Handler(Looper.getMainLooper());
        }
        for (Runnable listener : new ArrayList<>(LISTENERS)) {
            main.post(listener);
        }
    }

    private static StatusCatalogResponse normalise(StatusCatalogResponse raw) {
        StatusCatalogResponse clean = new StatusCatalogResponse();
        clean.reservation = raw.reservation != null ? raw.reservation : new ArrayList<>();
        clean.booking = raw.booking != null ? raw.booking : new ArrayList<>();
        return clean;
    }
}
