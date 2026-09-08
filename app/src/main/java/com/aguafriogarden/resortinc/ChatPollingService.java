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

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Keeps polling {@code chat/poll} for new staff messages while the guest is
 * logged in, independent of whether the Chat tab is on screen, so
 * {@link UnreadChatStore} and the bell/nav badges in {@link DashboardActivity}
 * stay current while backgrounded. Runs as a foreground service because
 * Android kills plain background services within seconds of the app leaving
 * the foreground; started from DashboardActivity#onCreate, stopped on logout.
 */
public class ChatPollingService extends Service {

    private static final long POLL_INTERVAL_MS = 8000L;
    private static final String CHANNEL_SERVICE = "chat_polling";
    private static final String CHANNEL_MESSAGES = "chat_messages";
    private static final int NOTIFICATION_ID_SERVICE = 1001;
    private static final int NOTIFICATION_ID_MESSAGE = 1002;

    static final String EXTRA_OPEN_MODULE = "open_module";
    static final String EXTRA_VALUE_CHAT = "chat";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = this::poll;
    private long cursor = 0L;
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
            handler.post(pollRunnable);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        handler.removeCallbacks(pollRunnable);
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
        ApiClient.chatApi().poll("Bearer " + token, cursor).enqueue(new Callback<ChatHistoryResponse>() {
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

    private void handleIncoming(List<ChatMessageDto> messages) {
        for (ChatMessageDto dto : messages) {
            cursor = Math.max(cursor, dto.id);
        }
        int newCount = UnreadChatStore.registerIncoming(messages);
        if (newCount > 0) {
            notifyNewMessages();
        }
    }

    private void notifyNewMessages() {
        int total = UnreadChatStore.unreadCount();
        String body = total == 1
                ? getString(R.string.chat_new_message_body_one)
                : getString(R.string.chat_new_message_body_many, total);

        NotificationStore.push(new NotificationStore.Notification(
                getString(R.string.chat_new_message_title), body,
                getString(R.string.notif_button_view_chat), null, System.currentTimeMillis(),
                NotificationStore.Type.CHAT));

        if (ProfileStore.isNotificationsEnabled(this)) {
            postSystemNotification(body);
        }
    }

    private void postSystemNotification(String body) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        Intent intent = new Intent(this, DashboardActivity.class);
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
    }
}
