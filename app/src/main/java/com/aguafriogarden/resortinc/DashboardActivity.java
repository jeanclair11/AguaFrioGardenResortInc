package com.aguafriogarden.resortinc;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Post-login home screen: a top bar (logo + paired notification/profile
 * icons) that shows only on the Home tab, and a fixed five-tab bottom
 * navigation (Home, Reserve, Book, Chat, Feedback) that stays visible on
 * every screen. Theme switching lives solely in Profile > Preferences.
 * Notifications and Profile only open from the Home tab's header icons.
 * Module bodies are placeholders until each one is built out.
 */
public class DashboardActivity extends Activity {

    private static final long CONTENT_FADE_MS = 150L;
    private static final long BADGE_REFRESH_MS = 2000L;
    private static final int REQUEST_POST_NOTIFICATIONS = 1001;

    private static final String STATE_MODULE = "module";

    // Order matches the modules array: the first NAV_COUNT entries are the
    // bottom-nav tabs; Profile and Notifications are reached from the Home
    // tab's header icons instead.
    private static final int MODULE_DASHBOARD = 0; // hosts the greeting/booking/transaction cards
    private static final int MODULE_RESERVATION = 1; // hosts the booking wizard
    private static final int MODULE_BOOKING = 2; // hosts the service catalog (categories -> browse -> detail)
    private static final int MODULE_CHAT = 3; // hosts the customer support conversation
    private static final int MODULE_FEEDBACK = 4; // hosts the feedback history + write-feedback flow
    private static final int MODULE_ME = 5;
    private static final int MODULE_ALERTS = 6;
    private static final int NAV_COUNT = 5;

    /** One module screen and, for the first NAV_COUNT, its bottom-nav entry. */
    private static class Module {
        final int navLabelRes;
        final int titleRes;
        final int descriptionRes;
        final int iconRes;

        Module(int navLabelRes, int titleRes, int descriptionRes, int iconRes) {
            this.navLabelRes = navLabelRes;
            this.titleRes = titleRes;
            this.descriptionRes = descriptionRes;
            this.iconRes = iconRes;
        }
    }

    private final Module[] modules = {
            new Module(R.string.nav_home, R.string.module_dashboard_title,
                    R.string.module_dashboard_desc, R.drawable.ic_home),
            new Module(R.string.nav_reservation, R.string.module_reservation_title,
                    R.string.module_reservation_desc, R.drawable.ic_reservation),
            new Module(R.string.nav_booking, R.string.module_booking_title,
                    R.string.module_booking_desc, R.drawable.ic_calendar),
            new Module(R.string.nav_chat, R.string.module_chat_title,
                    R.string.module_chat_desc, R.drawable.ic_chat),
            new Module(R.string.nav_feedback, R.string.module_feedback_title,
                    R.string.module_feedback_desc, R.drawable.ic_feedback),
            new Module(R.string.nav_me, R.string.me_title,
                    R.string.me_title, R.drawable.ic_person),
            new Module(R.string.nav_notification, R.string.module_notification_title,
                    R.string.module_notification_desc, R.drawable.ic_bell),
    };

    private final View[] navItems = new View[NAV_COUNT];

    private View topBarRow;
    private LinearLayout bottomNav;
    private FrameLayout moduleContainer;
    private DashboardHomeController dashboardHome;
    private ReservationFlowController reservationFlow;
    private BookingCatalogController bookingCatalog;
    private ProfileController profileController;
    private FeedbackController feedbackController;
    private ChatController chatController;
    private NotificationController notificationController;
    private int currentModule = -1;

    private final Handler badgeHandler = new Handler(Looper.getMainLooper());
    private final Runnable badgeTick = this::tickBadges;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.apply(this);
        setContentView(R.layout.activity_dashboard);

        topBarRow = findViewById(R.id.topBarRow);
        bottomNav = findViewById(R.id.bottomNav);
        moduleContainer = findViewById(R.id.moduleContainer);
        // Forces ReservationStore's static seeding (and the notifications it pushes) to run
        // immediately, so the Alerts tab isn't empty for a guest who taps the bell before ever
        // opening the Reserve tab (Java only runs a class's static initializer on first use).
        ReservationStore.all();
        reservationFlow = new ReservationFlowController(this, () -> showModule(MODULE_DASHBOARD, true));
        bookingCatalog = new BookingCatalogController(this,
                () -> showModule(MODULE_RESERVATION, true), () -> showModule(MODULE_DASHBOARD, true));
        dashboardHome = new DashboardHomeController(this, () -> {
            bookingCatalog.showHotelFlow();
            showModule(MODULE_BOOKING, true);
        });
        profileController = new ProfileController(this,
                () -> showModule(MODULE_DASHBOARD, true), this::logout);
        feedbackController = new FeedbackController(this);
        chatController = new ChatController(this);
        notificationController = new NotificationController(this, n -> {
            if (n.type == NotificationStore.Type.CHAT) {
                showModule(MODULE_CHAT, true);
            } else {
                showModule(MODULE_RESERVATION, true);
                reservationFlow.openDetails(n.targetReference);
            }
        });

        findViewById(R.id.bellButton).setOnClickListener(v -> showModule(MODULE_ALERTS, true));
        findViewById(R.id.profileButton).setOnClickListener(v -> showModule(MODULE_ME, true));

        buildBottomNav();

        requestNotificationPermissionIfNeeded();
        startChatPollingService();

        // Stay on the same module when the theme toggle recreates us; a tap on the
        // "new message" system notification instead jumps straight to Chat.
        int initialModule = savedInstanceState != null ? savedInstanceState.getInt(STATE_MODULE, 0) : 0;
        if (savedInstanceState == null
                && ChatPollingService.EXTRA_VALUE_CHAT.equals(getIntent().getStringExtra(ChatPollingService.EXTRA_OPEN_MODULE))) {
            initialModule = MODULE_CHAT;
        }
        showModule(initialModule, false);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
        }
    }

    private void startChatPollingService() {
        Intent serviceIntent = new Intent(this, ChatPollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_MODULE, currentModule);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentModule == MODULE_CHAT) {
            chatController.onShown();
        } else if (currentModule == MODULE_FEEDBACK) {
            feedbackController.onShown();
        }
        badgeHandler.post(badgeTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        chatController.onHidden();
        badgeHandler.removeCallbacks(badgeTick);
    }

    @Override
    public void onBackPressed() {
        if (currentModule == MODULE_DASHBOARD && dashboardHome.handleBackPressed()) {
            return;
        }
        if (currentModule == MODULE_ME && profileController.handleBackPressed()) {
            return;
        }
        if (currentModule == MODULE_FEEDBACK && feedbackController.handleBackPressed()) {
            return;
        }
        if (currentModule == MODULE_BOOKING && bookingCatalog.handleBackPressed()) {
            return;
        }
        if (currentModule == MODULE_RESERVATION && reservationFlow.handleBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        profileController.onActivityResult(requestCode, resultCode, data);
        bookingCatalog.onActivityResult(requestCode, resultCode, data);
    }

    private void buildBottomNav() {
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < NAV_COUNT; i++) {
            View item = inflater.inflate(R.layout.view_nav_item, bottomNav, false);
            ((ImageView) item.findViewById(R.id.navIcon)).setImageResource(modules[i].iconRes);
            ((TextView) item.findViewById(R.id.navLabel)).setText(modules[i].navLabelRes);
            final int index = i;
            item.setOnClickListener(v -> showModule(index, true));
            bottomNav.addView(item);
            navItems[i] = item;
        }
    }

    /**
     * Switches the visible module, retinting the nav and cross-fading the
     * body. Notifications (opened from the Home tab's bell icon) leaves
     * every tab inactive. The shared top bar shows only while on Home (see
     * updateChromeVisibility()); the bottom nav stays visible throughout.
     */
    private void showModule(int index, boolean animate) {
        if (index == currentModule) {
            return;
        }
        if (currentModule == MODULE_CHAT) {
            chatController.onHidden();
        }
        currentModule = index;
        Module module = modules[index];

        updateChromeVisibility();

        for (int i = 0; i < navItems.length; i++) {
            int color = ThemeManager.color(this,
                    i == index ? R.attr.navItemActive : R.attr.navItemInactive);
            ((ImageView) navItems[i].findViewById(R.id.navIcon)).setColorFilter(color);
            ((TextView) navItems[i].findViewById(R.id.navLabel)).setTextColor(color);
        }

        View content;
        if (index == MODULE_DASHBOARD) {
            content = dashboardHome.getRootView();
        } else if (index == MODULE_RESERVATION) {
            content = reservationFlow.getRootView();
        } else if (index == MODULE_BOOKING) {
            content = bookingCatalog.getRootView();
        } else if (index == MODULE_CHAT) {
            content = chatController.getRootView();
            chatController.onShown();
        } else if (index == MODULE_FEEDBACK) {
            content = feedbackController.getRootView();
            feedbackController.onShown();
        } else if (index == MODULE_ME) {
            content = profileController.getRootView();
        } else if (index == MODULE_ALERTS) {
            content = notificationController.getRootView();
        } else {
            content = LayoutInflater.from(this)
                    .inflate(R.layout.view_module_placeholder, moduleContainer, false);
            ((ImageView) content.findViewById(R.id.moduleIcon)).setImageResource(module.iconRes);
            ((TextView) content.findViewById(R.id.moduleTitle)).setText(module.titleRes);
            ((TextView) content.findViewById(R.id.moduleDescription)).setText(module.descriptionRes);
        }

        moduleContainer.removeAllViews();
        moduleContainer.addView(content);
        // Reset in case the reused wizard view was mid-animation last time.
        content.setAlpha(1f);
        content.setTranslationY(0f);
        if (animate) {
            float shiftPx = 8f * getResources().getDisplayMetrics().density;
            content.setAlpha(0f);
            content.setTranslationY(shiftPx);
            content.animate().alpha(1f).translationY(0f)
                    .setDuration(CONTENT_FADE_MS).start();
        }
        refreshBadges();
    }

    /** Reflects {@link UnreadChatStore} and {@link NotificationStore} unread counts onto the
     *  Chat nav tab and bell icon; called on tab switches and on a short timer while resumed
     *  so a count that changes in the background (see ChatPollingService) shows up promptly. */
    private void refreshBadges() {
        updateBadge((TextView) findViewById(R.id.bellBadge), NotificationStore.unreadCount());
        updateBadge((TextView) navItems[MODULE_CHAT].findViewById(R.id.navBadge), UnreadChatStore.unreadCount());
    }

    private void tickBadges() {
        refreshBadges();
        badgeHandler.postDelayed(badgeTick, BADGE_REFRESH_MS);
    }

    private void updateBadge(TextView badge, int count) {
        if (count <= 0) {
            badge.setVisibility(View.GONE);
            return;
        }
        badge.setVisibility(View.VISIBLE);
        badge.setText(count > 9 ? getString(R.string.badge_count_overflow) : String.valueOf(count));
    }

    /** Shows the shared top bar only on Home; the bottom nav stays visible everywhere. */
    private void updateChromeVisibility() {
        topBarRow.setVisibility(currentModule == MODULE_DASHBOARD ? View.VISIBLE : View.GONE);
    }

    private void logout() {
        stopService(new Intent(this, ChatPollingService.class));
        ProfileStore.clearAuthToken(this);
        Intent intent = new Intent(DashboardActivity.this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_enter, R.anim.fade_exit);
        finish();
    }
}
