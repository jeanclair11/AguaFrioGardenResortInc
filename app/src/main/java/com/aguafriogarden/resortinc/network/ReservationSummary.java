package com.aguafriogarden.resortinc.network;

import java.util.List;

/** One of the logged-in guest's reservations, from GET /api/booking/my-bookings. */
public class ReservationSummary {
    /** "reservation" (real, already promoted/booked) or "advance_reservation" (still Pending or
     *  cancelled before ever being paid) — see Api\BookingController::summarizeReservation vs
     *  summarizeAdvanceReservation on the backend. Determines which id space {@link #id} is in. */
    public String kind;
    /** For kind "advance_reservation" this is the AdvanceReservation id (what Pay Now/Cancel/
     *  status-refresh expect); for kind "reservation" this is an online_bookingID instead — a
     *  different id space those endpoints don't accept. */
    public int id;
    public String booking_reference;
    public String service_type;
    public String item_name;
    public String room_type_name;
    public String check_in;
    public String check_out;
    public int adults;
    public int children;
    public int current_step;
    public boolean cancelled;
    public int cancelled_from_step;
    public String cancel_reason;
    public String cancelled_at;
    /** "guest" or "receptionist" — null unless cancelled, and only ever set for kind
     *  "advance_reservation" (the cancelled_by column doesn't exist for a promoted Reservation). */
    public String cancelled_by;
    public String guest_name;
    public String guest_email;
    public String guest_mobile;
    public List<String> service_lines;
    public double subtotal;
    public double amount_paid;
    public double remaining_balance;
    public String payment_status;
    public String created_at;
    /** Only meaningful for kind "advance_reservation" — null once promoted or if never set. */
    public String payment_deadline;
}
