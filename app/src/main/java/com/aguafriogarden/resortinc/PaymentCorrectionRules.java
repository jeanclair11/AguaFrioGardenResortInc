package com.aguafriogarden.resortinc;

import com.aguafriogarden.resortinc.network.ReservationSummary;

/**
 * The rules of correcting a payment the receptionist rejected, kept free of Android so they can be
 * tested directly: who may correct, what a corrected submission must contain, and what the guest
 * is asked to confirm. The server checks the same things again (13-digit GCash reference, an image
 * of at most 10 MB, a positive amount) and refuses a payment that is not waiting for a correction.
 */
final class PaymentCorrectionRules {

    /** GCash reference numbers are exactly this many digits (the backend's {@code digits:13}). */
    static final int REFERENCE_LENGTH = 13;
    /** The backend's upload limit for the proof of payment ({@code max:10240} KB). */
    static final long MAX_PROOF_BYTES = 10L * 1024 * 1024;
    /** The backend's ceiling for an amount ({@code max:10000000}). */
    static final double MAX_AMOUNT = 10_000_000d;
    private static final double AMOUNT_TOLERANCE = 0.005;

    /** What is wrong with a corrected submission, in the order the form checks it. */
    enum Problem {
        NONE,
        AMOUNT_REQUIRED,
        AMOUNT_TOO_HIGH,
        REFERENCE_REQUIRED,
        REFERENCE_LENGTH,
        PROOF_REQUIRED
    }

    private PaymentCorrectionRules() {
    }

    /** "Correct &amp; Resubmit" exists only on a booking whose payment was rejected and has not been
     *  corrected since — never on a reservation request, a payment awaiting verification, or a
     *  verified one. */
    static boolean canCorrect(ReservationSummary summary) {
        return summary != null && summary.isBooking() && summary.payment_verification != null
                && summary.payment_verification.canCorrect();
    }

    /** The Amount field as a number; -1 when it is empty or not a number. */
    static double parseAmount(String text) {
        if (text == null) {
            return -1;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The first thing wrong with the corrected details, or {@link Problem#NONE}. {@code hasProof} is
     * whether a screenshot will be on file after the submission: the guest kept the one already
     * there, or picked a new one.
     */
    static Problem validate(String amountText, String referenceNumber, boolean hasProof) {
        double amount = parseAmount(amountText);
        if (amount <= 0) {
            return Problem.AMOUNT_REQUIRED;
        }
        if (amount > MAX_AMOUNT) {
            return Problem.AMOUNT_TOO_HIGH;
        }
        String reference = referenceNumber == null ? "" : referenceNumber.trim();
        if (reference.isEmpty()) {
            return Problem.REFERENCE_REQUIRED;
        }
        if (reference.length() != REFERENCE_LENGTH || !isDigits(reference)) {
            return Problem.REFERENCE_LENGTH;
        }
        if (!hasProof) {
            return Problem.PROOF_REQUIRED;
        }
        return Problem.NONE;
    }

    /** More than the booking's total: the guest is asked to confirm before it is sent (the excess
     *  is recorded as an excess payment, as on every other Payment screen). */
    static boolean needsOverpaymentConfirmation(double amount, double bookingTotal) {
        return bookingTotal > 0 && amount > bookingTotal + AMOUNT_TOLERANCE;
    }

    /** Whether a proof of this size can be uploaded. */
    static boolean proofSizeAllowed(long bytes) {
        return bytes > 0 && bytes <= MAX_PROOF_BYTES;
    }

    private static boolean isDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
