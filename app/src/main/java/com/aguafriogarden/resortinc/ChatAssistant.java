package com.aguafriogarden.resortinc;

import android.content.Context;

import java.util.Locale;

/**
 * Stands in for the AI assistant: matches free-typed text (or a tapped quick
 * question) against a small predefined FAQ knowledge base and answers
 * instantly. When nothing matches confidently — including the "Contact
 * Receptionist" chip, which always escalates on purpose — respond() returns
 * null so ChatController shows the escalation message instead.
 *
 * TODO(backend): replace respond() with a real AI/REST call; the escalation
 * path (null return) is already where a human receptionist would take over.
 */
final class ChatAssistant {

    private static final class Topic {
        final String[] keywords;
        final int answerRes;

        Topic(int answerRes, String... keywords) {
            this.answerRes = answerRes;
            this.keywords = keywords;
        }
    }

    private static final Topic[] TOPICS = {
            new Topic(R.string.faq_room_availability_answer,
                    "availability", "available room", "vacant"),
            new Topic(R.string.faq_booking_requirements_answer,
                    "booking requirement", "requirements", "how to book", "how do i book"),
            new Topic(R.string.faq_resort_rates_answer,
                    "rate", "price", "cost", "how much"),
            new Topic(R.string.faq_pool_info_answer,
                    "pool", "swimming"),
            new Topic(R.string.faq_cottage_info_answer,
                    "cottage"),
            new Topic(R.string.faq_booking_status_answer,
                    "booking status", "my booking", "reservation status"),
            new Topic(R.string.faq_payment_instructions_answer,
                    "payment", "pay ", "gcash", "bank transfer", "how do i pay"),
            new Topic(R.string.faq_cancellation_policy_answer,
                    "cancel", "refund"),
            new Topic(R.string.faq_resort_amenities_answer,
                    "amenities", "facilities", "amenity"),
    };

    private ChatAssistant() {
    }

    /** The FAQ answer for this text, or null when it should escalate to a receptionist. */
    static String respond(Context context, String guestText) {
        String lower = " " + guestText.toLowerCase(Locale.US) + " ";
        for (Topic topic : TOPICS) {
            for (String keyword : topic.keywords) {
                if (lower.contains(keyword)) {
                    return context.getString(topic.answerRes);
                }
            }
        }
        return null;
    }
}
