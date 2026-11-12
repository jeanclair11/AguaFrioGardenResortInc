package com.aguafriogarden.resortinc.network;

import java.util.List;

/** Response body for GET /booking/my-statuses — the light feed the app polls every few seconds
 *  instead of re-downloading every reservation. */
public class MyStatusesResponse {
    public List<Row> statuses;
    public String live_version;

    public static class Row {
        /** Same values as {@link ReservationSummary#kind}. */
        public String kind;
        /** Same id space as {@link ReservationSummary#id} for that kind. */
        public int id;
        public String booking_reference;
        public StatusDetail status;
    }
}
