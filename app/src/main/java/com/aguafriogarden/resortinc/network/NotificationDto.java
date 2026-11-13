package com.aguafriogarden.resortinc.network;

/** One guest notification as stored by the backend (Api\NotificationController). */
public class NotificationDto {
    public long id;
    public String title;
    public String message;
    /** "chat", "reservation", "booking", "payment", "feedback", or null for notifications raised by
     *  staff actions on the website that carry no category. */
    public String type;
    /** What happened ("payment_rejected", "booking_confirmed", ...); null for older alerts and the
     *  ones the app uploaded itself. */
    public String event;
    /** The kind of record the alert is about, in the same words as {@link ReservationSummary#kind}
     *  ("reservation" = a booking, "advance_reservation" = a reservation request); null when it is
     *  about none (a feedback reply, a chat message). A request that has since been paid is already
     *  pointed at its booking by the backend. */
    public String target_kind;
    /** The record's id in that kind's id space; 0 when there is no target. */
    public long target_id;
    /** Set for alerts the app uploaded itself; lets a lost upload response be matched back up. */
    public String client_uuid;
    public boolean is_read;
    public boolean is_archived;
    public long created_at_ms;
}
