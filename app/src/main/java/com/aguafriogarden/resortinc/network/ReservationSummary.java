package com.aguafriogarden.resortinc.network;

import java.util.List;

/** One of the logged-in guest's reservations, from GET /api/booking/my-bookings. */
public class ReservationSummary {
    public int id;
    public String booking_reference;
    public String service_type;
    public String item_name;
    public String check_in;
    public String check_out;
    public int adults;
    public int children;
    public int current_step;
    public boolean cancelled;
    public String guest_name;
    public String guest_email;
    public String guest_mobile;
    public List<String> service_lines;
    public double subtotal;
    public double amount_paid;
    public double remaining_balance;
    public String payment_status;
}
