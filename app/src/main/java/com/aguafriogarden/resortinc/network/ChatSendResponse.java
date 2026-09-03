package com.aguafriogarden.resortinc.network;

/** Response body for POST /api/chat/send. */
public class ChatSendResponse {
    public long conversation_id;
    public ChatMessageDto message;
}
