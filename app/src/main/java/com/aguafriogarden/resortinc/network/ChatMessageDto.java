package com.aguafriogarden.resortinc.network;

/** One message as returned by GET /api/chat, GET /api/chat/poll, or POST /api/chat/send. */
public class ChatMessageDto {
    public long id;
    public String sender_type; // "guest", "staff", or "bot"
    public String sender_name;
    public String body;
    public String reaction;
    public String read_at; // ISO-8601, null while unread
    public String created_at; // ISO-8601
}
