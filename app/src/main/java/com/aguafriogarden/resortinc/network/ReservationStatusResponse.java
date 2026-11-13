package com.aguafriogarden.resortinc.network;

/** Response body for GET /api/booking/reservations/{id} — the current status of one reservation
 *  request. For a request that has since been paid this is the status of the BOOKING it became
 *  ({@code status_detail.kind} is then "booking"). */
public class ReservationStatusResponse {
    public StatusDetail status_detail;
    public String live_version;
    public String cancel_reason;
    public String cancelled_at;
    /** "guest" or "receptionist" — null unless the record is cancelled. */
    public String cancelled_by;
}
