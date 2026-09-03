package com.aguafriogarden.resortinc.network;

/** One feedback entry as returned by GET or POST /api/feedback. */
public class FeedbackEntryDto {
    public long id;
    public int rating;
    public String message;
    public String room_type;
    public String date; // pre-formatted display string, e.g. "Aug 28, 2026"
    public String response; // null until an admin responds
    public String responded_at; // pre-formatted display string, null until responded

    public boolean isResponded() {
        return response != null && !response.isEmpty();
    }
}
