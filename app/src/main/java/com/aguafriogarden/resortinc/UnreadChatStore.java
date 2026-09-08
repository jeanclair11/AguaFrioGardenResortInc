package com.aguafriogarden.resortinc;

import com.aguafriogarden.resortinc.network.ChatMessageDto;

import java.util.List;

/**
 * In-memory unread counter for incoming (non-guest) chat messages, mirroring
 * {@link NotificationStore}'s static, process-lifetime-only style. Populated
 * by {@link ChatPollingService}, which polls independently of whether the
 * Chat tab is on screen; cleared by {@link ChatController} when the tab is
 * actually opened.
 */
final class UnreadChatStore {

    private static int unreadCount = 0;
    private static boolean chatVisible = false;

    private UnreadChatStore() {
    }

    static int unreadCount() {
        return unreadCount;
    }

    static void setChatVisible(boolean visible) {
        chatVisible = visible;
    }

    /**
     * Counts newly-seen staff messages as unread, unless the Chat tab is
     * currently open (the guest is already looking at them live). Returns how
     * many were freshly counted, so the caller knows whether to alert.
     */
    static int registerIncoming(List<ChatMessageDto> dtos) {
        if (dtos == null || dtos.isEmpty() || chatVisible) {
            return 0;
        }
        int newCount = 0;
        for (ChatMessageDto dto : dtos) {
            if (!"guest".equals(dto.sender_type)) {
                newCount++;
            }
        }
        unreadCount += newCount;
        return newCount;
    }

    static void markSeen() {
        unreadCount = 0;
    }
}
