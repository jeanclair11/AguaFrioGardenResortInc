package com.aguafriogarden.resortinc;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Local placeholder for the guest's feedback history. Entries are only ever
 * appended locally (there's no backend yet), so an admin reply never
 * actually arrives — the data shape (adminResponse/respondedAtMillis) is
 * ready for that once a real GET /feedback call replaces getAll() and a real
 * POST /feedback call replaces addFeedback().
 */
final class FeedbackStore {

    private static final String PREFS = "feedback";
    private static final String KEY_COUNT = "count";

    static final class Entry {
        final int rating;
        final String message;
        final long submittedAtMillis;
        final String adminResponse;
        final long respondedAtMillis;

        Entry(int rating, String message, long submittedAtMillis, String adminResponse,
                long respondedAtMillis) {
            this.rating = rating;
            this.message = message;
            this.submittedAtMillis = submittedAtMillis;
            this.adminResponse = adminResponse;
            this.respondedAtMillis = respondedAtMillis;
        }

        boolean isResponded() {
            return !adminResponse.isEmpty();
        }
    }

    private FeedbackStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Newest submission first. */
    static List<Entry> getAll(Context context) {
        SharedPreferences p = prefs(context);
        int count = p.getInt(KEY_COUNT, 0);
        List<Entry> entries = new ArrayList<>(count);
        for (int i = count - 1; i >= 0; i--) {
            entries.add(new Entry(
                    p.getInt("rating_" + i, 0),
                    p.getString("message_" + i, ""),
                    p.getLong("submitted_at_" + i, 0L),
                    p.getString("admin_response_" + i, ""),
                    p.getLong("responded_at_" + i, 0L)));
        }
        return entries;
    }

    static void addFeedback(Context context, int rating, String message) {
        SharedPreferences p = prefs(context);
        int index = p.getInt(KEY_COUNT, 0);
        p.edit()
                .putInt("rating_" + index, rating)
                .putString("message_" + index, message)
                .putLong("submitted_at_" + index, System.currentTimeMillis())
                .putString("admin_response_" + index, "")
                .putLong("responded_at_" + index, 0L)
                .putInt(KEY_COUNT, index + 1)
                .apply();
    }
}
