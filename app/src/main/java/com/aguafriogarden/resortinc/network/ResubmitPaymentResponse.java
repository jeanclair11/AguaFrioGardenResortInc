package com.aguafriogarden.resortinc.network;

/** Response body for POST /booking/bookings/{id}/payment/resubmit: the same booking, its payment
 *  now Pending Reverification. Nothing new was created. */
public class ResubmitPaymentResponse {
    /** The booking's own id (the one the request was sent for). */
    public int reservation_id;
    public String booking_reference;
    public String status;
    public StatusDetail status_detail;
    public PaymentVerificationSummary payment_verification;
}
