package com.aguafriogarden.resortinc.network;

import java.util.List;

/** Response body for POST /api/booking/cottage-availability (auth required, date+Cottage
 *  Use-scoped, real availability — see BookingApi#checkCottageAvailability). */
public class CottageAvailabilityResponse {
    public String date;
    public String use;
    public List<CottageKtvOption> cottages;
}
