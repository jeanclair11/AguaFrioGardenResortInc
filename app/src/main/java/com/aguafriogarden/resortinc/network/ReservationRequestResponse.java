package com.aguafriogarden.resortinc.network;

/** Response body for POST /api/booking/reservations — creates a reservation request with no
 *  payment collected yet (see BookingApi#createReservation). */
public class ReservationRequestResponse {
    public int advance_reservation_id;
    public String booking_reference;
    public double subtotal;
    public StatusDetail status_detail;
}
