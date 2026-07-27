package com.aguafriogarden.resortinc.network;

import java.util.List;

/** Response body for GET /api/booking/my-bookings. */
public class MyBookingsResponse {
    public List<ReservationSummary> reservations;
}
