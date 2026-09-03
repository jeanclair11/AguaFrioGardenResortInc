package com.aguafriogarden.resortinc.network;

import java.util.List;

/** Response body for GET /api/chat and GET /api/chat/poll. */
public class ChatHistoryResponse {
    public long conversation_id;
    public List<ChatMessageDto> messages;
}
