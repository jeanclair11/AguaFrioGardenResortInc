package com.aguafriogarden.resortinc.network;

import java.util.List;

/** Response body for GET /api/booking/rooms. */
public class RoomListResponse {
    public List<RoomSummary> rooms;
}
