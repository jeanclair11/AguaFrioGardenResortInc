package com.aguafriogarden.resortinc;

import android.content.Context;
import android.text.TextUtils;

import com.aguafriogarden.resortinc.network.PaymentVerificationSummary;

/**
 * How a booking's payment verification reads to the guest, shared by the My Bookings card, the
 * booking's details and the correction screen so the wording and colours cannot drift apart. The
 * status label and the rejection reason come from the backend; only the sentence that explains a
 * rejection is composed here.
 */
final class PaymentVerificationUi {

    private PaymentVerificationUi() {
    }

    /** The status as the backend words it ("Rejected", "Pending Reverification", ...). */
    static String label(PaymentVerificationSummary pv) {
        if (!TextUtils.isEmpty(pv.label)) {
            return pv.label;
        }
        return pv.status == null ? "" : pv.status.replace('_', ' ');
    }

    /** "Payment: Rejected" — the line under the card / at the top of the details block. */
    static String chip(Context context, PaymentVerificationSummary pv) {
        return context.getString(R.string.payment_status_chip, label(pv));
    }

    /** Theme colour attribute: red for a rejection, green once verified, the accent while waiting. */
    static int colorAttr(PaymentVerificationSummary pv) {
        if (pv.isRejected()) {
            return R.attr.errorText;
        }
        return pv.isVerified() ? R.attr.successText : R.attr.accentPrimary;
    }

    /**
     * Why it was rejected, as the full sentence shown at the top of the correction screen, e.g.
     * "Your payment submission was rejected because the reference number is incorrect. Please
     * correct the information and resubmit." A reason without its own sentence ("Other") is quoted
     * from the backend's label instead.
     */
    static String rejectionMessage(Context context, PaymentVerificationSummary pv) {
        int reasonRes = reasonSentenceRes(pv.rejection_reason);
        if (reasonRes != 0) {
            return context.getString(R.string.payment_rejected_message, context.getString(reasonRes));
        }
        if (!TextUtils.isEmpty(pv.rejection_reason_label)) {
            return context.getString(R.string.payment_rejected_message_other, pv.rejection_reason_label);
        }
        return context.getString(R.string.payment_rejected_message_generic);
    }

    /** "Reason: Incorrect Reference Number", or null when the backend sent none. */
    static String reasonLine(Context context, PaymentVerificationSummary pv) {
        return TextUtils.isEmpty(pv.rejection_reason_label) ? null
                : context.getString(R.string.payment_reason_line, pv.rejection_reason_label);
    }

    /** "Receptionist's note: ...", or null when the receptionist wrote none. */
    static String remarksLine(Context context, PaymentVerificationSummary pv) {
        return TextUtils.isEmpty(pv.rejection_remarks) ? null
                : context.getString(R.string.payment_remarks_line, pv.rejection_remarks.trim());
    }

    /** The reason and the note together, one per line, for the compact card; null when neither. */
    static String reasonBlock(Context context, PaymentVerificationSummary pv) {
        String reason = reasonLine(context, pv);
        String remarks = remarksLine(context, pv);
        if (reason == null) {
            return remarks;
        }
        return remarks == null ? reason : reason + "\n" + remarks;
    }

    private static int reasonSentenceRes(String code) {
        if (code == null) {
            return 0;
        }
        switch (code) {
            case "incorrect_amount":
                return R.string.payment_reason_incorrect_amount;
            case "incorrect_reference":
                return R.string.payment_reason_incorrect_reference;
            case "invalid_proof":
                return R.string.payment_reason_invalid_proof;
            case "payment_not_found":
                return R.string.payment_reason_payment_not_found;
            default:
                return 0;
        }
    }

    /** What changes in a record's payment verification that a list must be rebuilt to show. */
    static String signature(PaymentVerificationSummary pv) {
        if (pv == null) {
            return "-";
        }
        return pv.status + "/" + pv.needs_correction + "/" + pv.rejection_reason + "/"
                + pv.rejection_remarks + "/" + pv.submitted_amount + "/" + pv.reference_number + "/"
                + pv.proof_url;
    }
}
