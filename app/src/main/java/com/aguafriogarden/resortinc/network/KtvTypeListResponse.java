package com.aguafriogarden.resortinc.network;

import java.util.List;

/** Response body for GET /api/booking/ktv-types (public, live-availability). */
public class KtvTypeListResponse {
    public List<CottageKtvOption> ktv_rooms;
}
