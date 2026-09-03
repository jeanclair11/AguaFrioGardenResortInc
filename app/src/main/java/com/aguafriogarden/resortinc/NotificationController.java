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
 * Renders the Alerts tab (previously an empty placeholder) as a list of
 * in-app notifications pushed by {@link ReservationStore}. There is no email
 * leg in this prototype — see the Reservation Module memory for why.
 */
final class NotificationController {

    private final Activity activity;
    private final Consumer<String> onOpenReservation;
    private final View root;

    NotificationController(Activity activity, Consumer<String> onOpenReservation) {
        this.activity = activity;
        this.onOpenReservation = onOpenReservation;
        root = LayoutInflater.from(activity).inflate(R.layout.view_notification_list, null, false);
    }

    View getRootView() {
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
            if (n.buttonLabel != null && n.targetReference != null) {
                actionButton.setText(n.buttonLabel);
                actionButton.setVisibility(View.VISIBLE);
                actionButton.setOnClickListener(v -> onOpenReservation.accept(n.targetReference));
            } else {
                actionButton.setVisibility(View.GONE);
            }

            container.addView(card);
        }
    }
}
