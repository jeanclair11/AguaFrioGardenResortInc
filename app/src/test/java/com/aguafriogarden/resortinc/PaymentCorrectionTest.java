package com.aguafriogarden.resortinc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.aguafriogarden.resortinc.network.MyBookingsResponse;
import com.aguafriogarden.resortinc.network.MyStatusesResponse;
import com.aguafriogarden.resortinc.network.PaymentVerificationSummary;
import com.aguafriogarden.resortinc.network.ReservationSummary;
import com.aguafriogarden.resortinc.network.ResubmitPaymentResponse;
import com.google.gson.Gson;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * "Reject &amp; Request Correction", guest side: how the backend's payment_verification JSON maps
 * onto the models, who may correct, what a corrected submission must contain, and when a status
 * poll must refetch because the receptionist acted on the payment. The JSON below has the shape
 * Api\BookingController / PaymentVerification::summaryForApp return.
 */
public class PaymentCorrectionTest {

    private static final Gson GSON = new Gson();

    private static final String BOOKED = "{\"kind\":\"booking\",\"key\":\"pending\",\"label\":\"Pending\","
            + "\"color\":\"#f5a623\",\"definition\":\"Paid, awaiting confirmation.\"}";

    private static String verificationJson(String status, String label, boolean needsCorrection,
            double submittedAmount, String history) {
        return "{\"payment_id\":7,\"status\":\"" + status + "\",\"label\":\"" + label + "\","
                + "\"needs_correction\":" + needsCorrection + ","
                + "\"rejection_reason\":\"incorrect_reference\","
                + "\"rejection_reason_label\":\"Incorrect Reference Number\","
                + "\"rejection_remarks\":\"Reference does not match the screenshot.\","
                + "\"submitted_amount\":" + submittedAmount + ","
                + "\"reference_number\":\"1234567890123\","
                + "\"proof_url\":\"https://example.test/storage/payments/proofs/a.jpg\","
                + "\"history\":" + history + "}";
    }

    // The booking BK-001 with the first submission rejected: the payment's own amount is back to 0.
    private static final String REJECTED_HISTORY = "["
            + "{\"event\":\"submitted\",\"label\":\"Payment submitted\",\"amount\":1500.0,"
            + "\"reference_number\":\"1234567890123\",\"actor_type\":\"guest\"},"
            + "{\"event\":\"rejected\",\"label\":\"Payment rejected\",\"amount\":1500.0,"
            + "\"reference_number\":\"1234567890123\",\"reason\":\"incorrect_reference\","
            + "\"reason_label\":\"Incorrect Reference Number\",\"actor_type\":\"receptionist\"}]";

    private static ReservationSummary booking(String verificationJsonOrNull) {
        String json = "{\"reservations\":[{\"kind\":\"reservation\",\"id\":1,\"booking_reference\":\"AFR-000001\","
                + "\"status\":" + BOOKED + ",\"subtotal\":5000.0,\"amount_paid\":0.0,"
                + "\"payment_verification\":" + verificationJsonOrNull + "}]}";
        return GSON.fromJson(json, MyBookingsResponse.class).reservations.get(0);
    }

    private static ReservationSummary rejectedBooking() {
        return booking(verificationJson("rejected", "Rejected", true, 0, REJECTED_HISTORY));
    }

    private static MyStatusesResponse.Row feedRow(int id, String verificationJsonOrNull) {
        String json = "{\"kind\":\"reservation\",\"id\":" + id + ",\"booking_reference\":\"AFR-00000" + id + "\","
                + "\"status\":" + BOOKED + ",\"payment_verification\":" + verificationJsonOrNull + "}";
        return GSON.fromJson(json, MyStatusesResponse.Row.class);
    }

    private static String lightVerification(String status, String label, boolean needsCorrection) {
        return "{\"status\":\"" + status + "\",\"label\":\"" + label + "\",\"needs_correction\":" + needsCorrection + "}";
    }

    // ---- The JSON maps onto the models --------------------------------------------------------

    @Test
    public void aRejectedPaymentShowsItsReasonAndOriginalValues() {
        ReservationSummary summary = rejectedBooking();
        PaymentVerificationSummary pv = summary.payment_verification;

        assertNotNull(pv);
        assertTrue(pv.isRejected());
        assertEquals("Rejected", pv.label);
        assertEquals("Incorrect Reference Number", pv.rejection_reason_label);
        assertEquals("Reference does not match the screenshot.", pv.rejection_remarks);
        assertEquals("1234567890123", pv.reference_number);
        assertEquals(2, pv.history.size());
        // The payment's own amount is 0 once rejected; what was rejected is read from the history.
        assertEquals(1500.0, pv.originalAmount(), 0.001);
    }

    @Test
    public void aBookingWithNothingToVerifyHasNoVerification() {
        assertNull(booking("null").payment_verification);
        // And from a server that predates the feature, the field is simply absent.
        ReservationSummary old = GSON.fromJson("{\"kind\":\"reservation\",\"id\":2}", ReservationSummary.class);
        assertNull(old.payment_verification);
        assertFalse(PaymentCorrectionRules.canCorrect(old));
    }

    @Test
    public void theResubmitResponseCarriesTheSameBookingAndPendingReverification() {
        String json = "{\"reservation_id\":1,\"booking_reference\":\"AFR-000001\",\"status\":\"pending\","
                + "\"status_detail\":" + BOOKED + ",\"payment_verification\":"
                + verificationJson("pending_reverification", "Pending Reverification", false, 1500.0,
                REJECTED_HISTORY) + "}";
        ResubmitPaymentResponse response = GSON.fromJson(json, ResubmitPaymentResponse.class);

        assertEquals(1, response.reservation_id);
        assertEquals("AFR-000001", response.booking_reference);
        assertTrue(response.payment_verification.isPendingReverification());
        assertEquals(1500.0, response.payment_verification.submitted_amount, 0.001);
        assertTrue(response.payment_verification.isNotReceived());
    }

    // ---- Who may correct ----------------------------------------------------------------------

    @Test
    public void onlyARejectedPaymentOnABookingCanBeCorrected() {
        assertTrue(PaymentCorrectionRules.canCorrect(rejectedBooking()));

        assertFalse(PaymentCorrectionRules.canCorrect(booking(
                verificationJson("pending_reverification", "Pending Reverification", false, 1500, REJECTED_HISTORY))));
        assertFalse(PaymentCorrectionRules.canCorrect(booking(
                verificationJson("verified", "Verified", false, 1500, REJECTED_HISTORY))));
        assertFalse(PaymentCorrectionRules.canCorrect(booking(
                verificationJson("pending_verification", "Pending Verification", false, 0, "[]"))));
        assertFalse(PaymentCorrectionRules.canCorrect(booking("null")));
        assertFalse(PaymentCorrectionRules.canCorrect(null));
    }

    @Test
    public void aReservationRequestCanNeverBeCorrected() {
        ReservationSummary request = rejectedBooking();
        request.kind = ReservationSummary.KIND_REQUEST;

        assertFalse(PaymentCorrectionRules.canCorrect(request));
    }

    @Test
    public void rejectedAgainAllowsAnotherCorrection() {
        // Rejected -> resubmitted (pending reverification, no button) -> rejected again (button back).
        assertFalse(PaymentCorrectionRules.canCorrect(booking(
                verificationJson("pending_reverification", "Pending Reverification", false, 1500, REJECTED_HISTORY))));
        assertTrue(PaymentCorrectionRules.canCorrect(booking(
                verificationJson("rejected", "Rejected", true, 0, REJECTED_HISTORY))));
    }

    @Test
    public void aRejectedOrReverifyingPaymentIsNeverMoneyReceived() {
        assertTrue(rejectedBooking().payment_verification.isNotReceived());
        assertTrue(booking(verificationJson("pending_reverification", "Pending Reverification", false, 1500, "[]"))
                .payment_verification.isNotReceived());
        assertFalse(booking(verificationJson("verified", "Verified", false, 1500, "[]"))
                .payment_verification.isNotReceived());
        // The first submission, before anyone has looked at it, is counted by the backend as before.
        assertFalse(booking(verificationJson("pending_verification", "Pending Verification", false, 1500, "[]"))
                .payment_verification.isNotReceived());
    }

    // ---- Validating the corrected details -----------------------------------------------------

    @Test
    public void aCompleteCorrectionIsAccepted() {
        assertEquals(PaymentCorrectionRules.Problem.NONE,
                PaymentCorrectionRules.validate("1500.00", "6543210987654", true));
        assertEquals(PaymentCorrectionRules.Problem.NONE,
                PaymentCorrectionRules.validate(" 1500 ", " 6543210987654 ", true));
    }

    @Test
    public void theAmountMustBeARealPositiveAmount() {
        assertEquals(PaymentCorrectionRules.Problem.AMOUNT_REQUIRED,
                PaymentCorrectionRules.validate("", "6543210987654", true));
        assertEquals(PaymentCorrectionRules.Problem.AMOUNT_REQUIRED,
                PaymentCorrectionRules.validate("0", "6543210987654", true));
        assertEquals(PaymentCorrectionRules.Problem.AMOUNT_REQUIRED,
                PaymentCorrectionRules.validate("abc", "6543210987654", true));
        assertEquals(PaymentCorrectionRules.Problem.AMOUNT_REQUIRED,
                PaymentCorrectionRules.validate(null, "6543210987654", true));
        assertEquals(PaymentCorrectionRules.Problem.AMOUNT_TOO_HIGH,
                PaymentCorrectionRules.validate("10000000.01", "6543210987654", true));
    }

    @Test
    public void theReferenceNumberIsRequiredAndThirteenDigits() {
        assertEquals(PaymentCorrectionRules.Problem.REFERENCE_REQUIRED,
                PaymentCorrectionRules.validate("1500", "", true));
        assertEquals(PaymentCorrectionRules.Problem.REFERENCE_REQUIRED,
                PaymentCorrectionRules.validate("1500", "   ", true));
        assertEquals(PaymentCorrectionRules.Problem.REFERENCE_REQUIRED,
                PaymentCorrectionRules.validate("1500", null, true));
        assertEquals(PaymentCorrectionRules.Problem.REFERENCE_LENGTH,
                PaymentCorrectionRules.validate("1500", "12345", true));
        assertEquals(PaymentCorrectionRules.Problem.REFERENCE_LENGTH,
                PaymentCorrectionRules.validate("1500", "12345678901234", true));
        assertEquals(PaymentCorrectionRules.Problem.REFERENCE_LENGTH,
                PaymentCorrectionRules.validate("1500", "12345678901ab", true));
    }

    @Test
    public void aProofOfPaymentMustBeOnFileAfterTheCorrection() {
        assertEquals(PaymentCorrectionRules.Problem.PROOF_REQUIRED,
                PaymentCorrectionRules.validate("1500", "6543210987654", false));
    }

    @Test
    public void problemsAreReportedInTheOrderOfTheForm() {
        assertEquals(PaymentCorrectionRules.Problem.AMOUNT_REQUIRED, PaymentCorrectionRules.validate("", "", false));
        assertEquals(PaymentCorrectionRules.Problem.REFERENCE_REQUIRED, PaymentCorrectionRules.validate("1500", "", false));
        assertEquals(PaymentCorrectionRules.Problem.PROOF_REQUIRED, PaymentCorrectionRules.validate("1500", "6543210987654", false));
    }

    @Test
    public void payingMoreThanTheBookingTotalAsksForConfirmation() {
        assertFalse(PaymentCorrectionRules.needsOverpaymentConfirmation(5000, 5000));
        assertFalse(PaymentCorrectionRules.needsOverpaymentConfirmation(1500, 5000));
        assertTrue(PaymentCorrectionRules.needsOverpaymentConfirmation(5000.50, 5000));
        // No known total, nothing to compare with.
        assertFalse(PaymentCorrectionRules.needsOverpaymentConfirmation(9999, 0));
    }

    @Test
    public void aProofMustBeNonEmptyAndAtMostTenMegabytes() {
        assertTrue(PaymentCorrectionRules.proofSizeAllowed(1));
        assertTrue(PaymentCorrectionRules.proofSizeAllowed(10L * 1024 * 1024));
        assertFalse(PaymentCorrectionRules.proofSizeAllowed(10L * 1024 * 1024 + 1));
        assertFalse(PaymentCorrectionRules.proofSizeAllowed(0));
    }

    // ---- The live feed notices the receptionist acting on the payment -------------------------

    @Test
    public void aRejectionRefetchesEvenThoughTheBookingStaysPending() {
        ReservationSummary mine = booking(verificationJson("pending_verification", "Pending Verification", false, 1500, "[]"));
        List<ReservationSummary> rows = new ArrayList<>(Arrays.asList(mine));

        FeedDiff diff = FeedDiff.apply(rows, Arrays.asList(feedRow(1, lightVerification("rejected", "Rejected", true))));

        assertTrue(diff.needsRefetch);
        // The status itself did not change, so nothing is patched in place: the refetch brings the
        // reason and amounts.
        assertFalse(diff.displayChanged);
    }

    @Test
    public void theReceptionistsVerdictOnACorrectionRefetches() {
        List<ReservationSummary> rows = new ArrayList<>(Arrays.asList(booking(
                verificationJson("pending_reverification", "Pending Reverification", false, 1500, REJECTED_HISTORY))));

        assertTrue(FeedDiff.apply(rows, Arrays.asList(
                feedRow(1, lightVerification("verified", "Verified", false)))).needsRefetch);
        assertTrue(FeedDiff.apply(rows, Arrays.asList(
                feedRow(1, lightVerification("rejected", "Rejected", true)))).needsRefetch);
    }

    @Test
    public void verificationBecomingAbsentRefetchesToo() {
        // The booking moved on (confirmed / cancelled): there is nothing to verify any more.
        List<ReservationSummary> rows = new ArrayList<>(Arrays.asList(rejectedBooking()));

        assertTrue(FeedDiff.apply(rows, Arrays.asList(feedRow(1, "null"))).needsRefetch);
    }

    @Test
    public void anUnchangedVerificationDoesNotRefetch() {
        List<ReservationSummary> rows = new ArrayList<>(Arrays.asList(rejectedBooking()));
        FeedDiff diff = FeedDiff.apply(rows, Arrays.asList(feedRow(1, lightVerification("rejected", "Rejected", true))));

        assertFalse(diff.needsRefetch);
        assertFalse(diff.displayChanged);

        // Likewise a booking with nothing to verify, polled against a feed that carries none.
        List<ReservationSummary> plain = new ArrayList<>(Arrays.asList(booking("null")));
        assertFalse(FeedDiff.apply(plain, Arrays.asList(feedRow(1, "null"))).needsRefetch);
    }

    @Test
    public void theHistoryListIsRebuiltWhenTheVerificationChanges() {
        String rejected = PaymentVerificationUi.signature(rejectedBooking().payment_verification);
        String resubmitted = PaymentVerificationUi.signature(booking(
                verificationJson("pending_reverification", "Pending Reverification", false, 1500, REJECTED_HISTORY))
                .payment_verification);

        assertFalse(rejected.equals(resubmitted));
        assertEquals("-", PaymentVerificationUi.signature(null));
    }
}
