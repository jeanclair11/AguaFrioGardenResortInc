package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.MyBookingsResponse;
import com.aguafriogarden.resortinc.network.ReservationSummary;

import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Drives the Home tab: the greeting card (unchanged) plus a Booking Progress
 * section that summarizes the guest's real reservations (see
 * {@link #fetchMyBookings}) across all four service categories, and a
 * Booking Details screen drilled into from a summary card. Kept alive by
 * DashboardActivity across tab switches, so returning to Home from Details
 * (or another tab) preserves whichever screen was showing.
 */
final class DashboardHomeController {

    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_DETAILS = 1;

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

    private int currentScreen = -1;

    DashboardHomeController(Activity activity) {
        this.activity = activity;
        root = new FrameLayout(activity);
        showHome();
    }

    View getRootView() {
        return root;
    }

    /** Steps back Details -> Home; returns false once already on Home. */
    boolean handleBackPressed() {
        if (currentScreen == SCREEN_DETAILS) {
            showHome();
            return true;
        }
        return false;
    }

    // ---- Home screen: greeting + booking progress -------------------------

    private void showHome() {
        currentScreen = SCREEN_HOME;
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

        fetchMyBookings(home);

        root.removeAllViews();
        root.addView(home);
    }

    /** Loads the guest's real reservations from the shared backend and renders the Booking Progress section. */
    private void fetchMyBookings(View home) {
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            bindBookingProgress(home, new ArrayList<>());
            return;
        }
        ApiClient.bookingApi().getMyBookings("Bearer " + token).enqueue(new Callback<MyBookingsResponse>() {
            @Override
            public void onResponse(Call<MyBookingsResponse> call, Response<MyBookingsResponse> response) {
                List<ReservationSummary> bookings = response.isSuccessful() && response.body() != null
                        && response.body().reservations != null
                        ? response.body().reservations : new ArrayList<>();
                bindBookingProgress(home, bookings);
            }

            @Override
            public void onFailure(Call<MyBookingsResponse> call, Throwable t) {
                bindBookingProgress(home, new ArrayList<>());
            }
        });
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

    /**
     * Shows the empty-state card when the guest has no active bookings, or a
     * title row + horizontally scrolling row of summary cards (one per
     * active booking) when they do.
     */
    private void bindBookingProgress(View home, List<ReservationSummary> bookings) {
        View emptyCard = home.findViewById(R.id.bookingProgressEmptyCard);
        View section = home.findViewById(R.id.bookingProgressSection);

        if (bookings.isEmpty()) {
            emptyCard.setVisibility(View.VISIBLE);
            section.setVisibility(View.GONE);
            return;
        }

        emptyCard.setVisibility(View.GONE);
        section.setVisibility(View.VISIBLE);

        LinearLayout container = home.findViewById(R.id.bookingProgressCardContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (ReservationSummary booking : bookings) {
            View card = inflater.inflate(R.layout.view_home_booking_card, container, false);
            bindBookingCard(card, booking);
            card.setOnClickListener(view -> showDetails(booking));
            container.addView(card);
        }

        home.findViewById(R.id.bookingProgressRefreshButton).setOnClickListener(view -> fetchMyBookings(home));
    }

    private void bindBookingCard(View card, ReservationSummary booking) {
        int serviceType = ActiveBookingStore.serviceTypeFromString(booking.service_type);
        ((ImageView) card.findViewById(R.id.cardServiceIcon))
                .setImageResource(ActiveBookingStore.serviceIconRes(serviceType));
        ((TextView) card.findViewById(R.id.cardServiceType))
                .setText(ActiveBookingStore.serviceTypeLabelRes(serviceType));
        ((TextView) card.findViewById(R.id.cardItemName)).setText(booking.item_name);
        ((TextView) card.findViewById(R.id.cardSchedule)).setText(formatScheduleLabel(booking));

        TextView statusView = card.findViewById(R.id.cardStatus);
        View progressRow = card.findViewById(R.id.cardProgressRow);
        View cancelledBadge = card.findViewById(R.id.cardCancelledBadge);

        if (booking.cancelled) {
            statusView.setText(R.string.status_cancelled);
            statusView.setTextColor(ThemeManager.color(activity, R.attr.errorText));
            progressRow.setVisibility(View.GONE);
            cancelledBadge.setVisibility(View.VISIBLE);
        } else {
            int[] labels = ActiveBookingStore.stepLabelsRes(serviceType);
            statusView.setText(labels[booking.current_step]);
            statusView.setTextColor(ThemeManager.color(activity, R.attr.accentPrimary));
            progressRow.setVisibility(View.VISIBLE);
            cancelledBadge.setVisibility(View.GONE);
            bindCompactDots(card, booking.current_step);
        }
    }

    /** "Aug 10 – Aug 12, 2026" for a multi-day stay, or "Aug 15, 2026" when check-in and check-out are the same day. */
    private String formatScheduleLabel(ReservationSummary booking) {
        try {
            SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date checkIn = parser.parse(booking.check_in);
            Date checkOut = parser.parse(booking.check_out);
            if (booking.check_in.equals(booking.check_out)) {
                return new SimpleDateFormat("MMM d, yyyy", Locale.US).format(checkIn);
            }
            return new SimpleDateFormat("MMM d", Locale.US).format(checkIn) + " – "
                    + new SimpleDateFormat("MMM d, yyyy", Locale.US).format(checkOut);
        } catch (ParseException e) {
            return booking.check_in + " – " + booking.check_out;
        }
    }

    private String formatDisplayDate(String isoDate) {
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate);
            return new SimpleDateFormat("MMM d, yyyy", Locale.US).format(date);
        } catch (ParseException e) {
            return isoDate;
        }
    }

    /** The 4 non-terminal steps (Pending Approval/Confirmed/Waiting/Ongoing) as dots + connecting lines. */
    private void bindCompactDots(View card, int currentStep) {
        int step = Math.min(currentStep, 3);
        int[] dotIds = {R.id.dot0, R.id.dot1, R.id.dot2, R.id.dot3};
        int[] lineIds = {R.id.line0, R.id.line1, R.id.line2};

        for (int i = 0; i < dotIds.length; i++) {
            card.findViewById(dotIds[i]).setBackgroundResource(
                    i <= step ? R.drawable.bg_progress_dot_filled : R.drawable.bg_progress_dot_hollow);
        }
        for (int i = 0; i < lineIds.length; i++) {
            card.findViewById(lineIds[i]).setBackgroundColor(ThemeManager.color(activity,
                    i < step ? R.attr.accentPrimary : R.attr.dividerColor));
        }
    }

    // ---- Booking Details screen --------------------------------------------

    private void showDetails(ReservationSummary booking) {
        currentScreen = SCREEN_DETAILS;
        View v = LayoutInflater.from(activity).inflate(R.layout.view_booking_details, root, false);

        bindHeader(v, R.id.detailsHeaderBar, R.string.booking_details_title, this::showHome);

        ((TextView) v.findViewById(R.id.detailsItemName)).setText(booking.item_name);

        int serviceType = ActiveBookingStore.serviceTypeFromString(booking.service_type);

        TextView statusView = v.findViewById(R.id.detailsCurrentStatus);
        if (booking.cancelled) {
            statusView.setText(R.string.status_cancelled);
            statusView.setTextColor(ThemeManager.color(activity, R.attr.errorText));
        } else {
            statusView.setText(ActiveBookingStore.stepLabelsRes(serviceType)[booking.current_step]);
            statusView.setTextColor(ThemeManager.color(activity, R.attr.accentPrimary));
        }

        bindDetailsProgress(v, booking, serviceType);

        ((TextView) v.findViewById(R.id.detailsBookingReference)).setText(booking.booking_reference);

        bindRow(v.findViewById(R.id.detailsRowFullName), R.string.label_full_name, booking.guest_name);
        bindRow(v.findViewById(R.id.detailsRowEmail), R.string.label_email_address, booking.guest_email);
        bindRow(v.findViewById(R.id.detailsRowMobile), R.string.label_mobile_number, booking.guest_mobile);

        bindRow(v.findViewById(R.id.detailsRowCheckIn), R.string.label_stay_check_in, formatDisplayDate(booking.check_in));
        bindRow(v.findViewById(R.id.detailsRowCheckOut), R.string.label_stay_check_out, formatDisplayDate(booking.check_out));
        bindRow(v.findViewById(R.id.detailsRowGuests), R.string.label_stay_guests,
                activity.getString(R.string.label_total_guests_format, booking.adults + booking.children));

        LinearLayout servicesContainer = v.findViewById(R.id.detailsServicesContainer);
        servicesContainer.removeAllViews();
        for (String line : booking.service_lines) {
            TextView lineView = new TextView(activity);
            lineView.setText(line);
            lineView.setTextColor(ThemeManager.color(activity, R.attr.textPrimary));
            lineView.setTextSize(13f);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (servicesContainer.getChildCount() > 0) {
                params.topMargin = (int) (8 * activity.getResources().getDisplayMetrics().density);
            }
            lineView.setLayoutParams(params);
            servicesContainer.addView(lineView);
        }

        bindLine(v.findViewById(R.id.detailsRowSubtotal), R.string.label_subtotal, (int) Math.round(booking.subtotal));
        bindLine(v.findViewById(R.id.detailsRowAmountPaid), R.string.label_amount_paid_details, (int) Math.round(booking.amount_paid));
        bindLine(v.findViewById(R.id.detailsRowRemainingBalance),
                R.string.label_remaining_balance_details, (int) Math.round(booking.remaining_balance));

        bindRow(v.findViewById(R.id.detailsRowPaymentStatus), R.string.label_payment_status, booking.payment_status);

        root.removeAllViews();
        root.addView(v);
    }

    private void bindDetailsProgress(View detailsView, ReservationSummary booking, int serviceType) {
        LinearLayout list = detailsView.findViewById(R.id.detailsProgressList);
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);

        if (booking.cancelled) {
            View row = inflater.inflate(R.layout.view_booking_details_step_row, list, false);
            TextView label = row.findViewById(R.id.stepLabel);
            label.setText(R.string.status_cancelled);
            label.setTextColor(ThemeManager.color(activity, R.attr.errorText));
            label.setTypeface(null, Typeface.BOLD);
            row.findViewById(R.id.stepDot).setBackgroundResource(R.drawable.bg_progress_dot_filled);
            row.findViewById(R.id.stepDot).setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(ThemeManager.color(activity, R.attr.errorText)));
            row.findViewById(R.id.stepLine).setVisibility(View.GONE);
            list.addView(row);
            return;
        }

        int[] labelsRes = ActiveBookingStore.stepLabelsRes(serviceType);
        for (int i = 0; i < labelsRes.length; i++) {
            View row = inflater.inflate(R.layout.view_booking_details_step_row, list, false);
            boolean reached = i <= booking.current_step;
            boolean isCurrent = i == booking.current_step;

            TextView label = row.findViewById(R.id.stepLabel);
            label.setText(labelsRes[i]);
            label.setTextColor(ThemeManager.color(activity, reached ? R.attr.textPrimary : R.attr.textMuted));
            label.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);

            row.findViewById(R.id.stepDot).setBackgroundResource(
                    reached ? R.drawable.bg_progress_dot_filled : R.drawable.bg_progress_dot_hollow);

            View line = row.findViewById(R.id.stepLine);
            if (i == labelsRes.length - 1) {
                line.setVisibility(View.GONE);
            } else {
                line.setBackgroundColor(ThemeManager.color(activity,
                        i < booking.current_step ? R.attr.accentPrimary : R.attr.dividerColor));
            }

            list.addView(row);
        }
    }

    // ---- Shared helpers --------------------------------------------------

    private void bindHeader(View v, int headerBarId, int titleRes, Runnable onBack) {
        View header = v.findViewById(headerBarId);
        ((TextView) header.findViewById(R.id.headerTitle)).setText(titleRes);
        header.findViewById(R.id.headerBackButton).setOnClickListener(view -> onBack.run());
    }

    private void bindRow(View row, int labelRes, String value) {
        ((TextView) row.findViewById(R.id.rowLabel)).setText(labelRes);
        ((TextView) row.findViewById(R.id.rowValue)).setText(value);
    }

    private void bindLine(View row, int nameRes, int amount) {
        ((TextView) row.findViewById(R.id.lineName)).setText(nameRes);
        ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(amount));
    }

    private String formatCurrency(int amount) {
        return activity.getString(R.string.format_currency, NumberFormat.getInstance(Locale.US).format(amount));
    }
}
