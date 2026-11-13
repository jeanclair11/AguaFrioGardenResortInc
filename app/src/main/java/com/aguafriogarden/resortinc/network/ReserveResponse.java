package com.aguafriogarden.resortinc.network;

/** Response body for POST /api/booking/reserve and POST /booking/reservations/{id}/payment. */
public class ReserveResponse {
    public int reservation_id;
    public String booking_reference;
    public double subtotal;
    public double amount_due;
    /** The status of the record just created/paid — a booking's Pending status after payment. */
    public StatusDetail status_detail;
    /** True once the payment has been recorded against the request (it is then a booking). */
    public boolean payment_submitted;
}
