package com.aguafriogarden.resortinc;

import com.aguafriogarden.resortinc.network.ReservationSummary;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Sorts the guest's live GET /api/booking/my-bookings records into the Profile page's two
 * sections (My Reservations / My Bookings) and three tabs (Current / Upcoming / Past). Pure
 * client-side classification of what the server already returns — nothing here is stored or
 * mocked. "Today" is always the resort's own date (Asia/Manila), not the phone's timezone, so
 * the tabs agree with the receptionist side around midnight.
 */
final class ReservationHistory {

    static final int SECTION_RESERVATIONS = 0;
    static final int SECTION_BOOKINGS = 1;

    static final int TAB_CURRENT = 0;
    static final int TAB_UPCOMING = 1;
    static final int TAB_PAST = 2;

    private static final TimeZone RESORT_ZONE = TimeZone.getTimeZone("Asia/Manila");
    private static final long PAYMENT_WINDOW_MS = 7L * 24 * 60 * 60 * 1000;

    private ReservationHistory() {
    }

    /** Reservations = requests still unpaid (or cancelled/expired before payment); Bookings =
     *  paid ones the receptionist side has promoted — the two kinds the server returns. */
    static boolean belongsTo(ReservationSummary summary, int section) {
        boolean isBooking = "reservation".equals(summary.kind);
        return section == SECTION_BOOKINGS ? isBooking : !isBooking;
    }

    static int tabOf(ReservationSummary summary) {
        if (summary.cancelled || isExpired(summary)) {
            return TAB_PAST;
        }
        String today = today();
        String checkIn = summary.check_in != null ? summary.check_in : "";
        String checkOut = summary.check_out != null ? summary.check_out : "";

        if ("reservation".equals(summary.kind)) {
            if (summary.current_step >= 3) {
                return TAB_PAST;
            }
            if (!checkOut.isEmpty() && checkOut.compareTo(today) < 0) {
                return TAB_PAST;
            }
            if (summary.current_step == 2) {
                return TAB_CURRENT;
            }
            return !checkIn.isEmpty() && checkIn.compareTo(today) > 0 ? TAB_UPCOMING : TAB_CURRENT;
        }

        if (!checkOut.isEmpty() && checkOut.compareTo(today) < 0) {
            return TAB_PAST;
        }
        return !checkIn.isEmpty() && checkIn.compareTo(today) > 0 ? TAB_UPCOMING : TAB_CURRENT;
    }

    /** A still-pending advance reservation whose payment window has run out. */
    static boolean isExpired(ReservationSummary summary) {
        if (!"advance_reservation".equals(summary.kind) || summary.cancelled) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (summary.payment_deadline != null && !summary.payment_deadline.isEmpty()) {
            long deadline = parseIso(summary.payment_deadline);
            return deadline > 0 && now > deadline;
        }
        long created = parseIso(summary.created_at);
        return created > 0 && now - created > PAYMENT_WINDOW_MS;
    }

    static List<ReservationSummary> select(List<ReservationSummary> all, int section, int tab) {
        List<ReservationSummary> result = new ArrayList<>();
        if (all == null) {
            return result;
        }
        for (ReservationSummary summary : all) {
            if (belongsTo(summary, section) && tabOf(summary) == tab) {
                result.add(summary);
            }
        }
        return result;
    }

    static int count(List<ReservationSummary> all, int section, int tab) {
        return select(all, section, tab).size();
    }

    private static String today() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(RESORT_ZONE);
        return format.format(new java.util.Date());
    }

    /** ISO-8601 with offset, or -1 if absent/unparseable. */
    static long parseIso(String iso) {
        if (iso == null || iso.isEmpty()) {
            return -1;
        }
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(iso).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }

    static long parseDate(String yyyyMmDd) {
        if (yyyyMmDd == null || yyyyMmDd.isEmpty()) {
            return -1;
        }
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(yyyyMmDd).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }
}
