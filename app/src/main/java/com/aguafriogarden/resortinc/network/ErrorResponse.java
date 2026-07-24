package com.aguafriogarden.resortinc.network;

import java.util.List;
import java.util.Map;

/** Shape of a Laravel validation-failure (422) or plain error JSON body. */
public class ErrorResponse {
    public String message;
    public Map<String, List<String>> errors;

    /** The first field-level message if present, otherwise the top-level message. */
    public String firstMessage() {
        if (errors != null) {
            for (List<String> messages : errors.values()) {
                if (messages != null && !messages.isEmpty()) {
                    return messages.get(0);
                }
            }
        }
        return message;
    }
}
