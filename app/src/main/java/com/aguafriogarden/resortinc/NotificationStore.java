package com.aguafriogarden.resortinc;

import java.util.ArrayList;
import java.util.List;

/**
 * In-app-only notification feed for the Alerts tab (no email is actually
 * sent — this is a frontend prototype). Entries come from {@link ReservationStore}
 * seeding and its guest-driven status transitions; there is no independent
 * seed here to avoid the two stores drifting out of sync.
 */
final class NotificationStore {

    /** Distinguishes reservation-lifecycle alerts from chat alerts so the bell's
     *  action button can route to the right module. */
    enum Type {
        RESERVATION, CHAT
    }

    static final class Notification {
        final String title;
        final String message;
        final String buttonLabel;
        final String targetReference;
        final long timestampMillis;
        final Type type;
        boolean isRead;

        Notification(String title, String message, String buttonLabel, String targetReference,
                long timestampMillis) {
            this(title, message, buttonLabel, targetReference, timestampMillis, Type.RESERVATION);
        }

        Notification(String title, String message, String buttonLabel, String targetReference,
                long timestampMillis, Type type) {
            this.title = title;
            this.message = message;
            this.buttonLabel = buttonLabel;
            this.targetReference = targetReference;
            this.timestampMillis = timestampMillis;
            this.type = type;
        }
    }

    private static final List<Notification> ALL = new ArrayList<>();

    /** Newest-first; callers insert at the front so no sort is needed on read. */
    static void push(Notification notification) {
        ALL.add(0, notification);
    }

    static List<Notification> all() {
        return ALL;
    }

    static int unreadCount() {
        int count = 0;
        for (Notification n : ALL) {
            if (!n.isRead) {
                count++;
            }
        }
        return count;
    }

    static void markAllRead() {
        for (Notification n : ALL) {
            n.isRead = true;
        }
    }

    private NotificationStore() {
    }
}
