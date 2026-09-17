package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.function.Consumer;

/**
 * Renders the Alerts tab as a list of in-app notifications pushed by
 * {@link ChatPollingService} (new chat messages, and reservation status changes like a
 * receptionist-side cancellation).
 */
final class NotificationController {

    private final Activity activity;
    private final Consumer<NotificationStore.Notification> onAction;
    private final View root;

    NotificationController(Activity activity, Consumer<NotificationStore.Notification> onAction) {
        this.activity = activity;
        this.onAction = onAction;
        root = LayoutInflater.from(activity).inflate(R.layout.view_notification_list, null, false);
    }

    View getRootView() {
        NotificationStore.markAllRead();
        render();
        return root;
    }

    private void render() {
        List<NotificationStore.Notification> notifications = NotificationStore.all();
        LinearLayout container = root.findViewById(R.id.notificationListContainer);
        container.removeAllViews();
        root.findViewById(R.id.notificationEmptyState)
                .setVisibility(notifications.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(activity);
        for (NotificationStore.Notification n : notifications) {
            View card = inflater.inflate(R.layout.view_notification_card, container, false);
            ((TextView) card.findViewById(R.id.notificationTitle)).setText(n.title);
            ((TextView) card.findViewById(R.id.notificationMessage)).setText(n.message);
            ((TextView) card.findViewById(R.id.notificationTimestamp)).setText(
                    DateUtils.getRelativeTimeSpanString(n.timestampMillis, System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS));

            Button actionButton = card.findViewById(R.id.notificationActionButton);
            if (n.buttonLabel != null) {
                actionButton.setText(n.buttonLabel);
                actionButton.setVisibility(View.VISIBLE);
                actionButton.setOnClickListener(v -> onAction.accept(n));
            } else {
                actionButton.setVisibility(View.GONE);
            }

            container.addView(card);
        }
    }
}
