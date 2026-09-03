package com.aguafriogarden.resortinc;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * In-memory mock reservation state for the Reservation Module. This is a
 * frontend prototype: there is no backend reservation endpoint yet, and no
 * receptionist-side control exists anywhere in this app to move a
 * reservation out of Pending — that is intentionally left for a future
 * website/receptionist feature. To keep every status screen reachable and
 * demoable in the meantime, {@link #seed()} pre-populates a handful of
 * reservations already sitting in each status/sub-status. New reservations
 * the guest submits always land in Pending/Under Review and stay there,
 * except for the two genuinely guest-driven transitions the spec defines:
 * accepting or declining a proposed alternative schedule.
 */
final class ReservationStore {

    static final int STATUS_PENDING = 0;
    static final int STATUS_CONFIRMED = 1;
    static final int STATUS_CANCELLED = 2;
    /** Used only as a filter value on the My Reservations tabs, never stored on a Reservation. */
    static final int STATUS_ALL = -1;

    static final String SUB_UNDER_REVIEW = "under_review";
    static final String SUB_ADDITIONAL_CONFIRMATION = "additional_confirmation";
    static final String SUB_ALTERNATIVE_PROPOSED = "alternative_proposed";
    static final String SUB_REJECTED = "rejected";
    static final String SUB_CANCELLED_BY_GUEST = "cancelled_by_guest";
    static final String SUB_NONE = "";

    static final int TIMELINE_FILLED = 0;
    static final int TIMELINE_CURRENT = 1;
    static final int TIMELINE_UPCOMING = 2;

    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

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

    static final class Reservation {
        int id;
        String referenceNo;
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
        String specialRequest;
        String guestName;
        String guestEmail;
        String guestMobile;
        String guestAddress;
        String purpose;
        long submittedAtMillis;
        String cancelReason;
        final List<TimelineEntry> timeline = new ArrayList<>();
    }

    private static final List<Reservation> ALL = new ArrayList<>();
    private static int nextId = 1;

    static {
        seed();
    }

    static List<Reservation> all() {
        return ALL;
    }

    static List<Reservation> byStatus(int status) {
        if (status == STATUS_ALL) {
            return ALL;
        }
        List<Reservation> result = new ArrayList<>();
        for (Reservation r : ALL) {
            if (r.status == status) {
                result.add(r);
            }
        }
        return result;
    }

    static Reservation byReference(String referenceNo) {
        for (Reservation r : ALL) {
            if (r.referenceNo.equals(referenceNo)) {
                return r;
            }
        }
        return null;
    }

    /** Creates a new Pending/Under-Review reservation and notifies the guest. Always inserted first (newest). */
    static Reservation submit(Context context, long checkInMillis, long checkOutMillis, int adults, int children,
            ReservationCatalog.RoomOption option, int quantity, String specialRequest, String guestName,
            String guestEmail, String guestMobile, String guestAddress, String purpose) {
        Reservation r = new Reservation();
        r.id = nextId++;
        r.checkInMillis = checkInMillis;
        r.checkOutMillis = checkOutMillis;
        r.adults = adults;
        r.children = children;
        r.roomOption = option;
        r.quantity = quantity;
        r.specialRequest = specialRequest;
        r.guestName = guestName;
        r.guestEmail = guestEmail;
        r.guestMobile = guestMobile;
        r.guestAddress = guestAddress;
        r.purpose = purpose;
        r.submittedAtMillis = System.currentTimeMillis();
        r.referenceNo = generateReference();
        r.status = STATUS_PENDING;
        r.subStatus = SUB_UNDER_REVIEW;
        r.timeline.add(new TimelineEntry(
                context.getString(R.string.timeline_reservation_submitted), r.submittedAtMillis, null, TIMELINE_FILLED));
        r.timeline.add(new TimelineEntry(
                context.getString(R.string.timeline_under_review), r.submittedAtMillis,
                context.getString(R.string.timeline_under_review_desc), TIMELINE_CURRENT));
        r.timeline.add(new TimelineEntry(context.getString(R.string.timeline_decision_pending), 0, null, TIMELINE_UPCOMING));
        ALL.add(0, r);

        NotificationStore.push(new NotificationStore.Notification(
                context.getString(R.string.notif_title_submitted),
                context.getString(R.string.notif_message_submitted_format, r.referenceNo),
                context.getString(R.string.notif_button_view_reservation), r.referenceNo, System.currentTimeMillis()));
        return r;
    }

    /** Guest accepts the receptionist's proposed alternative schedule: swaps in the new dates and returns to review. */
    static void acceptAlternativeSchedule(Context context, String referenceNo) {
        Reservation r = byReference(referenceNo);
        if (r == null || !SUB_ALTERNATIVE_PROPOSED.equals(r.subStatus)) {
            return;
        }
        r.checkInMillis = r.proposedCheckInMillis;
        r.checkOutMillis = r.proposedCheckOutMillis;
        r.proposedCheckInMillis = 0;
        r.proposedCheckOutMillis = 0;
        r.subStatus = SUB_UNDER_REVIEW;
        removeTrailingUpcoming(r);
        r.timeline.add(new TimelineEntry(
                context.getString(R.string.timeline_alternative_accepted), System.currentTimeMillis(), null, TIMELINE_FILLED));
        r.timeline.add(new TimelineEntry(
                context.getString(R.string.timeline_under_review), 0,
                context.getString(R.string.timeline_under_review_desc), TIMELINE_CURRENT));

        NotificationStore.push(new NotificationStore.Notification(
                context.getString(R.string.notif_title_submitted),
                context.getString(R.string.notif_message_submitted_format, r.referenceNo),
                context.getString(R.string.notif_button_view_reservation), r.referenceNo, System.currentTimeMillis()));
    }

    /** Guest declines the proposed alternative schedule: the reservation is cancelled on the guest's own decision. */
    static void declineAlternativeSchedule(Context context, String referenceNo) {
        Reservation r = byReference(referenceNo);
        if (r == null) {
            return;
        }
        r.status = STATUS_CANCELLED;
        r.subStatus = SUB_CANCELLED_BY_GUEST;
        r.proposedCheckInMillis = 0;
        r.proposedCheckOutMillis = 0;
        r.cancelReason = context.getString(R.string.timeline_cancelled_by_guest);
        removeTrailingUpcoming(r);
        r.timeline.add(new TimelineEntry(
                context.getString(R.string.timeline_cancelled_by_guest), System.currentTimeMillis(), null, TIMELINE_FILLED));

        NotificationStore.push(new NotificationStore.Notification(
                context.getString(R.string.notif_title_cancelled),
                context.getString(R.string.notif_message_cancelled_format, r.referenceNo),
                context.getString(R.string.notif_button_view_details), r.referenceNo, System.currentTimeMillis()));
    }

    /** Drops any trailing "Decision Pending"-style placeholder before a real event supersedes it. */
    private static void removeTrailingUpcoming(Reservation r) {
        while (!r.timeline.isEmpty()
                && r.timeline.get(r.timeline.size() - 1).state == TIMELINE_UPCOMING) {
            r.timeline.remove(r.timeline.size() - 1);
        }
    }

    private static String generateReference() {
        String datePart = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        int seq = 1;
        for (Reservation r : ALL) {
            if (r.referenceNo.startsWith("RES-" + datePart)) {
                seq++;
            }
        }
        return String.format(Locale.US, "RES-%s-%03d", datePart, seq);
    }

    private static long daysFromNow(int days) {
        return System.currentTimeMillis() + days * ONE_DAY_MS;
    }

    private static long hoursAgo(int hours) {
        return System.currentTimeMillis() - hours * 60L * 60 * 1000;
    }

    /**
     * Five examples spanning every status/sub-status the spec defines, so every
     * Details/status screen is reachable via "My Reservations" without any
     * in-app receptionist control. Text here is hardcoded (matching the
     * equivalent string resources) rather than resolved via Context, since
     * static init runs before any Activity exists to resolve resources with.
     */
    private static void seed() {
        // 1) Pending / Under Review
        Reservation r1 = new Reservation();
        r1.id = nextId++;
        r1.referenceNo = "RES-20260901-001";
        r1.status = STATUS_PENDING;
        r1.subStatus = SUB_UNDER_REVIEW;
        r1.checkInMillis = daysFromNow(5);
        r1.checkOutMillis = daysFromNow(6);
        r1.adults = 1;
        r1.children = 0;
        r1.roomOption = ReservationCatalog.find(1);
        r1.quantity = 1;
        r1.specialRequest = "";
        r1.guestName = "Adrianne N. Romero";
        r1.guestEmail = "adrianneromero2005@gmail.com";
        r1.guestMobile = "0912345678";
        r1.guestAddress = "Koronadal City, South Cotabato";
        r1.purpose = "";
        r1.submittedAtMillis = hoursAgo(2);
        r1.timeline.add(new TimelineEntry("Reservation Submitted", r1.submittedAtMillis, null, TIMELINE_FILLED));
        r1.timeline.add(new TimelineEntry("Under Review", r1.submittedAtMillis, "Receptionist is checking availability", TIMELINE_CURRENT));
        r1.timeline.add(new TimelineEntry("Decision Pending", 0, null, TIMELINE_UPCOMING));
        ALL.add(r1);

        // 2) Pending / Additional Confirmation Required
        Reservation r2 = new Reservation();
        r2.id = nextId++;
        r2.referenceNo = "RES-20260828-004";
        r2.status = STATUS_PENDING;
        r2.subStatus = SUB_ADDITIONAL_CONFIRMATION;
        r2.checkInMillis = daysFromNow(10);
        r2.checkOutMillis = daysFromNow(12);
        r2.adults = 2;
        r2.children = 1;
        r2.roomOption = ReservationCatalog.find(4);
        r2.quantity = 1;
        r2.specialRequest = "Late check-in, arriving around 9 PM.";
        r2.guestName = "Adrianne N. Romero";
        r2.guestEmail = "adrianneromero2005@gmail.com";
        r2.guestMobile = "0912345678";
        r2.guestAddress = "Koronadal City, South Cotabato";
        r2.purpose = "Family getaway";
        r2.submittedAtMillis = hoursAgo(48);
        r2.timeline.add(new TimelineEntry("Reservation Submitted", r2.submittedAtMillis, null, TIMELINE_FILLED));
        r2.timeline.add(new TimelineEntry("Under Review", r2.submittedAtMillis, "Receptionist is checking availability", TIMELINE_FILLED));
        r2.timeline.add(new TimelineEntry("Additional Confirmation Requested", hoursAgo(6),
                "Receptionist needs more time to verify your request", TIMELINE_CURRENT));
        r2.timeline.add(new TimelineEntry("Decision Pending", 0, null, TIMELINE_UPCOMING));
        ALL.add(r2);

        // 3) Pending / Alternative Schedule Proposed
        Reservation r3 = new Reservation();
        r3.id = nextId++;
        r3.referenceNo = "RES-20260830-002";
        r3.status = STATUS_PENDING;
        r3.subStatus = SUB_ALTERNATIVE_PROPOSED;
        r3.checkInMillis = daysFromNow(7);
        r3.checkOutMillis = daysFromNow(8);
        r3.proposedCheckInMillis = daysFromNow(9);
        r3.proposedCheckOutMillis = daysFromNow(10);
        r3.adults = 4;
        r3.children = 0;
        r3.roomOption = ReservationCatalog.find(3);
        r3.quantity = 1;
        r3.specialRequest = "";
        r3.guestName = "Adrianne N. Romero";
        r3.guestEmail = "adrianneromero2005@gmail.com";
        r3.guestMobile = "0912345678";
        r3.guestAddress = "Koronadal City, South Cotabato";
        r3.purpose = "Barkada trip";
        r3.submittedAtMillis = hoursAgo(30);
        r3.timeline.add(new TimelineEntry("Reservation Submitted", r3.submittedAtMillis, null, TIMELINE_FILLED));
        r3.timeline.add(new TimelineEntry("Under Review", r3.submittedAtMillis, "Receptionist is checking availability", TIMELINE_FILLED));
        r3.timeline.add(new TimelineEntry("Alternative Schedule Proposed", hoursAgo(4),
                "Your requested dates are fully booked", TIMELINE_CURRENT));
        r3.timeline.add(new TimelineEntry("Decision Pending", 0, null, TIMELINE_UPCOMING));
        ALL.add(r3);

        // 4) Confirmed
        Reservation r4 = new Reservation();
        r4.id = nextId++;
        r4.referenceNo = "RES-20260825-007";
        r4.status = STATUS_CONFIRMED;
        r4.subStatus = SUB_NONE;
        r4.checkInMillis = daysFromNow(3);
        r4.checkOutMillis = daysFromNow(5);
        r4.adults = 2;
        r4.children = 0;
        r4.roomOption = ReservationCatalog.find(2);
        r4.quantity = 1;
        r4.specialRequest = "";
        r4.guestName = "Adrianne N. Romero";
        r4.guestEmail = "adrianneromero2005@gmail.com";
        r4.guestMobile = "0912345678";
        r4.guestAddress = "Koronadal City, South Cotabato";
        r4.purpose = "Anniversary";
        r4.submittedAtMillis = hoursAgo(96);
        r4.timeline.add(new TimelineEntry("Reservation Submitted", r4.submittedAtMillis, null, TIMELINE_FILLED));
        r4.timeline.add(new TimelineEntry("Under Review", r4.submittedAtMillis, "Receptionist is checking availability", TIMELINE_FILLED));
        r4.timeline.add(new TimelineEntry("Reservation Confirmed", hoursAgo(60),
                "Reservation confirmed by the receptionist", TIMELINE_FILLED));
        ALL.add(r4);

        // 5) Cancelled / Rejected by Receptionist
        Reservation r5 = new Reservation();
        r5.id = nextId++;
        r5.referenceNo = "RES-20260820-003";
        r5.status = STATUS_CANCELLED;
        r5.subStatus = SUB_REJECTED;
        r5.checkInMillis = daysFromNow(1);
        r5.checkOutMillis = daysFromNow(2);
        r5.adults = 2;
        r5.children = 0;
        r5.roomOption = ReservationCatalog.find(5);
        r5.quantity = 1;
        r5.specialRequest = "";
        r5.guestName = "Adrianne N. Romero";
        r5.guestEmail = "adrianneromero2005@gmail.com";
        r5.guestMobile = "0912345678";
        r5.guestAddress = "Koronadal City, South Cotabato";
        r5.purpose = "";
        r5.submittedAtMillis = hoursAgo(120);
        r5.cancelReason = "The selected room is no longer available for your requested dates.";
        r5.timeline.add(new TimelineEntry("Reservation Submitted", r5.submittedAtMillis, null, TIMELINE_FILLED));
        r5.timeline.add(new TimelineEntry("Under Review", r5.submittedAtMillis, "Receptionist is checking availability", TIMELINE_FILLED));
        r5.timeline.add(new TimelineEntry("Reservation Rejected", hoursAgo(80), r5.cancelReason, TIMELINE_FILLED));
        ALL.add(r5);

        // Matching seed notifications (newest first, mirroring each reservation's current state).
        NotificationStore.push(new NotificationStore.Notification(
                "Reservation Request Rejected",
                "Your reservation request could not be accommodated for the selected schedule.",
                "VIEW DETAILS", r5.referenceNo, hoursAgo(80)));
        NotificationStore.push(new NotificationStore.Notification(
                "Reservation Confirmed!",
                "Your reservation for " + r4.roomOption.categoryName + " · " + r4.roomOption.variantName
                        + " has been confirmed.",
                "VIEW RESERVATION", r4.referenceNo, hoursAgo(60)));
        NotificationStore.push(new NotificationStore.Notification(
                "Reservation Review Extended",
                "Our receptionist needs additional time to verify your reservation request. We'll notify you once there is an update.",
                "VIEW RESERVATION", r2.referenceNo, hoursAgo(6)));
        NotificationStore.push(new NotificationStore.Notification(
                "Alternative Schedule Available",
                "The receptionist proposed a new schedule for your reservation.",
                "REVIEW PROPOSAL", r3.referenceNo, hoursAgo(4)));
        NotificationStore.push(new NotificationStore.Notification(
                "Reservation Request Received",
                "Your reservation request " + r1.referenceNo + " has been submitted and is waiting for receptionist confirmation.",
                "VIEW RESERVATION", r1.referenceNo, r1.submittedAtMillis));
    }

    private ReservationStore() {
    }
}
