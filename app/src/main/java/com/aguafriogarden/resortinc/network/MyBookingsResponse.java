package com.aguafriogarden.resortinc.network;

import java.util.List;

/** Response body for GET /api/booking/my-bookings — every reservation/booking the logged-in
 *  guest has, real or still-pending, newest first. */
public class MyBookingsResponse {
    public List<ReservationSummary> reservations;
}
