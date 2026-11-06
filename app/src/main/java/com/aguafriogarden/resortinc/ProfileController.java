package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.MyBookingsResponse;
import com.aguafriogarden.resortinc.network.ReservationSummary;

import java.text.NumberFormat;
import java.util.function.Consumer;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Drives the "Me" tab's Profile screen and its sub-screens (Personal
 * Information, Change Password, Valid ID, Upload New ID), each reached by
 * tapping a row on the Profile main screen and left via a header back arrow;
 * the main screen itself is left via its Back to Home button. DashboardActivity
 * keeps one instance alive across tab switches (only getRootView() is
 * detached/reattached), forwards onActivityResult() to it for the avatar and
 * ID image pickers, and consults handleBackPressed() so the hardware back
 * button steps out of a sub-screen (or off Profile entirely) instead of
 * exiting the app.
 */
final class ProfileController {

    private static final int SCREEN_MAIN = 0;
    private static final int SCREEN_PERSONAL_INFO = 1;
    private static final int SCREEN_PERSONAL_INFO_SUCCESS = 2;
    private static final int SCREEN_CHANGE_PASSWORD = 3;
    private static final int SCREEN_CHANGE_PASSWORD_SUCCESS = 4;
    private static final int SCREEN_VALID_ID = 5;
    private static final int SCREEN_UPLOAD_ID = 6;
    private static final int SCREEN_HISTORY = 7;
    private static final int SCREEN_STATEMENT = 8;

    /** How often the open Profile/history screen re-reads the guest's records, so receptionist-side
     *  changes show up without reopening it. */
    private static final long HISTORY_POLL_MS = 10000;

    private static final int REQUEST_ID_FRONT = 3001;
    private static final int REQUEST_ID_BACK = 3002;
    private static final int REQUEST_AVATAR = 3003;

    private static final String PASSWORD_COMPLEXITY_REGEX =
            "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=!])(?=\\S+$).{8,}$";

    private static final int PHONE_NUMBER_LENGTH = 11;

    /** Strips out anything that isn't a digit (Personal Information's Phone Number field). */
    private static final InputFilter DIGITS_ONLY_FILTER = (source, start, end, dest, dstart, dend) -> {
        StringBuilder kept = new StringBuilder();
        boolean rejectedAny = false;
        for (int i = start; i < end; i++) {
            char c = source.charAt(i);
            if (Character.isDigit(c)) {
                kept.append(c);
            } else {
                rejectedAny = true;
            }
        }
        return rejectedAny ? kept.toString() : null;
    };

    private interface DateCallback {
        void onDatePicked(String date);
    }

    private final Activity activity;
    private final Runnable onBackToHome;
    private final Runnable onLogout;
    private final Runnable onAvatarChanged;
    private final Consumer<ReservationSummary> onOpenReservation;
    private final FrameLayout root;
    private final Handler historyHandler = new Handler(Looper.getMainLooper());
    private boolean historyPolling = false;
    private boolean historyLoading = false;
    private List<ReservationSummary> historyData = new ArrayList<>();
    private int historySection = ReservationHistory.SECTION_RESERVATIONS;
    private int historyTab = ReservationHistory.TAB_CURRENT;
    /** Set by showBookingByReference until a fetch returns that record. */
    private String pendingHistoryReference = null;

    private View currentScreenView;
    private int currentScreen = -1;

    // Upload New ID's in-progress, unsaved picks.
    private Bitmap idFrontBitmap;
    private Bitmap idBackBitmap;

    ProfileController(Activity activity, Runnable onBackToHome, Runnable onLogout,
            Runnable onAvatarChanged, Consumer<ReservationSummary> onOpenReservation) {
        this.activity = activity;
        this.onOpenReservation = onOpenReservation;
        this.onBackToHome = onBackToHome;
        this.onLogout = onLogout;
        this.onAvatarChanged = onAvatarChanged;
        root = new FrameLayout(activity);
        showScreen(SCREEN_MAIN);
    }

    View getRootView() {
        return root;
    }

    /**
     * Steps out of a sub-screen, or off Profile entirely back to Home once
     * already on the main screen (there's no in-screen "Back to Home" button
     * or bottom nav to fall back on, so this always consumes the press).
     */
    boolean handleBackPressed() {
        switch (currentScreen) {
            case SCREEN_PERSONAL_INFO:
            case SCREEN_PERSONAL_INFO_SUCCESS:
            case SCREEN_CHANGE_PASSWORD:
            case SCREEN_CHANGE_PASSWORD_SUCCESS:
            case SCREEN_VALID_ID:
            case SCREEN_HISTORY:
            case SCREEN_STATEMENT:
                showScreen(SCREEN_MAIN);
                return true;
            case SCREEN_UPLOAD_ID:
                showScreen(SCREEN_VALID_ID);
                return true;
            default:
                onBackToHome.run();
                return true;
        }
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_AVATAR) {
            ProfileStore.saveAvatarUri(activity, uri);
            showAvatarPreview();
            onAvatarChanged.run();
            return;
        }
        if (requestCode != REQUEST_ID_FRONT && requestCode != REQUEST_ID_BACK) {
            return;
        }
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(activity.getContentResolver(), uri);
            if (requestCode == REQUEST_ID_FRONT) {
                idFrontBitmap = bitmap;
                showIdPreview(true, bitmap);
            } else {
                idBackBitmap = bitmap;
                showIdPreview(false, bitmap);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void showAvatarPreview() {
        if (currentScreen != SCREEN_MAIN || currentScreenView == null) {
            return;
        }
        bindAvatar(currentScreenView.findViewById(R.id.profileAvatar));
    }

    private void showIdPreview(boolean front, Bitmap bitmap) {
        if (currentScreen != SCREEN_UPLOAD_ID || currentScreenView == null) {
            return;
        }
        int previewId = front ? R.id.uiFrontPreview : R.id.uiBackPreview;
        int hintId = front ? R.id.uiFrontHint : R.id.uiBackHint;
        ImageView preview = currentScreenView.findViewById(previewId);
        preview.setImageBitmap(bitmap);
        preview.setVisibility(View.VISIBLE);
        currentScreenView.findViewById(hintId).setVisibility(View.GONE);
        if (front) {
            currentScreenView.findViewById(R.id.uiFrontError).setVisibility(View.GONE);
        }
    }

    private void showScreen(int screen) {
        currentScreen = screen;
        View v = LayoutInflater.from(activity).inflate(layoutFor(screen), root, false);
        bindScreen(screen, v);
        root.removeAllViews();
        root.addView(v);
        currentScreenView = v;
        if (screen == SCREEN_MAIN || screen == SCREEN_HISTORY || screen == SCREEN_STATEMENT) {
            startHistoryPolling();
        }
    }

    private int layoutFor(int screen) {
        switch (screen) {
            case SCREEN_PERSONAL_INFO:
                return R.layout.view_personal_information;
            case SCREEN_PERSONAL_INFO_SUCCESS:
            case SCREEN_CHANGE_PASSWORD_SUCCESS:
                return R.layout.view_success_screen;
            case SCREEN_CHANGE_PASSWORD:
                return R.layout.view_change_password;
            case SCREEN_VALID_ID:
                return R.layout.view_valid_id;
            case SCREEN_UPLOAD_ID:
                return R.layout.view_upload_new_id;
            case SCREEN_HISTORY:
                return R.layout.view_profile_history;
            case SCREEN_STATEMENT:
                return R.layout.view_profile_statement;
            default:
                return R.layout.view_profile_main;
        }
    }

    private void bindScreen(int screen, View v) {
        switch (screen) {
            case SCREEN_PERSONAL_INFO:
                bindPersonalInfo(v);
                break;
            case SCREEN_PERSONAL_INFO_SUCCESS:
                bindSuccess(v, R.string.personal_info_success_title,
                        R.string.personal_info_success_message, () -> showScreen(SCREEN_MAIN));
                break;
            case SCREEN_CHANGE_PASSWORD:
                bindChangePassword(v);
                break;
            case SCREEN_CHANGE_PASSWORD_SUCCESS:
                bindSuccess(v, R.string.password_success_title,
                        R.string.password_success_message, () -> showScreen(SCREEN_MAIN));
                break;
            case SCREEN_VALID_ID:
                bindValidId(v);
                break;
            case SCREEN_UPLOAD_ID:
                bindUploadId(v);
                break;
            case SCREEN_HISTORY:
                bindHistory(v);
                break;
            case SCREEN_STATEMENT:
                bindStatement(v);
                break;
            default:
                bindMain(v);
                break;
        }
    }

    // ---- Main profile screen ----------------------------------------

    private void bindMain(View v) {
        boolean light = ThemeManager.mode(activity) == ThemeManager.MODE_LIGHT;

        v.findViewById(R.id.profileBackButton).setOnClickListener(ClickGuard.wrap(view -> onBackToHome.run()));

        bindAvatar(v.findViewById(R.id.profileAvatar));
        View.OnClickListener changeAvatar = view -> pickImage(REQUEST_AVATAR);
        v.findViewById(R.id.profileAvatar).setOnClickListener(ClickGuard.wrap(changeAvatar));
        v.findViewById(R.id.profileChangeAvatarButton).setOnClickListener(ClickGuard.wrap(changeAvatar));

        ((TextView) v.findViewById(R.id.profileName)).setText(ProfileStore.getFullName(activity));

        TextView usernameView = v.findViewById(R.id.profileUsername);
        String username = ProfileStore.getUsername(activity);
        usernameView.setText(username);
        usernameView.setVisibility(username.isEmpty() ? View.GONE : View.VISIBLE);

        TextView emailView = v.findViewById(R.id.profileEmail);
        String email = ProfileStore.getEmail(activity);
        emailView.setText(email);
        emailView.setVisibility(email.isEmpty() ? View.GONE : View.VISIBLE);

        // Soft shadow in Light Mode; a subtle border reads better than a
        // shadow on Dark Mode's own dark surfaces.
        int cardBg = light ? R.drawable.bg_card : R.drawable.bg_card_bordered;
        float cardElevation = light ? 3f * activity.getResources().getDisplayMetrics().density : 0f;
        for (int cardId : new int[]{R.id.profileCard, R.id.accountCard, R.id.preferencesCard, R.id.logoutCard}) {
            View card = v.findViewById(cardId);
            card.setBackgroundResource(cardBg);
            card.setElevation(cardElevation);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                card.setOutlineAmbientShadowColor(
                        activity.getResources().getColor(R.color.card_shadow_ambient, activity.getTheme()));
                card.setOutlineSpotShadowColor(
                        activity.getResources().getColor(R.color.card_shadow_spot, activity.getTheme()));
            }
        }

        bindHistoryCard(v);
        fetchHistory();

        bindActionRow(v.findViewById(R.id.rowPersonalInfo), R.drawable.ic_person,
                R.string.row_personal_information_title, R.string.row_personal_information_subtitle,
                null, 0, () -> showScreen(SCREEN_PERSONAL_INFO));
        bindActionRow(v.findViewById(R.id.rowChangePassword), R.drawable.ic_lock,
                R.string.row_change_password_title, R.string.row_change_password_subtitle,
                null, 0, () -> showScreen(SCREEN_CHANGE_PASSWORD));

        String idBadgeText;
        int idBadgeBg;
        if (!ProfileStore.hasIdSubmission(activity)) {
            idBadgeText = activity.getString(R.string.status_not_submitted);
            idBadgeBg = R.drawable.bg_badge_neutral;
        } else if (ProfileStore.isIdVerified(activity)) {
            idBadgeText = activity.getString(R.string.status_verified);
            idBadgeBg = R.drawable.bg_badge_success;
        } else {
            idBadgeText = activity.getString(R.string.status_pending);
            idBadgeBg = R.drawable.bg_badge_pending;
        }
        bindActionRow(v.findViewById(R.id.rowValidId), R.drawable.ic_id_card,
                R.string.row_valid_id_title, R.string.row_valid_id_subtitle,
                idBadgeText, idBadgeBg, () -> showScreen(SCREEN_VALID_ID));
        bindActionRow(v.findViewById(R.id.rowStatement), R.drawable.ic_payment,
                R.string.row_statement_title, R.string.row_statement_subtitle,
                null, 0, () -> showScreen(SCREEN_STATEMENT));

        View notificationsRow = v.findViewById(R.id.rowNotifications);
        bindSwitchRowLabels(notificationsRow, R.drawable.ic_bell,
                R.string.row_enable_notifications_title, R.string.row_enable_notifications_subtitle);
        Switch notificationsSwitch = notificationsRow.findViewById(R.id.rowSwitch);
        notificationsSwitch.setChecked(ProfileStore.isNotificationsEnabled(activity));
        notificationsSwitch.setOnCheckedChangeListener((btn, checked) ->
                NotificationStore.setNotificationsEnabled(activity, checked));

        View darkModeRow = v.findViewById(R.id.rowDarkMode);
        bindSwitchRowLabels(darkModeRow, R.drawable.ic_moon,
                R.string.row_dark_mode_title, R.string.row_dark_mode_subtitle);
        Switch darkModeSwitch = darkModeRow.findViewById(R.id.rowSwitch);
        darkModeSwitch.setChecked(ThemeManager.mode(activity) == ThemeManager.MODE_DARK);
        darkModeSwitch.setOnCheckedChangeListener((btn, checked) -> ThemeManager.setMode(
                activity, checked ? ThemeManager.MODE_DARK : ThemeManager.MODE_LIGHT));

        v.findViewById(R.id.logoutRow).setOnClickListener(ClickGuard.wrap(view -> onLogout.run()));
    }

    // ---- My Reservations / My Bookings (live from GET /api/booking/my-bookings) ---------------

    /** Called when the Profile tab is (re)shown — re-reads the guest's records right away. */
    void onShown() {
        if (currentScreen == SCREEN_MAIN || currentScreen == SCREEN_HISTORY || currentScreen == SCREEN_STATEMENT) {
            fetchHistory();
            startHistoryPolling();
        }
    }

    private void bindHistoryCard(View v) {
        int[][] ids = {
                {R.id.historyReservationsCurrent, R.id.historyReservationsUpcoming, R.id.historyReservationsPast},
                {R.id.historyBookingsCurrent, R.id.historyBookingsUpcoming, R.id.historyBookingsPast}};
        int[] icons = {R.drawable.ic_clock, R.drawable.ic_calendar, R.drawable.ic_check_circle};
        int[] labels = {R.string.history_tab_current, R.string.history_tab_upcoming, R.string.history_tab_past};
        for (int section = 0; section < 2; section++) {
            for (int tab = 0; tab < 3; tab++) {
                final int sectionFinal = section;
                final int tabFinal = tab;
                View shortcut = v.findViewById(ids[section][tab]);
                ImageView icon = shortcut.findViewById(R.id.historyShortcutIcon);
                icon.setImageResource(icons[tab]);
                icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                        ThemeManager.color(activity, R.attr.textPrimary)));
                ((TextView) shortcut.findViewById(R.id.historyShortcutLabel)).setText(labels[tab]);
                TextView badge = shortcut.findViewById(R.id.historyShortcutBadge);
                int count = tab == ReservationHistory.TAB_PAST ? 0 : ReservationHistory.count(historyData, section, tab);
                badge.setText(String.valueOf(count));
                badge.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
                shortcut.setOnClickListener(ClickGuard.wrap(view -> openHistory(sectionFinal, tabFinal)));
            }
        }
        v.findViewById(R.id.historyReservationsViewAll).setOnClickListener(ClickGuard.wrap(
                view -> openHistory(ReservationHistory.SECTION_RESERVATIONS, ReservationHistory.TAB_CURRENT)));
        v.findViewById(R.id.historyBookingsViewAll).setOnClickListener(ClickGuard.wrap(
                view -> openHistory(ReservationHistory.SECTION_BOOKINGS, ReservationHistory.TAB_CURRENT)));
    }

    private void openHistory(int section, int tab) {
        historySection = section;
        historyTab = tab;
        showScreen(SCREEN_HISTORY);
    }

    /**
     * Opens the history list on the record with this booking reference (the Book flow's success
     * screen "View Booking"). The section/tab it lands on depends on what the server says about
     * that record, so it starts on Bookings/Current and jumps as soon as a fetch includes it.
     */
    void showBookingByReference(String reference) {
        pendingHistoryReference = reference;
        openHistory(ReservationHistory.SECTION_BOOKINGS, ReservationHistory.TAB_CURRENT);
    }

    private void applyPendingHistoryReference() {
        if (pendingHistoryReference == null || pendingHistoryReference.isEmpty()) {
            return;
        }
        for (ReservationSummary summary : historyData) {
            if (pendingHistoryReference.equals(summary.booking_reference)) {
                historySection = "reservation".equals(summary.kind)
                        ? ReservationHistory.SECTION_BOOKINGS : ReservationHistory.SECTION_RESERVATIONS;
                historyTab = ReservationHistory.tabOf(summary);
                pendingHistoryReference = null;
                if (currentScreen == SCREEN_HISTORY) {
                    // Re-bind so the header title matches the section we jumped to.
                    showScreen(SCREEN_HISTORY);
                }
                return;
            }
        }
    }

    private void bindHistory(View v) {
        bindHeader(v, historySection == ReservationHistory.SECTION_RESERVATIONS
                ? R.string.my_reservations_title : R.string.my_bookings_title, () -> showScreen(SCREEN_MAIN));
        int[] tabIds = {R.id.historyTabCurrent, R.id.historyTabUpcoming, R.id.historyTabPast};
        for (int tab = 0; tab < tabIds.length; tab++) {
            final int tabFinal = tab;
            v.findViewById(tabIds[tab]).setOnClickListener(view -> {
                historyTab = tabFinal;
                renderHistory(v);
            });
        }
        renderHistory(v);
        fetchHistory();
    }

    private void renderHistory(View v) {
        int[] tabIds = {R.id.historyTabCurrent, R.id.historyTabUpcoming, R.id.historyTabPast};
        for (int tab = 0; tab < tabIds.length; tab++) {
            ((TextView) v.findViewById(tabIds[tab])).setTextColor(ThemeManager.color(activity,
                    tab == historyTab ? R.attr.accentPrimary : R.attr.textMuted));
        }

        List<ReservationSummary> bucket = ReservationHistory.select(historyData, historySection, historyTab);
        LinearLayout container = v.findViewById(R.id.historyContainer);
        container.removeAllViews();
        TextView emptyState = v.findViewById(R.id.historyEmptyState);
        emptyState.setVisibility(bucket.isEmpty() ? View.VISIBLE : View.GONE);
        boolean reservations = historySection == ReservationHistory.SECTION_RESERVATIONS;
        emptyState.setText(historyTab == ReservationHistory.TAB_CURRENT
                ? (reservations ? R.string.history_empty_reservations_current : R.string.history_empty_bookings_current)
                : historyTab == ReservationHistory.TAB_UPCOMING
                ? (reservations ? R.string.history_empty_reservations_upcoming : R.string.history_empty_bookings_upcoming)
                : (reservations ? R.string.history_empty_reservations_past : R.string.history_empty_bookings_past));

        LayoutInflater inflater = LayoutInflater.from(activity);
        for (ReservationSummary summary : bucket) {
            container.addView(buildHistoryCard(inflater, container, summary));
        }
    }

    // ---- Statement of Account -----------------------------------------------

    private void bindStatement(View v) {
        bindHeader(v, R.string.statement_title, () -> showScreen(SCREEN_MAIN));
        renderStatement(v);
        fetchHistory();
    }

    /** One card per paid-for booking (kind "reservation" - the ones that have a bill), newest
     *  first as the server returns them; cancelled bookings are listed but left out of the
     *  totals at the top. */
    private void renderStatement(View v) {
        LinearLayout container = v.findViewById(R.id.statementContainer);
        container.removeAllViews();
        double billed = 0;
        double paid = 0;
        double balance = 0;
        int count = 0;
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (ReservationSummary summary : historyData) {
            if (!"reservation".equals(summary.kind)) {
                continue;
            }
            count++;
            if (!summary.cancelled) {
                billed += summary.subtotal;
                paid += summary.amount_paid;
                balance += summary.remaining_balance;
            }
            container.addView(buildStatementCard(inflater, container, summary));
        }
        v.findViewById(R.id.statementEmptyState).setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        ((TextView) v.findViewById(R.id.statementTotalBilled)).setText(money(billed));
        ((TextView) v.findViewById(R.id.statementTotalPaid)).setText(money(paid));
        ((TextView) v.findViewById(R.id.statementBalanceDue)).setText(money(balance));
    }

    private View buildStatementCard(LayoutInflater inflater, LinearLayout parent, ReservationSummary summary) {
        // Reuses the history card for the booking's own title/dates/reference/status, then adds
        // the money breakdown and its payment lines underneath.
        View card = buildHistoryCard(inflater, parent, summary);
        card.findViewById(R.id.reservationCardTotal).setVisibility(View.GONE);
        LinearLayout lines = (LinearLayout) card;

        addStatementRow(lines, R.string.statement_line_total, money(summary.subtotal), R.attr.textPrimary, true);
        addStatementRow(lines, R.string.statement_line_paid, money(summary.amount_paid), R.attr.successText, false);
        addStatementRow(lines, R.string.statement_line_balance, money(summary.remaining_balance), R.attr.textPrimary, false);

        if (summary.payments == null || summary.payments.isEmpty()) {
            TextView none = statementText(R.attr.textMuted, 12, false);
            none.setText(R.string.statement_no_payments);
            none.setPadding(0, dp(10), 0, 0);
            lines.addView(none);
            return card;
        }
        for (ReservationSummary.PaymentLine payment : summary.payments) {
            View divider = new View(activity);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            dividerParams.topMargin = dp(10);
            divider.setLayoutParams(dividerParams);
            divider.setBackgroundColor(ThemeManager.color(activity, R.attr.textMuted));
            divider.setAlpha(0.25f);
            lines.addView(divider);

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(8), 0, 0);
            LinearLayout left = new LinearLayout(activity);
            left.setOrientation(LinearLayout.VERTICAL);
            left.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView what = statementText(R.attr.textPrimary, 13, true);
            what.setText(activity.getString(R.string.statement_payment_line,
                    methodLabel(payment.method), paymentDate(payment)));
            left.addView(what);
            if (payment.reference_number != null && !payment.reference_number.isEmpty()) {
                TextView ref = statementText(R.attr.textMuted, 12, false);
                ref.setText(activity.getString(R.string.statement_reference_line, payment.reference_number));
                left.addView(ref);
            }
            boolean verified = "verified".equals(payment.status);
            boolean cancelled = "cancelled".equals(payment.status);
            TextView state = statementText(verified ? R.attr.successText
                    : cancelled ? R.attr.errorText : R.attr.accentPrimary, 12, true);
            state.setText(verified ? R.string.statement_payment_verified
                    : cancelled ? R.string.statement_payment_cancelled
                    : R.string.statement_payment_pending);
            left.addView(state);
            row.addView(left);
            TextView amount = statementText(R.attr.textPrimary, 14, true);
            amount.setText(money(payment.amount));
            row.addView(amount);
            lines.addView(row);
        }
        return card;
    }

    private void addStatementRow(LinearLayout parent, int labelRes, String value, int valueColorAttr, boolean first) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(first ? 10 : 2), 0, 0);
        TextView label = statementText(R.attr.textMuted, 13, false);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        label.setText(labelRes);
        TextView amount = statementText(valueColorAttr, 13, true);
        amount.setText(value);
        row.addView(label);
        row.addView(amount);
        parent.addView(row);
    }

    private TextView statementText(int colorAttr, int sp, boolean bold) {
        TextView t = new TextView(activity);
        t.setTextColor(ThemeManager.color(activity, colorAttr));
        t.setTextSize(sp);
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return t;
    }

    private String methodLabel(String method) {
        if ("gcash".equalsIgnoreCase(method)) {
            return activity.getString(R.string.statement_method_gcash);
        }
        if (method == null || method.isEmpty()) {
            return "";
        }
        return method.substring(0, 1).toUpperCase(Locale.US) + method.substring(1);
    }

    private String paymentDate(ReservationSummary.PaymentLine payment) {
        long date = ReservationHistory.parseDate(payment.date);
        return date > 0 ? new SimpleDateFormat("MMM d, yyyy", Locale.US).format(new java.util.Date(date)) : "";
    }

    private String money(double amount) {
        return activity.getString(R.string.format_currency,
                NumberFormat.getInstance(Locale.US).format(Math.round(amount)));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private View buildHistoryCard(LayoutInflater inflater, android.view.ViewGroup parent, ReservationSummary summary) {
        View card = inflater.inflate(R.layout.view_my_reservation_list_card, parent, false);
        ((TextView) card.findViewById(R.id.reservationCardTitle)).setText(facilityTitle(summary));

        int colorAttr;
        int labelRes;
        if (summary.cancelled) {
            colorAttr = R.attr.errorText;
            labelRes = R.string.reservation_status_cancelled;
        } else if (ReservationHistory.isExpired(summary)) {
            colorAttr = R.attr.textMuted;
            labelRes = R.string.reservation_status_expired;
        } else if ("reservation".equals(summary.kind)) {
            colorAttr = R.attr.successText;
            labelRes = summary.current_step >= 3 ? R.string.history_status_checked_out
                    : summary.current_step == 2 ? R.string.history_status_checked_in
                    : summary.current_step == 1 ? R.string.history_status_confirmed
                    : R.string.reservation_status_booked;
        } else {
            colorAttr = R.attr.accentPrimary;
            labelRes = R.string.reservation_status_pending;
        }
        card.findViewById(R.id.reservationCardStatusDot).setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(ThemeManager.color(activity, colorAttr)));
        TextView statusLabel = card.findViewById(R.id.reservationCardStatusLabel);
        statusLabel.setText(labelRes);
        statusLabel.setTextColor(ThemeManager.color(activity, colorAttr));

        SimpleDateFormat shown = new SimpleDateFormat("MMM d, yyyy", Locale.US);
        long in = ReservationHistory.parseDate(summary.check_in);
        long out = ReservationHistory.parseDate(summary.check_out);
        ((TextView) card.findViewById(R.id.reservationCardDates)).setText(
                (in > 0 ? shown.format(new java.util.Date(in)) : "") + " – "
                        + (out > 0 ? shown.format(new java.util.Date(out)) : ""));
        ((TextView) card.findViewById(R.id.reservationCardReference)).setText(summary.booking_reference);
        ((TextView) card.findViewById(R.id.reservationCardTotal)).setText(activity.getString(
                R.string.format_currency, NumberFormat.getInstance(Locale.US).format(Math.round(summary.subtotal))));
        card.setOnClickListener(ClickGuard.wrap(view -> onOpenReservation.accept(summary)));
        return card;
    }

    /** Card title: the facility kinds in the record ("Hotel Room", "Cottage", "KTV Room", or
     *  several joined), never the room type name. A not-yet-paid reservation lists one line per
     *  item ("Room"/"Cottage"/"KTV Room"/"Function Hall"), which covers multi-facility ones; a
     *  paid booking's lines are item names, so it falls back to its single service type. */
    private String facilityTitle(ReservationSummary summary) {
        boolean hotel = false;
        boolean cottage = false;
        boolean ktv = false;
        boolean hall = false;
        // The server's facilities[] lists every facility kind in the record (multi-facility
        // bookings have several); older servers omit it, so the checks below remain as fallback.
        if (summary.facilities != null) {
            hotel = summary.facilities.contains("hotel");
            cottage = summary.facilities.contains("cottage");
            ktv = summary.facilities.contains("ktv");
            hall = summary.facilities.contains("hall");
        }
        if (!hotel && !cottage && !ktv && !hall
                && "advance_reservation".equals(summary.kind) && summary.service_lines != null) {
            for (String line : summary.service_lines) {
                if ("Room".equals(line)) {
                    hotel = true;
                } else if ("Cottage".equals(line)) {
                    cottage = true;
                } else if ("KTV Room".equals(line)) {
                    ktv = true;
                } else if ("Function Hall".equals(line)) {
                    hall = true;
                }
            }
        }
        if (!hotel && !cottage && !ktv && !hall) {
            hotel = "hotel".equals(summary.service_type);
            cottage = "cottage".equals(summary.service_type);
            ktv = "ktv".equals(summary.service_type);
            hall = "hall".equals(summary.service_type);
        }
        List<String> names = new ArrayList<>();
        if (hotel) {
            names.add(activity.getString(R.string.service_type_hotel));
        }
        if (cottage) {
            names.add(activity.getString(R.string.booking_type_cottage));
        }
        if (ktv) {
            names.add(activity.getString(R.string.service_type_ktv));
        }
        if (hall) {
            names.add(activity.getString(R.string.history_facility_hall));
        }
        return names.isEmpty() ? activity.getString(R.string.service_type_hotel) : String.join(", ", names);
    }

    private void fetchHistory() {
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty() || historyLoading) {
            return;
        }
        historyLoading = true;
        ApiClient.bookingApi().getMyBookings("Bearer " + token).enqueue(new Callback<MyBookingsResponse>() {
            @Override
            public void onResponse(Call<MyBookingsResponse> call, Response<MyBookingsResponse> response) {
                historyLoading = false;
                if (response.isSuccessful() && response.body() != null && response.body().reservations != null) {
                    historyData = response.body().reservations;
                    applyPendingHistoryReference();
                    refreshHistoryUi();
                }
            }

            @Override
            public void onFailure(Call<MyBookingsResponse> call, Throwable t) {
                // Keeps whatever was last loaded; the next poll or visit retries.
                historyLoading = false;
            }
        });
    }

    private void refreshHistoryUi() {
        if (currentScreenView == null) {
            return;
        }
        if (currentScreen == SCREEN_MAIN) {
            bindHistoryCard(currentScreenView);
        } else if (currentScreen == SCREEN_HISTORY) {
            renderHistory(currentScreenView);
        } else if (currentScreen == SCREEN_STATEMENT) {
            renderStatement(currentScreenView);
        }
    }

    private void startHistoryPolling() {
        if (historyPolling) {
            return;
        }
        historyPolling = true;
        historyHandler.postDelayed(this::historyPollTick, HISTORY_POLL_MS);
    }

    private void historyPollTick() {
        boolean onHistoryScreen = currentScreen == SCREEN_MAIN || currentScreen == SCREEN_HISTORY
                || currentScreen == SCREEN_STATEMENT;
        if (!onHistoryScreen || !root.isAttachedToWindow()) {
            historyPolling = false;
            return;
        }
        fetchHistory();
        historyHandler.postDelayed(this::historyPollTick, HISTORY_POLL_MS);
    }

    /** Shows the saved avatar bitmap, or the placeholder person icon when none is set. */
    private void bindAvatar(ImageView avatar) {
        Bitmap picture = ProfileStore.loadAvatar(activity);
        if (picture != null) {
            avatar.setPadding(0, 0, 0, 0);
            avatar.setImageTintList(null);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setImageBitmap(picture);
        } else {
            avatar.setPadding(16, 16, 16, 16);
            avatar.setImageResource(R.drawable.ic_person);
            avatar.setImageTintList(android.content.res.ColorStateList.valueOf(
                    ThemeManager.color(activity, R.attr.textOnScreen)));
            avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
        avatar.setClipToOutline(true);
    }

    private void bindActionRow(View row, int iconRes, int titleRes, int subtitleRes,
            String badgeText, int badgeBgRes, Runnable onClick) {
        ((ImageView) row.findViewById(R.id.rowIcon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.rowTitle)).setText(titleRes);
        ((TextView) row.findViewById(R.id.rowSubtitle)).setText(subtitleRes);
        TextView badge = row.findViewById(R.id.rowBadge);
        if (badgeText != null) {
            badge.setText(badgeText);
            badge.setBackgroundResource(badgeBgRes);
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }
        row.setOnClickListener(ClickGuard.wrap(view -> onClick.run()));
    }

    private void bindSwitchRowLabels(View row, int iconRes, int titleRes, int subtitleRes) {
        ((ImageView) row.findViewById(R.id.rowIcon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.rowTitle)).setText(titleRes);
        ((TextView) row.findViewById(R.id.rowSubtitle)).setText(subtitleRes);
    }

    private void bindSuccess(View v, int titleRes, int messageRes, Runnable onBack) {
        ((TextView) v.findViewById(R.id.successTitle)).setText(titleRes);
        ((TextView) v.findViewById(R.id.successMessage)).setText(messageRes);
        v.findViewById(R.id.successButton).setOnClickListener(ClickGuard.wrap(view -> onBack.run()));
    }

    // ---- Personal Information ----------------------------------------

    private void bindPersonalInfo(View v) {
        bindHeader(v, R.string.personal_info_title, () -> showScreen(SCREEN_MAIN));

        ((EditText) v.findViewById(R.id.piFirstNameField)).setText(ProfileStore.getFirstName(activity));
        ((EditText) v.findViewById(R.id.piMiddleNameField)).setText(ProfileStore.getMiddleName(activity));
        ((EditText) v.findViewById(R.id.piLastNameField)).setText(ProfileStore.getLastName(activity));
        setUpAddressSpinners(v);
        ((EditText) v.findViewById(R.id.piUsernameField)).setText(ProfileStore.getUsername(activity));
        ((EditText) v.findViewById(R.id.piEmailField)).setText(ProfileStore.getEmail(activity));

        setUpPromptedSpinner(v.findViewById(R.id.piGenderSpinner), R.string.gender_prompt,
                R.array.gender_options, ProfileStore.getGenderPos(activity));

        EditText birthDate = v.findViewById(R.id.piBirthDateField);
        birthDate.setText(ProfileStore.getBirthDate(activity));
        birthDate.setOnClickListener(ClickGuard.wrap(view -> showPastDatePicker(birthDate.getText().toString(), date -> {
            birthDate.setText(date);
            v.findViewById(R.id.piBirthDateError).setVisibility(View.GONE);
        })));

        EditText phoneNumber = v.findViewById(R.id.piPhoneNumberField);
        phoneNumber.setFilters(new InputFilter[]{DIGITS_ONLY_FILTER, new InputFilter.LengthFilter(PHONE_NUMBER_LENGTH)});
        phoneNumber.setText(ProfileStore.getContactNumber(activity));

        v.findViewById(R.id.piCancelButton).setOnClickListener(ClickGuard.wrap(view -> showScreen(SCREEN_MAIN)));
        v.findViewById(R.id.piChangeButton).setOnClickListener(ClickGuard.wrap(view -> {
            if (validatePersonalInfo(v)) {
                savePersonalInfo(v);
                showScreen(SCREEN_PERSONAL_INFO_SUCCESS);
            }
        }));
    }

    /**
     * The address last applied to the spinners. Spinner reports a selection after every
     * setSelection(), including the one that restores the saved value, so a report that matches
     * this is not a user change and must not clear the levels below it.
     */
    private static final class AddressSelection {
        String province = "";
        String city = "";
    }

    /**
     * Wires Province -> City/Municipality -> Barangay: City lists only the chosen province's
     * cities, Barangay only the chosen city's, and each stays disabled until the one above is
     * picked. The saved address is restored by name; a saved value the dataset doesn't contain
     * (e.g. free text from before these were dropdowns) falls back to the prompt.
     */
    private void setUpAddressSpinners(View v) {
        Spinner provinceSpinner = v.findViewById(R.id.piProvinceSpinner);
        AddressSelection applied = new AddressSelection();
        String savedProvince = ProfileStore.getProvince(activity);
        String savedCity = ProfileStore.getCity(activity);
        String savedBarangay = ProfileStore.getBarangay(activity);

        resetAddressSpinner(v.findViewById(R.id.piCitySpinner), R.string.city_prompt_select_province_first);
        resetAddressSpinner(v.findViewById(R.id.piBarangaySpinner), R.string.barangay_prompt_select_city_first);

        AddressRepository.getProvinces(activity, provinces -> {
            List<String> options = withPrompt(R.string.province_prompt, provinces);
            provinceSpinner.setAdapter(new AuthSpinnerAdapter(activity, options));

            int restoreIndex = Math.max(0, options.indexOf(savedProvince));
            provinceSpinner.setSelection(restoreIndex);
            applied.province = restoreIndex > 0 ? savedProvince : "";
            if (restoreIndex > 0) {
                loadCities(v, applied, savedProvince, savedCity, savedBarangay);
            }

            onPositionSelected(provinceSpinner, position -> {
                String picked = position > 0 ? options.get(position) : "";
                if (picked.equals(applied.province)) {
                    return;
                }
                applied.province = picked;
                applied.city = "";
                if (position > 0) {
                    loadCities(v, applied, picked, "", "");
                } else {
                    resetAddressSpinner(v.findViewById(R.id.piCitySpinner), R.string.city_prompt_select_province_first);
                    resetAddressSpinner(v.findViewById(R.id.piBarangaySpinner), R.string.barangay_prompt_select_city_first);
                }
            });
        });
    }

    private void loadCities(View v, AddressSelection applied, String province, String restoreCity,
            String restoreBarangay) {
        Spinner citySpinner = v.findViewById(R.id.piCitySpinner);
        citySpinner.setOnItemSelectedListener(null);
        AddressRepository.getCities(activity, province, cities -> {
            List<String> options = withPrompt(R.string.city_prompt, cities);
            citySpinner.setEnabled(true);
            citySpinner.setAdapter(new AuthSpinnerAdapter(activity, options));

            int restoreIndex = Math.max(0, options.indexOf(restoreCity));
            citySpinner.setSelection(restoreIndex);
            applied.city = restoreIndex > 0 ? restoreCity : "";
            if (restoreIndex > 0) {
                loadBarangays(v, province, restoreCity, restoreBarangay);
            } else {
                resetAddressSpinner(v.findViewById(R.id.piBarangaySpinner), R.string.barangay_prompt_select_city_first);
            }

            onPositionSelected(citySpinner, position -> {
                String picked = position > 0 ? options.get(position) : "";
                if (picked.equals(applied.city)) {
                    return;
                }
                applied.city = picked;
                if (position > 0) {
                    loadBarangays(v, province, picked, "");
                } else {
                    resetAddressSpinner(v.findViewById(R.id.piBarangaySpinner), R.string.barangay_prompt_select_city_first);
                }
            });
        });
    }

    private void loadBarangays(View v, String province, String city, String restoreBarangay) {
        Spinner barangaySpinner = v.findViewById(R.id.piBarangaySpinner);
        barangaySpinner.setOnItemSelectedListener(null);
        AddressRepository.getBarangays(activity, province, city, barangays -> {
            List<String> options = withPrompt(R.string.barangay_prompt, barangays);
            barangaySpinner.setEnabled(true);
            barangaySpinner.setAdapter(new AuthSpinnerAdapter(activity, options));
            barangaySpinner.setSelection(Math.max(0, options.indexOf(restoreBarangay)));
        });
    }

    /** A locked dropdown showing only "Select a ... first". */
    private void resetAddressSpinner(Spinner spinner, int promptRes) {
        spinner.setOnItemSelectedListener(null);
        spinner.setEnabled(false);
        spinner.setAdapter(new AuthSpinnerAdapter(activity,
                Collections.singletonList(activity.getString(promptRes))));
        spinner.setSelection(0);
    }

    private List<String> withPrompt(int promptRes, List<String> choices) {
        List<String> options = new ArrayList<>();
        options.add(activity.getString(promptRes));
        options.addAll(choices);
        return options;
    }

    private static void onPositionSelected(Spinner spinner, IntConsumer onPosition) {
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onPosition.accept(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** The chosen option's text, or "" while the dropdown is locked or still on its prompt. */
    private String selectedOption(View v, int spinnerId) {
        Spinner spinner = v.findViewById(spinnerId);
        return spinner.isEnabled() && spinner.getSelectedItemPosition() > 0
                ? (String) spinner.getSelectedItem() : "";
    }

    private boolean validatePersonalInfo(View v) {
        boolean valid = true;

        String firstName = fieldText(v, R.id.piFirstNameField);
        String middleName = fieldText(v, R.id.piMiddleNameField);
        String lastName = fieldText(v, R.id.piLastNameField);
        String birthDate = fieldText(v, R.id.piBirthDateField);
        String phoneNumber = fieldText(v, R.id.piPhoneNumberField);
        String province = selectedOption(v, R.id.piProvinceSpinner);
        String city = selectedOption(v, R.id.piCitySpinner);
        String barangay = selectedOption(v, R.id.piBarangaySpinner);
        String username = fieldText(v, R.id.piUsernameField);
        String email = fieldText(v, R.id.piEmailField);
        int genderPos = ((Spinner) v.findViewById(R.id.piGenderSpinner)).getSelectedItemPosition();

        valid &= checkField(v, R.id.piFirstNameError,
                firstName.matches("[a-zA-Z ]+"), R.string.error_name_invalid);
        valid &= checkField(v, R.id.piMiddleNameError,
                middleName.isEmpty() || middleName.matches("[a-zA-Z. ]+"), R.string.error_name_invalid);
        valid &= checkField(v, R.id.piLastNameError,
                lastName.matches("[a-zA-Z ]+"), R.string.error_name_invalid);
        valid &= checkField(v, R.id.piGenderError, genderPos > 0, R.string.error_gender_required);
        valid &= checkField(v, R.id.piPhoneNumberError,
                phoneNumber.matches("09\\d{9}"), R.string.error_phone_invalid);

        if (birthDate.isEmpty()) {
            valid &= checkField(v, R.id.piBirthDateError, false, R.string.error_birth_date_required);
        } else {
            int year = Integer.parseInt(birthDate.split("-")[0]);
            valid &= checkField(v, R.id.piBirthDateError,
                    Calendar.getInstance().get(Calendar.YEAR) - year >= 18, R.string.error_age_invalid);
        }

        valid &= checkField(v, R.id.piProvinceError, !province.isEmpty(), R.string.error_province_required);
        valid &= checkField(v, R.id.piCityError, !city.isEmpty(), R.string.error_city_required);
        valid &= checkField(v, R.id.piBarangayError, !barangay.isEmpty(), R.string.error_barangay_required);
        valid &= checkField(v, R.id.piUsernameError,
                username.length() >= 4 && username.length() <= 20 && username.matches("[a-zA-Z0-9_]+"),
                R.string.error_username_invalid);
        valid &= checkField(v, R.id.piEmailError,
                Patterns.EMAIL_ADDRESS.matcher(email).matches(), R.string.error_email_invalid);

        return valid;
    }

    private void savePersonalInfo(View v) {
        int genderPos = ((Spinner) v.findViewById(R.id.piGenderSpinner)).getSelectedItemPosition();
        ProfileStore.savePersonalInfo(activity,
                fieldText(v, R.id.piFirstNameField),
                fieldText(v, R.id.piMiddleNameField),
                fieldText(v, R.id.piLastNameField),
                genderPos,
                fieldText(v, R.id.piBirthDateField),
                fieldText(v, R.id.piPhoneNumberField),
                selectedOption(v, R.id.piProvinceSpinner),
                selectedOption(v, R.id.piCitySpinner),
                selectedOption(v, R.id.piBarangaySpinner),
                fieldText(v, R.id.piUsernameField),
                fieldText(v, R.id.piEmailField));
    }

    // ---- Change Password ----------------------------------------

    private void bindChangePassword(View v) {
        bindHeader(v, R.string.change_password_title, () -> showScreen(SCREEN_MAIN));

        EditText current = v.findViewById(R.id.cpCurrentPasswordField);
        EditText newPassword = v.findViewById(R.id.cpNewPasswordField);
        EditText confirm = v.findViewById(R.id.cpConfirmPasswordField);
        TextView strengthText = v.findViewById(R.id.cpStrengthText);

        AuthUiUtils.attachPasswordToggle(current, v.findViewById(R.id.cpCurrentToggle));
        AuthUiUtils.attachPasswordToggle(newPassword, v.findViewById(R.id.cpNewToggle));
        AuthUiUtils.attachPasswordToggle(confirm, v.findViewById(R.id.cpConfirmToggle));

        AuthUiUtils.afterTextChanged(newPassword, () -> {
            String pw = newPassword.getText().toString();
            AuthUiUtils.updatePasswordStrength(pw, strengthText);
            updatePasswordRequirements(v, pw);
        });
        updatePasswordRequirements(v, "");

        v.findViewById(R.id.cpCancelButton).setOnClickListener(ClickGuard.wrap(view -> showScreen(SCREEN_MAIN)));
        v.findViewById(R.id.cpUpdateButton).setOnClickListener(ClickGuard.wrap(view -> {
            boolean valid = true;
            String currentPw = current.getText().toString();
            String newPw = newPassword.getText().toString();
            String confirmPw = confirm.getText().toString();

            valid &= checkField(v, R.id.cpCurrentError,
                    !currentPw.isEmpty() && currentPw.equals(ProfileStore.getPassword(activity)),
                    R.string.error_password_incorrect);
            valid &= checkField(v, R.id.cpNewError,
                    newPw.matches(PASSWORD_COMPLEXITY_REGEX), R.string.error_password_complexity);
            valid &= checkField(v, R.id.cpConfirmError,
                    confirmPw.equals(newPw), R.string.error_passwords_dont_match);

            if (valid) {
                ProfileStore.setPassword(activity, newPw);
                showScreen(SCREEN_CHANGE_PASSWORD_SUCCESS);
            }
        }));
    }

    private void updatePasswordRequirements(View v, String password) {
        setRequirementState(v, R.id.cpReqLengthIcon, R.id.cpReqLengthText, password.length() >= 8);
        setRequirementState(v, R.id.cpReqCaseIcon, R.id.cpReqCaseText,
                password.matches(".*[A-Z].*") && password.matches(".*[a-z].*"));
        setRequirementState(v, R.id.cpReqNumberIcon, R.id.cpReqNumberText,
                password.matches(".*[0-9].*"));
        setRequirementState(v, R.id.cpReqSpecialIcon, R.id.cpReqSpecialText,
                password.matches(".*[^A-Za-z0-9].*"));
    }

    private void setRequirementState(View v, int iconId, int textId, boolean satisfied) {
        ImageView icon = v.findViewById(iconId);
        TextView text = v.findViewById(textId);
        icon.setColorFilter(satisfied ? 0xFF2E7D32 : ThemeManager.color(activity, R.attr.iconMuted));
        text.setTextColor(satisfied
                ? ThemeManager.color(activity, R.attr.textPrimary)
                : ThemeManager.color(activity, R.attr.textMuted));
    }

    // ---- Valid ID ----------------------------------------

    private void bindValidId(View v) {
        bindHeader(v, R.string.valid_id_title, () -> showScreen(SCREEN_MAIN));

        boolean hasUpload = ProfileStore.hasIdSubmission(activity);
        boolean verified = hasUpload && ProfileStore.isIdVerified(activity);
        String uploadDate = ProfileStore.getIdUploadDate(activity);

        TextView statusText = v.findViewById(R.id.viStatusText);
        TextView statusDesc = v.findViewById(R.id.viStatusDesc);
        int statusTextRes;
        int statusDescRes;
        int statusColor;
        if (!hasUpload) {
            statusTextRes = R.string.status_not_submitted;
            statusDescRes = R.string.no_id_uploaded_message;
            statusColor = ThemeManager.color(activity, R.attr.iconMuted);
        } else if (verified) {
            statusTextRes = R.string.status_verified;
            statusDescRes = R.string.verification_status_verified_desc;
            statusColor = 0xFF2E7D32;
        } else {
            statusTextRes = R.string.status_pending;
            statusDescRes = R.string.verification_status_pending_desc;
            statusColor = 0xFFE0912E;
        }
        statusText.setText(statusTextRes);
        statusText.setTextColor(statusColor);
        ((ImageView) v.findViewById(R.id.viStatusIcon)).setColorFilter(statusColor);
        statusDesc.setText(statusDescRes);

        v.findViewById(R.id.viUploadedIdCard).setVisibility(hasUpload ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.viNoIdMessage).setVisibility(hasUpload ? View.GONE : View.VISIBLE);
        if (hasUpload) {
            String[] idTypes = activity.getResources().getStringArray(R.array.id_type_options);
            int idTypePos = ProfileStore.getIdTypePos(activity) - 1;
            String idTypeLabel = idTypePos >= 0 && idTypePos < idTypes.length
                    ? idTypes[idTypePos] : "";
            ((TextView) v.findViewById(R.id.viIdTypeText)).setText(idTypeLabel);
            ((TextView) v.findViewById(R.id.viUploadedOnText)).setText(
                    activity.getString(R.string.uploaded_on_format, uploadDate));
            ((TextView) v.findViewById(R.id.viIdNumberText)).setText(
                    activity.getString(R.string.id_number_format, ProfileStore.getIdNumber(activity)));
        }

        v.findViewById(R.id.viUploadNewIdButton).setOnClickListener(ClickGuard.wrap(view -> showScreen(SCREEN_UPLOAD_ID)));
        v.findViewById(R.id.viGuidelinesButton).setOnClickListener(ClickGuard.wrap(view ->
                Toast.makeText(activity, R.string.coming_soon_toast, Toast.LENGTH_SHORT).show()));
    }

    // ---- Upload New ID ----------------------------------------

    private void bindUploadId(View v) {
        bindHeader(v, R.string.upload_new_id_title, () -> showScreen(SCREEN_VALID_ID));

        // Always starts as a fresh, blank submission.
        idFrontBitmap = null;
        idBackBitmap = null;

        setUpPromptedSpinner(v.findViewById(R.id.uiIdTypeSpinner), R.string.id_type_prompt,
                R.array.id_type_options, 0);

        v.findViewById(R.id.uiFrontDropzone).setOnClickListener(ClickGuard.wrap(view -> pickImage(REQUEST_ID_FRONT)));
        v.findViewById(R.id.uiBackDropzone).setOnClickListener(ClickGuard.wrap(view -> pickImage(REQUEST_ID_BACK)));

        EditText expiry = v.findViewById(R.id.uiExpiryField);
        expiry.setOnClickListener(ClickGuard.wrap(view -> showFutureDatePicker(date -> expiry.setText(date))));

        v.findViewById(R.id.uiCancelButton).setOnClickListener(ClickGuard.wrap(view -> showScreen(SCREEN_VALID_ID)));
        v.findViewById(R.id.uiSubmitButton).setOnClickListener(ClickGuard.wrap(view -> {
            boolean valid = true;
            int idTypePos = ((Spinner) v.findViewById(R.id.uiIdTypeSpinner)).getSelectedItemPosition();
            String idNumber = fieldText(v, R.id.uiIdNumberField);

            valid &= checkField(v, R.id.uiIdTypeError, idTypePos > 0, R.string.error_id_type_required);
            valid &= checkField(v, R.id.uiFrontError, idFrontBitmap != null, R.string.error_id_required);
            valid &= checkField(v, R.id.uiIdNumberError, !idNumber.isEmpty(),
                    R.string.error_id_number_required);

            if (valid) {
                String today = new SimpleDateFormat("MMMM d, yyyy", Locale.US)
                        .format(Calendar.getInstance().getTime());
                ProfileStore.saveIdSubmission(activity, idTypePos, idNumber, today);
                Toast.makeText(activity, R.string.id_submitted_message, Toast.LENGTH_LONG).show();
                showScreen(SCREEN_VALID_ID);
            }
        }));
    }

    private void pickImage(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        activity.startActivityForResult(intent, requestCode);
    }

    // ---- Shared helpers ----------------------------------------

    private void bindHeader(View v, int titleRes, Runnable onBack) {
        View header = v.findViewById(R.id.headerBar);
        ((TextView) header.findViewById(R.id.headerTitle)).setText(titleRes);
        header.findViewById(R.id.headerBackButton).setOnClickListener(ClickGuard.wrap(view -> onBack.run()));
    }

    private void setUpPromptedSpinner(Spinner spinner, int promptRes, int optionsRes, int selection) {
        List<String> options = new ArrayList<>();
        options.add(activity.getString(promptRes));
        options.addAll(Arrays.asList(activity.getResources().getStringArray(optionsRes)));
        ArrayAdapter<String> adapter = new AuthSpinnerAdapter(activity, options);
        spinner.setAdapter(adapter);
        spinner.setSelection(selection);
    }

    private String fieldText(View v, int fieldId) {
        return ((EditText) v.findViewById(fieldId)).getText().toString().trim();
    }

    private boolean checkField(View v, int errorId, boolean ok, int messageRes) {
        TextView errorView = v.findViewById(errorId);
        if (ok) {
            errorView.setVisibility(View.GONE);
        } else {
            errorView.setText(messageRes);
            errorView.setVisibility(View.VISIBLE);
        }
        return ok;
    }

    private void showPastDatePicker(String initialDate, DateCallback callback) {
        long current = -1;
        try {
            if (!initialDate.isEmpty()) {
                Calendar c = Calendar.getInstance();
                String[] parts = initialDate.split("-");
                c.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
                current = c.getTimeInMillis();
            }
        } catch (Exception ignored) {}

        GlassDatePicker.showBirthdatePicker(activity, current, millis -> {
            callback.onDatePicked(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date(millis)));
        });
    }

    private void showFutureDatePicker(DateCallback callback) {
        GlassDatePicker.showCalendarPicker(activity, -1, System.currentTimeMillis() - 1000L, millis -> {
            callback.onDatePicked(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date(millis)));
        });
    }
}
