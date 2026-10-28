package com.aguafriogarden.resortinc;

import android.content.Context;

import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.MessageResponse;
import com.aguafriogarden.resortinc.network.NotificationDto;
import com.aguafriogarden.resortinc.network.NotificationListResponse;
import com.aguafriogarden.resortinc.network.NotificationResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * The guest's notification feed for the Alerts tab, kept in sync with the backend's
 * guest_notifications table (Api\NotificationController): the in-memory list is a cache of the
 * server copy, so read/unread and archived state follow the guest across devices. Alerts the app
 * raises itself (new chat message, receptionist-side cancellation — see ChatPollingService) are
 * uploaded when pushed; ones staff actions raise on the website arrive through {@link #refresh}.
 * State changes apply locally first and are sent to the server in the background; a failed
 * update is reverted by re-fetching the server copy.
 */
final class NotificationStore {

    /** Distinguishes reservation-lifecycle alerts from chat alerts so the bell's
     *  action button can route to the right module. GENERAL is anything staff raised on the
     *  website (payment received, checked in, ...), which has no in-app screen to route to. */
    enum Type {
        RESERVATION, CHAT, FEEDBACK, GENERAL
    }

    static final class Notification {
        final String title;
        final String message;
        final String buttonLabel;
        final String targetReference;
        final long timestampMillis;
        final Type type;
        /** Idempotency key for the upload; null for rows that originated on the server. */
        final String clientUuid;
        long serverId;
        boolean isRead;
        boolean isArchived;
        /** Upload in flight, or the server declined it (guest turned notifications off). */
        boolean uploading;
        boolean localOnly;

        Notification(String title, String message, String buttonLabel, String targetReference,
                long timestampMillis) {
            this(title, message, buttonLabel, targetReference, timestampMillis, Type.RESERVATION);
        }

        Notification(String title, String message, String buttonLabel, String targetReference,
                long timestampMillis, Type type) {
            this(title, message, buttonLabel, targetReference, timestampMillis, type,
                    UUID.randomUUID().toString());
        }

        private Notification(String title, String message, String buttonLabel, String targetReference,
                long timestampMillis, Type type, String clientUuid) {
            this.title = title;
            this.message = message;
            this.buttonLabel = buttonLabel;
            this.targetReference = targetReference;
            this.timestampMillis = timestampMillis;
            this.type = type;
            this.clientUuid = clientUuid;
        }
    }

    private static final List<Notification> ALL = new ArrayList<>();

    /** Updates still on their way to the server; while any are, a refresh must not overwrite
     *  local state with the older server copy. */
    private static int pendingUpdates;

    private static Runnable changeListener;

    /** Called (on the main thread) whenever the list or any flag changes after a server round
     *  trip; the Alerts screen sets it while visible. Pass null to clear. */
    static void setChangeListener(Runnable listener) {
        changeListener = listener;
    }

    private static void notifyChanged() {
        if (changeListener != null) {
            changeListener.run();
        }
    }

    /** Newest-first; callers insert at the front so no sort is needed on read. Uploads the new
     *  alert to the server. */
    static void push(Context context, Notification notification) {
        ALL.add(0, notification);
        upload(context.getApplicationContext(), notification);
    }

    static List<Notification> all() {
        return ALL;
    }

    /** The Alerts tab's two lists: Inbox (not archived) or Archived. */
    static List<Notification> byArchived(boolean archived) {
        List<Notification> result = new ArrayList<>();
        for (Notification n : ALL) {
            if (n.isArchived == archived) {
                result.add(n);
            }
        }
        return result;
    }

    /** Unread, un-archived — archiving marks a notification read, so it never counts here. */
    static int unreadCount() {
        int count = 0;
        for (Notification n : ALL) {
            if (!n.isRead && !n.isArchived) {
                count++;
            }
        }
        return count;
    }

    /** Backs the Reserve tab's own bottom-nav badge — only RESERVATION-type entries (a
     *  receptionist-side cancellation, etc. — see ChatPollingService), so an unread chat message
     *  doesn't also light up Reserve. */
    static int unreadCountByType(Type type) {
        int count = 0;
        for (Notification n : ALL) {
            if (!n.isRead && !n.isArchived && n.type == type) {
                count++;
            }
        }
        return count;
    }

    static void markAllRead(Context context) {
        boolean any = false;
        for (Notification n : ALL) {
            if (!n.isRead) {
                n.isRead = true;
                any = true;
            }
        }
        if (!any) {
            return;
        }
        Context app = context.getApplicationContext();
        String token = ProfileStore.getAuthToken(app);
        if (token.isEmpty()) {
            return;
        }
        pendingUpdates++;
        ApiClient.notificationApi().readAll("Bearer " + token).enqueue(new Callback<MessageResponse>() {
            @Override
            public void onResponse(Call<MessageResponse> call, Response<MessageResponse> response) {
                finishUpdate(app, response.isSuccessful());
            }

            @Override
            public void onFailure(Call<MessageResponse> call, Throwable t) {
                finishUpdate(app, false);
            }
        });
    }

    /** Marks only one type's entries read — used when the guest opens the tab that type's own
     *  badge belongs to (e.g. Reserve for RESERVATION), mirroring how opening Chat itself already
     *  clears UnreadChatStore without needing a trip through the Alerts tab. */
    static void markReadByType(Context context, Type type) {
        for (Notification n : ALL) {
            if (n.type == type && !n.isRead) {
                setRead(context, n, true);
            }
        }
    }

    static void setRead(Context context, Notification n, boolean read) {
        n.isRead = read;
        sendState(context.getApplicationContext(), n);
    }

    /** Archiving also marks it read (same as the staff Notifications page); unarchiving leaves
     *  it read. */
    static void setArchived(Context context, Notification n, boolean archived) {
        n.isArchived = archived;
        if (archived) {
            n.isRead = true;
        }
        sendState(context.getApplicationContext(), n);
    }

    /** Drops the cache on logout so the next guest never sees the previous one's alerts. */
    static void clear() {
        ALL.clear();
        pendingUpdates = 0;
    }

    /** Fetches the server copy, merges it into the cache, and retries any alert whose upload
     *  hasn't gone through yet. */
    static void refresh(Context context) {
        Context app = context.getApplicationContext();
        String token = ProfileStore.getAuthToken(app);
        if (token.isEmpty()) {
            return;
        }
        ApiClient.notificationApi().list("Bearer " + token).enqueue(new Callback<NotificationListResponse>() {
            @Override
            public void onResponse(Call<NotificationListResponse> call, Response<NotificationListResponse> response) {
                // Logged out while the request was in flight: don't resurrect the old guest's cache.
                if (ProfileStore.getAuthToken(app).isEmpty()) {
                    return;
                }
                if (response.isSuccessful() && response.body() != null && response.body().notifications != null) {
                    merge(app, response.body().notifications);
                    notifyChanged();
                }
            }

            @Override
            public void onFailure(Call<NotificationListResponse> call, Throwable t) {
            }
        });
    }

    private static void merge(Context context, List<NotificationDto> dtos) {
        Map<Long, Notification> byId = new HashMap<>();
        Map<String, Notification> byUuid = new HashMap<>();
        for (Notification n : ALL) {
            if (n.serverId > 0) {
                byId.put(n.serverId, n);
            }
            if (n.clientUuid != null) {
                byUuid.put(n.clientUuid, n);
            }
        }
        for (NotificationDto dto : dtos) {
            Notification n = byId.get(dto.id);
            if (n == null && dto.client_uuid != null) {
                n = byUuid.get(dto.client_uuid);
            }
            if (n == null) {
                n = fromDto(context, dto);
                ALL.add(n);
            } else {
                n.serverId = dto.id;
                if (pendingUpdates == 0) {
                    n.isRead = dto.is_read;
                    n.isArchived = dto.is_archived;
                }
            }
        }
        ALL.sort((a, b) -> Long.compare(b.timestampMillis, a.timestampMillis));
        for (Notification n : new ArrayList<>(ALL)) {
            if (n.serverId == 0 && !n.uploading && !n.localOnly) {
                upload(context, n);
            }
        }
    }

    private static Notification fromDto(Context context, NotificationDto dto) {
        Type type = "chat".equals(dto.type) ? Type.CHAT
                : "reservation".equals(dto.type) ? Type.RESERVATION
                : "feedback".equals(dto.type) ? Type.FEEDBACK : Type.GENERAL;
        String button = type == Type.CHAT ? context.getString(R.string.notif_button_view_chat)
                : type == Type.RESERVATION ? context.getString(R.string.notif_button_view_reservation)
                : type == Type.FEEDBACK ? context.getString(R.string.notif_button_view_feedback)
                : null;
        Notification n = new Notification(dto.title, dto.message, button, null, dto.created_at_ms,
                type, dto.client_uuid);
        n.serverId = dto.id;
        n.isRead = dto.is_read;
        n.isArchived = dto.is_archived;
        return n;
    }

    private static void upload(Context context, Notification n) {
        String token = ProfileStore.getAuthToken(context);
        if (token.isEmpty() || n.uploading || n.localOnly || n.serverId > 0 || n.clientUuid == null) {
            return;
        }
        n.uploading = true;
        String type = n.type == Type.CHAT ? "chat" : n.type == Type.RESERVATION ? "reservation" : null;
        ApiClient.notificationApi().create("Bearer " + token, n.clientUuid, n.title, n.message, type,
                n.timestampMillis).enqueue(new Callback<NotificationResponse>() {
            @Override
            public void onResponse(Call<NotificationResponse> call, Response<NotificationResponse> response) {
                n.uploading = false;
                if (response.isSuccessful() && response.body() != null && response.body().notification != null) {
                    n.serverId = response.body().notification.id;
                    // Read/archived before the upload finished: the server row starts unread.
                    if (n.isRead || n.isArchived) {
                        sendState(context, n);
                    }
                } else if (response.code() == 204) {
                    // Guest switched notifications off on the server — keep it on this device only.
                    n.localOnly = true;
                }
            }

            @Override
            public void onFailure(Call<NotificationResponse> call, Throwable t) {
                n.uploading = false;
            }
        });
    }

    private static void sendState(Context context, Notification n) {
        String token = ProfileStore.getAuthToken(context);
        if (token.isEmpty() || n.serverId == 0) {
            return;
        }
        pendingUpdates++;
        ApiClient.notificationApi().update("Bearer " + token, n.serverId, n.isRead ? 1 : 0,
                n.isArchived ? 1 : 0).enqueue(new Callback<NotificationResponse>() {
            @Override
            public void onResponse(Call<NotificationResponse> call, Response<NotificationResponse> response) {
                finishUpdate(context, response.isSuccessful());
            }

            @Override
            public void onFailure(Call<NotificationResponse> call, Throwable t) {
                finishUpdate(context, false);
            }
        });
    }

    private static void finishUpdate(Context context, boolean success) {
        pendingUpdates = Math.max(0, pendingUpdates - 1);
        if (!success) {
            // Put the server's copy back so the screen never claims a change that didn't save.
            refresh(context);
        }
    }

    private NotificationStore() {
    }
}
