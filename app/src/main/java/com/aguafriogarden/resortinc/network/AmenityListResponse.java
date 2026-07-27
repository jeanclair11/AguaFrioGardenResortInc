package com.aguafriogarden.resortinc.network;

import java.util.List;

/** Response body for GET /api/booking/amenities. */
public class AmenityListResponse {
    public List<AmenityAvailability> amenities;
}
