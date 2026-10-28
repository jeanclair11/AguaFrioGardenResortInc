package com.aguafriogarden.resortinc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.ChatHistoryResponse;
import com.aguafriogarden.resortinc.network.ChatMessageDto;
import com.aguafriogarden.resortinc.network.ReservationStatusResponse;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Keeps polling {@code chat/poll} for new staff messages, and separately
 * {@code booking/reservations/{id}} for a change to the guest's active Hotel Rooms reservation
 * (see {@link ProfileStore#getActiveReservationId}) — e.g. a receptionist cancelling it from
 * Reservation Management, which the guest's device would otherwise have no way to ever learn
 * about (there's no push notification system). Both run while the guest is logged in,
 * independent of which tab is on screen, so {@link UnreadChatStore}/{@link NotificationStore}
 * and the bell/nav badges in {@link LandingActivity} stay current while backgrounded. Runs as a
 * foreground service because Android kills plain background services within seconds of the app
 * leaving the foreground; started from LandingActivity#enterLoggedInState, stopped on logout.
 */
public class ChatPollingService extends Service {

    private static final long POLL_INTERVAL_MS = 8000L;
    private static final long RESERVATION_POLL_INTERVAL_MS = 15000L;
    private static final String CHANNEL_SERVICE = "chat_polling";
    private static final String CHANNEL_MESSAGES = "chat_messages";
    private static final String CHANNEL_RESERVATIONS = "reservation_updates";
    private static final int NOTIFICATION_ID_SERVICE = 1001;
    private static final int NOTIFICATION_ID_MESSAGE = 1002;
    private static final int NOTIFICATION_ID_RESERVATION = 1003;

    static final String EXTRA_OPEN_MODULE = "open_module";
    static final String EXTRA_VALUE_CHAT = "chat";
    static final String EXTRA_VALUE_RESERVE = "reserve";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = this::poll;
    private final Runnable reservationPollRunnable = this::pollReservationStatus;
    /** Newest chat message id already handled; -1 until the first poll of a fresh login. */
    private long cursor = -1L;
    private boolean running;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID_SERVICE, buildServiceNotification());
        if (!running) {
            running = true;
            cursor = ProfileStore.getChatCursor(this);
            handler.post(pollRunnable);
            handler.post(reservationPollRunnable);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(reservationPollRunnable);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void poll() {
        if (!running) {
            return;
        }
        String token = ProfileStore.getAuthToken(this);
        if (token.isEmpty()) {
            stopSelf();
            return;
        }
        ApiClient.chatApi().poll("Bearer " + token, Math.max(cursor, 0L)).enqueue(new Callback<ChatHistoryResponse>() {
            @Override
            public void onResponse(Call<ChatHistoryResponse> call, Response<ChatHistoryResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().messages != null) {
                    handleIncoming(response.body().messages);
                }
                schedule();
            }

            @Override
            public void onFailure(Call<ChatHistoryResponse> call, Throwable t) {
                schedule();
            }
        });
    }

    private void schedule() {
        if (running) {
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        }
    }

    private void pollReservationStatus() {
        if (!running) {
            return;
        }
        // Same cadence: pulls in alerts raised elsewhere (front-desk actions on the website) and
        // read/archive changes made from another device.
        NotificationStore.refresh(this);
        String token = ProfileStore.getAuthToken(this);
        int reservationId = ProfileStore.getActiveReservationId(this);
        if (token.isEmpty() || reservationId <= 0) {
            scheduleReservationPoll();
            return;
        }
        ApiClient.bookingApi().getReservationStatus("Bearer " + token, reservationId)
                .enqueue(new Callback<ReservationStatusResponse>() {
                    @Override
                    public void onResponse(Call<ReservationStatusResponse> call, Response<ReservationStatusResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            handleReservationStatus(response.body());
                        }
                        scheduleReservationPoll();
                    }

                    @Override
                    public void onFailure(Call<ReservationStatusResponse> call, Throwable t) {
                        scheduleReservationPoll();
                    }
                });
    }

    private void scheduleReservationPoll() {
        if (running) {
            handler.postDelayed(reservationPollRunnable, RESERVATION_POLL_INTERVAL_MS);
        }
    }

    /** Only acts on an actual transition (compares against the status last persisted — by either
     *  this same check or HotelBookingFlowController's own foreground refresh on Reservation
     *  Details, whichever saw it first) so the guest is never notified twice for one change. */
    private void handleReservationStatus(ReservationStatusResponse body) {
        String lastKnown = ProfileStore.getActiveReservationStatus(this);
        if (body.status == null || body.status.equals(lastKnown)) {
            return;
        }
        ProfileStore.setActiveReservationStatus(this, body.status);
        if ("cancelled".equals(body.status)) {
            notifyReservationCancelled(body.cancel_reason);
        } else if ("booked".equals(body.status)) {
            notifyReservationBooked();
        }
    }

    private void notifyReservationCancelled(String reason) {
        String body = reason != null && !reason.trim().isEmpty()
                ? getString(R.string.reservation_cancelled_notification_body_with_reason, reason)
                : getString(R.string.reservation_cancelled_notification_body_no_reason);
        pushReservationAlert(getString(R.string.reservation_cancelled_notification_title), body);
    }

    /** Not stored in the Alerts feed: the front desk confirming a reservation already creates a
     *  "Reservation Confirmed" guest notification on the server, which the next refresh brings in —
     *  storing this one too would show the same event twice. Only the system notification is raised. */
    private void notifyReservationBooked() {
        postSystemIfEnabled(getString(R.string.reservation_booked_notification_title),
                getString(R.string.reservation_booked_notification_body));
        NotificationStore.refresh(this);
    }

    private void pushReservationAlert(String title, String body) {
        NotificationStore.push(this, new NotificationStore.Notification(
                title, body, getString(R.string.notif_button_view_reservation), null,
                System.currentTimeMillis(), NotificationStore.Type.RESERVATION));
        postSystemIfEnabled(title, body);
    }

    private void postSystemIfEnabled(String title, String body) {
        if (ProfileStore.isNotificationsEnabled(this)) {
            postSystemReservationNotification(title, body);
        }
    }

    private void postSystemReservationNotification(String title, String body) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        Intent intent = new Intent(this, LandingActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_OPEN_MODULE, EXTRA_VALUE_RESERVE);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_RESERVATIONS)
                : new Notification.Builder(this);
        builder.setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_calendar)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(Notification.PRIORITY_DEFAULT);
        manager.notify(NOTIFICATION_ID_RESERVATION, builder.build());
    }

    private void handleIncoming(List<ChatMessageDto> messages) {
        boolean firstPoll = cursor < 0;
        for (ChatMessageDto dto : messages) {
            cursor = Math.max(cursor, dto.id);
        }
        cursor = Math.max(cursor, 0L);
        // Logged out while the request was in flight: don't write the old guest's cursor back.
        if (!ProfileStore.getAuthToken(this).isEmpty()) {
            ProfileStore.setChatCursor(this, cursor);
        }
        if (firstPoll) {
            // First poll after a fresh login: the thread so far is history, not new messages.
            return;
        }

        ChatMessageDto latest = null;
        int incomingCount = 0;
        for (ChatMessageDto dto : messages) {
            if (!"guest".equals(dto.sender_type)) {
                incomingCount++;
                latest = dto;
            }
        }
        if (latest == null) {
            return;
        }

        // Every staff message gets an Alerts entry — even one that arrives while the guest has the
        // Chat tab open (they see it live there, so it's recorded as already read, with no system
        // notification and no badge). Otherwise a reply received in the chat left no trace.
        boolean seenLive = UnreadChatStore.isChatVisible();
        UnreadChatStore.registerIncoming(messages);
        notifyNewMessages(latest, incomingCount, seenLive);
    }

    private void notifyNewMessages(ChatMessageDto latest, int incomingCount, boolean seenLive) {
        String text = latest.body == null ? "" : latest.body.trim();
        if (text.length() > 140) {
            text = text.substring(0, 137) + "...";
        }
        String body = incomingCount > 1 ? getString(R.string.chat_new_message_body_many, incomingCount)
                : text.isEmpty() ? getString(R.string.chat_new_message_body_one) : text;

        NotificationStore.Notification notification = new NotificationStore.Notification(
                getString(R.string.chat_new_message_title), body,
                getString(R.string.notif_button_view_chat), null, System.currentTimeMillis(),
                NotificationStore.Type.CHAT);
        notification.isRead = seenLive;
        NotificationStore.push(this, notification);

        if (!seenLive && ProfileStore.isNotificationsEnabled(this)) {
            postSystemNotification(body);
        }
    }

    private void postSystemNotification(String body) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        Intent intent = new Intent(this, LandingActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_OPEN_MODULE, EXTRA_VALUE_CHAT);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_MESSAGES)
                : new Notification.Builder(this);
        builder.setContentTitle(getString(R.string.chat_new_message_title))
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_chat)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(Notification.PRIORITY_DEFAULT);
        manager.notify(NOTIFICATION_ID_MESSAGE, builder.build());
    }

    private Notification buildServiceNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_SERVICE)
                : new Notification.Builder(this);
        builder.setContentTitle(getString(R.string.chat_poll_notification_title))
                .setContentText(getString(R.string.chat_poll_notification_text))
                .setSmallIcon(R.drawable.ic_chat)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN);
        return builder.build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        if (manager.getNotificationChannel(CHANNEL_SERVICE) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_SERVICE,
                    getString(R.string.chat_poll_channel_name), NotificationManager.IMPORTANCE_MIN);
            channel.setDescription(getString(R.string.chat_poll_channel_desc));
            manager.createNotificationChannel(channel);
        }
        if (manager.getNotificationChannel(CHANNEL_MESSAGES) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_MESSAGES,
                    getString(R.string.chat_messages_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(getString(R.string.chat_messages_channel_desc));
            manager.createNotificationChannel(channel);
        }
        if (manager.getNotificationChannel(CHANNEL_RESERVATIONS) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_RESERVATIONS,
                    getString(R.string.reservation_updates_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(getString(R.string.reservation_updates_channel_desc));
            manager.createNotificationChannel(channel);
        }
    }
}
