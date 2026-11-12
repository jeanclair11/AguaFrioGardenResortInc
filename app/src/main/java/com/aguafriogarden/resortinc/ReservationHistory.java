package com.aguafriogarden.resortinc;

import com.aguafriogarden.resortinc.network.ReservationSummary;
import com.aguafriogarden.resortinc.network.StatusDetail;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Sorts the guest's live GET /api/booking/my-bookings records (see {@link ReservationFeed}) into
 * the Profile page's two sections (My Reservations / My Bookings) and three tabs (Current /
 * Upcoming / Past). The status of a record is whatever the backend says it is ({@link
 * ReservationSummary#status}); the tabs are decided from that status's key plus the stay's dates.
 * "Today" is always the resort's own date (Asia/Manila), not the phone's timezone, so the tabs
 * agree with the receptionist side around midnight.
 *
 * <p>Rules (the date rules are the same as before the statuses came from the backend):
 * <ul>
 *   <li><b>Past</b> — cancelled, expired or checked-out; also a record that never got to
 *       check-in and whose check-out date has gone by;</li>
 *   <li><b>Current</b> — a guest who is in the house: checked in (or waiting to be checked out)
 *       stays Current until the backend says checked-out, cancelled or expired, whatever the
 *       dates say (the system auto-checks-out and an extension moves the check-out date, so the
 *       status is the truth); otherwise a stay whose check-in date is today or earlier;</li>
 *   <li><b>Upcoming</b> — check-in is tomorrow or any later date.</li>
 * </ul>
 */
final class ReservationHistory {

    static final int SECTION_RESERVATIONS = 0;
    static final int SECTION_BOOKINGS = 1;

    static final int TAB_CURRENT = 0;
    static final int TAB_UPCOMING = 1;
    static final int TAB_PAST = 2;

    private static final TimeZone RESORT_ZONE = TimeZone.getTimeZone("Asia/Manila");

    private ReservationHistory() {
    }

    /** Reservations = requests that haven't been paid; Bookings = paid ones — the two kinds the
     *  server returns. */
    static boolean belongsTo(ReservationSummary summary, int section) {
        return section == SECTION_BOOKINGS ? summary.isBooking() : !summary.isBooking();
    }

    static int tabOf(ReservationSummary summary) {
        return tabOf(summary, today());
    }

    /** {@code today} is "yyyy-MM-dd" in the resort's timezone (a parameter so the rules can be
     *  tested against fixed dates). */
    static int tabOf(ReservationSummary summary, String today) {
        StatusDetail status = summary.status;
        if (status != null && (status.hasKey(StatusDetail.KEY_CANCELLED)
                || status.hasKey(StatusDetail.KEY_EXPIRED)
                || status.hasKey(StatusDetail.KEY_CHECKED_OUT))) {
            return TAB_PAST;
        }
        // Still in the house: stays Current until the backend checks the guest out, whatever the
        // check-out date says (an extension moves that date; auto check-out changes the status).
        if (status != null && (status.hasKey(StatusDetail.KEY_CHECKED_IN)
                || status.hasKey(StatusDetail.KEY_FOR_CHECK_OUT))) {
            return TAB_CURRENT;
        }
        String checkIn = summary.check_in != null ? summary.check_in : "";
        String checkOut = summary.check_out != null ? summary.check_out : "";

        if (!checkOut.isEmpty() && checkOut.compareTo(today) < 0) {
            return TAB_PAST;
        }
        return !checkIn.isEmpty() && checkIn.compareTo(today) > 0 ? TAB_UPCOMING : TAB_CURRENT;
    }

    static List<ReservationSummary> select(List<ReservationSummary> all, int section, int tab) {
        List<ReservationSummary> result = new ArrayList<>();
        if (all == null) {
            return result;
        }
        String today = today();
        for (ReservationSummary summary : all) {
            if (belongsTo(summary, section) && tabOf(summary, today) == tab) {
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
