package com.aguafriogarden.resortinc.network;

/** Response body for GET /api/booking/reservations/{id} — lets the app pick up a
 *  receptionist-side change (e.g. Reservation Management's own Cancel) the guest's device could
 *  otherwise never find out about, since there's no push notification system yet. */
public class ReservationStatusResponse {
    public String status;
    public String cancel_reason;
    public String cancelled_at;
    /** "guest" or "receptionist" — null unless status is "cancelled". */
    public String cancelled_by;
}
