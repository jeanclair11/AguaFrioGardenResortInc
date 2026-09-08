package com.aguafriogarden.resortinc;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * In-memory mock booking state for the Book tab's "View My Bookings" screen — the Booking
 * module's counterpart to {@link ReservationStore}, same shape, same seeded demo data, same
 * guest-driven accept/decline transitions, just booking-flavored terminology. Like
 * {@link ReservationStore}, this is a frontend prototype: there is no backend "my bookings
 * history" endpoint wired here (the Book tab's real submission flow in
 * {@link HotelBookingFlowController} posts to the live backend separately), so
 * {@link #seed()} pre-populates a handful of bookings already sitting in each status/
 * sub-status so every status screen stays reachable and demoable.
 */
final class BookingStore {

    static final int STATUS_PENDING = 0;
    static final int STATUS_CONFIRMED = 1;
    static final int STATUS_CANCELLED = 2;

    /** My Bookings only ever shows two tabs — Current (Pending/Confirmed) and Past
     *  (Cancelled) — never the raw per-status split View My Reservations uses. */
    static final int TAB_CURRENT = 0;
    static final int TAB_PAST = 1;

    static final String SUB_UNDER_REVIEW = "under_review";
    static final String SUB_ADDITIONAL_CONFIRMATION = "additional_confirmation";
    static final String SUB_ALTERNATIVE_PROPOSED = "alternative_proposed";
    static final String SUB_REJECTED = "rejected";
    static final String SUB_CANCELLED_BY_GUEST = "cancelled_by_guest";
    static final String SUB_NONE = "";

    static final int TIMELINE_FILLED = 0;
    static final int TIMELINE_CURRENT = 1;
    static final int TIMELINE_UPCOMING = 2;

    static final class TimelineEntry {
        final String label;
        final long millis;
        final String description;
        final int state;

        TimelineEntry(String label, long millis, String description, int state) {
            this.label = label;
            this.millis = millis;
            this.description = description;
            this.state = state;
        }
    }

    static final class Booking {
        int id;
        String bookingReference;
        int status;
        String subStatus;
        long checkInMillis;
        long checkOutMillis;
        long proposedCheckInMillis;
        long proposedCheckOutMillis;
        int adults;
        int children;
        ReservationCatalog.RoomOption roomOption;
        int quantity;
        String cancelReason;
        final List<TimelineEntry> timeline = new ArrayList<>();
    }

    private static final List<Booking> ALL = new ArrayList<>();
    private static int nextId = 1;

    static {
        seed();
    }

    static List<Booking> byTab(int tab) {
        List<Booking> result = new ArrayList<>();
        for (Booking b : ALL) {
            boolean current = b.status == STATUS_PENDING || b.status == STATUS_CONFIRMED;
            if ((tab == TAB_CURRENT) == current) {
                result.add(b);
            }
        }
        return result;
    }

    static Booking byReference(String bookingReference) {
        for (Booking b : ALL) {
            if (b.bookingReference.equals(bookingReference)) {
                return b;
            }
        }
        return null;
    }

    /** Guest accepts the receptionist's proposed alternative schedule: swaps in the new dates. */
    static void acceptAlternativeSchedule(String bookingReference) {
        Booking b = byReference(bookingReference);
        if (b == null || !SUB_ALTERNATIVE_PROPOSED.equals(b.subStatus)) {
            return;
        }
        b.checkInMillis = b.proposedCheckInMillis;
        b.checkOutMillis = b.proposedCheckOutMillis;
        b.proposedCheckInMillis = 0;
        b.proposedCheckOutMillis = 0;
        b.subStatus = SUB_UNDER_REVIEW;
        removeTrailingUpcoming(b);
        b.timeline.add(new TimelineEntry("Alternative Schedule Accepted", System.currentTimeMillis(), null, TIMELINE_FILLED));
        b.timeline.add(new TimelineEntry("Under Review", 0, "Receptionist is checking availability", TIMELINE_CURRENT));
    }

    /** Guest declines the proposed alternative schedule: the booking is cancelled on the guest's own decision. */
    static void declineAlternativeSchedule(String bookingReference) {
        Booking b = byReference(bookingReference);
        if (b == null) {
            return;
        }
        b.status = STATUS_CANCELLED;
        b.subStatus = SUB_CANCELLED_BY_GUEST;
        b.proposedCheckInMillis = 0;
        b.proposedCheckOutMillis = 0;
        b.cancelReason = "Cancelled by Guest";
        removeTrailingUpcoming(b);
        b.timeline.add(new TimelineEntry("Cancelled by Guest", System.currentTimeMillis(), null, TIMELINE_FILLED));
    }

    /** Drops any trailing "Decision Pending"-style placeholder before a real event supersedes it. */
    private static void removeTrailingUpcoming(Booking b) {
        while (!b.timeline.isEmpty()
                && b.timeline.get(b.timeline.size() - 1).state == TIMELINE_UPCOMING) {
            b.timeline.remove(b.timeline.size() - 1);
        }
    }

    private static long daysFromNow(int days) {
        return System.currentTimeMillis() + days * 24L * 60 * 60 * 1000;
    }

    private static long hoursAgo(int hours) {
        return System.currentTimeMillis() - hours * 60L * 60 * 1000;
    }

    private static String generateReference(String datePart, int seq) {
        return String.format(Locale.US, "BKG-%s-%03d", datePart, seq);
    }

    /**
     * Five examples spanning every status/sub-status the spec defines, so every Details/status
     * screen is reachable via "My Bookings" without any in-app receptionist control — the
     * booking-flavored twin of {@link ReservationStore#seed()}.
     */
    private static void seed() {
        String datePart = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());

        // 1) Pending / Under Review
        Booking b1 = new Booking();
        b1.id = nextId++;
        b1.bookingReference = generateReference(datePart, 1);
        b1.status = STATUS_PENDING;
        b1.subStatus = SUB_UNDER_REVIEW;
        b1.checkInMillis = daysFromNow(5);
        b1.checkOutMillis = daysFromNow(6);
        b1.adults = 1;
        b1.children = 0;
        b1.roomOption = ReservationCatalog.find(1);
        b1.quantity = 1;
        long b1Submitted = hoursAgo(2);
        b1.timeline.add(new TimelineEntry("Booking Submitted", b1Submitted, null, TIMELINE_FILLED));
        b1.timeline.add(new TimelineEntry("Under Review", b1Submitted, "Receptionist is checking availability", TIMELINE_CURRENT));
        b1.timeline.add(new TimelineEntry("Decision Pending", 0, null, TIMELINE_UPCOMING));
        ALL.add(b1);

        // 2) Pending / Additional Confirmation Required
        Booking b2 = new Booking();
        b2.id = nextId++;
        b2.bookingReference = generateReference(datePart, 2);
        b2.status = STATUS_PENDING;
        b2.subStatus = SUB_ADDITIONAL_CONFIRMATION;
        b2.checkInMillis = daysFromNow(10);
        b2.checkOutMillis = daysFromNow(12);
        b2.adults = 2;
        b2.children = 1;
        b2.roomOption = ReservationCatalog.find(4);
        b2.quantity = 1;
        long b2Submitted = hoursAgo(48);
        b2.timeline.add(new TimelineEntry("Booking Submitted", b2Submitted, null, TIMELINE_FILLED));
        b2.timeline.add(new TimelineEntry("Under Review", b2Submitted, "Receptionist is checking availability", TIMELINE_FILLED));
        b2.timeline.add(new TimelineEntry("Additional Confirmation Requested", hoursAgo(6),
                "Receptionist needs more time to verify your request", TIMELINE_CURRENT));
        b2.timeline.add(new TimelineEntry("Decision Pending", 0, null, TIMELINE_UPCOMING));
        ALL.add(b2);

        // 3) Pending / Alternative Schedule Proposed
        Booking b3 = new Booking();
        b3.id = nextId++;
        b3.bookingReference = generateReference(datePart, 3);
        b3.status = STATUS_PENDING;
        b3.subStatus = SUB_ALTERNATIVE_PROPOSED;
        b3.checkInMillis = daysFromNow(7);
        b3.checkOutMillis = daysFromNow(8);
        b3.proposedCheckInMillis = daysFromNow(9);
        b3.proposedCheckOutMillis = daysFromNow(10);
        b3.adults = 4;
        b3.children = 0;
        b3.roomOption = ReservationCatalog.find(3);
        b3.quantity = 1;
        long b3Submitted = hoursAgo(30);
        b3.timeline.add(new TimelineEntry("Booking Submitted", b3Submitted, null, TIMELINE_FILLED));
        b3.timeline.add(new TimelineEntry("Under Review", b3Submitted, "Receptionist is checking availability", TIMELINE_FILLED));
        b3.timeline.add(new TimelineEntry("Alternative Schedule Proposed", hoursAgo(4),
                "Your requested dates are fully booked", TIMELINE_CURRENT));
        b3.timeline.add(new TimelineEntry("Decision Pending", 0, null, TIMELINE_UPCOMING));
        ALL.add(b3);

        // 4) Confirmed
        Booking b4 = new Booking();
        b4.id = nextId++;
        b4.bookingReference = generateReference(datePart, 4);
        b4.status = STATUS_CONFIRMED;
        b4.subStatus = SUB_NONE;
        b4.checkInMillis = daysFromNow(3);
        b4.checkOutMillis = daysFromNow(5);
        b4.adults = 2;
        b4.children = 0;
        b4.roomOption = ReservationCatalog.find(2);
        b4.quantity = 1;
        long b4Submitted = hoursAgo(96);
        b4.timeline.add(new TimelineEntry("Booking Submitted", b4Submitted, null, TIMELINE_FILLED));
        b4.timeline.add(new TimelineEntry("Under Review", b4Submitted, "Receptionist is checking availability", TIMELINE_FILLED));
        b4.timeline.add(new TimelineEntry("Booking Confirmed", hoursAgo(60),
                "Booking confirmed by the receptionist", TIMELINE_FILLED));
        ALL.add(b4);

        // 5) Cancelled / Rejected by Receptionist
        Booking b5 = new Booking();
        b5.id = nextId++;
        b5.bookingReference = generateReference(datePart, 5);
        b5.status = STATUS_CANCELLED;
        b5.subStatus = SUB_REJECTED;
        b5.checkInMillis = daysFromNow(1);
        b5.checkOutMillis = daysFromNow(2);
        b5.adults = 2;
        b5.children = 0;
        b5.roomOption = ReservationCatalog.find(5);
        b5.quantity = 1;
        b5.cancelReason = "The selected room is no longer available for your requested dates.";
        long b5Submitted = hoursAgo(120);
        b5.timeline.add(new TimelineEntry("Booking Submitted", b5Submitted, null, TIMELINE_FILLED));
        b5.timeline.add(new TimelineEntry("Under Review", b5Submitted, "Receptionist is checking availability", TIMELINE_FILLED));
        b5.timeline.add(new TimelineEntry("Booking Rejected", hoursAgo(80), b5.cancelReason, TIMELINE_FILLED));
        ALL.add(b5);
    }

    private BookingStore() {
    }
}
