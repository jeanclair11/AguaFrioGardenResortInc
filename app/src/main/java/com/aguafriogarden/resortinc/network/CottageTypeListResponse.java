package com.aguafriogarden.resortinc.network;

import java.util.List;

/** Response body for GET /api/booking/cottage-types (public, live-availability). */
public class CottageTypeListResponse {
    public List<CottageKtvOption> cottages;
}
