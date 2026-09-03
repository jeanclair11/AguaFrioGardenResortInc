package com.aguafriogarden.resortinc.network;

/** Response body for POST /api/booking/reserve. */
public class ReserveResponse {
    public int reservation_id;
    public String booking_reference;
    public double subtotal;
    public double amount_due;
    public String status;
}
