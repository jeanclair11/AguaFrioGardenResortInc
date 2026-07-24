package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

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

    private static final String STATE_MODULE = "module";

    private static final long CLOCK_TICK_MS = 30_000L;

    private static final long GREETING_FADE_MS = 400L;

    /** Time-of-day bucket driving the greeting card's photo, icon and label. */
    private enum GreetingPeriod {
        MORNING(R.string.greeting_morning, R.drawable.bg_good_morning, R.drawable.ic_good_morning),
        AFTERNOON(R.string.greeting_afternoon, R.drawable.bg_good_afternoon, R.drawable.ic_good_afternoon),
        EVENING(R.string.greeting_evening, R.drawable.bg_good_evening, R.drawable.ic_good_evening),
        NIGHT(R.string.greeting_night, R.drawable.bg_good_night, R.drawable.ic_good_night);

        final int greetingRes;
        final int backgroundRes;
        final int iconRes;

        GreetingPeriod(int greetingRes, int backgroundRes, int iconRes) {
            this.greetingRes = greetingRes;
            this.backgroundRes = backgroundRes;
            this.iconRes = iconRes;
        }

        /** 5-11:59 morning, 12-16:59 afternoon, 17-18:59 evening, else night. */
        static GreetingPeriod forHour(int hour) {
            if (hour >= 5 && hour < 12) {
                return MORNING;
            } else if (hour < 17) {
                return AFTERNOON;
            } else if (hour < 19) {
                return EVENING;
            } else {
                return NIGHT;
            }
        }
    }

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
                    R.string.module_dashboard_desc, R.drawable.ic_dashboard),
            new Module(R.string.nav_reservation, R.string.module_reservation_title,
                    R.string.module_reservation_desc, R.drawable.ic_reservation),
            new Module(R.string.nav_booking, R.string.module_booking_title,
                    R.string.module_booking_desc, R.drawable.ic_booking),
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
    private BookingWizardController bookingWizard;
    private BookingCatalogController bookingCatalog;
    private ProfileController profileController;
    private FeedbackController feedbackController;
    private ChatController chatController;
    private int currentModule = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.apply(this);
        setContentView(R.layout.activity_dashboard);

        topBarRow = findViewById(R.id.topBarRow);
        bottomNav = findViewById(R.id.bottomNav);
        moduleContainer = findViewById(R.id.moduleContainer);
        bookingWizard = new BookingWizardController(this);
        bookingCatalog = new BookingCatalogController(this, () -> showModule(MODULE_RESERVATION, true));
        profileController = new ProfileController(this,
                () -> showModule(MODULE_DASHBOARD, true), this::logout);
        feedbackController = new FeedbackController(this);
        chatController = new ChatController(this);

        findViewById(R.id.bellButton).setOnClickListener(v -> showModule(MODULE_ALERTS, true));
        findViewById(R.id.profileButton).setOnClickListener(v -> showModule(MODULE_ME, true));

        buildBottomNav();
        // Stay on the same module when the theme toggle recreates us.
        showModule(savedInstanceState != null
                ? savedInstanceState.getInt(STATE_MODULE, 0) : 0, false);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_MODULE, currentModule);
    }

    @Override
    public void onBackPressed() {
        if (currentModule == MODULE_ME && profileController.handleBackPressed()) {
            return;
        }
        if (currentModule == MODULE_FEEDBACK && feedbackController.handleBackPressed()) {
            return;
        }
        if (currentModule == MODULE_BOOKING && bookingCatalog.handleBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        profileController.onActivityResult(requestCode, resultCode, data);
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
            content = buildDashboardHome();
        } else if (index == MODULE_RESERVATION) {
            content = bookingWizard.getRootView();
        } else if (index == MODULE_BOOKING) {
            content = bookingCatalog.getRootView();
        } else if (index == MODULE_CHAT) {
            content = chatController.getRootView();
        } else if (index == MODULE_FEEDBACK) {
            content = feedbackController.getRootView();
        } else if (index == MODULE_ME) {
            content = profileController.getRootView();
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
    }

    /** Shows the shared top bar only on Home; the bottom nav stays visible everywhere. */
    private void updateChromeVisibility() {
        topBarRow.setVisibility(currentModule == MODULE_DASHBOARD ? View.VISIBLE : View.GONE);
    }

    /**
     * Dashboard tab: greeting card (name, date, live clock) plus empty-state
     * booking progress and recent transaction cards. The clock ticks every
     * {@link #CLOCK_TICK_MS} while this view is attached.
     */
    private View buildDashboardHome() {
        View home = LayoutInflater.from(this)
                .inflate(R.layout.view_dashboard_home, moduleContainer, false);

        Handler handler = new Handler(Looper.getMainLooper());
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                updateGreeting(home);
                handler.postDelayed(this, CLOCK_TICK_MS);
            }
        };
        home.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                tick.run();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                handler.removeCallbacks(tick);
            }
        });
        return home;
    }

    private void updateGreeting(View home) {
        Calendar now = Calendar.getInstance();
        GreetingPeriod period = GreetingPeriod.forHour(now.get(Calendar.HOUR_OF_DAY));
        // The view's tag holds the period it's currently showing; null right
        // after inflation, which tells applyGreetingPeriod to apply the first
        // photo/icon/label immediately instead of crossfading from nothing.
        GreetingPeriod previous = (GreetingPeriod) home.getTag();
        if (period != previous) {
            home.setTag(period);
            applyGreetingPeriod(home, period, previous != null);
        }

        ((TextView) home.findViewById(R.id.greetingName))
                .setText(ProfileStore.getDisplayFirstName(this));
        ((TextView) home.findViewById(R.id.greetingDate)).setText(
                new SimpleDateFormat("EEEE • MMMM d", Locale.getDefault()).format(now.getTime()));
        ((TextView) home.findViewById(R.id.greetingTime)).setText(
                new SimpleDateFormat("h:mm a", Locale.getDefault()).format(now.getTime()));
    }

    /**
     * Crossfades the greeting card's background photo, icon and label into
     * the new time-of-day period. The back ImageView is primed with the
     * incoming photo while the front one (currently showing it on top) fades
     * out to reveal it, then swaps to the new resource so it's ready to be
     * the fade target next time.
     */
    private void applyGreetingPeriod(View home, GreetingPeriod period, boolean animate) {
        ImageView back = home.findViewById(R.id.greetingBackgroundBack);
        ImageView front = home.findViewById(R.id.greetingBackground);
        ImageView icon = home.findViewById(R.id.greetingIcon);
        TextView label = home.findViewById(R.id.greetingLabel);

        if (!animate) {
            front.setImageResource(period.backgroundRes);
            front.setAlpha(1f);
            icon.setImageResource(period.iconRes);
            icon.setAlpha(1f);
            label.setText(period.greetingRes);
            label.setAlpha(1f);
            return;
        }

        back.setImageResource(period.backgroundRes);
        front.animate().cancel();
        front.animate().alpha(0f).setDuration(GREETING_FADE_MS)
                .withEndAction(() -> {
                    front.setImageResource(period.backgroundRes);
                    front.setAlpha(1f);
                }).start();

        icon.animate().cancel();
        icon.animate().alpha(0f).setDuration(GREETING_FADE_MS / 2)
                .withEndAction(() -> {
                    icon.setImageResource(period.iconRes);
                    icon.animate().alpha(1f).setDuration(GREETING_FADE_MS / 2).start();
                }).start();

        label.animate().cancel();
        label.animate().alpha(0f).setDuration(GREETING_FADE_MS / 2)
                .withEndAction(() -> {
                    label.setText(period.greetingRes);
                    label.animate().alpha(1f).setDuration(GREETING_FADE_MS / 2).start();
                }).start();
    }

    private void logout() {
        Intent intent = new Intent(DashboardActivity.this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_enter, R.anim.fade_exit);
        finish();
    }
}
