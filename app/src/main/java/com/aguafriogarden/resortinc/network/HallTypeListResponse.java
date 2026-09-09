package com.aguafriogarden.resortinc.network;

import java.util.List;

/** Response body for GET /api/booking/hall-types (public, live-availability). */
public class HallTypeListResponse {
    public List<CottageKtvOption> halls;
}
