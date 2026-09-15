package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Drives the Greeting card shown on LandingActivity's Home once a guest is logged in (see
 * LandingActivity#heroOrGreetingSlot).
 */
final class DashboardHomeController {

    private static final long CLOCK_TICK_MS = 1000L;
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

    private final Activity activity;
    private final FrameLayout root;

    DashboardHomeController(Activity activity) {
        this.activity = activity;
        root = new FrameLayout(activity);
        buildHome();
    }

    View getRootView() {
        return root;
    }

    // ---- Greeting -----------------------------------------------------------

    private void buildHome() {
        View home = LayoutInflater.from(activity).inflate(R.layout.view_dashboard_home, root, false);

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

        root.removeAllViews();
        root.addView(home);
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
                .setText(ProfileStore.getDisplayFirstName(activity));
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
}
