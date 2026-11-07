package com.aguafriogarden.resortinc.network;

import java.util.List;

public class NotificationListResponse {
    /** The server's current time; 0 from a backend that predates the field. */
    public long server_time_ms;
    /** The guest's "Enable Notifications" switch on the server; null from a backend that predates
     *  the field (or can't read it yet), in which case the phone keeps its own choice. */
    public Boolean notifications_enabled;
    public List<NotificationDto> notifications;
}
