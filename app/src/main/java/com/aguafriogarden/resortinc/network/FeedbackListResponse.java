package com.aguafriogarden.resortinc.network;

import java.util.List;

/** Response body for GET /api/feedback. */
public class FeedbackListResponse {
    public List<FeedbackEntryDto> feedback;
}
