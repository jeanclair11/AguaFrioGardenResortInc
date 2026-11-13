package com.aguafriogarden.resortinc.network;

import java.util.List;

/** One of the logged-in guest's reservations, from GET /api/booking/my-bookings. */
public class ReservationSummary {
    /** {@link #kind} of a booking (a paid reservation; reference AFR-xxxxxx). */
    public static final String KIND_BOOKING = "reservation";
    /** {@link #kind} of a reservation request that has not been paid (reference REQ-xxxxxx). */
    public static final String KIND_REQUEST = "advance_reservation";

    /** {@link #KIND_BOOKING} or {@link #KIND_REQUEST} — see Api\BookingController::summarizeReservation
     *  vs summarizeAdvanceReservation on the backend. Determines which id space {@link #id} is in. */
    public String kind;
    /** The record's status exactly as the backend reports it (label, colour, definition). Every
     *  status shown or branched on in the app comes from here — never from dates, flags or steps. */
    public StatusDetail status;
    /** For kind "advance_reservation" this is the AdvanceReservation id (what Pay Now/Cancel/
     *  status-refresh expect); for kind "reservation" this is an online_bookingID instead — a
     *  different id space those endpoints don't accept. */
    public int id;
    public String booking_reference;
    public String service_type;
    /** Every facility kind in the record ("hotel"/"cottage"/"ktv"/"hall") — several for a
     *  multi-facility booking. Null from servers that predate it. */
    public List<String> facilities;
    public String item_name;
    public String room_type_name;
    public String check_in;
    public String check_out;
    public int adults;
    public int children;
    public String cancel_reason;
    public String cancelled_at;
    /** "guest" or "receptionist" — null unless cancelled, and only ever set for a
     *  {@link #KIND_REQUEST} (the cancelled_by column doesn't exist for a promoted Reservation). */
    public String cancelled_by;
    public String guest_name;
    public String guest_email;
    public String guest_mobile;
    public List<String> service_lines;
    public double subtotal;
    public double amount_paid;
    /** Excess the guest sent over what was due (summed over the payments); 0 when none or from
     *  servers that predate it. */
    public double amount_change;
    public double remaining_balance;
    public String payment_status;
    /** Payments recorded against this booking's bill (Statement of Account). Null from servers
     *  that predate it; always empty for a not-yet-paid advance reservation. */
    public List<PaymentLine> payments;

    public static class PaymentLine {
        public String date;
        public String time;
        /** "gcash", "cash", ... */
        public String method;
        public String reference_number;
        public double amount;
        /** Excess over what was due on this payment; 0 when none. */
        public double change;
        /** "pending" (awaiting staff verification), "verified" or "cancelled" — or, once the
         *  receptionist has looked at it, "rejected" / "pending_reverification". */
        public String status;
    }
    /** The receptionist's verification of the payment submitted with this booking (rejected,
     *  waiting for a correction, ...); null when there is nothing to verify or from servers that
     *  predate it. Only ever set for a {@link #KIND_BOOKING}. */
    public PaymentVerificationSummary payment_verification;
    public String created_at;
    /** Only meaningful for a {@link #KIND_REQUEST} — null once promoted or if never set. */
    public String payment_deadline;

    public boolean isBooking() {
        return KIND_BOOKING.equals(kind);
    }

    public boolean isRequest() {
        return KIND_REQUEST.equals(kind);
    }

    /** Pay and Cancel exist only for an unpaid request that is still pending. */
    public boolean canPayOrCancel() {
        return isRequest() && status != null && status.hasKey(StatusDetail.KEY_PENDING);
    }
}
