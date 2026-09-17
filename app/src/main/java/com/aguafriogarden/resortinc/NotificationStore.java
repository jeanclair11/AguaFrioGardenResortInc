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

    /** Backs the Reserve tab's own bottom-nav badge — only RESERVATION-type entries (a
     *  receptionist-side cancellation, etc. — see ChatPollingService), so an unread chat message
     *  doesn't also light up Reserve. */
    static int unreadCountByType(Type type) {
        int count = 0;
        for (Notification n : ALL) {
            if (!n.isRead && n.type == type) {
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

    /** Marks only one type's entries read — used when the guest opens the tab that type's own
     *  badge belongs to (e.g. Reserve for RESERVATION), mirroring how opening Chat itself already
     *  clears UnreadChatStore without needing a trip through the Alerts tab. */
    static void markReadByType(Type type) {
        for (Notification n : ALL) {
            if (n.type == type) {
                n.isRead = true;
            }
        }
    }

    private NotificationStore() {
    }
}
