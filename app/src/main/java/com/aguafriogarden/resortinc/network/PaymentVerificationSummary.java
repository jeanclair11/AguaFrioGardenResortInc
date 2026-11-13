package com.aguafriogarden.resortinc.network;

import java.util.List;
import java.util.Objects;

/**
 * Where the receptionist's check of a booking's submitted payment stands (the
 * {@code payment_verification} object of GET /booking/my-bookings), and what to correct when it was
 * rejected. Null on a record with nothing to verify, and from servers that predate it.
 *
 * <p>The poll feed (GET /booking/my-statuses) carries only {@link #status}, {@link #label} and
 * {@link #needs_correction} of it; the rest arrives with the full list.
 *
 * <p>A rejected or pending payment is never a verified one: the backend takes a rejected
 * submission's amount back off the booking and counts a correction only once the receptionist
 * verifies it, so the booking's paid amount and balance always follow the verified payment.
 */
public class PaymentVerificationSummary {

    /** The guest's first submission, not yet looked at. */
    public static final String STATUS_PENDING_VERIFICATION = "pending_verification";
    /** The receptionist rejected it: the guest corrects and resubmits. */
    public static final String STATUS_REJECTED = "rejected";
    /** The guest's correction, waiting for the receptionist. */
    public static final String STATUS_PENDING_REVERIFICATION = "pending_reverification";
    public static final String STATUS_VERIFIED = "verified";

    public static final String EVENT_REJECTED = "rejected";

    public int payment_id;
    /** One of the STATUS_ constants. */
    public String status;
    /** The backend's wording of {@link #status} ("Rejected", "Pending Reverification", ...). */
    public String label;
    public boolean needs_correction;
    /** Code (incorrect_amount, incorrect_reference, invalid_proof, payment_not_found, other). */
    public String rejection_reason;
    public String rejection_reason_label;
    /** The receptionist's own note; null when none was written. */
    public String rejection_remarks;
    public String rejected_at;
    public String resubmitted_at;
    public String verified_at;
    /** The amount the guest says they paid for the submission now on file: their correction while it
     *  awaits verification, otherwise the amount recorded on the payment (0 once it was rejected;
     *  use {@link #originalAmount()} for what was rejected). */
    public double submitted_amount;
    public String reference_number;
    /** Absolute URL of the proof-of-payment screenshot now on file; null when there is none. */
    public String proof_url;
    /** Every submission, rejection, correction and verification of this payment, oldest first. */
    public List<HistoryEntry> history;

    /** One line of {@link #history}: the figures as they were at that moment. */
    public static class HistoryEntry {
        public String event;
        public String label;
        /** Null when the event carries no amount. */
        public Double amount;
        public String reference_number;
        public String proof_url;
        public String reason;
        public String reason_label;
        public String remarks;
        /** "guest" or "receptionist". */
        public String actor_type;
        public String at;
    }

    public boolean isRejected() {
        return STATUS_REJECTED.equals(status);
    }

    public boolean isPendingReverification() {
        return STATUS_PENDING_REVERIFICATION.equals(status);
    }

    public boolean isVerified() {
        return STATUS_VERIFIED.equals(status);
    }

    /** The guest may correct this payment: it was rejected and nothing newer is on file. */
    public boolean canCorrect() {
        return isRejected() || (needs_correction && !isPendingReverification() && !isVerified());
    }

    /** True while the payment on file was rejected or is a correction nobody has verified yet: it
     *  is not money received, so no screen may present it as "Paid". (The backend already takes
     *  such a payment out of the booking's paid amount; this keeps the app from ever showing it.) */
    public boolean isNotReceived() {
        return isRejected() || isPendingReverification();
    }

    /**
     * The amount of the submission the receptionist rejected, for the guest to compare with. The
     * payment's own amount is back to 0 after a rejection, so it is read from the rejection in
     * {@link #history}; {@link #submitted_amount} is the fallback.
     */
    public double originalAmount() {
        if (history != null) {
            for (int i = history.size() - 1; i >= 0; i--) {
                HistoryEntry entry = history.get(i);
                if (EVENT_REJECTED.equals(entry.event) && entry.amount != null) {
                    return entry.amount;
                }
            }
        }
        return submitted_amount;
    }

    /** Whether {@code other} is in the same state — what a poll compares to know the guest's view
     *  of the payment is out of date. Null equals "nothing to verify". */
    public static boolean sameState(PaymentVerificationSummary a, PaymentVerificationSummary b) {
        if (a == null || b == null) {
            return a == b;
        }
        return Objects.equals(a.status, b.status) && a.needs_correction == b.needs_correction;
    }
}
