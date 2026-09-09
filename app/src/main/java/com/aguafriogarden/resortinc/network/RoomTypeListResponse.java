package com.aguafriogarden.resortinc.network;

import java.util.List;

/** Response body for the public GET /api/booking/room-types. */
public class RoomTypeListResponse {
    public List<RoomTypeAvailability> room_types;
}
