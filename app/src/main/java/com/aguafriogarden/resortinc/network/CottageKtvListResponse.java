package com.aguafriogarden.resortinc.network;

import java.util.List;

/** Response body for GET /api/booking/cottages-ktv. */
public class CottageKtvListResponse {
    public List<CottageKtvOption> cottages;
    public List<CottageKtvOption> ktv_rooms;
}
