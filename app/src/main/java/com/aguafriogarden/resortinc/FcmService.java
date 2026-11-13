package com.aguafriogarden.resortinc;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Receives the backend's push for a guest notification (booking, reservation or payment update),
 * which arrives even while the app is closed or in the background. The push is data-only, so this
 * builds the system notification itself — the same one {@link NotificationStore} would raise when
 * its poll finds the alert, in the same slot — and tapping it marks the alert read and opens the
 * booking or reservation it is about.
 */
public class FcmService extends FirebaseMessagingService {

    @Override
    public void onNewToken(String token) {
        PushTokens.onNewToken(this, token);
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        // Signed out meanwhile: the previous guest's alert must not show on this phone.
        if (ProfileStore.getAuthToken(this).isEmpty()) {
            return;
        }
        Map<String, String> data = message.getData();
        long alertId = parseLong(data.get("notification_id"));
        NotificationStore.Type type = NotificationStore.typeFromKey(data.get("type"));
        if (alertId > 0 && type != NotificationStore.Type.CHAT
                && !ProfileStore.wasAlertAnnounced(this, alertId)) {
            // Marked first, so the poll finding the same alert a moment later doesn't raise it again.
            ProfileStore.markAlertAnnounced(this, alertId);
            ChatPollingService.postServerAlert(this, alertId, data.get("title"), data.get("message"), type,
                    data.get("event"), data.get("target_kind"), parseLong(data.get("target_id")));
        }
        // Brings the alert into the in-app list and badges (and persists it) while the process lives.
        NotificationStore.refresh(this);
    }

    private static long parseLong(String value) {
        try {
            return value == null || value.isEmpty() ? 0L : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
