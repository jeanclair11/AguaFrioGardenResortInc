package com.aguafriogarden.resortinc.network;

import java.util.List;

/** Response body for GET /booking/statuses (public): every status the backend can assign, in the
 *  order the receptionist and website modules show them. {@code booking}'s order is also the
 *  lifecycle order, which is what a progress display is derived from. */
public class StatusCatalogResponse {
    public List<StatusDetail> reservation;
    public List<StatusDetail> booking;
}
