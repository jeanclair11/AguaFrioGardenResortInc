package com.aguafriogarden.resortinc.network;

import java.util.List;

public class NotificationListResponse {
    /** The server's current time; 0 from a backend that predates the field. */
    public long server_time_ms;
    public List<NotificationDto> notifications;
}
