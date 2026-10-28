package com.aguafriogarden.resortinc.network;

/** One guest notification as stored by the backend (Api\NotificationController). */
public class NotificationDto {
    public long id;
    public String title;
    public String message;
    /** "chat", "reservation", or null for notifications raised by staff actions on the website. */
    public String type;
    /** Set for alerts the app uploaded itself; lets a lost upload response be matched back up. */
    public String client_uuid;
    public boolean is_read;
    public boolean is_archived;
    public long created_at_ms;
}
