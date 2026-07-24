package com.aguafriogarden.resortinc;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Local placeholder for the guest's support conversation. Messages are kept
 * oldest-first (unlike FeedbackStore's newest-first history) since this is a
 * single running thread. There's no backend yet, so replies come from
 * ChatAssistant's canned FAQ answers rather than a real receptionist — the
 * data shape is otherwise ready for a real GET/POST /chat-messages call.
 */
final class ChatStore {

    static final int SENDER_GUEST = 0;
    static final int SENDER_OTHER = 1; // AI assistant or receptionist — one shared thread

    static final int STATUS_SENDING = 0;
    static final int STATUS_SENT = 1;
    static final int STATUS_DELIVERED = 2;
    static final int STATUS_READ = 3;

    private static final String PREFS = "chat";
    private static final String KEY_COUNT = "count";

    static final class Message {
        final int index;
        final int sender;
        final String text;
        final long timestampMillis;
        final int status;

        Message(int index, int sender, String text, long timestampMillis, int status) {
            this.index = index;
            this.sender = sender;
            this.text = text;
            this.timestampMillis = timestampMillis;
            this.status = status;
        }
    }

    private ChatStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Oldest first, matching how a conversation reads top to bottom. */
    static List<Message> getAll(Context context) {
        SharedPreferences p = prefs(context);
        int count = p.getInt(KEY_COUNT, 0);
        List<Message> messages = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            messages.add(new Message(i,
                    p.getInt("sender_" + i, SENDER_GUEST),
                    p.getString("text_" + i, ""),
                    p.getLong("timestamp_" + i, 0L),
                    p.getInt("status_" + i, STATUS_SENT)));
        }
        return messages;
    }

    /** Returns the new message's index so its status can be progressed afterward. */
    static int addMessage(Context context, int sender, String text, int status) {
        SharedPreferences p = prefs(context);
        int index = p.getInt(KEY_COUNT, 0);
        p.edit()
                .putInt("sender_" + index, sender)
                .putString("text_" + index, text)
                .putLong("timestamp_" + index, System.currentTimeMillis())
                .putInt("status_" + index, status)
                .putInt(KEY_COUNT, index + 1)
                .apply();
        return index;
    }

    static void updateStatus(Context context, int index, int status) {
        prefs(context).edit().putInt("status_" + index, status).apply();
    }

    /** Marks every guest message as read, simulating the other side having seen them. */
    static void markGuestMessagesRead(Context context) {
        SharedPreferences p = prefs(context);
        int count = p.getInt(KEY_COUNT, 0);
        SharedPreferences.Editor editor = p.edit();
        for (int i = 0; i < count; i++) {
            if (p.getInt("sender_" + i, SENDER_GUEST) == SENDER_GUEST) {
                editor.putInt("status_" + i, STATUS_READ);
            }
        }
        editor.apply();
    }

    /** Removes one message (guest-initiated deletes only) and reindexes the rest. */
    static void deleteMessage(Context context, int indexToDelete) {
        List<Message> remaining = new ArrayList<>();
        for (Message m : getAll(context)) {
            if (m.index != indexToDelete) {
                remaining.add(m);
            }
        }
        SharedPreferences.Editor editor = prefs(context).edit().clear();
        for (int i = 0; i < remaining.size(); i++) {
            Message m = remaining.get(i);
            editor.putInt("sender_" + i, m.sender)
                    .putString("text_" + i, m.text)
                    .putLong("timestamp_" + i, m.timestampMillis)
                    .putInt("status_" + i, m.status);
        }
        editor.putInt(KEY_COUNT, remaining.size()).apply();
    }
}
