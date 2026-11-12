package com.aguafriogarden.resortinc.network;

import java.util.Objects;

/**
 * One status as the backend describes it — an item of GET /booking/statuses and the {@code status}
 * / {@code status_detail} object on every reservation and booking the API returns. The backend
 * (the same source the website and receptionist modules use) owns the wording and the colour, so
 * the app shows {@link #label}, {@link #color} and {@link #definition} exactly as received and
 * only ever branches on {@link #kind} and {@link #key}.
 */
public class StatusDetail {

    /** {@link #kind} of a reservation-request status (pending, cancelled, expired). */
    public static final String KIND_RESERVATION = "reservation";
    /** {@link #kind} of a booking status (pending, booked, ..., checked_out). */
    public static final String KIND_BOOKING = "booking";

    // The only keys the app's logic needs: a request can be paid/cancelled while it is pending,
    // a cancelled record is shown separately from progress and kept out of money totals, and the
    // history tabs put cancelled / expired / checked-out records in Past and a checked-in stay in
    // Current. Everything the guest reads (label, colour, definition) always comes from the API.
    public static final String KEY_PENDING = "pending";
    public static final String KEY_CANCELLED = "cancelled";
    public static final String KEY_EXPIRED = "expired";
    public static final String KEY_CHECKED_IN = "checked_in";
    public static final String KEY_FOR_CHECK_OUT = "for_check_out";
    public static final String KEY_CHECKED_OUT = "checked_out";

    public String kind;
    public String key;
    public String label;
    /** "#RRGGBB" hex. */
    public String color;
    public String definition;

    public boolean hasKey(String candidate) {
        return key != null && key.equals(candidate);
    }

    /** Stable "kind:key" form, for remembering the last status a record was seen in. */
    public String token() {
        return kind + ":" + key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StatusDetail)) {
            return false;
        }
        StatusDetail other = (StatusDetail) o;
        return Objects.equals(kind, other.kind) && Objects.equals(key, other.key)
                && Objects.equals(label, other.label) && Objects.equals(color, other.color)
                && Objects.equals(definition, other.definition);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, key, label, color, definition);
    }
}
