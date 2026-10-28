package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.text.format.DateUtils;
import android.util.TypedValue;
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
 * receptionist-side cancellation) or raised by front-desk actions on the website. The guest can
 * mark each one read/unread and archive/unarchive it; {@link NotificationStore} syncs those
 * changes with the backend.
 */
final class NotificationController {

    private final Activity activity;
    private final Consumer<NotificationStore.Notification> onAction;
    private final Runnable onStateChanged;
    private final View root;
    private boolean showingArchived;

    NotificationController(Activity activity, Consumer<NotificationStore.Notification> onAction,
            Runnable onStateChanged) {
        this.activity = activity;
        this.onAction = onAction;
        this.onStateChanged = onStateChanged;
        root = LayoutInflater.from(activity).inflate(R.layout.view_notification_list, null, false);

        root.findViewById(R.id.notificationTabInbox).setOnClickListener(v -> selectTab(false));
        root.findViewById(R.id.notificationTabArchived).setOnClickListener(v -> selectTab(true));
        root.findViewById(R.id.notificationMarkAllRead).setOnClickListener(v -> {
            NotificationStore.markAllRead(activity);
            changed();
        });
    }

    View getRootView() {
        showingArchived = false;
        NotificationStore.setChangeListener(this::render);
        NotificationStore.refresh(activity);
        render();
        return root;
    }

    private void selectTab(boolean archived) {
        showingArchived = archived;
        render();
    }

    /** Re-renders and lets the host refresh the bell/nav badges right away. */
    private void changed() {
        render();
        onStateChanged.run();
    }

    private void render() {
        styleTab(root.findViewById(R.id.notificationTabInbox), !showingArchived);
        styleTab(root.findViewById(R.id.notificationTabArchived), showingArchived);
        root.findViewById(R.id.notificationMarkAllRead)
                .setVisibility(!showingArchived && NotificationStore.unreadCount() > 0 ? View.VISIBLE : View.GONE);

        List<NotificationStore.Notification> notifications = NotificationStore.byArchived(showingArchived);
        LinearLayout container = root.findViewById(R.id.notificationListContainer);
        container.removeAllViews();
        TextView emptyState = root.findViewById(R.id.notificationEmptyState);
        emptyState.setText(showingArchived ? R.string.notification_archived_empty_message
                : R.string.notification_empty_message);
        emptyState.setVisibility(notifications.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(activity);
        for (NotificationStore.Notification n : notifications) {
            View card = inflater.inflate(R.layout.view_notification_card, container, false);
            TextView title = card.findViewById(R.id.notificationTitle);
            title.setText(n.title);
            title.setTypeface(null, n.isRead ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
            card.findViewById(R.id.notificationUnreadDot).setVisibility(n.isRead ? View.INVISIBLE : View.VISIBLE);
            ((TextView) card.findViewById(R.id.notificationMessage)).setText(n.message);
            ((TextView) card.findViewById(R.id.notificationTimestamp)).setText(
                    DateUtils.getRelativeTimeSpanString(n.timestampMillis, System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS));

            Button actionButton = card.findViewById(R.id.notificationActionButton);
            if (n.buttonLabel != null) {
                actionButton.setText(n.buttonLabel);
                actionButton.setVisibility(View.VISIBLE);
                actionButton.setOnClickListener(v -> {
                    // Following the alert to its screen counts as having read it.
                    if (!n.isRead) {
                        NotificationStore.setRead(activity, n, true);
                    }
                    onAction.accept(n);
                });
            } else {
                actionButton.setVisibility(View.GONE);
            }

            TextView readToggle = card.findViewById(R.id.notificationReadToggle);
            readToggle.setText(n.isRead ? R.string.notification_mark_unread : R.string.notification_mark_read);
            readToggle.setOnClickListener(v -> {
                NotificationStore.setRead(activity, n, !n.isRead);
                changed();
            });

            TextView archiveToggle = card.findViewById(R.id.notificationArchiveToggle);
            archiveToggle.setText(n.isArchived ? R.string.notification_unarchive : R.string.notification_archive);
            archiveToggle.setOnClickListener(v -> {
                NotificationStore.setArchived(activity, n, !n.isArchived);
                changed();
            });

            container.addView(card);
        }
    }

    private void styleTab(View tab, boolean selected) {
        Button button = (Button) tab;
        if (selected) {
            button.setBackgroundResource(R.drawable.bg_button_primary);
            button.setTextColor(activity.getColor(R.color.button_primary_text));
        } else {
            TypedValue value = new TypedValue();
            activity.getTheme().resolveAttribute(R.attr.textOnScreen, value, true);
            button.setBackgroundResource(R.drawable.bg_button_secondary);
            button.setTextColor(value.data);
        }
    }
}
