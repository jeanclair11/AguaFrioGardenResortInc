package com.aguafriogarden.resortinc.network;

import java.util.List;

/** Response body for POST /api/booking/availability. */
public class AvailabilityResponse {
    public String check_in;
    public String check_out;
    public int adults;
    public int children;
    public int nights;
    public List<RoomTypeAvailability> room_types;
}
