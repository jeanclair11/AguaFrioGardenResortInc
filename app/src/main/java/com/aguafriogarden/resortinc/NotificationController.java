package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Renders the Alerts tab as a list of in-app notifications pushed by
 * {@link ChatPollingService} (new chat messages, and reservation status changes like a
 * receptionist-side cancellation) or raised by front-desk actions on the website, grouped under
 * Today / Yesterday / Earlier. The guest can mark each one read/unread and archive/unarchive it
 * (from the card's bottom row or its three-dot menu); {@link NotificationStore} syncs those
 * changes with the backend.
 */
final class NotificationController {

    private static final int GROUP_TODAY = 0;
    private static final int GROUP_YESTERDAY = 1;
    private static final int GROUP_EARLIER = 2;

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
        root.findViewById(R.id.notificationPageMenu).setOnClickListener(this::showPageMenu);
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

    private void showPageMenu(View anchor) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(R.string.notification_menu_mark_all_read)
                .setEnabled(NotificationStore.unreadCount() > 0);
        menu.setOnMenuItemClickListener(item -> {
            NotificationStore.markAllRead(activity);
            changed();
            return true;
        });
        menu.show();
    }

    private void render() {
        int inboxCount = NotificationStore.byArchived(false).size();
        ((Button) root.findViewById(R.id.notificationTabInbox)).setText(inboxCount > 0
                ? activity.getString(R.string.notification_tab_inbox_count, inboxCount)
                : activity.getString(R.string.notification_tab_inbox));
        styleTab(root.findViewById(R.id.notificationTabInbox), !showingArchived);
        styleTab(root.findViewById(R.id.notificationTabArchived), showingArchived);

        List<NotificationStore.Notification> notifications = NotificationStore.byArchived(showingArchived);
        LinearLayout container = root.findViewById(R.id.notificationListContainer);
        container.removeAllViews();
        TextView emptyState = root.findViewById(R.id.notificationEmptyState);
        emptyState.setText(showingArchived ? R.string.notification_archived_empty_message
                : R.string.notification_empty_message);
        emptyState.setVisibility(notifications.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(activity);
        long todayStart = startOfDay(0);
        long yesterdayStart = startOfDay(-1);
        int lastGroup = -1;
        for (NotificationStore.Notification n : notifications) {
            int group = n.timestampMillis >= todayStart ? GROUP_TODAY
                    : n.timestampMillis >= yesterdayStart ? GROUP_YESTERDAY : GROUP_EARLIER;
            if (group != lastGroup) {
                container.addView(buildGroupHeader(group));
                lastGroup = group;
            }
            container.addView(buildCard(inflater, container, n, group));
        }
    }

    private View buildGroupHeader(int group) {
        TextView header = new TextView(activity);
        header.setText(group == GROUP_TODAY ? R.string.notification_group_today
                : group == GROUP_YESTERDAY ? R.string.notification_group_yesterday
                : R.string.notification_group_earlier);
        header.setTextColor(resolveColor(R.attr.textOnScreen));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        header.setTypeface(null, Typeface.BOLD);
        float density = activity.getResources().getDisplayMetrics().density;
        header.setPadding(0, Math.round(14 * density), 0, Math.round(8 * density));
        return header;
    }

    private View buildCard(LayoutInflater inflater, LinearLayout container,
            NotificationStore.Notification n, int group) {
        View card = inflater.inflate(R.layout.view_notification_card, container, false);
        card.setBackgroundResource(n.isRead ? R.drawable.bg_notification_card_read
                : R.drawable.bg_notification_card_unread);

        ((ImageView) card.findViewById(R.id.notificationIcon)).setImageResource(iconFor(n));
        TextView title = card.findViewById(R.id.notificationTitle);
        title.setText(n.title);
        title.setTypeface(null, n.isRead ? Typeface.NORMAL : Typeface.BOLD);
        card.findViewById(R.id.notificationUnreadDot).setVisibility(n.isRead ? View.INVISIBLE : View.VISIBLE);
        ((TextView) card.findViewById(R.id.notificationMessage)).setText(n.message);
        ((TextView) card.findViewById(R.id.notificationTimestamp)).setText(timestampLabel(n, group));

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
        readToggle.setOnClickListener(v -> toggleRead(n));

        ((TextView) card.findViewById(R.id.notificationArchiveText))
                .setText(n.isArchived ? R.string.notification_unarchive : R.string.notification_archive);
        card.findViewById(R.id.notificationArchiveToggle).setOnClickListener(v -> toggleArchived(n));

        return card;
    }

    private void toggleRead(NotificationStore.Notification n) {
        NotificationStore.setRead(activity, n, !n.isRead);
        changed();
    }

    private void toggleArchived(NotificationStore.Notification n) {
        NotificationStore.setArchived(activity, n, !n.isArchived);
        changed();
    }

    /** Icon by what the alert is about; staff-raised (GENERAL) ones are matched on their title. */
    private int iconFor(NotificationStore.Notification n) {
        switch (n.type) {
            case CHAT:
                return R.drawable.ic_chat;
            case FEEDBACK:
                return R.drawable.ic_feedback;
            case RESERVATION:
                return R.drawable.ic_calendar;
            default:
                String title = n.title == null ? "" : n.title.toLowerCase(Locale.ROOT);
                if (title.contains("order") || title.contains("food")) {
                    return R.drawable.ic_food;
                } else if (title.contains("payment")) {
                    return R.drawable.ic_payment;
                } else if (title.contains("booking") || title.contains("reservation")
                        || title.contains("check")) {
                    return R.drawable.ic_calendar;
                }
                return R.drawable.ic_bell;
        }
    }

    /** "3 minutes ago" today, "Yesterday, 2:15 PM", or "Oct 24, 2026, 10:30 AM" before that. */
    private String timestampLabel(NotificationStore.Notification n, int group) {
        Date when = new Date(n.timestampMillis);
        if (group == GROUP_TODAY) {
            return DateUtils.getRelativeTimeSpanString(n.timestampMillis, NotificationStore.now(),
                    DateUtils.MINUTE_IN_MILLIS).toString();
        }
        String time = DateFormat.getTimeFormat(activity).format(when);
        if (group == GROUP_YESTERDAY) {
            return activity.getString(R.string.notification_yesterday_at, time);
        }
        return DateFormat.getMediumDateFormat(activity).format(when) + ", " + time;
    }

    private static long startOfDay(int dayOffset) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(NotificationStore.now());
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, dayOffset);
        return c.getTimeInMillis();
    }

    private int resolveColor(int attr) {
        TypedValue value = new TypedValue();
        activity.getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    private void styleTab(View tab, boolean selected) {
        Button button = (Button) tab;
        if (selected) {
            button.setBackgroundResource(R.drawable.bg_button_primary);
            button.setTextColor(activity.getColor(R.color.button_primary_text));
        } else {
            button.setBackgroundResource(R.drawable.bg_button_secondary);
            button.setTextColor(resolveColor(R.attr.textOnScreen));
        }
    }
}
