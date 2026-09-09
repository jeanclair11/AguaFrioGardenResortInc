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
import java.util.function.Consumer;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Drives the Greeting card + Booking Progress section shown on LandingActivity's Home once a
 * guest is logged in (see LandingActivity#heroOrGreetingSlot). Booking Details is a separate,
 * full-screen state owned by LandingActivity itself (see {@link #buildDetailsView}) rather than
 * something this controller swaps into its own root, since Details should take over the whole
 * screen the way Hotel Rooms/Cottages/etc. do — not just the small Greeting/Progress slot.
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
    private final Consumer<ReservationSummary> onBookingTap;

    DashboardHomeController(Activity activity, Consumer<ReservationSummary> onBookingTap) {
        this.activity = activity;
        this.onBookingTap = onBookingTap;
        root = new FrameLayout(activity);
        buildHome();
    }

    View getRootView() {
        return root;
    }

    // ---- Greeting + booking progress ---------------------------------------

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
            card.setOnClickListener(view -> onBookingTap.accept(booking));
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

        bindPrimarySecondary(card.findViewById(R.id.cardPrimaryTitle), card.findViewById(R.id.cardSecondaryInfo),
                booking, true);

        ((TextView) card.findViewById(R.id.cardSchedule)).setText(formatScheduleLabel(booking));

        TextView statusView = card.findViewById(R.id.cardStatus);
        TextView descriptionView = card.findViewById(R.id.cardDescription);
        View cancelledBadge = card.findViewById(R.id.cardCancelledBadge);
        View cancelledOnRow = card.findViewById(R.id.cardCancelledOnRow);
        View cancelledReasonRow = card.findViewById(R.id.cardCancelledReasonRow);

        if (booking.cancelled) {
            statusView.setText(R.string.status_cancelled);
            statusView.setTextColor(ThemeManager.color(activity, R.attr.errorText));
            descriptionView.setText(R.string.desc_cancelled);
            cancelledBadge.setVisibility(View.VISIBLE);
            bindCompactDots(card, booking.cancelled_from_step, true);

            if (booking.cancelled_at != null && !booking.cancelled_at.isEmpty()) {
                bindRow(cancelledOnRow, R.string.label_cancelled_on, formatDisplayDateTime(booking.cancelled_at));
                cancelledOnRow.setVisibility(View.VISIBLE);
            } else {
                cancelledOnRow.setVisibility(View.GONE);
            }
            if (booking.cancel_reason != null && !booking.cancel_reason.isEmpty()) {
                bindRow(cancelledReasonRow, R.string.label_cancellation_reason, booking.cancel_reason);
                cancelledReasonRow.setVisibility(View.VISIBLE);
            } else {
                cancelledReasonRow.setVisibility(View.GONE);
            }
        } else {
            int step = Math.min(booking.current_step, ActiveBookingStore.STEP_LABELS_RES.length - 1);
            statusView.setText(ActiveBookingStore.STEP_LABELS_RES[step]);
            statusView.setTextColor(ThemeManager.color(activity, R.attr.accentPrimary));
            descriptionView.setText(ActiveBookingStore.STEP_DESCRIPTIONS_RES[step]);
            cancelledBadge.setVisibility(View.GONE);
            cancelledOnRow.setVisibility(View.GONE);
            cancelledReasonRow.setVisibility(View.GONE);
            bindCompactDots(card, step, false);
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

    /** "August 5, 2026 • 2:45 PM" from an ISO-8601 timestamp (e.g. cancelled_at). */
    private String formatDisplayDateTime(String isoDateTime) {
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(isoDateTime);
            return new SimpleDateFormat("MMMM d, yyyy • h:mm a", Locale.US).format(date);
        } catch (ParseException e) {
            return isoDateTime;
        }
    }

    /**
     * The 4-step progress row (Pending Booking/Confirmed/Ongoing/Checked Out) as dots +
     * connecting lines. When cancelled, filledStep is cancelled_from_step and the dot/line at
     * that point is tinted red to mark where the booking stopped; steps beyond it stay hollow.
     */
    private void bindCompactDots(View card, int filledStep, boolean cancelled) {
        int step = Math.min(filledStep, 3);
        int[] dotIds = {R.id.dot0, R.id.dot1, R.id.dot2, R.id.dot3};
        int[] lineIds = {R.id.line0, R.id.line1, R.id.line2};

        for (int i = 0; i < dotIds.length; i++) {
            View dot = card.findViewById(dotIds[i]);
            dot.setBackgroundResource(i <= step ? R.drawable.bg_progress_dot_filled : R.drawable.bg_progress_dot_hollow);
            dot.setBackgroundTintList(cancelled && i == step
                    ? android.content.res.ColorStateList.valueOf(ThemeManager.color(activity, R.attr.errorText))
                    : null);
        }
        for (int i = 0; i < lineIds.length; i++) {
            int color = cancelled && i == step
                    ? ThemeManager.color(activity, R.attr.errorText)
                    : ThemeManager.color(activity, i < step ? R.attr.accentPrimary : R.attr.dividerColor);
            card.findViewById(lineIds[i]).setBackgroundColor(color);
        }
    }

    // ---- Booking Details screen --------------------------------------------

    /** Builds a standalone, full-screen Booking Details view for LandingActivity to show. */
    View buildDetailsView(ReservationSummary booking, Runnable onBack) {
        View v = LayoutInflater.from(activity).inflate(R.layout.view_booking_details, null, false);

        bindHeader(v, R.id.detailsHeaderBar, R.string.booking_details_title, onBack);

        ((TextView) v.findViewById(R.id.detailsItemName)).setText(booking.item_name);

        int serviceType = ActiveBookingStore.serviceTypeFromString(booking.service_type);

        TextView statusView = v.findViewById(R.id.detailsCurrentStatus);
        TextView descriptionView = v.findViewById(R.id.detailsDescription);
        View cancelledOnRow = v.findViewById(R.id.detailsRowCancelledOn);
        View cancelledReasonRow = v.findViewById(R.id.detailsRowCancelledReason);

        if (booking.cancelled) {
            statusView.setText(R.string.status_cancelled);
            statusView.setTextColor(ThemeManager.color(activity, R.attr.errorText));
            descriptionView.setText(R.string.desc_cancelled);

            if (booking.cancelled_at != null && !booking.cancelled_at.isEmpty()) {
                bindRow(cancelledOnRow, R.string.label_cancelled_on, formatDisplayDateTime(booking.cancelled_at));
                cancelledOnRow.setVisibility(View.VISIBLE);
            } else {
                cancelledOnRow.setVisibility(View.GONE);
            }
            if (booking.cancel_reason != null && !booking.cancel_reason.isEmpty()) {
                bindRow(cancelledReasonRow, R.string.label_cancellation_reason, booking.cancel_reason);
                cancelledReasonRow.setVisibility(View.VISIBLE);
            } else {
                cancelledReasonRow.setVisibility(View.GONE);
            }
        } else {
            int step = Math.min(booking.current_step, ActiveBookingStore.STEP_LABELS_RES.length - 1);
            statusView.setText(ActiveBookingStore.STEP_LABELS_RES[step]);
            statusView.setTextColor(ThemeManager.color(activity, R.attr.accentPrimary));
            descriptionView.setText(ActiveBookingStore.STEP_DESCRIPTIONS_RES[step]);
            cancelledOnRow.setVisibility(View.GONE);
            cancelledReasonRow.setVisibility(View.GONE);
        }

        bindDetailsProgress(v, booking);

        bindRow(v.findViewById(R.id.detailsRowAccommodationType), R.string.label_accommodation_type,
                activity.getString(ActiveBookingStore.serviceTypeLabelRes(serviceType)));
        bindPrimarySecondary(v.findViewById(R.id.detailsPrimaryTitle), v.findViewById(R.id.detailsSecondaryInfo),
                booking, false);

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

        return v;
    }

    /**
     * Renders the 4 lifecycle steps (Pending Booking/Confirmed/Ongoing/Checked Out). For a
     * cancelled booking, the steps freeze at cancelled_from_step (0 = cancelled while pending,
     * 1 = cancelled after confirmed) and a red "Cancelled" row is inserted right after the
     * frozen step; steps beyond it stay hollow/grayed to show they never happened.
     */
    private void bindDetailsProgress(View detailsView, ReservationSummary booking) {
        LinearLayout list = detailsView.findViewById(R.id.detailsProgressList);
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);

        int[] labelsRes = ActiveBookingStore.STEP_LABELS_RES;
        int reachedStep = booking.cancelled ? booking.cancelled_from_step : booking.current_step;

        for (int i = 0; i < labelsRes.length; i++) {
            View row = inflater.inflate(R.layout.view_booking_details_step_row, list, false);
            boolean reached = i <= reachedStep;
            boolean isCurrent = i == reachedStep && !booking.cancelled;

            TextView label = row.findViewById(R.id.stepLabel);
            label.setText(labelsRes[i]);
            label.setTextColor(ThemeManager.color(activity, reached ? R.attr.textPrimary : R.attr.textMuted));
            label.setTypeface(null, isCurrent ? Typeface.BOLD : Typeface.NORMAL);

            row.findViewById(R.id.stepDot).setBackgroundResource(
                    reached ? R.drawable.bg_progress_dot_filled : R.drawable.bg_progress_dot_hollow);

            View line = row.findViewById(R.id.stepLine);
            if (i == labelsRes.length - 1 && !booking.cancelled) {
                line.setVisibility(View.GONE);
            } else if (booking.cancelled && i == reachedStep) {
                line.setBackgroundColor(ThemeManager.color(activity, R.attr.errorText));
            } else {
                line.setBackgroundColor(ThemeManager.color(activity,
                        i < reachedStep ? R.attr.accentPrimary : R.attr.dividerColor));
            }

            list.addView(row);

            if (booking.cancelled && i == reachedStep) {
                View cancelledRow = inflater.inflate(R.layout.view_booking_details_step_row, list, false);
                TextView cancelledLabel = cancelledRow.findViewById(R.id.stepLabel);
                cancelledLabel.setText(R.string.status_cancelled);
                cancelledLabel.setTextColor(ThemeManager.color(activity, R.attr.errorText));
                cancelledLabel.setTypeface(null, Typeface.BOLD);
                cancelledRow.findViewById(R.id.stepDot).setBackgroundResource(R.drawable.bg_progress_dot_filled);
                cancelledRow.findViewById(R.id.stepDot).setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(ThemeManager.color(activity, R.attr.errorText)));
                cancelledRow.findViewById(R.id.stepLine).setVisibility(View.GONE);
                list.addView(cancelledRow);
            }
        }
    }

    // ---- Shared helpers --------------------------------------------------

    private void bindHeader(View v, int headerBarId, int titleRes, Runnable onBack) {
        View header = v.findViewById(headerBarId);
        ((TextView) header.findViewById(R.id.headerTitle)).setText(titleRes);
        header.findViewById(R.id.headerBackButton).setOnClickListener(view -> onBack.run());
    }

    /**
     * Room Type is the primary heading (what a guest identifies first,
     * mirroring how a receptionist scans a booking); Category — plus party
     * size on the compact Home card — is secondary context below it. Falls
     * back to Category alone as the primary heading when the reservation has
     * no room type variant set.
     */
    private void bindPrimarySecondary(TextView primaryView, TextView secondaryView, ReservationSummary booking, boolean includePax) {
        boolean hasRoomType = booking.room_type_name != null && !booking.room_type_name.isEmpty();
        primaryView.setText(hasRoomType ? booking.room_type_name : booking.item_name);

        String pax = activity.getString(R.string.label_total_guests_format, booking.adults + booking.children);
        if (!hasRoomType) {
            secondaryView.setVisibility(includePax ? View.VISIBLE : View.GONE);
            secondaryView.setText(includePax ? pax : "");
            return;
        }

        secondaryView.setText(includePax ? booking.item_name + " · " + pax : booking.item_name);
        secondaryView.setVisibility(View.VISIBLE);
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

    private String formatMoney(double amount) {
        return NumberFormat.getInstance(Locale.US).format(Math.round(amount));
    }
}
