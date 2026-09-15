package com.aguafriogarden.resortinc;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aguafriogarden.resortinc.network.AmenityAvailability;
import com.aguafriogarden.resortinc.network.AmenityListResponse;
import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.AvailabilityResponse;
import com.aguafriogarden.resortinc.network.CottageKtvListResponse;
import com.aguafriogarden.resortinc.network.CottageKtvOption;
import com.aguafriogarden.resortinc.network.ErrorResponse;
import com.aguafriogarden.resortinc.network.FoodListResponse;
import com.aguafriogarden.resortinc.network.MenuItemAvailability;
import com.aguafriogarden.resortinc.network.ReserveResponse;
import com.aguafriogarden.resortinc.network.RoomListResponse;
import com.aguafriogarden.resortinc.network.RoomSummary;
import com.aguafriogarden.resortinc.network.RoomTypeAvailability;
import com.bumptech.glide.Glide;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Drives the Hotel Rooms booking flow reached from the Book tab: stay dates,
 * room selection, review, optional amenities/food, billing summary, mock
 * GCash payment and a success screen. Rooms, amenities and food all come
 * from the shared backend (see {@link #fetchAvailability}, {@link #fetchAmenities}
 * and {@link #fetchFood}) with real photos loaded via Glide. One instance is
 * kept alive by {@link BookingCatalogController} for as long as the Book tab
 * exists, so answers survive navigating to another module and back;
 * {@link #resetFlow()} clears them once a booking is completed.
 */
final class HotelBookingFlowController {

    private static final int SCREEN_REVIEW = 2;
    private static final int SCREEN_AMENITY = 3;
    private static final int SCREEN_FOOD = 4;
    private static final int SCREEN_BILLING = 5;
    private static final int SCREEN_PAYMENT = 6;
    private static final int SCREEN_SUCCESS = 7;
    // Room Overview's "Book Now" entry only — see #bindRoomDates and #showRoomDatesEntry.
    private static final int SCREEN_ROOM_DATES = 10;
    // Cottage Overview's "Book Now" entry only — see #bindCottageDatesEntry and #showCottageDatesEntry.
    private static final int SCREEN_COTTAGE_DATES = 11;
    // KTV Overview's "Book Now" entry only — see #bindKtvDatesEntry and #showKtvDatesEntry.
    private static final int SCREEN_KTV_DATES = 12;
    // "Your Selected ..." screens, reached from the dates screens above only.
    private static final int SCREEN_ROOM_SELECTION = 13;
    private static final int SCREEN_COTTAGE_SELECTION = 14;
    private static final int SCREEN_KTV_SELECTION = 15;
    // "Add Another ..." browse screens, reached from the selection screens above only.
    private static final int SCREEN_ROOM_BROWSE = 16;
    private static final int SCREEN_COTTAGE_BROWSE = 17;
    private static final int SCREEN_KTV_BROWSE = 18;
    // Cottage/KTV flows' own Review Your Selection, reached from Your Selected Cottage/KTV Room/s
    // only — separate from SCREEN_REVIEW, which is Hotel Rooms-only (stay dates/nights, room rows).
    private static final int SCREEN_COTTAGE_REVIEW = 19;
    private static final int SCREEN_KTV_REVIEW = 22;
    // Cottage/KTV flows' own Billing Summary/Payment — reuse the same layouts as SCREEN_BILLING/
    // SCREEN_PAYMENT (see layoutFor) with separate bind methods (bindCottageBilling/
    // bindCottagePayment/bindKtvBilling/bindKtvPayment), same pattern as the Room/Cottage/KTV
    // Selection screens sharing view_item_selection.xml. Amenity/Food themselves stay fully
    // shared (see activeContinuation).
    private static final int SCREEN_COTTAGE_BILLING = 20;
    private static final int SCREEN_COTTAGE_PAYMENT = 21;
    private static final int SCREEN_KTV_BILLING = 23;
    private static final int SCREEN_KTV_PAYMENT = 24;
    // Dedicated Summary screen for a Reserve run (any category — see reserveMode), reached from
    // Order Food instead of that category's own Billing/Payment. One shared layout/bind method
    // (bindReservationSummary) branching on activeContinuation for its category-specific fields,
    // same reuse pattern as the Billing screens.
    private static final int SCREEN_RESERVATION_SUMMARY = 25;
    // Confirmation screen shown after tapping RESERVE on the Reservation Summary — a terminal,
    // mock "request submitted" screen (no real backend endpoint yet), matching SCREEN_SUCCESS's
    // own point-of-no-return pattern (back/Return Home both reset the flow).
    private static final int SCREEN_RESERVATION_CONFIRMED = 26;
    // Reservation Confirmed's "View Reservation" button — a standalone status screen for the
    // reservation just submitted (reference/accommodation/date/guest, guest info, and a mock
    // Pay Now since there's no backend to actually process payment against yet).
    private static final int SCREEN_RESERVATION_DETAILS = 27;
    // Pay Now on Reservation Details — see bindReservationPayment.
    private static final int SCREEN_RESERVATION_PAYMENT = 28;
    // Reservation Payment's Complete Payment button — a terminal, mock "payment submitted"
    // screen, matching SCREEN_RESERVATION_CONFIRMED's own point-of-no-return pattern.
    private static final int SCREEN_RESERVATION_PAYMENT_CONFIRMED = 29;

    private static final int PAYMENT_NONE = 0;
    private static final int PAYMENT_FULL = 1;
    private static final int PAYMENT_PARTIAL = 2;

    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;
    private static final double PARTIAL_PAYMENT_RATE = 0.30;
    private static final int REQUEST_PAYMENT_SCREENSHOT = 4101;
    private static final int REFERENCE_NUMBER_LENGTH = 13;

    // Reservation lifecycle statuses — identical across Hotel/Cottage/KTV (see
    // resolveReservationStatus). BOOKED/CANCELLED are receptionist-only and unreachable until
    // that system exists; EXPIRED is derived automatically, never stored.
    private static final int RES_STATUS_PENDING = 0;
    private static final int RES_STATUS_BOOKED = 1;
    private static final int RES_STATUS_CANCELLED = 2;
    private static final int RES_STATUS_EXPIRED = 3;
    private static final long RESERVATION_PAYMENT_WINDOW_MS = 7L * ONE_DAY_MS;

    // Reservation Details' own timeline row states — see buildReservationTimeline/
    // bindReservationTimeline. Mirrors ReservationStore.TIMELINE_* in spirit, kept local since
    // this timeline isn't backed by that store.
    private static final int TL_FILLED = 0;
    private static final int TL_CURRENT = 1;
    private static final int TL_UPCOMING = 2;

    /** Strips out anything that isn't a digit (Payment screen's Reference Number field). */
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

    /** Digits plus a single decimal point (Payment screen's Amount Paid field). */
    private static final InputFilter AMOUNT_INPUT_FILTER = (source, start, end, dest, dstart, dend) -> {
        boolean alreadyHasDot = dest.toString().contains(".");
        StringBuilder kept = new StringBuilder();
        boolean rejectedAny = false;
        for (int i = start; i < end; i++) {
            char c = source.charAt(i);
            if (Character.isDigit(c)) {
                kept.append(c);
            } else if (c == '.' && !alreadyHasDot) {
                kept.append(c);
                alreadyHasDot = true;
            } else {
                rejectedAny = true;
            }
        }
        return rejectedAny ? kept.toString() : null;
    };

    // Client-side placeholder durations for the KTV rate types, matching the Reserve tab's
    // ReservationFlowController — no backend field for this exists yet.
    private static final int KTV_REGULAR_HOURS = 5;
    private static final int KTV_CONSUMABLE_HOURS = 3;

    private final Activity activity;
    private final Runnable onBookNowOtherCategory;
    private final Runnable onBackToHome;
    private final FrameLayout root;

    private int currentScreen = -1;
    private View currentScreenView;

    // Set from Cottage/KTV Review's Next onward (through Amenity/Food, which are otherwise
    // shared with the Hotel Rooms flow) until that category's own Payment screen or back past
    // its Review — lets those shared screens know to route back/forward into the Cottage/KTV
    // continuation instead of the Hotel Rooms one. Reset by resetFlow().
    private static final int CONTINUATION_NONE = 0;
    private static final int CONTINUATION_COTTAGE = 1;
    private static final int CONTINUATION_KTV = 2;
    private int activeContinuation = CONTINUATION_NONE;

    // True for a Room/Cottage/KTV entered via its Overview's "Reserve Now" (see
    // showRoomDatesEntry/showCottageDatesEntry/showKtvDatesEntry) — the flow is otherwise
    // identical to Book (same Dates/Selection/Review/Amenity/Food screens), but Food leads to
    // the dedicated Reservation Summary screen (see SCREEN_RESERVATION_SUMMARY) instead of the
    // category's own Billing/Payment continuation. Reset by resetFlow().
    private boolean reserveMode = false;

    // ---- "Your Selected ..." screen state (Room/Cottage/KTV Overview entry only) -----------
    // variant_id of the item the guest originally picked before login; -1 means none (e.g. the
    // Book tab's own tabbed entry never sets this). Reset each time a dates-entry screen is
    // (re)entered via showRoomDatesEntry/showCottageDatesEntry/showKtvDatesEntry.
    private int selectionOriginVariantId = -1;
    // Non-null only for the duration of one focused Room/Cottage/KTV Dates entry (set by
    // showRoomDatesEntry/showCottageDatesEntry/showKtvDatesEntry, cleared by resetFlow()). Lets
    // the dates-entry screen's back arrow (and system back, see handleBackPressed) return to
    // whichever picker/Overview screen the flow was entered from.
    private Runnable onBackToOverview;
    // Whether "other available" alternatives are shown below the originally-chosen item — always
    // true once that item turns out unavailable, otherwise only after "Add Another Room" is tapped.
    private boolean selectionShowOthers = false;

    // ---- Persisted answers (survive navigating away and back) -----------
    private long checkInMillis = -1;
    private long checkOutMillis = -1;
    private int adults = 0;
    private int children = 0;

    // ---- Rate type / date / time / guests (Cottage tab) ------------------
    private String cottageRateType = "";
    private long cottageDateMillis = -1;
    private int cottageTimeHour = -1;
    private int cottageTimeMinute = -1;
    private int cottageAdults = 1;
    private int cottageChildren = 0;

    // ---- Rate type / date / time / guests (KTV tab) -----------------------
    private String ktvRateType = "";
    private long ktvDateMillis = -1;
    private int ktvStartHour = -1;
    private int ktvStartMinute = -1;
    private int ktvGuestCount = 1;

    private List<CottageKtvOption> cottageItems = new ArrayList<>();
    private List<CottageKtvOption> ktvItems = new ArrayList<>();
    private boolean isLoadingCottageKtv = false;
    private boolean cottageKtvLoaded = false;
    private List<RoomTypeAvailability> availableRoomTypes = new ArrayList<>();
    private final Map<Integer, Integer> roomQuantities = new LinkedHashMap<>();
    private boolean isLoadingAvailability = false;
    private String specialRequest = "";
    private List<AmenityAvailability> availableAmenities = new ArrayList<>();
    private final Map<Integer, Integer> amenityQuantities = new LinkedHashMap<>();
    private boolean isLoadingAmenities = false;
    private List<MenuItemAvailability> availableFood = new ArrayList<>();
    private final Map<Integer, Integer> foodQuantities = new LinkedHashMap<>();
    private boolean isLoadingFood = false;
    private int paymentOption = PAYMENT_NONE;
    private boolean termsChecked;
    private boolean cancellationChecked;
    private String amountPaidText = "";
    private String referenceNumberText = "";
    private Uri screenshotUri;
    private String bookingReference = "";
    private String reservationReferenceNo = "";
    // Reservation lifecycle state (see resolveReservationStatus) — reservationStatus only ever
    // becomes RES_STATUS_PENDING in this app today (no receptionist system to move it to
    // BOOKED/CANCELLED yet); reservationCancelReason is likewise unused until that exists.
    private int reservationStatus = RES_STATUS_PENDING;
    private boolean reservationPaymentSubmitted = false;
    private long reservationSubmittedAtMillis = -1;
    private long reservationPaymentSubmittedAtMillis = -1;
    private long reservationBookedAtMillis = -1;
    private long reservationCancelledAtMillis = -1;
    private String reservationCancelReason = "";

    HotelBookingFlowController(Activity activity, Runnable onBookNowOtherCategory, Runnable onBackToHome) {
        this.activity = activity;
        this.onBookNowOtherCategory = onBookNowOtherCategory;
        this.onBackToHome = onBackToHome;
        root = new FrameLayout(activity);
        // No default screen shown here — every caller (BookingCatalogController,
        // ReserveCatalogController) immediately follows construction with
        // showRoomDatesEntry/showCottageDatesEntry/showKtvDatesEntry, so root stays empty only
        // for the instant between the two calls.
    }

    View getRootView() {
        return root;
    }

    /**
     * Entry point for LandingActivity's Room Overview "Book Now"/"Reserve Now" (Hotel Rooms
     * only, see BookingCatalogController#showHotelFlowFromRoomOverview) — lands on the focused
     * Choose Your Stay Dates gate screen instead of the Book tab's own tabbed dates+search
     * screen. Defaults to 1 adult if the shared guest fields are still untouched, matching that
     * screen's own Cottage/KTV tabs' default. reserveOnly routes Order Food into the
     * Reservation Summary screen (see reserveMode) instead of Billing/Payment.
     */
    void showRoomDatesEntry(int variantId, Runnable onBackToOverview, boolean reserveOnly) {
        if (adults == 0 && children == 0) {
            adults = 1;
        }
        selectionOriginVariantId = variantId;
        selectionShowOthers = false;
        this.onBackToOverview = onBackToOverview;
        this.reserveMode = reserveOnly;
        showScreen(SCREEN_ROOM_DATES);
    }

    /**
     * Entry point for LandingActivity's Room/Cottage Overview "Book Now"/"Reserve Now" (Cottages
     * only, see BookingCatalogController#showHotelFlowFromCottageOverview) — lands on the
     * focused Choose Your Visit Date gate screen instead of the Book tab's own tabbed
     * dates+search screen. reserveOnly routes Order Food into the Reservation Summary screen
     * (see reserveMode) instead of Cottage Billing/Payment.
     */
    void showCottageDatesEntry(int variantId, Runnable onBackToOverview, boolean reserveOnly) {
        selectionOriginVariantId = variantId;
        selectionShowOthers = false;
        this.onBackToOverview = onBackToOverview;
        this.reserveMode = reserveOnly;
        showScreen(SCREEN_COTTAGE_DATES);
    }

    /**
     * Entry point for LandingActivity's Room/Cottage/KTV Overview "Book Now"/"Reserve Now" (KTV
     * only, see BookingCatalogController#showHotelFlowFromKtvOverview) — lands on the focused
     * Plan Your KTV Session gate screen instead of the Book tab's own tabbed dates+search
     * screen. reserveOnly routes Order Food into the Reservation Summary screen (see
     * reserveMode) instead of KTV Billing/Payment.
     */
    void showKtvDatesEntry(int variantId, Runnable onBackToOverview, boolean reserveOnly) {
        selectionOriginVariantId = variantId;
        selectionShowOthers = false;
        this.onBackToOverview = onBackToOverview;
        this.reserveMode = reserveOnly;
        showScreen(SCREEN_KTV_DATES);
    }

    /** Steps back one screen at a time. The Room/Cottage/KTV Dates entry screens are the flow's
     *  own root (only reached via the Book/Reserve tabs' picker or a Room/Cottage/KTV Overview's
     *  "Book Now"/"Reserve Now" — see showRoomDatesEntry etc.), so they step back to whichever of
     *  those the flow was entered from instead; Success exits the flow back to Home. */
    boolean handleBackPressed() {
        switch (currentScreen) {
            case SCREEN_ROOM_DATES:
            case SCREEN_COTTAGE_DATES:
            case SCREEN_KTV_DATES:
                onBackToOverview.run();
                return true;
            case SCREEN_ROOM_SELECTION:
                showScreen(SCREEN_ROOM_DATES);
                return true;
            case SCREEN_COTTAGE_SELECTION:
                showScreen(SCREEN_COTTAGE_DATES);
                return true;
            case SCREEN_KTV_SELECTION:
                showScreen(SCREEN_KTV_DATES);
                return true;
            case SCREEN_ROOM_BROWSE:
                showScreen(SCREEN_ROOM_SELECTION);
                return true;
            case SCREEN_COTTAGE_BROWSE:
                showScreen(SCREEN_COTTAGE_SELECTION);
                return true;
            case SCREEN_KTV_BROWSE:
                showScreen(SCREEN_KTV_SELECTION);
                return true;
            case SCREEN_COTTAGE_REVIEW:
                showScreen(SCREEN_COTTAGE_SELECTION);
                return true;
            case SCREEN_KTV_REVIEW:
                showScreen(SCREEN_KTV_SELECTION);
                return true;
            case SCREEN_REVIEW:
                // SCREEN_REVIEW is only ever reached from Your Selected Room/s' Next button now.
                showScreen(SCREEN_ROOM_SELECTION);
                return true;
            case SCREEN_AMENITY:
                showScreen(reviewScreenForContinuation());
                return true;
            case SCREEN_FOOD:
                showScreen(SCREEN_AMENITY);
                return true;
            case SCREEN_BILLING:
                showScreen(SCREEN_FOOD);
                return true;
            case SCREEN_PAYMENT:
                showScreen(SCREEN_BILLING);
                return true;
            case SCREEN_COTTAGE_BILLING:
                showScreen(SCREEN_FOOD);
                return true;
            case SCREEN_COTTAGE_PAYMENT:
                showScreen(SCREEN_COTTAGE_BILLING);
                return true;
            case SCREEN_KTV_BILLING:
                showScreen(SCREEN_FOOD);
                return true;
            case SCREEN_KTV_PAYMENT:
                showScreen(SCREEN_KTV_BILLING);
                return true;
            case SCREEN_RESERVATION_SUMMARY:
                showScreen(SCREEN_FOOD);
                return true;
            case SCREEN_SUCCESS:
            case SCREEN_RESERVATION_CONFIRMED:
            case SCREEN_RESERVATION_PAYMENT_CONFIRMED:
                resetFlow();
                onBackToHome.run();
                return true;
            case SCREEN_RESERVATION_DETAILS:
                showScreen(SCREEN_RESERVATION_CONFIRMED);
                return true;
            case SCREEN_RESERVATION_PAYMENT:
                showScreen(SCREEN_RESERVATION_DETAILS);
                return true;
            default:
                return true;
        }
    }

    /** Which Review screen the shared Amenity/Food screens should step back to. */
    private int reviewScreenForContinuation() {
        switch (activeContinuation) {
            case CONTINUATION_COTTAGE:
                return SCREEN_COTTAGE_REVIEW;
            case CONTINUATION_KTV:
                return SCREEN_KTV_REVIEW;
            default:
                return SCREEN_REVIEW;
        }
    }

    /** Which Billing screen Food's Skip/Next should advance to. */
    private int billingScreenForContinuation() {
        if (reserveMode) {
            return SCREEN_RESERVATION_SUMMARY;
        }
        switch (activeContinuation) {
            case CONTINUATION_COTTAGE:
                return SCREEN_COTTAGE_BILLING;
            case CONTINUATION_KTV:
                return SCREEN_KTV_BILLING;
            default:
                return SCREEN_BILLING;
        }
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PAYMENT_SCREENSHOT || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        screenshotUri = data.getData();
        if ((currentScreen == SCREEN_PAYMENT || currentScreen == SCREEN_COTTAGE_PAYMENT
                || currentScreen == SCREEN_KTV_PAYMENT || currentScreen == SCREEN_RESERVATION_PAYMENT)
                && currentScreenView != null) {
            showScreenshotPreview(currentScreenView);
        }
    }

    private void showScreen(int screen) {
        currentScreen = screen;
        View v = LayoutInflater.from(activity).inflate(layoutFor(screen), root, false);
        bindScreen(screen, v);
        root.removeAllViews();
        root.addView(v);
        currentScreenView = v;
    }

    private int layoutFor(int screen) {
        switch (screen) {
            case SCREEN_REVIEW:
                return R.layout.view_hotel_review;
            case SCREEN_AMENITY:
                return R.layout.view_hotel_amenity;
            case SCREEN_FOOD:
                return R.layout.view_hotel_food;
            case SCREEN_BILLING:
                return R.layout.view_hotel_billing;
            case SCREEN_PAYMENT:
                return R.layout.view_hotel_payment;
            case SCREEN_SUCCESS:
                return R.layout.view_hotel_success;
            case SCREEN_ROOM_DATES:
                return R.layout.view_room_booking_dates;
            case SCREEN_COTTAGE_DATES:
                return R.layout.view_cottage_booking_dates;
            case SCREEN_KTV_DATES:
                return R.layout.view_ktv_booking_dates;
            case SCREEN_ROOM_SELECTION:
            case SCREEN_COTTAGE_SELECTION:
            case SCREEN_KTV_SELECTION:
                return R.layout.view_item_selection;
            case SCREEN_COTTAGE_REVIEW:
                return R.layout.view_cottage_review;
            case SCREEN_KTV_REVIEW:
                return R.layout.view_ktv_review;
            case SCREEN_COTTAGE_BILLING:
            case SCREEN_KTV_BILLING:
                return R.layout.view_hotel_billing;
            case SCREEN_COTTAGE_PAYMENT:
            case SCREEN_KTV_PAYMENT:
                return R.layout.view_hotel_payment;
            case SCREEN_RESERVATION_SUMMARY:
                return R.layout.view_reservation_summary_flow;
            case SCREEN_RESERVATION_CONFIRMED:
                return R.layout.view_reservation_confirmed;
            case SCREEN_RESERVATION_DETAILS:
                return R.layout.view_hotel_reservation_details;
            case SCREEN_RESERVATION_PAYMENT:
                return R.layout.view_hotel_payment;
            case SCREEN_RESERVATION_PAYMENT_CONFIRMED:
                return R.layout.view_reservation_payment_confirmed;
            case SCREEN_ROOM_BROWSE:
            case SCREEN_COTTAGE_BROWSE:
            case SCREEN_KTV_BROWSE:
                return R.layout.view_item_browse;
            default:
                throw new IllegalStateException("Unknown screen: " + screen);
        }
    }

    private void bindScreen(int screen, View v) {
        switch (screen) {
            case SCREEN_REVIEW:
                bindReview(v);
                break;
            case SCREEN_AMENITY:
                bindAmenity(v);
                break;
            case SCREEN_FOOD:
                bindFood(v);
                break;
            case SCREEN_BILLING:
                bindBilling(v);
                break;
            case SCREEN_PAYMENT:
                bindPayment(v);
                break;
            case SCREEN_SUCCESS:
                bindSuccess(v);
                break;
            case SCREEN_ROOM_DATES:
                bindRoomDates(v);
                break;
            case SCREEN_COTTAGE_DATES:
                bindCottageDatesEntry(v);
                break;
            case SCREEN_KTV_DATES:
                bindKtvDatesEntry(v);
                break;
            case SCREEN_ROOM_SELECTION:
                bindRoomSelection(v);
                break;
            case SCREEN_COTTAGE_SELECTION:
                bindCottageSelection(v);
                break;
            case SCREEN_KTV_SELECTION:
                bindKtvSelection(v);
                break;
            case SCREEN_ROOM_BROWSE:
                bindRoomBrowse(v);
                break;
            case SCREEN_COTTAGE_BROWSE:
                bindCottageBrowse(v);
                break;
            case SCREEN_KTV_BROWSE:
                bindKtvBrowse(v);
                break;
            case SCREEN_COTTAGE_REVIEW:
                bindCottageReview(v);
                break;
            case SCREEN_KTV_REVIEW:
                bindKtvReview(v);
                break;
            case SCREEN_COTTAGE_BILLING:
                bindCottageBilling(v);
                break;
            case SCREEN_COTTAGE_PAYMENT:
                bindCottagePayment(v);
                break;
            case SCREEN_KTV_BILLING:
                bindKtvBilling(v);
                break;
            case SCREEN_KTV_PAYMENT:
                bindKtvPayment(v);
                break;
            case SCREEN_RESERVATION_SUMMARY:
                bindReservationSummary(v);
                break;
            case SCREEN_RESERVATION_CONFIRMED:
                bindReservationConfirmed(v);
                break;
            case SCREEN_RESERVATION_DETAILS:
                bindReservationDetails(v);
                break;
            case SCREEN_RESERVATION_PAYMENT:
                bindReservationPayment(v);
                break;
            case SCREEN_RESERVATION_PAYMENT_CONFIRMED:
                bindReservationPaymentConfirmed(v);
                break;
            default:
                throw new IllegalStateException("Unknown screen: " + screen);
        }
    }

    // ---- Screen 0b: Choose Your Stay Dates (Room Overview entry only) -----

    /**
     * Focused check-in/check-out/adults/children/Next form for Room Overview's "Book Now" (see
     * #showRoomDatesEntry). Feeds the same shared checkInMillis/checkOutMillis/adults/children
     * fields the tabbed Book-tab screen uses, so tapping Next just switches to that screen (with
     * the Hotel Rooms tab forced active) and immediately runs its availability search — reusing
     * its room-selection/results logic rather than duplicating it here.
     */
    private void bindRoomDates(View v) {
        bindHeader(v, R.id.roomDatesHeaderBar, R.string.room_dates_title, onBackToOverview);

        TextView checkInDateText = v.findViewById(R.id.roomDatesCheckInDateText);
        TextView checkInDayText = v.findViewById(R.id.roomDatesCheckInDayText);
        TextView checkOutDateText = v.findViewById(R.id.roomDatesCheckOutDateText);
        TextView checkOutDayText = v.findViewById(R.id.roomDatesCheckOutDayText);
        TextView checkInError = v.findViewById(R.id.roomDatesCheckInError);
        TextView checkOutError = v.findViewById(R.id.roomDatesCheckOutError);
        EditText adultsValue = v.findViewById(R.id.roomDatesAdultsValue);
        EditText childrenValue = v.findViewById(R.id.roomDatesChildrenValue);

        updateDateField(checkInDateText, checkInDayText, checkInMillis);
        updateDateField(checkOutDateText, checkOutDayText, checkOutMillis);

        View.OnClickListener openDatePicker = view -> GlassDatePicker.showRangePicker(activity, checkInMillis, checkOutMillis, System.currentTimeMillis() - 1000L, (start, end) -> {
            checkInMillis = start;
            checkOutMillis = end;
            updateDateField(checkInDateText, checkInDayText, checkInMillis);
            updateDateField(checkOutDateText, checkOutDayText, checkOutMillis);
            checkInError.setVisibility(View.GONE);
            checkOutError.setVisibility(View.GONE);
        });
        v.findViewById(R.id.roomDatesCheckInField).setOnClickListener(openDatePicker);
        v.findViewById(R.id.roomDatesCheckOutField).setOnClickListener(openDatePicker);

        AuthUiUtils.bindQuantityStepper(adultsValue, v.findViewById(R.id.roomDatesAdultsMinus),
                v.findViewById(R.id.roomDatesAdultsPlus), adults, 0, Integer.MAX_VALUE, value -> adults = value);
        AuthUiUtils.bindQuantityStepper(childrenValue, v.findViewById(R.id.roomDatesChildrenMinus),
                v.findViewById(R.id.roomDatesChildrenPlus), children, 0, Integer.MAX_VALUE, value -> children = value);

        v.findViewById(R.id.roomDatesNextButton).setOnClickListener(view -> {
            boolean valid = true;
            if (checkInMillis < 0) {
                showError(checkInError, R.string.error_check_in_required);
                valid = false;
            } else {
                checkInError.setVisibility(View.GONE);
            }
            if (checkOutMillis < 0) {
                showError(checkOutError, R.string.error_check_out_required);
                valid = false;
            } else if (checkInMillis >= 0 && checkOutMillis <= checkInMillis) {
                showError(checkOutError, R.string.error_check_out_before_checkin);
                valid = false;
            } else {
                checkOutError.setVisibility(View.GONE);
            }
            if (adults + children < 1) {
                Toast.makeText(activity, R.string.toast_guests_required, Toast.LENGTH_LONG).show();
                valid = false;
            }
            if (valid) {
                fetchAvailabilityForRoomSelection(v);
            }
        });
    }

    /**
     * Checks real room availability (same backend call as {@link #fetchAvailability}, but
     * targets this screen's own Next button for loading feedback and always lands on the
     * dedicated Your Selected Room/s screen on success, rather than the tabbed Book-tab screen).
     */
    private void fetchAvailabilityForRoomSelection(View datesView) {
        if (isLoadingAvailability) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }

        Button nextButton = datesView.findViewById(R.id.roomDatesNextButton);
        isLoadingAvailability = true;
        nextButton.setEnabled(false);
        nextButton.setText(R.string.button_checking_availability);

        String checkInStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(checkInMillis));
        String checkOutStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(checkOutMillis));

        ApiClient.bookingApi().checkAvailability("Bearer " + token, checkInStr, checkOutStr, adults, children)
                .enqueue(new Callback<AvailabilityResponse>() {
                    @Override
                    public void onResponse(Call<AvailabilityResponse> call, Response<AvailabilityResponse> response) {
                        isLoadingAvailability = false;
                        if (response.isSuccessful() && response.body() != null) {
                            availableRoomTypes = response.body().room_types != null
                                    ? response.body().room_types : new ArrayList<>();
                            // Fresh search: wipe every previously-added room and quantity (and
                            // the unavailable-origin "show everything inline" mode, in case the
                            // origin is available this time) — only the origin itself survives,
                            // re-checked below against these new results.
                            roomQuantities.clear();
                            selectionShowOthers = false;
                            for (RoomTypeAvailability type : availableRoomTypes) {
                                roomQuantities.put(type.group_id, 0);
                            }
                            // Pre-select quantity 1 for the room the guest originally chose
                            // (Room Overview), if it turned out to be available.
                            for (RoomTypeAvailability type : availableRoomTypes) {
                                if (type.variant_id == selectionOriginVariantId && type.available_quantity > 0
                                        && meetsCapacity(type.capacity, adults + children)) {
                                    roomQuantities.put(type.group_id, 1);
                                    break;
                                }
                            }
                            showScreen(SCREEN_ROOM_SELECTION);
                        } else {
                            nextButton.setEnabled(true);
                            nextButton.setText(R.string.button_next);
                            if (response.code() == 401) {
                                Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(activity, R.string.toast_availability_check_failed, Toast.LENGTH_LONG).show();
                            }
                        }
                    }

                    @Override
                    public void onFailure(Call<AvailabilityResponse> call, Throwable t) {
                        isLoadingAvailability = false;
                        nextButton.setEnabled(true);
                        nextButton.setText(R.string.button_next);
                        Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
                    }
                });
    }

    // ---- Screen 0c: Choose Your Visit Date (Cottage Overview entry only) --

    /**
     * Focused rate-type/date/time/adults/children/Next form for Cottage Overview's "Book Now"
     * (see #showCottageDatesEntry). Feeds the same shared cottageRateType/cottageDateMillis/
     * cottageTimeHour/cottageTimeMinute/cottageAdults/cottageChildren fields the tabbed Book-tab
     * screen's Cottage tab uses, so tapping Next just switches to that screen (with the Cottage
     * tab forced active) and collapses its search panel to reveal the already-loaded cottage
     * catalog below — reusing its browse/select logic rather than duplicating it here.
     */
    private void bindCottageDatesEntry(View v) {
        bindHeader(v, R.id.cottageDatesHeaderBar, R.string.cottage_dates_title, onBackToOverview);

        View dateField = v.findViewById(R.id.cottageDatesDateField);
        TextView dateText = v.findViewById(R.id.cottageDatesDateText);
        TextView dateError = v.findViewById(R.id.cottageDatesDateError);
        View timeField = v.findViewById(R.id.cottageDatesTimeField);
        TextView timeText = v.findViewById(R.id.cottageDatesTimeText);
        TextView timeError = v.findViewById(R.id.cottageDatesTimeError);
        RadioGroup rateGroup = v.findViewById(R.id.cottageDatesRateTypeGroup);
        TextView rateError = v.findViewById(R.id.cottageDatesRateTypeError);
        EditText adultsValue = v.findViewById(R.id.cottageDatesAdultsValue);
        EditText childrenValue = v.findViewById(R.id.cottageDatesChildrenValue);
        TextView totalGuestValue = v.findViewById(R.id.cottageDatesTotalGuestValue);

        updateCottageDateField(dateText);
        updateCottageTimeField(timeText);
        totalGuestValue.setText(String.valueOf(cottageAdults + cottageChildren));
        if (ReservationCatalog.RATE_TYPE_DAY.equals(cottageRateType)) {
            rateGroup.check(R.id.cottageDatesRateDay);
        } else if (ReservationCatalog.RATE_TYPE_NIGHT.equals(cottageRateType)) {
            rateGroup.check(R.id.cottageDatesRateNight);
        } else {
            rateGroup.clearCheck();
        }

        rateGroup.setOnCheckedChangeListener((group, checkedId) -> {
            cottageRateType = checkedId == R.id.cottageDatesRateDay
                    ? ReservationCatalog.RATE_TYPE_DAY : ReservationCatalog.RATE_TYPE_NIGHT;
            rateError.setVisibility(View.GONE);
        });

        dateField.setOnClickListener(view -> GlassDatePicker.showDatePickerDialog(
                activity, cottageDateMillis, System.currentTimeMillis() - 1000L, millis -> {
                    cottageDateMillis = millis;
                    updateCottageDateField(dateText);
                    dateError.setVisibility(View.GONE);
                }));

        timeField.setOnClickListener(view -> {
            Calendar now = Calendar.getInstance();
            int initialHour = cottageTimeHour >= 0 ? cottageTimeHour : now.get(Calendar.HOUR_OF_DAY);
            int initialMinute = cottageTimeMinute >= 0 ? cottageTimeMinute : now.get(Calendar.MINUTE);
            TimePickerDialog dialog = new TimePickerDialog(activity, (picker, hour, minute) -> {
                cottageTimeHour = hour;
                cottageTimeMinute = minute;
                updateCottageTimeField(timeText);
                timeError.setVisibility(View.GONE);
            }, initialHour, initialMinute, false);
            ThemeManager.applyGlassEffect(dialog.getWindow());
            dialog.show();
        });

        AuthUiUtils.bindQuantityStepper(adultsValue, v.findViewById(R.id.cottageDatesAdultsMinus),
                v.findViewById(R.id.cottageDatesAdultsPlus), cottageAdults, 1, Integer.MAX_VALUE, value -> {
                    cottageAdults = value;
                    totalGuestValue.setText(String.valueOf(cottageAdults + cottageChildren));
                });
        AuthUiUtils.bindQuantityStepper(childrenValue, v.findViewById(R.id.cottageDatesChildrenMinus),
                v.findViewById(R.id.cottageDatesChildrenPlus), cottageChildren, 0, Integer.MAX_VALUE, value -> {
                    cottageChildren = value;
                    totalGuestValue.setText(String.valueOf(cottageAdults + cottageChildren));
                });

        v.findViewById(R.id.cottageDatesNextButton).setOnClickListener(view -> {
            boolean valid = true;
            if (cottageRateType.isEmpty()) {
                showError(rateError, R.string.error_rate_type_required);
                valid = false;
            } else {
                rateError.setVisibility(View.GONE);
            }
            if (cottageDateMillis < 0) {
                showError(dateError, R.string.error_date_required);
                valid = false;
            } else if (cottageDateMillis < System.currentTimeMillis() - ONE_DAY_MS) {
                showError(dateError, R.string.error_date_past);
                valid = false;
            } else {
                dateError.setVisibility(View.GONE);
            }
            if (cottageTimeHour < 0) {
                showError(timeError, R.string.error_time_required);
                valid = false;
            } else {
                timeError.setVisibility(View.GONE);
            }
            if (valid) {
                proceedToCottageSelection(v);
            }
        });
    }

    /** Ensures the Cottages & KTV catalog is loaded (see {@link #fetchCottagesKtvData}) before
     *  advancing to Your Selected Cottage/s, showing a brief loading state on Next if a fetch is
     *  needed. */
    private void proceedToCottageSelection(View datesView) {
        Button nextButton = datesView.findViewById(R.id.cottageDatesNextButton);
        if (cottageKtvLoaded) {
            resetAndPreselectCottageSelection();
            showScreen(SCREEN_COTTAGE_SELECTION);
            return;
        }
        nextButton.setEnabled(false);
        nextButton.setText(R.string.button_checking_availability);
        fetchCottagesKtvData(() -> {
            nextButton.setEnabled(true);
            nextButton.setText(R.string.button_next);
            resetAndPreselectCottageSelection();
            showScreen(SCREEN_COTTAGE_SELECTION);
        });
    }

    /**
     * Every time the guest (re-)enters Your Selected Cottage/s from the dates screen — including
     * after going Back and changing the date/time/guests — this wipes any cottage quantities and
     * "show everything inline" state left over from a previous attempt (the cottage catalog
     * itself is cached and never re-fetched, unlike Hotel Rooms' dated search, so nothing else
     * naturally clears this), then pre-selects quantity 1 for the guest's originally-chosen
     * cottage if it's available under the new criteria. Only the origin's identity survives
     * across attempts — everything else is re-derived fresh.
     */
    private void resetAndPreselectCottageSelection() {
        for (CottageKtvOption item : cottageItems) {
            roomQuantities.put(item.group_id, 0);
        }
        selectionShowOthers = false;

        int totalGuests = cottageAdults + cottageChildren;
        for (CottageKtvOption item : cottageItems) {
            if (item.variant_id == selectionOriginVariantId && item.available_quantity > 0
                    && meetsCapacity(item.capacity, totalGuests)) {
                roomQuantities.put(item.group_id, 1);
                return;
            }
        }
    }

    // ---- Screen 0d: Plan Your KTV Session (KTV Overview entry only) -------

    /**
     * Focused rate-type/date/start-time/guest-count/Next form for KTV Overview's "Book Now"
     * (see #showKtvDatesEntry). End Time and Duration are read-only, recomputed whenever the
     * rate type or start time changes, mirroring the tabbed screen's KTV tab. Feeds the same
     * shared ktvRateType/ktvDateMillis/ktvStartHour/ktvStartMinute/ktvGuestCount fields that tab
     * uses, so tapping Next just switches to that screen (with the KTV tab forced active) and
     * collapses its search panel to reveal the already-loaded KTV catalog below — reusing its
     * browse/select logic rather than duplicating it here.
     */
    private void bindKtvDatesEntry(View v) {
        bindHeader(v, R.id.ktvDatesHeaderBar, R.string.ktv_dates_title, onBackToOverview);

        View dateField = v.findViewById(R.id.ktvDatesDateField);
        TextView dateText = v.findViewById(R.id.ktvDatesDateText);
        TextView dateError = v.findViewById(R.id.ktvDatesDateError);
        View startTimeField = v.findViewById(R.id.ktvDatesStartTimeField);
        TextView startTimeText = v.findViewById(R.id.ktvDatesStartTimeText);
        TextView startTimeError = v.findViewById(R.id.ktvDatesStartTimeError);
        RadioGroup rateGroup = v.findViewById(R.id.ktvDatesRateTypeGroup);
        TextView rateError = v.findViewById(R.id.ktvDatesRateTypeError);
        EditText guestCountValue = v.findViewById(R.id.ktvDatesGuestCountValue);

        updateKtvDateField(dateText);
        updateKtvStartTimeField(startTimeText);
        updateKtvDatesEndTimeAndDuration(v);
        if (ReservationCatalog.RATE_TYPE_REGULAR.equals(ktvRateType)) {
            rateGroup.check(R.id.ktvDatesRateRegular);
        } else if (ReservationCatalog.RATE_TYPE_CONSUMABLE.equals(ktvRateType)) {
            rateGroup.check(R.id.ktvDatesRateConsumable);
        } else {
            rateGroup.clearCheck();
        }

        rateGroup.setOnCheckedChangeListener((group, checkedId) -> {
            ktvRateType = checkedId == R.id.ktvDatesRateRegular
                    ? ReservationCatalog.RATE_TYPE_REGULAR : ReservationCatalog.RATE_TYPE_CONSUMABLE;
            rateError.setVisibility(View.GONE);
            updateKtvDatesEndTimeAndDuration(v);
        });

        dateField.setOnClickListener(view -> GlassDatePicker.showDatePickerDialog(
                activity, ktvDateMillis, System.currentTimeMillis() - 1000L, millis -> {
                    ktvDateMillis = millis;
                    updateKtvDateField(dateText);
                    dateError.setVisibility(View.GONE);
                }));

        startTimeField.setOnClickListener(view -> {
            Calendar now = Calendar.getInstance();
            int initialHour = ktvStartHour >= 0 ? ktvStartHour : now.get(Calendar.HOUR_OF_DAY);
            int initialMinute = ktvStartMinute >= 0 ? ktvStartMinute : now.get(Calendar.MINUTE);
            TimePickerDialog dialog = new TimePickerDialog(activity, (picker, hour, minute) -> {
                ktvStartHour = hour;
                ktvStartMinute = minute;
                updateKtvStartTimeField(startTimeText);
                updateKtvDatesEndTimeAndDuration(v);
                startTimeError.setVisibility(View.GONE);
            }, initialHour, initialMinute, false);
            ThemeManager.applyGlassEffect(dialog.getWindow());
            dialog.show();
        });

        AuthUiUtils.bindQuantityStepper(guestCountValue, v.findViewById(R.id.ktvDatesGuestCountMinus),
                v.findViewById(R.id.ktvDatesGuestCountPlus), ktvGuestCount, 1, Integer.MAX_VALUE, value -> ktvGuestCount = value);

        v.findViewById(R.id.ktvDatesNextButton).setOnClickListener(view -> {
            boolean valid = true;
            if (ktvRateType.isEmpty()) {
                showError(rateError, R.string.error_rate_type_required);
                valid = false;
            } else {
                rateError.setVisibility(View.GONE);
            }
            if (ktvDateMillis < 0) {
                showError(dateError, R.string.error_date_required);
                valid = false;
            } else if (ktvDateMillis < System.currentTimeMillis() - ONE_DAY_MS) {
                showError(dateError, R.string.error_date_past);
                valid = false;
            } else {
                dateError.setVisibility(View.GONE);
            }
            if (ktvStartHour < 0) {
                showError(startTimeError, R.string.error_time_required);
                valid = false;
            } else {
                startTimeError.setVisibility(View.GONE);
            }
            if (valid) {
                proceedToKtvSelection(v);
            }
        });
    }

    /** Ensures the Cottages & KTV catalog is loaded (see {@link #fetchCottagesKtvData}) before
     *  advancing to Your Selected KTV Room/s, showing a brief loading state on Next if a fetch
     *  is needed. */
    private void proceedToKtvSelection(View datesView) {
        Button nextButton = datesView.findViewById(R.id.ktvDatesNextButton);
        if (cottageKtvLoaded) {
            resetAndPreselectKtvSelection();
            showScreen(SCREEN_KTV_SELECTION);
            return;
        }
        nextButton.setEnabled(false);
        nextButton.setText(R.string.button_checking_availability);
        fetchCottagesKtvData(() -> {
            nextButton.setEnabled(true);
            nextButton.setText(R.string.button_next);
            resetAndPreselectKtvSelection();
            showScreen(SCREEN_KTV_SELECTION);
        });
    }

    /**
     * Every time the guest (re-)enters Your Selected KTV Room/s from the dates screen —
     * including after going Back and changing the date/time/guests — this wipes any KTV
     * quantities and "show everything inline" state left over from a previous attempt (see
     * {@link #resetAndPreselectCottageSelection}'s note on why nothing else clears this
     * automatically), then pre-selects quantity 1 for the guest's originally-chosen KTV room if
     * it's available under the new criteria. Only the origin's identity survives across
     * attempts — everything else is re-derived fresh.
     */
    private void resetAndPreselectKtvSelection() {
        for (CottageKtvOption item : ktvItems) {
            roomQuantities.put(item.group_id, 0);
        }
        selectionShowOthers = false;

        for (CottageKtvOption item : ktvItems) {
            if (item.variant_id == selectionOriginVariantId && item.available_quantity > 0
                    && meetsCapacity(item.capacity, ktvGuestCount)) {
                roomQuantities.put(item.group_id, 1);
                return;
            }
        }
    }

    private void updateKtvDatesEndTimeAndDuration(View v) {
        TextView endTimeText = v.findViewById(R.id.ktvDatesEndTimeText);
        TextView durationValue = v.findViewById(R.id.ktvDatesDurationValue);
        int hours = ReservationCatalog.RATE_TYPE_REGULAR.equals(ktvRateType) ? KTV_REGULAR_HOURS
                : ReservationCatalog.RATE_TYPE_CONSUMABLE.equals(ktvRateType) ? KTV_CONSUMABLE_HOURS : 0;

        durationValue.setText(hours <= 0 ? activity.getString(R.string.placeholder_em_dash)
                : activity.getString(R.string.format_duration_hours, hours));

        if (hours <= 0 || ktvStartHour < 0) {
            endTimeText.setText(R.string.placeholder_em_dash);
            endTimeText.setAlpha(0.5f);
            return;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, ktvStartHour);
        calendar.set(Calendar.MINUTE, ktvStartMinute);
        calendar.add(Calendar.HOUR_OF_DAY, hours);
        endTimeText.setText(formatHourMinute(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)));
        endTimeText.setAlpha(1f);
    }

    // ---- Screen 0e/f/g: "Your Selected ..." (Room/Cottage/KTV Overview entry only) ---------

    /**
     * Shows the room the guest originally chose (see selectionOriginVariantId) pre-selected with
     * a quantity control if it's available for their entered dates/guests, or grayed out with an
     * unavailable message otherwise — plus other available room types below (immediately, if the
     * original pick failed, or only once "Add Another Room" is tapped). Next reuses
     * roomQuantities/availableRoomTypes exactly as bindReview already expects, so it hands off to
     * the existing Review screen with no changes needed there.
     */
    private void bindRoomSelection(View v) {
        bindHeader(v, R.id.itemSelectionHeaderBar, R.string.room_selection_title,
                () -> showScreen(SCREEN_ROOM_DATES));

        int totalGuests = adults + children;
        RoomTypeAvailability origin = null;
        for (RoomTypeAvailability room : availableRoomTypes) {
            if (room.variant_id == selectionOriginVariantId) {
                origin = room;
                break;
            }
        }
        boolean originAvailable = origin != null && origin.available_quantity > 0
                && meetsCapacity(origin.capacity, totalGuests);
        if (!originAvailable) {
            selectionShowOthers = true;
        }

        LayoutInflater inflater = LayoutInflater.from(activity);
        FrameLayout originSlot = v.findViewById(R.id.itemSelectionOriginSlot);
        originSlot.removeAllViews();
        if (origin != null) {
            int pricePerNight = (int) Math.round(origin.price_per_night);
            View card = buildSelectableCard(inflater, originSlot, origin.image_path,
                    formatShowcaseName(origin.variant_name, origin.category_name), origin.capacity, origin.description,
                    activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)),
                    origin.group_id, origin.available_quantity, originAvailable, !originAvailable,
                    R.string.unavailable_room_message, () -> bindRoomSelection(v));
            originSlot.addView(card);
        }

        LinearLayout otherContainer = v.findViewById(R.id.itemSelectionOtherContainer);
        otherContainer.removeAllViews();
        Button addAnotherButton = v.findViewById(R.id.itemSelectionAddAnotherButton);
        addAnotherButton.setText(R.string.button_add_another_room);

        if (selectionShowOthers) {
            // The origin was unavailable: show every alternative right here, inline, so the
            // guest — who has nothing selected yet — can immediately pick a replacement.
            addAnotherButton.setVisibility(View.GONE);
            for (RoomTypeAvailability room : availableRoomTypes) {
                if (room.variant_id == selectionOriginVariantId
                        || room.available_quantity <= 0 || !meetsCapacity(room.capacity, totalGuests)) {
                    continue;
                }
                int pricePerNight = (int) Math.round(room.price_per_night);
                View card = buildSelectableCard(inflater, otherContainer, room.image_path,
                        formatShowcaseName(room.variant_name, room.category_name), room.capacity, room.description,
                        activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)),
                        room.group_id, room.available_quantity, false, false,
                        R.string.unavailable_room_message, () -> bindRoomSelection(v));
                otherContainer.addView(card);
            }
        } else {
            // The origin is available: only list room types the guest has already added via the
            // dedicated Hotel Rooms browse screen (see itemSelectionAddAnotherButton below) —
            // browsing everything else happens on that separate screen, not inline here.
            for (RoomTypeAvailability room : availableRoomTypes) {
                if (room.variant_id == selectionOriginVariantId) {
                    continue;
                }
                int qty = roomQuantities.containsKey(room.group_id) ? roomQuantities.get(room.group_id) : 0;
                if (qty <= 0) {
                    continue;
                }
                int pricePerNight = (int) Math.round(room.price_per_night);
                View card = buildSelectableCard(inflater, otherContainer, room.image_path,
                        formatShowcaseName(room.variant_name, room.category_name), room.capacity, room.description,
                        activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)),
                        room.group_id, room.available_quantity, false, false,
                        R.string.unavailable_room_message, () -> bindRoomSelection(v));
                otherContainer.addView(card);
            }
            addAnotherButton.setVisibility(View.VISIBLE);
            addAnotherButton.setOnClickListener(view -> showScreen(SCREEN_ROOM_BROWSE));
        }

        v.findViewById(R.id.itemSelectionNextButton).setOnClickListener(view -> {
            int total = 0;
            for (RoomTypeAvailability room : availableRoomTypes) {
                if (roomQuantities.containsKey(room.group_id)) {
                    total += roomQuantities.get(room.group_id);
                }
            }
            if (total <= 0) {
                Toast.makeText(activity, R.string.dialog_no_room_message, Toast.LENGTH_LONG).show();
                return;
            }
            showScreen(SCREEN_REVIEW);
        });
    }

    /**
     * The dedicated "Hotel Rooms" browse screen reached from "Add Another Room" on Your Selected
     * Room/s (see bindRoomSelection) — lists every available room type except the guest's
     * original pick (already shown on that screen), each with its own quantity control. Next
     * returns to Your Selected Room/s, which then shows any of these given a quantity above the
     * origin card.
     */
    private void bindRoomBrowse(View v) {
        bindHeader(v, R.id.itemBrowseHeaderBar, R.string.room_browse_title,
                () -> showScreen(SCREEN_ROOM_SELECTION));
        ((TextView) v.findViewById(R.id.itemBrowseSubtitle)).setText(R.string.label_browse_available_rooms);

        int totalGuests = adults + children;
        LinearLayout container = v.findViewById(R.id.itemBrowseContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (RoomTypeAvailability room : availableRoomTypes) {
            if (room.variant_id == selectionOriginVariantId
                    || room.available_quantity <= 0 || !meetsCapacity(room.capacity, totalGuests)) {
                continue;
            }
            int pricePerNight = (int) Math.round(room.price_per_night);
            View card = buildSelectableCard(inflater, container, room.image_path,
                    formatShowcaseName(room.variant_name, room.category_name), room.capacity, room.description,
                    activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)),
                    room.group_id, room.available_quantity, false, false,
                    R.string.unavailable_room_message, () -> bindRoomBrowse(v));
            container.addView(card);
        }

        v.findViewById(R.id.itemBrowseNextButton).setOnClickListener(view -> showScreen(SCREEN_ROOM_SELECTION));
    }

    /**
     * Shows the cottage the guest originally chose pre-selected with a quantity control if it's
     * in stock and fits their guest count, or grayed out with an unavailable message otherwise —
     * plus other available cottage types below, same pattern as {@link #bindRoomSelection}.
     * Cottages have no per-date/time availability check on the backend (only overall stock, see
     * fetchCottagesKtvData) so "available" here means in-stock and capacity-fitting, not
     * conflict-checked against the guest's chosen date/time. Next is a placeholder for now —
     * Cottage bookings don't have a Review/Billing/Payment continuation yet.
     */
    private void bindCottageSelection(View v) {
        bindHeader(v, R.id.itemSelectionHeaderBar, R.string.cottage_selection_title,
                () -> showScreen(SCREEN_COTTAGE_DATES));

        int totalGuests = cottageAdults + cottageChildren;
        CottageKtvOption origin = null;
        for (CottageKtvOption item : cottageItems) {
            if (item.variant_id == selectionOriginVariantId) {
                origin = item;
                break;
            }
        }
        boolean originAvailable = origin != null && origin.available_quantity > 0
                && meetsCapacity(origin.capacity, totalGuests);
        if (!originAvailable) {
            selectionShowOthers = true;
        }

        LayoutInflater inflater = LayoutInflater.from(activity);
        FrameLayout originSlot = v.findViewById(R.id.itemSelectionOriginSlot);
        originSlot.removeAllViews();
        if (origin != null) {
            View card = buildSelectableCard(inflater, originSlot, origin.image_path,
                    formatShowcaseName(origin.variant_name, origin.category_name), origin.capacity, origin.description,
                    activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(origin.price)), origin.price_unit),
                    origin.group_id, origin.available_quantity, originAvailable, !originAvailable,
                    R.string.unavailable_cottage_message, () -> bindCottageSelection(v));
            originSlot.addView(card);
        }

        LinearLayout otherContainer = v.findViewById(R.id.itemSelectionOtherContainer);
        otherContainer.removeAllViews();
        Button addAnotherButton = v.findViewById(R.id.itemSelectionAddAnotherButton);
        addAnotherButton.setText(R.string.button_add_another_cottage);

        if (selectionShowOthers) {
            // The origin was unavailable: show every alternative right here, inline, so the
            // guest — who has nothing selected yet — can immediately pick a replacement.
            addAnotherButton.setVisibility(View.GONE);
            for (CottageKtvOption item : cottageItems) {
                if (item.variant_id == selectionOriginVariantId
                        || item.available_quantity <= 0 || !meetsCapacity(item.capacity, totalGuests)) {
                    continue;
                }
                View card = buildSelectableCard(inflater, otherContainer, item.image_path,
                        formatShowcaseName(item.variant_name, item.category_name), item.capacity, item.description,
                        activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(item.price)), item.price_unit),
                        item.group_id, item.available_quantity, false, false,
                        R.string.unavailable_cottage_message, () -> bindCottageSelection(v));
                otherContainer.addView(card);
            }
        } else {
            // The origin is available: only list cottage types the guest has already added via
            // the dedicated Cottages browse screen — browsing everything else happens there.
            for (CottageKtvOption item : cottageItems) {
                if (item.variant_id == selectionOriginVariantId) {
                    continue;
                }
                int qty = roomQuantities.containsKey(item.group_id) ? roomQuantities.get(item.group_id) : 0;
                if (qty <= 0) {
                    continue;
                }
                View card = buildSelectableCard(inflater, otherContainer, item.image_path,
                        formatShowcaseName(item.variant_name, item.category_name), item.capacity, item.description,
                        activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(item.price)), item.price_unit),
                        item.group_id, item.available_quantity, false, false,
                        R.string.unavailable_cottage_message, () -> bindCottageSelection(v));
                otherContainer.addView(card);
            }
            addAnotherButton.setVisibility(View.VISIBLE);
            addAnotherButton.setOnClickListener(view -> showScreen(SCREEN_COTTAGE_BROWSE));
        }

        v.findViewById(R.id.itemSelectionNextButton).setOnClickListener(view -> {
            int total = 0;
            for (CottageKtvOption item : cottageItems) {
                if (roomQuantities.containsKey(item.group_id)) {
                    total += roomQuantities.get(item.group_id);
                }
            }
            if (total <= 0) {
                Toast.makeText(activity, R.string.dialog_no_room_message, Toast.LENGTH_LONG).show();
                return;
            }
            showScreen(SCREEN_COTTAGE_REVIEW);
        });
    }

    /**
     * The dedicated "Cottages" browse screen reached from "Add Another Cottage" on Your Selected
     * Cottage/s (see bindCottageSelection) — lists every in-stock cottage type except the
     * guest's original pick, each with its own quantity control. Next returns to Your Selected
     * Cottage/s, which then shows any of these given a quantity above the origin card.
     */
    private void bindCottageBrowse(View v) {
        bindHeader(v, R.id.itemBrowseHeaderBar, R.string.cottage_browse_title,
                () -> showScreen(SCREEN_COTTAGE_SELECTION));
        ((TextView) v.findViewById(R.id.itemBrowseSubtitle)).setText(R.string.label_browse_available_cottages);

        int totalGuests = cottageAdults + cottageChildren;
        LinearLayout container = v.findViewById(R.id.itemBrowseContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (CottageKtvOption item : cottageItems) {
            if (item.variant_id == selectionOriginVariantId
                    || item.available_quantity <= 0 || !meetsCapacity(item.capacity, totalGuests)) {
                continue;
            }
            View card = buildSelectableCard(inflater, container, item.image_path,
                    formatShowcaseName(item.variant_name, item.category_name), item.capacity, item.description,
                    activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(item.price)), item.price_unit),
                    item.group_id, item.available_quantity, false, false,
                    R.string.unavailable_cottage_message, () -> bindCottageBrowse(v));
            container.addView(card);
        }

        v.findViewById(R.id.itemBrowseNextButton).setOnClickListener(view -> showScreen(SCREEN_COTTAGE_SELECTION));
    }

    /**
     * Cottage flow's own Review Your Selection (see view_cottage_review.xml) — rate type/date/
     * time/total guest, every cottage the guest added on Your Selected Cottage/s, and a special
     * request note. Separate from bindReview, which is Hotel Rooms-only (check-in/out, nights,
     * room rows); Next is a placeholder for now, since cottage bookings don't have an Amenity/
     * Food/Billing/Payment continuation yet.
     */
    private void bindCottageReview(View v) {
        bindHeader(v, R.id.cottageReviewHeaderBar, R.string.hotel_review_title,
                () -> showScreen(SCREEN_COTTAGE_SELECTION));

        bindRow(v.findViewById(R.id.cottageReviewRowRateType), R.string.label_selected_rate_type,
                cottageRateTypeLabel());
        bindRow(v.findViewById(R.id.cottageReviewRowDate), R.string.label_date, formatDate(cottageDateMillis));
        bindRow(v.findViewById(R.id.cottageReviewRowTime), R.string.label_time,
                formatHourMinute(cottageTimeHour, cottageTimeMinute));
        bindRow(v.findViewById(R.id.cottageReviewRowTotalGuest), R.string.label_total_guest, formatCottageGuests());

        LinearLayout container = v.findViewById(R.id.cottageReviewCottagesContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (CottageKtvOption item : cottageItems) {
            int qty = roomQuantities.containsKey(item.group_id) ? roomQuantities.get(item.group_id) : 0;
            if (qty <= 0) {
                continue;
            }
            View row = inflater.inflate(R.layout.view_cottage_review_row, container, false);
            loadImage(row.findViewById(R.id.cottageReviewRowImage), item.image_path, R.drawable.ic_bed);
            ((TextView) row.findViewById(R.id.cottageReviewRowName))
                    .setText(formatShowcaseName(item.variant_name, item.category_name));
            ((TextView) row.findViewById(R.id.cottageReviewRowMeta)).setText(activity.getString(
                    R.string.format_capacity, item.capacity) + " · " + activity.getString(R.string.format_quantity, qty));
            ((TextView) row.findViewById(R.id.cottageReviewRowPrice)).setText(activity.getString(
                    R.string.format_price_with_unit, formatMoney((int) Math.round(item.price)), item.price_unit));
            container.addView(row);
        }

        EditText specialRequestField = v.findViewById(R.id.cottageReviewSpecialRequestField);
        specialRequestField.setText(specialRequest);
        AuthUiUtils.afterTextChanged(specialRequestField,
                () -> specialRequest = specialRequestField.getText().toString());

        v.findViewById(R.id.hotelReviewNextButton).setOnClickListener(view -> {
            activeContinuation = CONTINUATION_COTTAGE;
            fetchAmenities(v);
        });
    }

    private String cottageRateTypeLabel() {
        if (ReservationCatalog.RATE_TYPE_NIGHT.equals(cottageRateType)) {
            return activity.getString(R.string.rate_type_night);
        }
        return activity.getString(R.string.rate_type_day);
    }

    private String formatCottageGuests() {
        String guests = cottageAdults + (cottageAdults == 1 ? " Adult" : " Adults");
        if (cottageChildren > 0) {
            guests += ", " + cottageChildren + (cottageChildren == 1 ? " Child" : " Children");
        }
        return guests;
    }

    /**
     * Cottage flow's own Billing Summary — reuses view_hotel_billing.xml (see layoutFor) with
     * cottage-appropriate Stay Details rows (rate type/date/time instead of check-in/check-out/
     * nights) and cottage line items instead of room ones; guest info, amenities, food and
     * payment option sections are identical to bindBilling since none of those are Hotel
     * Rooms-specific.
     */
    private void bindCottageBilling(View v) {
        bindHeader(v, R.id.hotelBillingHeaderBar, R.string.hotel_billing_title, () -> showScreen(SCREEN_FOOD));

        bindRow(v.findViewById(R.id.billingRowFullName), R.string.label_full_name, ProfileStore.getFullName(activity));
        bindRow(v.findViewById(R.id.billingRowEmail), R.string.label_email_address, ProfileStore.getEmail(activity));
        bindRow(v.findViewById(R.id.billingRowMobile), R.string.label_mobile_number, ProfileStore.getContactNumber(activity));
        bindRow(v.findViewById(R.id.billingRowAddress), R.string.label_address, ProfileStore.getAddress(activity));

        bindRow(v.findViewById(R.id.billingRowCheckIn), R.string.label_selected_rate_type, cottageRateTypeLabel());
        bindRow(v.findViewById(R.id.billingRowCheckOut), R.string.label_date, formatDate(cottageDateMillis));
        bindRow(v.findViewById(R.id.billingRowNights), R.string.label_time,
                formatHourMinute(cottageTimeHour, cottageTimeMinute));
        bindRow(v.findViewById(R.id.billingRowAdults), R.string.label_billing_adults, String.valueOf(cottageAdults));
        bindRow(v.findViewById(R.id.billingRowChildren), R.string.label_billing_children, String.valueOf(cottageChildren));
        bindRow(v.findViewById(R.id.billingRowTotalGuests), R.string.label_total_guests,
                String.valueOf(cottageAdults + cottageChildren));

        ((TextView) v.findViewById(R.id.billingAccommodationLabel)).setText(R.string.section_cottage);
        LinearLayout accommodationContainer = v.findViewById(R.id.billingAccommodationContainer);
        accommodationContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        int cottageTotal = 0;
        for (CottageKtvOption item : cottageItems) {
            int qty = roomQuantities.get(item.group_id);
            if (qty <= 0) {
                continue;
            }
            int unitPrice = (int) Math.round(item.price);
            int subtotal = unitPrice * qty;
            cottageTotal += subtotal;
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, accommodationContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(formatShowcaseName(item.variant_name, item.category_name));
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText(activity.getString(R.string.format_capacity, item.capacity) + " · Qty " + qty + " · "
                    + activity.getString(R.string.format_price_with_unit, formatMoney(unitPrice), item.price_unit));
            meta.setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(subtotal));
            accommodationContainer.addView(row);
        }

        View amenitiesCard = v.findViewById(R.id.billingAmenitiesCard);
        LinearLayout amenitiesContainer = v.findViewById(R.id.billingAmenitiesContainer);
        amenitiesContainer.removeAllViews();
        int amenitiesTotal = 0;
        for (AmenityAvailability amenity : availableAmenities) {
            int qty = amenityQuantities.get(amenity.id);
            if (qty <= 0) {
                continue;
            }
            int unitPrice = (int) Math.round(amenity.price);
            int subtotal = unitPrice * qty;
            amenitiesTotal += subtotal;
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, amenitiesContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(amenity.name);
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText("Qty " + qty + " · " + formatCurrency(unitPrice));
            meta.setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(subtotal));
            amenitiesContainer.addView(row);
        }
        amenitiesCard.setVisibility(amenitiesTotal > 0 ? View.VISIBLE : View.GONE);

        View foodCard = v.findViewById(R.id.billingFoodCard);
        LinearLayout foodContainer = v.findViewById(R.id.billingFoodContainer);
        foodContainer.removeAllViews();
        int foodTotal = 0;
        for (MenuItemAvailability food : availableFood) {
            int qty = foodQuantities.get(food.id);
            if (qty <= 0) {
                continue;
            }
            int unitPrice = (int) Math.round(food.price);
            int subtotal = unitPrice * qty;
            foodTotal += subtotal;
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, foodContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(food.name);
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText("Qty " + qty + " · " + formatCurrency(unitPrice));
            meta.setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(subtotal));
            foodContainer.addView(row);
        }
        foodCard.setVisibility(foodTotal > 0 ? View.VISIBLE : View.GONE);

        bindLine(v.findViewById(R.id.billingRowRoomCharges), R.string.label_cottage_charges, cottageTotal);
        bindLine(v.findViewById(R.id.billingRowAmenitiesTotal), R.string.label_amenities_total, amenitiesTotal);
        bindLine(v.findViewById(R.id.billingRowFoodTotal), R.string.label_food_total, foodTotal);

        int grandTotal = cottageTotal + amenitiesTotal + foodTotal;
        ((TextView) v.findViewById(R.id.billingGrandTotal)).setText(formatCurrency(grandTotal));

        View fullCard = v.findViewById(R.id.paymentOptionFullCard);
        View partialCard = v.findViewById(R.id.paymentOptionPartialCard);
        ((TextView) v.findViewById(R.id.paymentOptionFullPrice)).setText(formatCurrency(grandTotal));
        int partialAmount = computePartialAmount(grandTotal);
        ((TextView) v.findViewById(R.id.paymentOptionPartialPrice)).setText(formatCurrency(partialAmount));
        ((TextView) v.findViewById(R.id.paymentOptionPartialRemaining)).setText(
                activity.getString(R.string.label_remaining_balance) + ": "
                        + formatCurrency(grandTotal - partialAmount));
        updatePaymentOptionCards(fullCard, partialCard);
        fullCard.setOnClickListener(view -> {
            paymentOption = PAYMENT_FULL;
            updatePaymentOptionCards(fullCard, partialCard);
        });
        partialCard.setOnClickListener(view -> {
            paymentOption = PAYMENT_PARTIAL;
            updatePaymentOptionCards(fullCard, partialCard);
        });

        CheckBox termsBox = v.findViewById(R.id.checkboxTerms);
        CheckBox cancellationBox = v.findViewById(R.id.checkboxCancellation);
        termsBox.setChecked(termsChecked);
        cancellationBox.setChecked(cancellationChecked);
        termsBox.setOnCheckedChangeListener((button, checked) -> termsChecked = checked);
        cancellationBox.setOnCheckedChangeListener((button, checked) -> cancellationChecked = checked);

        v.findViewById(R.id.hotelBillingProceedButton).setOnClickListener(view -> {
            if (paymentOption == PAYMENT_NONE) {
                Toast.makeText(activity, R.string.toast_select_payment_option, Toast.LENGTH_LONG).show();
                return;
            }
            if (!termsChecked || !cancellationChecked) {
                Toast.makeText(activity, R.string.toast_agree_terms_required, Toast.LENGTH_LONG).show();
                return;
            }
            showScreen(SCREEN_COTTAGE_PAYMENT);
        });
    }

    /**
     * Cottage flow's own Payment screen — reuses view_hotel_payment.xml. Amount Paid/Reference
     * Number/screenshot validation is identical to bindPayment, but Complete Booking is a
     * placeholder for now: reserve() has no field for a cottage's rate type/date/time, so wiring
     * a real submission here would either silently drop that data or need a backend change
     * first (flagged, not assumed).
     */
    private void bindCottagePayment(View v) {
        bindHeader(v, R.id.hotelPaymentHeaderBar, R.string.hotel_payment_title, () -> showScreen(SCREEN_COTTAGE_BILLING));

        int grandTotal = computeCottageGrandTotal();
        int amountDue = paymentOption == PAYMENT_PARTIAL ? computePartialAmount(grandTotal) : grandTotal;
        ((TextView) v.findViewById(R.id.paymentAmountDue)).setText(formatCurrency(amountDue));

        EditText amountPaidField = v.findViewById(R.id.paymentAmountPaidField);
        EditText referenceField = v.findViewById(R.id.paymentReferenceField);
        amountPaidField.setFilters(new InputFilter[]{AMOUNT_INPUT_FILTER});
        referenceField.setFilters(new InputFilter[]{DIGITS_ONLY_FILTER, new InputFilter.LengthFilter(REFERENCE_NUMBER_LENGTH)});
        amountPaidField.setText(amountPaidText);
        referenceField.setText(referenceNumberText);
        AuthUiUtils.afterTextChanged(amountPaidField, () -> amountPaidText = amountPaidField.getText().toString());
        amountPaidField.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                return;
            }
            String typed = amountPaidField.getText().toString().trim();
            if (typed.isEmpty()) {
                return;
            }
            try {
                String formatted = String.format(Locale.US, "%.2f", Double.parseDouble(typed));
                amountPaidField.setText(formatted);
                amountPaidText = formatted;
            } catch (NumberFormatException ignored) {
                // Same as bindPayment: nothing further to validate here.
            }
        });
        AuthUiUtils.afterTextChanged(referenceField, () -> referenceNumberText = referenceField.getText().toString());

        v.findViewById(R.id.paymentScreenshotDropzone).setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            activity.startActivityForResult(intent, REQUEST_PAYMENT_SCREENSHOT);
        });
        if (screenshotUri != null) {
            showScreenshotPreview(v);
        }

        v.findViewById(R.id.hotelPaymentCompleteButton).setOnClickListener(view -> {
            if (amountPaidText.trim().isEmpty()) {
                Toast.makeText(activity, R.string.toast_amount_paid_required, Toast.LENGTH_LONG).show();
                return;
            }
            if (referenceNumberText.trim().isEmpty()) {
                Toast.makeText(activity, R.string.toast_reference_number_required, Toast.LENGTH_LONG).show();
                return;
            }
            if (referenceNumberText.trim().length() != REFERENCE_NUMBER_LENGTH) {
                Toast.makeText(activity, R.string.toast_reference_number_invalid_length, Toast.LENGTH_LONG).show();
                return;
            }
            if (screenshotUri == null) {
                Toast.makeText(activity, R.string.toast_screenshot_required, Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(activity, R.string.toast_feature_coming_soon, Toast.LENGTH_LONG).show();
        });
    }

    private int computeCottageGrandTotal() {
        int total = 0;
        for (CottageKtvOption item : cottageItems) {
            total += (int) Math.round(item.price) * roomQuantities.get(item.group_id);
        }
        for (AmenityAvailability amenity : availableAmenities) {
            total += (int) Math.round(amenity.price) * amenityQuantities.get(amenity.id);
        }
        for (MenuItemAvailability food : availableFood) {
            total += (int) Math.round(food.price) * foodQuantities.get(food.id);
        }
        return total;
    }

    /**
     * Shows the KTV room the guest originally chose pre-selected with a quantity control if it's
     * in stock and fits their guest count, or grayed out with an unavailable message otherwise —
     * plus other available KTV rooms below, same pattern as {@link #bindRoomSelection}. Same
     * stock-only availability caveat as {@link #bindCottageSelection} applies. Next is a
     * placeholder for now — KTV bookings don't have a Review/Billing/Payment continuation yet.
     */
    private void bindKtvSelection(View v) {
        bindHeader(v, R.id.itemSelectionHeaderBar, R.string.ktv_selection_title,
                () -> showScreen(SCREEN_KTV_DATES));

        CottageKtvOption origin = null;
        for (CottageKtvOption item : ktvItems) {
            if (item.variant_id == selectionOriginVariantId) {
                origin = item;
                break;
            }
        }
        boolean originAvailable = origin != null && origin.available_quantity > 0
                && meetsCapacity(origin.capacity, ktvGuestCount);
        if (!originAvailable) {
            selectionShowOthers = true;
        }

        LayoutInflater inflater = LayoutInflater.from(activity);
        FrameLayout originSlot = v.findViewById(R.id.itemSelectionOriginSlot);
        originSlot.removeAllViews();
        if (origin != null) {
            View card = buildSelectableCard(inflater, originSlot, origin.image_path,
                    formatShowcaseName(origin.variant_name, origin.category_name), origin.capacity, origin.description,
                    activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(origin.price)), origin.price_unit),
                    origin.group_id, origin.available_quantity, originAvailable, !originAvailable,
                    R.string.unavailable_ktv_message, () -> bindKtvSelection(v));
            originSlot.addView(card);
        }

        LinearLayout otherContainer = v.findViewById(R.id.itemSelectionOtherContainer);
        otherContainer.removeAllViews();
        Button addAnotherButton = v.findViewById(R.id.itemSelectionAddAnotherButton);
        addAnotherButton.setText(R.string.button_add_another_ktv);

        if (selectionShowOthers) {
            // The origin was unavailable: show every alternative right here, inline, so the
            // guest — who has nothing selected yet — can immediately pick a replacement.
            addAnotherButton.setVisibility(View.GONE);
            for (CottageKtvOption item : ktvItems) {
                if (item.variant_id == selectionOriginVariantId
                        || item.available_quantity <= 0 || !meetsCapacity(item.capacity, ktvGuestCount)) {
                    continue;
                }
                View card = buildSelectableCard(inflater, otherContainer, item.image_path,
                        formatShowcaseName(item.variant_name, item.category_name), item.capacity, item.description,
                        activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(item.price)), item.price_unit),
                        item.group_id, item.available_quantity, false, false,
                        R.string.unavailable_ktv_message, () -> bindKtvSelection(v));
                otherContainer.addView(card);
            }
        } else {
            // The origin is available: only list KTV rooms the guest has already added via the
            // dedicated KTV Rooms browse screen — browsing everything else happens there.
            for (CottageKtvOption item : ktvItems) {
                if (item.variant_id == selectionOriginVariantId) {
                    continue;
                }
                int qty = roomQuantities.containsKey(item.group_id) ? roomQuantities.get(item.group_id) : 0;
                if (qty <= 0) {
                    continue;
                }
                View card = buildSelectableCard(inflater, otherContainer, item.image_path,
                        formatShowcaseName(item.variant_name, item.category_name), item.capacity, item.description,
                        activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(item.price)), item.price_unit),
                        item.group_id, item.available_quantity, false, false,
                        R.string.unavailable_ktv_message, () -> bindKtvSelection(v));
                otherContainer.addView(card);
            }
            addAnotherButton.setVisibility(View.VISIBLE);
            addAnotherButton.setOnClickListener(view -> showScreen(SCREEN_KTV_BROWSE));
        }

        v.findViewById(R.id.itemSelectionNextButton).setOnClickListener(view -> {
            int total = 0;
            for (CottageKtvOption item : ktvItems) {
                if (roomQuantities.containsKey(item.group_id)) {
                    total += roomQuantities.get(item.group_id);
                }
            }
            if (total <= 0) {
                Toast.makeText(activity, R.string.dialog_no_room_message, Toast.LENGTH_LONG).show();
                return;
            }
            showScreen(SCREEN_KTV_REVIEW);
        });
    }

    /**
     * The dedicated "KTV Rooms" browse screen reached from "Add Another KTV Room" on Your
     * Selected KTV Room/s (see bindKtvSelection) — lists every in-stock KTV room except the
     * guest's original pick, each with its own quantity control. Next returns to Your Selected
     * KTV Room/s, which then shows any of these given a quantity above the origin card.
     */
    private void bindKtvBrowse(View v) {
        bindHeader(v, R.id.itemBrowseHeaderBar, R.string.ktv_browse_title,
                () -> showScreen(SCREEN_KTV_SELECTION));
        ((TextView) v.findViewById(R.id.itemBrowseSubtitle)).setText(R.string.label_browse_available_ktv);

        LinearLayout container = v.findViewById(R.id.itemBrowseContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (CottageKtvOption item : ktvItems) {
            if (item.variant_id == selectionOriginVariantId
                    || item.available_quantity <= 0 || !meetsCapacity(item.capacity, ktvGuestCount)) {
                continue;
            }
            View card = buildSelectableCard(inflater, container, item.image_path,
                    formatShowcaseName(item.variant_name, item.category_name), item.capacity, item.description,
                    activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(item.price)), item.price_unit),
                    item.group_id, item.available_quantity, false, false,
                    R.string.unavailable_ktv_message, () -> bindKtvBrowse(v));
            container.addView(card);
        }

        v.findViewById(R.id.itemBrowseNextButton).setOnClickListener(view -> showScreen(SCREEN_KTV_SELECTION));
    }

    /**
     * KTV flow's own Review Your Selection (see view_ktv_review.xml) — rate type/date/start
     * time/end time/total guests, every KTV room the guest added on Your Selected KTV Room/s,
     * and a special request note. Separate from bindReview, which is Hotel Rooms-only; Next is a
     * placeholder for now, since KTV bookings don't have an Amenity/Food/Billing/Payment
     * continuation yet.
     */
    private void bindKtvReview(View v) {
        bindHeader(v, R.id.ktvReviewHeaderBar, R.string.hotel_review_title,
                () -> showScreen(SCREEN_KTV_SELECTION));

        bindRow(v.findViewById(R.id.ktvReviewRowRateType), R.string.label_selected_rate_type, ktvRateTypeLabel());
        bindRow(v.findViewById(R.id.ktvReviewRowDate), R.string.label_date, formatDate(ktvDateMillis));
        bindRow(v.findViewById(R.id.ktvReviewRowTime), R.string.label_time, formatHourMinute(ktvStartHour, ktvStartMinute));
        bindRow(v.findViewById(R.id.ktvReviewRowEndTime), R.string.label_end_time, formatKtvEndTime());
        bindRow(v.findViewById(R.id.ktvReviewRowTotalGuest), R.string.label_total_guest, formatKtvGuests());

        LinearLayout container = v.findViewById(R.id.ktvReviewRoomsContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (CottageKtvOption item : ktvItems) {
            int qty = roomQuantities.containsKey(item.group_id) ? roomQuantities.get(item.group_id) : 0;
            if (qty <= 0) {
                continue;
            }
            View row = inflater.inflate(R.layout.view_ktv_review_row, container, false);
            loadImage(row.findViewById(R.id.ktvReviewRowImage), item.image_path, R.drawable.ic_bed);
            ((TextView) row.findViewById(R.id.ktvReviewRowName))
                    .setText(formatShowcaseName(item.variant_name, item.category_name));
            ((TextView) row.findViewById(R.id.ktvReviewRowMeta)).setText(activity.getString(
                    R.string.format_capacity, item.capacity) + " · " + activity.getString(R.string.format_quantity, qty));
            ((TextView) row.findViewById(R.id.ktvReviewRowPrice)).setText(activity.getString(
                    R.string.format_price_with_unit, formatMoney((int) Math.round(item.price)), item.price_unit));
            container.addView(row);
        }

        EditText specialRequestField = v.findViewById(R.id.ktvReviewSpecialRequestField);
        specialRequestField.setText(specialRequest);
        AuthUiUtils.afterTextChanged(specialRequestField,
                () -> specialRequest = specialRequestField.getText().toString());

        v.findViewById(R.id.hotelReviewNextButton).setOnClickListener(view -> {
            activeContinuation = CONTINUATION_KTV;
            fetchAmenities(v);
        });
    }

    private String ktvRateTypeLabel() {
        if (ReservationCatalog.RATE_TYPE_CONSUMABLE.equals(ktvRateType)) {
            return activity.getString(R.string.ktv_rate_type_consumable);
        }
        return activity.getString(R.string.ktv_rate_type_regular);
    }

    private String formatKtvGuests() {
        return ktvGuestCount + (ktvGuestCount == 1 ? " Guest" : " Guests");
    }

    /** Same calculation as updateKtvDatesEndTimeAndDuration, as a plain String for the Review
     *  screen's read-only row instead of writing straight into a TextView. */
    private String formatKtvEndTime() {
        int hours = ReservationCatalog.RATE_TYPE_REGULAR.equals(ktvRateType) ? KTV_REGULAR_HOURS
                : ReservationCatalog.RATE_TYPE_CONSUMABLE.equals(ktvRateType) ? KTV_CONSUMABLE_HOURS : 0;
        if (hours <= 0 || ktvStartHour < 0) {
            return activity.getString(R.string.placeholder_em_dash);
        }
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, ktvStartHour);
        calendar.set(Calendar.MINUTE, ktvStartMinute);
        calendar.add(Calendar.HOUR_OF_DAY, hours);
        return formatHourMinute(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
    }

    /**
     * KTV flow's own Billing Summary — reuses view_hotel_billing.xml (see layoutFor) with
     * KTV-appropriate Stay Details rows (rate type/date/start time/end time instead of check-in/
     * check-out/nights) and KTV room line items instead of room ones; guest info, amenities,
     * food and payment option sections are identical to bindBilling since none of those are
     * Hotel Rooms-specific.
     */
    private void bindKtvBilling(View v) {
        bindHeader(v, R.id.hotelBillingHeaderBar, R.string.hotel_billing_title, () -> showScreen(SCREEN_FOOD));

        bindRow(v.findViewById(R.id.billingRowFullName), R.string.label_full_name, ProfileStore.getFullName(activity));
        bindRow(v.findViewById(R.id.billingRowEmail), R.string.label_email_address, ProfileStore.getEmail(activity));
        bindRow(v.findViewById(R.id.billingRowMobile), R.string.label_mobile_number, ProfileStore.getContactNumber(activity));
        bindRow(v.findViewById(R.id.billingRowAddress), R.string.label_address, ProfileStore.getAddress(activity));

        // Stay Details has 6 generic label/value row slots (built for check-in/out/nights/
        // adults/children/total-guests); KTV only needs 5 differently-shaped fields, so they're
        // remapped onto those same rows (CheckIn->RateType, CheckOut->Date, Nights->Time,
        // Adults->EndTime, Children->TotalGuest) and the 6th slot is hidden — same reuse-the-
        // layout approach as bindCottageBilling, just with one fewer field needed.
        bindRow(v.findViewById(R.id.billingRowCheckIn), R.string.label_selected_rate_type, ktvRateTypeLabel());
        bindRow(v.findViewById(R.id.billingRowCheckOut), R.string.label_date, formatDate(ktvDateMillis));
        bindRow(v.findViewById(R.id.billingRowNights), R.string.label_time, formatHourMinute(ktvStartHour, ktvStartMinute));
        bindRow(v.findViewById(R.id.billingRowAdults), R.string.label_end_time, formatKtvEndTime());
        bindRow(v.findViewById(R.id.billingRowChildren), R.string.label_total_guest, formatKtvGuests());
        v.findViewById(R.id.billingRowTotalGuests).setVisibility(View.GONE);

        ((TextView) v.findViewById(R.id.billingAccommodationLabel)).setText(R.string.section_ktv_rooms);
        LinearLayout accommodationContainer = v.findViewById(R.id.billingAccommodationContainer);
        accommodationContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        int ktvTotal = 0;
        for (CottageKtvOption item : ktvItems) {
            int qty = roomQuantities.get(item.group_id);
            if (qty <= 0) {
                continue;
            }
            int unitPrice = (int) Math.round(item.price);
            int subtotal = unitPrice * qty;
            ktvTotal += subtotal;
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, accommodationContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(formatShowcaseName(item.variant_name, item.category_name));
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText(activity.getString(R.string.format_capacity, item.capacity) + " · Qty " + qty + " · "
                    + activity.getString(R.string.format_price_with_unit, formatMoney(unitPrice), item.price_unit));
            meta.setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(subtotal));
            accommodationContainer.addView(row);
        }

        View amenitiesCard = v.findViewById(R.id.billingAmenitiesCard);
        LinearLayout amenitiesContainer = v.findViewById(R.id.billingAmenitiesContainer);
        amenitiesContainer.removeAllViews();
        int amenitiesTotal = 0;
        for (AmenityAvailability amenity : availableAmenities) {
            int qty = amenityQuantities.get(amenity.id);
            if (qty <= 0) {
                continue;
            }
            int unitPrice = (int) Math.round(amenity.price);
            int subtotal = unitPrice * qty;
            amenitiesTotal += subtotal;
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, amenitiesContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(amenity.name);
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText("Qty " + qty + " · " + formatCurrency(unitPrice));
            meta.setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(subtotal));
            amenitiesContainer.addView(row);
        }
        amenitiesCard.setVisibility(amenitiesTotal > 0 ? View.VISIBLE : View.GONE);

        View foodCard = v.findViewById(R.id.billingFoodCard);
        LinearLayout foodContainer = v.findViewById(R.id.billingFoodContainer);
        foodContainer.removeAllViews();
        int foodTotal = 0;
        for (MenuItemAvailability food : availableFood) {
            int qty = foodQuantities.get(food.id);
            if (qty <= 0) {
                continue;
            }
            int unitPrice = (int) Math.round(food.price);
            int subtotal = unitPrice * qty;
            foodTotal += subtotal;
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, foodContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(food.name);
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText("Qty " + qty + " · " + formatCurrency(unitPrice));
            meta.setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(subtotal));
            foodContainer.addView(row);
        }
        foodCard.setVisibility(foodTotal > 0 ? View.VISIBLE : View.GONE);

        bindLine(v.findViewById(R.id.billingRowRoomCharges), R.string.label_ktv_charges, ktvTotal);
        bindLine(v.findViewById(R.id.billingRowAmenitiesTotal), R.string.label_amenities_total, amenitiesTotal);
        bindLine(v.findViewById(R.id.billingRowFoodTotal), R.string.label_food_total, foodTotal);

        int grandTotal = ktvTotal + amenitiesTotal + foodTotal;
        ((TextView) v.findViewById(R.id.billingGrandTotal)).setText(formatCurrency(grandTotal));

        View fullCard = v.findViewById(R.id.paymentOptionFullCard);
        View partialCard = v.findViewById(R.id.paymentOptionPartialCard);
        ((TextView) v.findViewById(R.id.paymentOptionFullPrice)).setText(formatCurrency(grandTotal));
        int partialAmount = computePartialAmount(grandTotal);
        ((TextView) v.findViewById(R.id.paymentOptionPartialPrice)).setText(formatCurrency(partialAmount));
        ((TextView) v.findViewById(R.id.paymentOptionPartialRemaining)).setText(
                activity.getString(R.string.label_remaining_balance) + ": "
                        + formatCurrency(grandTotal - partialAmount));
        updatePaymentOptionCards(fullCard, partialCard);
        fullCard.setOnClickListener(view -> {
            paymentOption = PAYMENT_FULL;
            updatePaymentOptionCards(fullCard, partialCard);
        });
        partialCard.setOnClickListener(view -> {
            paymentOption = PAYMENT_PARTIAL;
            updatePaymentOptionCards(fullCard, partialCard);
        });

        CheckBox termsBox = v.findViewById(R.id.checkboxTerms);
        CheckBox cancellationBox = v.findViewById(R.id.checkboxCancellation);
        termsBox.setChecked(termsChecked);
        cancellationBox.setChecked(cancellationChecked);
        termsBox.setOnCheckedChangeListener((button, checked) -> termsChecked = checked);
        cancellationBox.setOnCheckedChangeListener((button, checked) -> cancellationChecked = checked);

        v.findViewById(R.id.hotelBillingProceedButton).setOnClickListener(view -> {
            if (paymentOption == PAYMENT_NONE) {
                Toast.makeText(activity, R.string.toast_select_payment_option, Toast.LENGTH_LONG).show();
                return;
            }
            if (!termsChecked || !cancellationChecked) {
                Toast.makeText(activity, R.string.toast_agree_terms_required, Toast.LENGTH_LONG).show();
                return;
            }
            showScreen(SCREEN_KTV_PAYMENT);
        });
    }

    /**
     * KTV flow's own Payment screen — reuses view_hotel_payment.xml. Amount Paid/Reference
     * Number/screenshot validation is identical to bindPayment, but Complete Booking is a
     * placeholder for now: reserve() has no field for a KTV session's rate type/date/time, so
     * wiring a real submission here would either silently drop that data or need a backend
     * change first (flagged, not assumed).
     */
    private void bindKtvPayment(View v) {
        bindHeader(v, R.id.hotelPaymentHeaderBar, R.string.hotel_payment_title, () -> showScreen(SCREEN_KTV_BILLING));

        int grandTotal = computeKtvGrandTotal();
        int amountDue = paymentOption == PAYMENT_PARTIAL ? computePartialAmount(grandTotal) : grandTotal;
        ((TextView) v.findViewById(R.id.paymentAmountDue)).setText(formatCurrency(amountDue));

        EditText amountPaidField = v.findViewById(R.id.paymentAmountPaidField);
        EditText referenceField = v.findViewById(R.id.paymentReferenceField);
        amountPaidField.setFilters(new InputFilter[]{AMOUNT_INPUT_FILTER});
        referenceField.setFilters(new InputFilter[]{DIGITS_ONLY_FILTER, new InputFilter.LengthFilter(REFERENCE_NUMBER_LENGTH)});
        amountPaidField.setText(amountPaidText);
        referenceField.setText(referenceNumberText);
        AuthUiUtils.afterTextChanged(amountPaidField, () -> amountPaidText = amountPaidField.getText().toString());
        amountPaidField.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                return;
            }
            String typed = amountPaidField.getText().toString().trim();
            if (typed.isEmpty()) {
                return;
            }
            try {
                String formatted = String.format(Locale.US, "%.2f", Double.parseDouble(typed));
                amountPaidField.setText(formatted);
                amountPaidText = formatted;
            } catch (NumberFormatException ignored) {
                // Same as bindPayment: nothing further to validate here.
            }
        });
        AuthUiUtils.afterTextChanged(referenceField, () -> referenceNumberText = referenceField.getText().toString());

        v.findViewById(R.id.paymentScreenshotDropzone).setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            activity.startActivityForResult(intent, REQUEST_PAYMENT_SCREENSHOT);
        });
        if (screenshotUri != null) {
            showScreenshotPreview(v);
        }

        v.findViewById(R.id.hotelPaymentCompleteButton).setOnClickListener(view -> {
            if (amountPaidText.trim().isEmpty()) {
                Toast.makeText(activity, R.string.toast_amount_paid_required, Toast.LENGTH_LONG).show();
                return;
            }
            if (referenceNumberText.trim().isEmpty()) {
                Toast.makeText(activity, R.string.toast_reference_number_required, Toast.LENGTH_LONG).show();
                return;
            }
            if (referenceNumberText.trim().length() != REFERENCE_NUMBER_LENGTH) {
                Toast.makeText(activity, R.string.toast_reference_number_invalid_length, Toast.LENGTH_LONG).show();
                return;
            }
            if (screenshotUri == null) {
                Toast.makeText(activity, R.string.toast_screenshot_required, Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(activity, R.string.toast_feature_coming_soon, Toast.LENGTH_LONG).show();
        });
    }

    private int computeKtvGrandTotal() {
        int total = 0;
        for (CottageKtvOption item : ktvItems) {
            total += (int) Math.round(item.price) * roomQuantities.get(item.group_id);
        }
        for (AmenityAvailability amenity : availableAmenities) {
            total += (int) Math.round(amenity.price) * amenityQuantities.get(amenity.id);
        }
        for (MenuItemAvailability food : availableFood) {
            total += (int) Math.round(food.price) * foodQuantities.get(food.id);
        }
        return total;
    }

    /**
     * Reserve flow's own Summary screen (see view_reservation_summary_flow.xml) — reuses Billing
     * Summary's exact card styling (BillingSectionLabel headers, view_hotel_billing_line_row.xml
     * line items) from Guest Information through Amount to Pay (Billing's "Payment Summary",
     * renamed) for visual consistency between Book and Reserve, while the Important Notice +
     * policy checkboxes below it stay Reserve-specific. Branches on activeContinuation (set by
     * whichever category's Review screen was visited) for its category-specific Stay/Visit
     * Details fields, selected item list, and grand total; Guest Information, Amenities/Food,
     * and the two policy checkboxes are identical across categories. Amount to Pay shows Full
     * Payment/30% Down Payment as plain informational rows, not Billing's clickable Payment
     * Option cards, since Reserve has nothing to select toward. The RESERVE button is a
     * placeholder for now — no backend endpoint yet for a reservation request shaped like this.
     */
    private void bindReservationSummary(View v) {
        bindHeader(v, R.id.reservationSummaryHeaderBar, R.string.hotel_billing_title, () -> showScreen(SCREEN_FOOD));

        bindRow(v.findViewById(R.id.reservationSummaryRowFullName), R.string.label_full_name, ProfileStore.getFullName(activity));
        bindRow(v.findViewById(R.id.reservationSummaryRowEmail), R.string.label_email_address, ProfileStore.getEmail(activity));
        bindRow(v.findViewById(R.id.reservationSummaryRowMobile), R.string.label_mobile_number, ProfileStore.getContactNumber(activity));
        bindRow(v.findViewById(R.id.reservationSummaryRowAddress), R.string.label_address, ProfileStore.getAddress(activity));

        TextView detailsTitle = v.findViewById(R.id.reservationSummaryDetailsTitle);
        TextView selectedTitle = v.findViewById(R.id.reservationSummarySelectedTitle);
        View row1 = v.findViewById(R.id.reservationSummaryDetailsRow1);
        View row2 = v.findViewById(R.id.reservationSummaryDetailsRow2);
        View row3 = v.findViewById(R.id.reservationSummaryDetailsRow3);
        View row4 = v.findViewById(R.id.reservationSummaryDetailsRow4);
        View row5 = v.findViewById(R.id.reservationSummaryDetailsRow5);
        LinearLayout itemsContainer = v.findViewById(R.id.reservationSummaryItemsContainer);
        itemsContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        int grandTotal;

        if (activeContinuation == CONTINUATION_COTTAGE) {
            detailsTitle.setText(R.string.section_visit_details);
            bindRow(row1, R.string.label_selected_rate_type, cottageRateTypeLabel());
            bindRow(row2, R.string.label_date, formatDate(cottageDateMillis));
            bindRow(row3, R.string.label_time, formatHourMinute(cottageTimeHour, cottageTimeMinute));
            bindRow(row4, R.string.label_number_of_guests, formatCottageGuests());
            row5.setVisibility(View.GONE);

            selectedTitle.setText(R.string.section_selected_cottage);
            for (CottageKtvOption item : cottageItems) {
                int qty = roomQuantities.get(item.group_id);
                if (qty <= 0) {
                    continue;
                }
                int unitPrice = (int) Math.round(item.price);
                View row = inflater.inflate(R.layout.view_hotel_billing_line_row, itemsContainer, false);
                ((TextView) row.findViewById(R.id.lineName)).setText(formatShowcaseName(item.variant_name, item.category_name));
                TextView meta = row.findViewById(R.id.lineMeta);
                meta.setText(activity.getString(R.string.format_capacity, item.capacity) + " · Qty " + qty + " · "
                        + activity.getString(R.string.format_price_with_unit, formatMoney(unitPrice), item.price_unit));
                meta.setVisibility(View.VISIBLE);
                ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(unitPrice * qty));
                itemsContainer.addView(row);
            }
            grandTotal = computeCottageGrandTotal();
        } else if (activeContinuation == CONTINUATION_KTV) {
            detailsTitle.setText(R.string.section_visit_details);
            bindRow(row1, R.string.label_selected_rate_type, ktvRateTypeLabel());
            bindRow(row2, R.string.label_date, formatDate(ktvDateMillis));
            bindRow(row3, R.string.label_start_time, formatHourMinute(ktvStartHour, ktvStartMinute));
            bindRow(row4, R.string.label_end_time, formatKtvEndTime());
            bindRow(row5, R.string.label_number_of_guests, formatKtvGuests());
            row5.setVisibility(View.VISIBLE);

            selectedTitle.setText(R.string.section_selected_ktv_rooms);
            for (CottageKtvOption item : ktvItems) {
                int qty = roomQuantities.get(item.group_id);
                if (qty <= 0) {
                    continue;
                }
                int unitPrice = (int) Math.round(item.price);
                View row = inflater.inflate(R.layout.view_hotel_billing_line_row, itemsContainer, false);
                ((TextView) row.findViewById(R.id.lineName)).setText(formatShowcaseName(item.variant_name, item.category_name));
                TextView meta = row.findViewById(R.id.lineMeta);
                meta.setText(activity.getString(R.string.format_capacity, item.capacity) + " · Qty " + qty + " · "
                        + activity.getString(R.string.format_price_with_unit, formatMoney(unitPrice), item.price_unit));
                meta.setVisibility(View.VISIBLE);
                ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(unitPrice * qty));
                itemsContainer.addView(row);
            }
            grandTotal = computeKtvGrandTotal();
        } else {
            int nights = nights();
            detailsTitle.setText(R.string.section_stay_details);
            bindRow(row1, R.string.label_stay_check_in, formatDate(checkInMillis));
            bindRow(row2, R.string.label_stay_check_out, formatDate(checkOutMillis));
            bindRow(row3, R.string.label_number_of_nights, String.valueOf(nights));
            bindRow(row4, R.string.label_number_of_guests, formatGuests());
            row5.setVisibility(View.GONE);

            selectedTitle.setText(R.string.section_selected_rooms);
            for (RoomTypeAvailability room : availableRoomTypes) {
                int qty = roomQuantities.get(room.group_id);
                if (qty <= 0) {
                    continue;
                }
                int pricePerNight = (int) Math.round(room.price_per_night);
                int subtotal = pricePerNight * qty * nights;
                View row = inflater.inflate(R.layout.view_hotel_billing_line_row, itemsContainer, false);
                ((TextView) row.findViewById(R.id.lineName)).setText(room.category_name + " · " + room.variant_name);
                TextView meta = row.findViewById(R.id.lineMeta);
                meta.setText(activity.getString(R.string.format_capacity, room.capacity) + " · Qty " + qty + " · "
                        + activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)) + " × " + nights);
                meta.setVisibility(View.VISIBLE);
                ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(subtotal));
                itemsContainer.addView(row);
            }
            grandTotal = computeGrandTotal();
        }

        // Amenities/Food are category-agnostic — identical loop across Room/Cottage/KTV, same as
        // bindBilling/bindCottageBilling/bindKtvBilling. Already folded into grandTotal above by
        // computeGrandTotal()/computeCottageGrandTotal()/computeKtvGrandTotal(); shown here too
        // so the guest can see what they're actually being charged for, not just a lump sum.
        View amenitiesCard = v.findViewById(R.id.reservationSummaryAmenitiesCard);
        LinearLayout amenitiesContainer = v.findViewById(R.id.reservationSummaryAmenitiesContainer);
        amenitiesContainer.removeAllViews();
        boolean anyAmenity = false;
        for (AmenityAvailability amenity : availableAmenities) {
            int qty = amenityQuantities.get(amenity.id);
            if (qty <= 0) {
                continue;
            }
            anyAmenity = true;
            int unitPrice = (int) Math.round(amenity.price);
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, amenitiesContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(amenity.name);
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText("Qty " + qty + " · " + formatCurrency(unitPrice));
            meta.setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(unitPrice * qty));
            amenitiesContainer.addView(row);
        }
        amenitiesCard.setVisibility(anyAmenity ? View.VISIBLE : View.GONE);

        View foodCard = v.findViewById(R.id.reservationSummaryFoodCard);
        LinearLayout foodContainer = v.findViewById(R.id.reservationSummaryFoodContainer);
        foodContainer.removeAllViews();
        boolean anyFood = false;
        for (MenuItemAvailability food : availableFood) {
            int qty = foodQuantities.get(food.id);
            if (qty <= 0) {
                continue;
            }
            anyFood = true;
            int unitPrice = (int) Math.round(food.price);
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, foodContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(food.name);
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText("Qty " + qty + " · " + formatCurrency(unitPrice));
            meta.setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(unitPrice * qty));
            foodContainer.addView(row);
        }
        foodCard.setVisibility(anyFood ? View.VISIBLE : View.GONE);

        bindLine(v.findViewById(R.id.reservationSummaryFullPaymentRow), R.string.label_full_payment, grandTotal);
        bindLine(v.findViewById(R.id.reservationSummaryDownPaymentRow), R.string.label_down_payment_30,
                computePartialAmount(grandTotal));

        CheckBox termsBox = v.findViewById(R.id.checkboxTerms);
        CheckBox cancellationBox = v.findViewById(R.id.checkboxCancellation);
        termsBox.setChecked(termsChecked);
        cancellationBox.setChecked(cancellationChecked);
        termsBox.setOnCheckedChangeListener((button, checked) -> termsChecked = checked);
        cancellationBox.setOnCheckedChangeListener((button, checked) -> cancellationChecked = checked);

        // No backend endpoint yet for actually submitting a Reserve-shaped request — this just
        // shows the same mock "request submitted" confirmation as SCREEN_SUCCESS does for Book.
        v.findViewById(R.id.reservationSummaryReserveButton).setOnClickListener(view -> {
            reservationReferenceNo = generateReservationReference();
            reservationStatus = RES_STATUS_PENDING;
            reservationPaymentSubmitted = false;
            reservationSubmittedAtMillis = System.currentTimeMillis();
            reservationPaymentSubmittedAtMillis = -1;
            reservationBookedAtMillis = -1;
            reservationCancelledAtMillis = -1;
            reservationCancelReason = "";
            showScreen(SCREEN_RESERVATION_CONFIRMED);
        });
    }

    private void bindReservationConfirmed(View v) {
        v.findViewById(R.id.reservationConfirmedViewButton).setOnClickListener(view -> showScreen(SCREEN_RESERVATION_DETAILS));
        v.findViewById(R.id.reservationConfirmedHomeButton).setOnClickListener(view -> {
            resetFlow();
            onBackToHome.run();
        });
    }

    /** No backend reservation endpoint yet (see resetFlow/reservationReferenceNo) — a locally
     *  generated, display-only reference so the mock Reservation Confirmed/Details screens have
     *  something to show. */
    private String generateReservationReference() {
        return "RES-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    /**
     * The reservation lifecycle status — identical across Hotel/Cottage/KTV, ready to be driven
     * by a future receptionist system (see reservationStatus/reservationCancelReason). Today
     * there's no receptionist UI anywhere in this app (same documented limitation as
     * ReservationStore's own Reserve-tab module), so BOOKED/CANCELLED are never actually reached —
     * only PENDING (default), the guest-driven payment-submitted sub-state, and the
     * automatically-derived EXPIRED are reachable in practice. Kept as real branches (not
     * removed) so wiring a receptionist endpoint later only means setting reservationStatus/
     * reservationCancelReason from the response, not rebuilding this logic.
     */
    private StatusPresentation resolveReservationStatus() {
        if (reservationStatus == RES_STATUS_CANCELLED) {
            return new StatusPresentation(R.attr.errorText, R.string.reservation_status_cancelled,
                    R.string.reservation_desc_cancelled_default);
        }
        if (reservationStatus == RES_STATUS_BOOKED) {
            return new StatusPresentation(R.attr.successText, R.string.reservation_status_booked,
                    R.string.reservation_desc_booked);
        }
        if (isReservationExpired()) {
            return new StatusPresentation(R.attr.textMuted, R.string.reservation_status_expired,
                    R.string.reservation_desc_expired);
        }
        if (reservationPaymentSubmitted) {
            return new StatusPresentation(R.attr.accentPrimary, R.string.reservation_status_pending,
                    R.string.reservation_desc_payment_submitted);
        }
        return new StatusPresentation(R.attr.accentPrimary, R.string.reservation_status_pending,
                R.string.reservation_desc_awaiting_payment);
    }

    /** True once 7 calendar days have passed since submission with no payment ever submitted —
     *  computed on the fly (not stored) so it's always accurate against the current time,
     *  matching how a real backend would evaluate it. */
    private boolean isReservationExpired() {
        return reservationStatus == RES_STATUS_PENDING && !reservationPaymentSubmitted
                && reservationSubmittedAtMillis > 0
                && System.currentTimeMillis() - reservationSubmittedAtMillis > RESERVATION_PAYMENT_WINDOW_MS;
    }

    /** Reservation Confirmed's "View Reservation" — a standalone status screen for the reservation
     *  just submitted (see SCREEN_RESERVATION_DETAILS). Reuses the same category-branching data
     *  already gathered for the Reservation Summary screen (see bindReservationSummary). */
    private void bindReservationDetails(View v) {
        bindHeader(v, R.id.reservationDetailsHeaderBar, R.string.reservation_details_title,
                () -> showScreen(SCREEN_RESERVATION_CONFIRMED));

        StatusPresentation status = resolveReservationStatus();
        v.findViewById(R.id.reservationDetailsStatusDot).setBackgroundTintList(
                ColorStateList.valueOf(ThemeManager.color(activity, status.colorAttr)));
        TextView statusLabel = v.findViewById(R.id.reservationDetailsStatusLabel);
        statusLabel.setText(status.labelRes);
        statusLabel.setTextColor(ThemeManager.color(activity, status.colorAttr));
        ((TextView) v.findViewById(R.id.reservationDetailsStatusDesc)).setText(status.descRes);

        v.findViewById(R.id.reservationDetailsCancelledContainer).setVisibility(
                reservationStatus == RES_STATUS_CANCELLED ? View.VISIBLE : View.GONE);
        if (reservationStatus == RES_STATUS_CANCELLED) {
            ((TextView) v.findViewById(R.id.reservationDetailsCancelledReason)).setText(
                    reservationCancelReason != null && !reservationCancelReason.isEmpty()
                            ? reservationCancelReason : activity.getString(R.string.reservation_desc_cancelled_default));
        }
        v.findViewById(R.id.reservationDetailsExpiredContainer).setVisibility(
                isReservationExpired() ? View.VISIBLE : View.GONE);

        bindRow(v.findViewById(R.id.reservationDetailsRowReference), R.string.label_reference_no, reservationReferenceNo);
        bindRow(v.findViewById(R.id.reservationDetailsRowAccommodation), R.string.label_accommodation, reservationAccommodationSummary());
        bindRow(v.findViewById(R.id.reservationDetailsRowDate), R.string.label_date, reservationDateSummary());
        View nightsRow = v.findViewById(R.id.reservationDetailsRowNights);
        if (activeContinuation == CONTINUATION_NONE) {
            bindRow(nightsRow, R.string.label_nights_short, String.valueOf(nights()));
            nightsRow.setVisibility(View.VISIBLE);
        } else {
            nightsRow.setVisibility(View.GONE);
        }
        bindRow(v.findViewById(R.id.reservationDetailsRowGuests), R.string.label_stay_guests, reservationGuestSummary());
        int reservationGrandTotal = reservationGrandTotal();
        bindRow(v.findViewById(R.id.reservationDetailsRowFullPayment), R.string.label_full_payment,
                formatCurrency(reservationGrandTotal));
        bindRow(v.findViewById(R.id.reservationDetailsRowDownPayment), R.string.label_down_payment_30,
                formatCurrency(computePartialAmount(reservationGrandTotal)));

        bindRow(v.findViewById(R.id.reservationDetailsRowFullName), R.string.label_full_name, ProfileStore.getFullName(activity));
        bindRow(v.findViewById(R.id.reservationDetailsRowEmail), R.string.label_email_address, ProfileStore.getEmail(activity));
        bindRow(v.findViewById(R.id.reservationDetailsRowMobile), R.string.label_mobile_number, ProfileStore.getContactNumber(activity));
        bindRow(v.findViewById(R.id.reservationDetailsRowAddress), R.string.label_address, ProfileStore.getAddress(activity));

        bindReservationTimeline(v);

        // Pay Now only makes sense while the reservation is Pending and still awaiting its first
        // payment submission — gone once paid (awaiting receptionist verification), booked,
        // cancelled, or expired.
        boolean canPayNow = reservationStatus == RES_STATUS_PENDING && !reservationPaymentSubmitted
                && !isReservationExpired();
        Button payNowButton = v.findViewById(R.id.reservationDetailsPayNowButton);
        payNowButton.setVisibility(canPayNow ? View.VISIBLE : View.GONE);
        payNowButton.setOnClickListener(view -> showScreen(SCREEN_RESERVATION_PAYMENT));

        // Cancel is guest-driven (unlike Booked, which stays receptionist-only) — available any
        // time the reservation is still Pending, whether or not payment has been submitted yet;
        // gone once Cancelled/Booked/Expired since there's nothing left to cancel.
        boolean canCancel = reservationStatus == RES_STATUS_PENDING && !isReservationExpired();
        Button cancelButton = v.findViewById(R.id.reservationDetailsCancelButton);
        cancelButton.setVisibility(canCancel ? View.VISIBLE : View.GONE);
        cancelButton.setOnClickListener(view -> showCancelConfirmDialog());
    }

    /** Cancel button, step 1: a plain Yes/No confirmation before asking for a reason. */
    private void showCancelConfirmDialog() {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setMessage(R.string.dialog_cancel_reservation_message)
                .setNegativeButton(R.string.button_no, null)
                .setPositiveButton(R.string.button_yes, (d, which) -> showCancelReasonDialog())
                .create();
        ThemeManager.applyGlassEffect(dialog.getWindow());
        dialog.show();
    }

    /**
     * Cancel button, step 2: collects a required reason, then applies the cancellation locally
     * (see reservationStatus/reservationCancelReason/reservationCancelledAtMillis — no backend
     * endpoint exists yet for this mock Reserve flow, same as the rest of it) and rebinds
     * Reservation Details so the status/timeline/cancelled-reason card reflect it immediately.
     */
    private void showCancelReasonDialog() {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.view_dialog_cancel_reason, null, false);
        EditText reasonField = dialogView.findViewById(R.id.cancelReasonField);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_cancel_reservation_message)
                .setView(dialogView)
                .setNegativeButton(R.string.button_cancel, null)
                .setPositiveButton(R.string.button_submit_cancellation, null)
                .create();
        ThemeManager.applyGlassEffect(dialog.getWindow());
        // Overriding the positive button's listener after show() (rather than in
        // setPositiveButton above) is what lets an empty reason re-prompt instead of the dialog
        // auto-dismissing — AlertDialog always dismisses on a setPositiveButton click otherwise.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String reason = reasonField.getText().toString().trim();
            if (reason.isEmpty()) {
                Toast.makeText(activity, R.string.toast_cancellation_reason_required, Toast.LENGTH_LONG).show();
                return;
            }
            reservationStatus = RES_STATUS_CANCELLED;
            reservationCancelReason = reason;
            reservationCancelledAtMillis = System.currentTimeMillis();
            dialog.dismiss();
            showScreen(SCREEN_RESERVATION_DETAILS);
        }));
        dialog.show();
    }

    /**
     * Reservation Details' progress timeline: Pending Reservation -> Payment Submitted -> Booked,
     * with Cancelled/Expired as terminal branches off Pending — same three/branch shape for every
     * category (Hotel/Cottage/KTV), driven purely by reservationStatus/reservationPaymentSubmitted/
     * isReservationExpired(). Each reached stage carries the exact date/time it was reached;
     * stages not yet reached stay hollow with no timestamp, exactly like bindBookingTimeline's
     * own upcoming entries.
     */
    private List<TimelineStep> buildReservationTimeline() {
        List<TimelineStep> steps = new ArrayList<>();
        String pendingLabel = activity.getString(R.string.reservation_status_pending);
        String paymentLabel = activity.getString(R.string.timeline_reservation_payment_submitted);
        String bookedLabel = activity.getString(R.string.reservation_status_booked);

        if (reservationStatus == RES_STATUS_CANCELLED) {
            steps.add(new TimelineStep(pendingLabel, reservationSubmittedAtMillis,
                    activity.getString(R.string.timeline_reservation_desc_submitted), TL_FILLED));
            if (reservationPaymentSubmitted) {
                steps.add(new TimelineStep(paymentLabel, reservationPaymentSubmittedAtMillis, null, TL_FILLED));
            }
            steps.add(new TimelineStep(activity.getString(R.string.reservation_status_cancelled),
                    reservationCancelledAtMillis, null, TL_FILLED));
            return steps;
        }
        if (isReservationExpired()) {
            steps.add(new TimelineStep(pendingLabel, reservationSubmittedAtMillis,
                    activity.getString(R.string.timeline_reservation_desc_submitted), TL_FILLED));
            steps.add(new TimelineStep(activity.getString(R.string.reservation_status_expired),
                    reservationSubmittedAtMillis + RESERVATION_PAYMENT_WINDOW_MS, null, TL_FILLED));
            return steps;
        }

        boolean pastPending = reservationPaymentSubmitted || reservationStatus == RES_STATUS_BOOKED;
        steps.add(new TimelineStep(pendingLabel, reservationSubmittedAtMillis,
                activity.getString(R.string.timeline_reservation_desc_submitted),
                pastPending ? TL_FILLED : TL_CURRENT));

        if (reservationStatus == RES_STATUS_BOOKED) {
            steps.add(new TimelineStep(paymentLabel, reservationPaymentSubmittedAtMillis, null, TL_FILLED));
            steps.add(new TimelineStep(bookedLabel, reservationBookedAtMillis,
                    activity.getString(R.string.reservation_desc_booked), TL_FILLED));
        } else if (reservationPaymentSubmitted) {
            steps.add(new TimelineStep(paymentLabel, reservationPaymentSubmittedAtMillis,
                    activity.getString(R.string.timeline_reservation_desc_awaiting_verification), TL_CURRENT));
            steps.add(new TimelineStep(bookedLabel, -1, null, TL_UPCOMING));
        } else {
            steps.add(new TimelineStep(paymentLabel, -1, null, TL_UPCOMING));
            steps.add(new TimelineStep(bookedLabel, -1, null, TL_UPCOMING));
        }
        return steps;
    }

    /** Renders buildReservationTimeline() into reservationDetailsTimelineContainer using the same
     *  dot/line row (view_reservation_timeline_row.xml) and coloring rules as bindBookingTimeline,
     *  plus a staggered fade/rise-in on each row so the progress reads as something that just
     *  advanced rather than a screen that was simply redrawn. */
    private void bindReservationTimeline(View v) {
        LinearLayout container = v.findViewById(R.id.reservationDetailsTimelineContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        List<TimelineStep> steps = buildReservationTimeline();

        for (int i = 0; i < steps.size(); i++) {
            TimelineStep step = steps.get(i);
            boolean isLast = i == steps.size() - 1;
            boolean filled = step.state != TL_UPCOMING;

            View row = inflater.inflate(R.layout.view_reservation_timeline_row, container, false);
            TextView label = row.findViewById(R.id.timelineLabel);
            label.setText(step.label);
            label.setTextColor(ThemeManager.color(activity, filled ? R.attr.textPrimary : R.attr.textMuted));
            label.setTypeface(null, step.state == TL_CURRENT ? Typeface.BOLD : Typeface.NORMAL);

            TextView meta = row.findViewById(R.id.timelineMeta);
            String metaText = step.millis > 0 ? formatDateTime(step.millis) : "";
            if (step.description != null && !step.description.isEmpty()) {
                metaText = metaText.isEmpty() ? step.description : metaText + " · " + step.description;
            }
            meta.setText(metaText);
            meta.setVisibility(metaText.isEmpty() ? View.GONE : View.VISIBLE);

            View dot = row.findViewById(R.id.timelineDot);
            dot.setBackgroundResource(filled ? R.drawable.bg_progress_dot_filled : R.drawable.bg_progress_dot_hollow);
            if (filled && isLast) {
                if (reservationStatus == RES_STATUS_CANCELLED) {
                    dot.setBackgroundTintList(ColorStateList.valueOf(ThemeManager.color(activity, R.attr.errorText)));
                } else if (isReservationExpired()) {
                    dot.setBackgroundTintList(ColorStateList.valueOf(ThemeManager.color(activity, R.attr.textMuted)));
                } else if (reservationStatus == RES_STATUS_BOOKED) {
                    dot.setBackgroundTintList(ColorStateList.valueOf(ThemeManager.color(activity, R.attr.successText)));
                }
            }

            View line = row.findViewById(R.id.timelineLine);
            if (isLast) {
                line.setVisibility(View.GONE);
            } else {
                line.setBackgroundColor(ThemeManager.color(activity, filled ? R.attr.accentPrimary : R.attr.dividerColor));
            }

            row.setAlpha(0f);
            row.setTranslationY(16f);
            row.animate().alpha(1f).translationY(0f).setStartDelay(i * 120L).setDuration(280L)
                    .setInterpolator(new DecelerateInterpolator()).start();

            container.addView(row);
        }
    }

    /**
     * Reservation flow's own mock Payment screen (Pay Now on Reservation Details) — reuses the
     * Book flow's view_hotel_payment.xml (GCash QR + amount paid/reference/screenshot fields) but
     * always collects the fixed 30% down payment, not a full/partial choice, and has no backend
     * endpoint to submit to yet (see submitReservation for the real one). Completing it just marks
     * reservationPaymentSubmitted so Reservation Details shows "awaiting verification" and hides
     * this button, matching what a receptionist would need to confirm before Booked.
     */
    private void bindReservationPayment(View v) {
        bindHeader(v, R.id.hotelPaymentHeaderBar, R.string.hotel_payment_title, () -> showScreen(SCREEN_RESERVATION_DETAILS));
        ((TextView) v.findViewById(R.id.hotelPaymentSubtitle)).setText(R.string.reservation_payment_subtitle);

        // Both figures are shown here (not just the down payment) so the guest can see the full
        // cost alongside what's actually due right now — the single paymentAmountDue figure this
        // layout otherwise shows (Book flow's own Payment screens) doesn't apply to Reserve,
        // which only ever collects the 30% down payment.
        v.findViewById(R.id.paymentAmountDue).setVisibility(View.GONE);
        int grandTotal = reservationGrandTotal();
        View fullRow = v.findViewById(R.id.paymentAmountDueFullRow);
        View downRow = v.findViewById(R.id.paymentAmountDueDownRow);
        fullRow.setVisibility(View.VISIBLE);
        downRow.setVisibility(View.VISIBLE);
        bindRow(fullRow, R.string.label_full_payment, formatCurrency(grandTotal));
        bindRow(downRow, R.string.label_down_payment_30, formatCurrency(computePartialAmount(grandTotal)));

        EditText amountPaidField = v.findViewById(R.id.paymentAmountPaidField);
        EditText referenceField = v.findViewById(R.id.paymentReferenceField);
        amountPaidField.setFilters(new InputFilter[]{AMOUNT_INPUT_FILTER});
        referenceField.setFilters(new InputFilter[]{DIGITS_ONLY_FILTER, new InputFilter.LengthFilter(REFERENCE_NUMBER_LENGTH)});
        amountPaidField.setText(amountPaidText);
        referenceField.setText(referenceNumberText);
        AuthUiUtils.afterTextChanged(amountPaidField, () -> amountPaidText = amountPaidField.getText().toString());
        amountPaidField.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                return;
            }
            String typed = amountPaidField.getText().toString().trim();
            if (typed.isEmpty()) {
                return;
            }
            try {
                String formatted = String.format(Locale.US, "%.2f", Double.parseDouble(typed));
                amountPaidField.setText(formatted);
                amountPaidText = formatted;
            } catch (NumberFormatException ignored) {
                // Leave whatever the guest typed as-is; the Complete Payment button's own
                // required-field check catches an empty result, nothing further to validate here.
            }
        });
        AuthUiUtils.afterTextChanged(referenceField, () -> referenceNumberText = referenceField.getText().toString());

        v.findViewById(R.id.paymentScreenshotDropzone).setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            activity.startActivityForResult(intent, REQUEST_PAYMENT_SCREENSHOT);
        });
        if (screenshotUri != null) {
            showScreenshotPreview(v);
        }

        Button completeButton = v.findViewById(R.id.hotelPaymentCompleteButton);
        completeButton.setText(R.string.button_complete_reservation_payment);
        completeButton.setOnClickListener(view -> {
            if (amountPaidText.trim().isEmpty()) {
                Toast.makeText(activity, R.string.toast_amount_paid_required, Toast.LENGTH_LONG).show();
                return;
            }
            if (referenceNumberText.trim().isEmpty()) {
                Toast.makeText(activity, R.string.toast_reference_number_required, Toast.LENGTH_LONG).show();
                return;
            }
            if (referenceNumberText.trim().length() != REFERENCE_NUMBER_LENGTH) {
                Toast.makeText(activity, R.string.toast_reference_number_invalid_length, Toast.LENGTH_LONG).show();
                return;
            }
            if (screenshotUri == null) {
                Toast.makeText(activity, R.string.toast_screenshot_required, Toast.LENGTH_LONG).show();
                return;
            }
            reservationPaymentSubmitted = true;
            reservationPaymentSubmittedAtMillis = System.currentTimeMillis();
            showScreen(SCREEN_RESERVATION_PAYMENT_CONFIRMED);
        });
    }

    private void bindReservationPaymentConfirmed(View v) {
        v.findViewById(R.id.reservationPaymentConfirmedReceiptButton).setOnClickListener(view ->
                showScreen(SCREEN_RESERVATION_DETAILS));
        v.findViewById(R.id.reservationPaymentConfirmedHomeButton).setOnClickListener(view -> {
            resetFlow();
            onBackToHome.run();
        });
    }

    /** Comma-joined "Category · Variant (x qty)" for every item with a quantity above zero in
     *  whichever list applies to the active category — mirrors bindReservationSummary's own
     *  per-category item loop, just condensed to one line for the Details screen. */
    private String reservationAccommodationSummary() {
        StringBuilder sb = new StringBuilder();
        if (activeContinuation == CONTINUATION_COTTAGE) {
            for (CottageKtvOption item : cottageItems) {
                int qty = roomQuantities.containsKey(item.group_id) ? roomQuantities.get(item.group_id) : 0;
                if (qty <= 0) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(formatShowcaseName(item.variant_name, item.category_name)).append(" (x").append(qty).append(")");
            }
        } else if (activeContinuation == CONTINUATION_KTV) {
            for (CottageKtvOption item : ktvItems) {
                int qty = roomQuantities.containsKey(item.group_id) ? roomQuantities.get(item.group_id) : 0;
                if (qty <= 0) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(formatShowcaseName(item.variant_name, item.category_name)).append(" (x").append(qty).append(")");
            }
        } else {
            for (RoomTypeAvailability room : availableRoomTypes) {
                int qty = roomQuantities.containsKey(room.group_id) ? roomQuantities.get(room.group_id) : 0;
                if (qty <= 0) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(formatShowcaseName(room.variant_name, room.category_name)).append(" (x").append(qty).append(")");
            }
        }
        return sb.toString();
    }

    private String reservationDateSummary() {
        if (activeContinuation == CONTINUATION_COTTAGE) {
            return formatDate(cottageDateMillis) + " · " + formatHourMinute(cottageTimeHour, cottageTimeMinute);
        }
        if (activeContinuation == CONTINUATION_KTV) {
            return formatDate(ktvDateMillis) + " · " + formatHourMinute(ktvStartHour, ktvStartMinute) + "–" + formatKtvEndTime();
        }
        return formatDate(checkInMillis) + " – " + formatDate(checkOutMillis);
    }

    /** Same grandTotal-per-category computation as bindReservationSummary's Amount to Pay
     *  section — the Details screen shows both the full amount and, via computePartialAmount,
     *  the 30% down payment due to secure the reservation (see reservation_confirmed_message). */
    private int reservationGrandTotal() {
        if (activeContinuation == CONTINUATION_COTTAGE) {
            return computeCottageGrandTotal();
        }
        if (activeContinuation == CONTINUATION_KTV) {
            return computeKtvGrandTotal();
        }
        return computeGrandTotal();
    }

    private String reservationGuestSummary() {
        if (activeContinuation == CONTINUATION_COTTAGE) {
            return formatCottageGuests();
        }
        if (activeContinuation == CONTINUATION_KTV) {
            return formatKtvGuests();
        }
        return formatGuests();
    }

    /**
     * Builds one card for the "Your Selected ..." screens (see view_selectable_item_card.xml),
     * shared by Room/Cottage/KTV. isSelectedBadge shows the green "✓ SELECTED" mark (the
     * originally-chosen item, when available); isUnavailable grays the whole card, shows
     * unavailableMessageRes as a banner over the image, and hides the quantity control entirely.
     * onChanged is called after every +/- tap so the caller can rebind and reflect the new count.
     */
    private View buildSelectableCard(LayoutInflater inflater, ViewGroup parent, String imagePath,
                                      String typeLabel, int capacity, String description, String priceText,
                                      int groupId, int availableQty, boolean isSelectedBadge, boolean isUnavailable,
                                      int unavailableMessageRes, Runnable onChanged) {
        View card = inflater.inflate(R.layout.view_selectable_item_card, parent, false);
        loadImage(card.findViewById(R.id.itemImage), imagePath, R.drawable.ic_bed);
        ((TextView) card.findViewById(R.id.itemType)).setText(typeLabel);
        ((TextView) card.findViewById(R.id.itemCapacity)).setText(activity.getString(R.string.format_capacity, capacity));
        TextView descriptionView = card.findViewById(R.id.itemDescription);
        if (description != null && !description.trim().isEmpty()) {
            descriptionView.setText(description);
            descriptionView.setVisibility(View.VISIBLE);
        } else {
            descriptionView.setVisibility(View.GONE);
        }
        ((TextView) card.findViewById(R.id.itemPrice)).setText(priceText);

        TextView banner = card.findViewById(R.id.itemUnavailableBanner);
        View badge = card.findViewById(R.id.itemSelectedBadge);
        View quantityRow = card.findViewById(R.id.itemQuantityRow);

        if (isUnavailable) {
            banner.setText(unavailableMessageRes);
            banner.setVisibility(View.VISIBLE);
            badge.setVisibility(View.GONE);
            quantityRow.setVisibility(View.GONE);
            card.setAlpha(0.5f);
            return card;
        }

        banner.setVisibility(View.GONE);
        badge.setVisibility(isSelectedBadge ? View.VISIBLE : View.GONE);
        quantityRow.setVisibility(View.VISIBLE);
        card.setAlpha(1f);

        EditText qtyValue = card.findViewById(R.id.itemQuantityValue);
        ImageView minus = card.findViewById(R.id.itemQuantityMinus);
        ImageView plus = card.findViewById(R.id.itemQuantityPlus);
        int qty = roomQuantities.containsKey(groupId) ? roomQuantities.get(groupId) : 0;
        AuthUiUtils.bindQuantityStepper(qtyValue, minus, plus, qty, 0, availableQty, value -> {
            roomQuantities.put(groupId, value);
            onChanged.run();
        });

        return card;
    }

    /**
     * The Cottages & KTV catalog network call — used by the dedicated Cottage/KTV Overview
     * selection screens. Runs onLoaded once data has arrived (or immediately, if another caller
     * already loaded it first).
     */
    private void fetchCottagesKtvData(Runnable onLoaded) {
        if (cottageKtvLoaded) {
            onLoaded.run();
            return;
        }
        if (isLoadingCottageKtv) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            return;
        }
        isLoadingCottageKtv = true;

        ApiClient.bookingApi().getCottagesKtv("Bearer " + token).enqueue(new Callback<CottageKtvListResponse>() {
            @Override
            public void onResponse(Call<CottageKtvListResponse> call, Response<CottageKtvListResponse> response) {
                isLoadingCottageKtv = false;
                cottageKtvLoaded = true;
                cottageItems = new ArrayList<>();
                ktvItems = new ArrayList<>();
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body().cottages != null) {
                        cottageItems.addAll(response.body().cottages);
                    }
                    if (response.body().ktv_rooms != null) {
                        ktvItems.addAll(response.body().ktv_rooms);
                    }
                }
                for (CottageKtvOption item : cottageItems) {
                    if (!roomQuantities.containsKey(item.group_id)) {
                        roomQuantities.put(item.group_id, 0);
                    }
                }
                for (CottageKtvOption item : ktvItems) {
                    if (!roomQuantities.containsKey(item.group_id)) {
                        roomQuantities.put(item.group_id, 0);
                    }
                }
                onLoaded.run();
            }

            @Override
            public void onFailure(Call<CottageKtvListResponse> call, Throwable t) {
                isLoadingCottageKtv = false;
                cottageKtvLoaded = true;
                cottageItems = new ArrayList<>();
                ktvItems = new ArrayList<>();
                onLoaded.run();
            }
        });
    }

    /** A room passes the guest-count filter if its listed capacity covers the party, OR if its
     *  capacity is missing/zero — treating that as unknown data rather than "sleeps nobody" so a
     *  backend data gap can't silently hide an entire room type from the catalog. */
    private boolean meetsCapacity(int roomCapacity, int totalGuests) {
        return roomCapacity <= 0 || roomCapacity >= totalGuests;
    }

    /** Showcase card title format: "TYPE (CATEGORY)", e.g. "Standard (Claricon)" — rendered in
     *  caps by the TextView itself. Falls back to just the type if no category is known. */
    private String formatShowcaseName(String type, String category) {
        if (category == null || category.trim().isEmpty()) {
            return type;
        }
        return type + " (" + category + ")";
    }

    private void updateDateField(TextView dateText, TextView dayText, long millis) {
        if (millis < 0) {
            dateText.setText(R.string.hint_select_date);
            dateText.setAlpha(0.5f);
            dayText.setVisibility(View.GONE);
        } else {
            dateText.setText(formatDate(millis));
            dateText.setAlpha(1f);
            dayText.setText(formatWeekday(millis));
            dayText.setVisibility(View.VISIBLE);
        }
    }


    private void updateCottageDateField(TextView cottageDateText) {
        if (cottageDateMillis < 0) {
            cottageDateText.setText(R.string.hint_select_date);
            cottageDateText.setAlpha(0.5f);
        } else {
            cottageDateText.setText(formatDate(cottageDateMillis));
            cottageDateText.setAlpha(1f);
        }
    }

    private void updateCottageTimeField(TextView cottageTimeText) {
        if (cottageTimeHour < 0) {
            cottageTimeText.setText(R.string.hint_select_time);
            cottageTimeText.setAlpha(0.5f);
        } else {
            cottageTimeText.setText(formatHourMinute(cottageTimeHour, cottageTimeMinute));
            cottageTimeText.setAlpha(1f);
        }
    }

    private void updateKtvDateField(TextView ktvDateText) {
        if (ktvDateMillis < 0) {
            ktvDateText.setText(R.string.hint_select_date);
            ktvDateText.setAlpha(0.5f);
        } else {
            ktvDateText.setText(formatDate(ktvDateMillis));
            ktvDateText.setAlpha(1f);
        }
    }

    private void updateKtvStartTimeField(TextView ktvStartTimeText) {
        if (ktvStartHour < 0) {
            ktvStartTimeText.setText(R.string.hint_select_time);
            ktvStartTimeText.setAlpha(0.5f);
        } else {
            ktvStartTimeText.setText(formatHourMinute(ktvStartHour, ktvStartMinute));
            ktvStartTimeText.setAlpha(1f);
        }
    }

    private String formatHourMinute(int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        return new SimpleDateFormat("h:mm a", Locale.US).format(calendar.getTime());
    }


    private String formatWeekday(long millis) {
        return new SimpleDateFormat("EEEE", Locale.US).format(new Date(millis));
    }

    // ---- Screen 2: Review Your Selection -----------------------------------

    private void bindReview(View v) {
        bindHeader(v, R.id.hotelReviewHeaderBar, R.string.hotel_review_title,
                () -> showScreen(SCREEN_ROOM_SELECTION));

        bindRow(v.findViewById(R.id.reviewRowCheckIn), R.string.label_stay_check_in, formatDate(checkInMillis));
        bindRow(v.findViewById(R.id.reviewRowCheckOut), R.string.label_stay_check_out, formatDate(checkOutMillis));
        bindRow(v.findViewById(R.id.reviewRowGuests), R.string.label_stay_guests, formatGuests());
        bindRow(v.findViewById(R.id.reviewRowNights), R.string.label_stay_nights, formatNights(nights()));

        LinearLayout container = v.findViewById(R.id.reviewRoomsContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        int nights = nights();
        for (RoomTypeAvailability room : availableRoomTypes) {
            int qty = roomQuantities.get(room.group_id);
            if (qty <= 0) {
                continue;
            }
            int pricePerNight = (int) Math.round(room.price_per_night);
            View row = inflater.inflate(R.layout.view_hotel_review_room_row, container, false);
            loadImage(row.findViewById(R.id.reviewRoomImage), room.image_path, R.drawable.ic_bed);
            ((TextView) row.findViewById(R.id.reviewRoomName)).setText(room.category_name + " · " + room.variant_name);
            ((TextView) row.findViewById(R.id.reviewRoomMeta))
                    .setText(activity.getString(R.string.format_capacity, room.capacity));
            ((TextView) row.findViewById(R.id.reviewRoomPrice))
                    .setText(activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)));
            ((TextView) row.findViewById(R.id.reviewRoomQtyNights)).setText(
                    activity.getString(R.string.format_quantity, qty) + " · " + formatNights(nights));
            int subtotal = pricePerNight * qty * nights;
            ((TextView) row.findViewById(R.id.reviewRoomSubtotal))
                    .setText(activity.getString(R.string.format_subtotal, formatMoney(subtotal)));
            container.addView(row);
        }

        EditText specialRequestField = v.findViewById(R.id.reviewSpecialRequestField);
        specialRequestField.setText(specialRequest);
        AuthUiUtils.afterTextChanged(specialRequestField,
                () -> specialRequest = specialRequestField.getText().toString());

        v.findViewById(R.id.hotelReviewNextButton).setOnClickListener(view -> {
            activeContinuation = CONTINUATION_NONE;
            // reserveMode is already set correctly by showRoomDatesEntry right before this
            // screen, whether entered from a scoped Room Overview (selectionOriginVariantId >= 0)
            // or the Book/Reserve tabs' picker (selectionOriginVariantId == -1, no pre-chosen
            // room) — every entry point sets it explicitly now, so nothing here should touch it.
            fetchAmenities(v);
        });
    }

    /** Loads real add-on amenities from the shared backend, then advances to Choose Amenity. */
    private void fetchAmenities(View reviewView) {
        if (isLoadingAmenities) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }

        Button nextButton = reviewView.findViewById(R.id.hotelReviewNextButton);
        isLoadingAmenities = true;
        nextButton.setEnabled(false);
        nextButton.setText(R.string.button_loading);

        ApiClient.bookingApi().getAmenities("Bearer " + token).enqueue(new Callback<AmenityListResponse>() {
            @Override
            public void onResponse(Call<AmenityListResponse> call, Response<AmenityListResponse> response) {
                isLoadingAmenities = false;
                nextButton.setEnabled(true);
                nextButton.setText(R.string.button_next);

                if (response.isSuccessful() && response.body() != null) {
                    availableAmenities = response.body().amenities != null
                            ? response.body().amenities : new ArrayList<>();
                    for (AmenityAvailability amenity : availableAmenities) {
                        amenityQuantities.putIfAbsent(amenity.id, 0);
                    }
                    showScreen(SCREEN_AMENITY);
                } else if (response.code() == 401) {
                    Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(activity, R.string.toast_amenities_load_failed, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<AmenityListResponse> call, Throwable t) {
                isLoadingAmenities = false;
                nextButton.setEnabled(true);
                nextButton.setText(R.string.button_next);
                Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---- Screen 4: Choose Amenity ------------------------------------------

    private void bindAmenity(View v) {
        bindHeader(v, R.id.hotelAmenityHeaderBar, R.string.hotel_amenity_title,
                () -> showScreen(reviewScreenForContinuation()));

        LinearLayout container = v.findViewById(R.id.hotelAmenityContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (AmenityAvailability amenity : availableAmenities) {
            View card = inflater.inflate(R.layout.view_hotel_stepper_item, container, false);
            loadImage(card.findViewById(R.id.stepperItemImage), amenity.image_path, R.drawable.ic_star);
            ((TextView) card.findViewById(R.id.stepperItemName)).setText(amenity.name);
            card.findViewById(R.id.stepperItemDescription).setVisibility(View.GONE);
            int unitPrice = (int) Math.round(amenity.price);
            ((TextView) card.findViewById(R.id.stepperItemPrice))
                    .setText(activity.getString(R.string.format_currency, formatMoney(unitPrice)));

            EditText qtyValue = card.findViewById(R.id.stepperItemQtyValue);
            ImageView minus = card.findViewById(R.id.stepperItemMinus);
            ImageView plus = card.findViewById(R.id.stepperItemPlus);
            disableManualQuantityEntry(qtyValue);
            qtyValue.setText(String.valueOf(amenityQuantities.get(amenity.id)));
            setStepperEnabled(plus, amenityCanIncrement(amenity, amenityQuantities.get(amenity.id)));

            minus.setOnClickListener(view -> {
                int qty = amenityQuantities.get(amenity.id);
                if (qty > 0) {
                    amenityQuantities.put(amenity.id, qty - 1);
                    qtyValue.setText(String.valueOf(qty - 1));
                    setStepperEnabled(plus, amenityCanIncrement(amenity, qty - 1));
                }
            });
            plus.setOnClickListener(view -> {
                int qty = amenityQuantities.get(amenity.id);
                if (amenityCanIncrement(amenity, qty)) {
                    amenityQuantities.put(amenity.id, qty + 1);
                    qtyValue.setText(String.valueOf(qty + 1));
                    setStepperEnabled(plus, amenityCanIncrement(amenity, qty + 1));
                }
            });

            container.addView(card);
        }

        v.findViewById(R.id.hotelAmenitySkipButton).setOnClickListener(view -> fetchFood(v));
        v.findViewById(R.id.hotelAmenityNextButton).setOnClickListener(view -> {
            if (totalFromMap(amenityQuantities) == 0) {
                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle(R.string.dialog_no_amenity_title)
                        .setMessage(R.string.dialog_no_amenity_message)
                        .setPositiveButton(R.string.button_ok, null)
                        .create();
                ThemeManager.applyGlassEffect(dialog.getWindow());
                dialog.show();
                return;
            }
            fetchFood(v);
        });
    }

    private boolean amenityCanIncrement(AmenityAvailability amenity, int currentQty) {
        return amenity.unlimited || amenity.available_quantity == null || currentQty < amenity.available_quantity;
    }

    private void setStepperEnabled(ImageView button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
    }

    /**
     * Amenity/Food quantities are +/- only, not manually typed (per explicit instruction — every
     * other quantity stepper in the app does support typing, see AuthUiUtils#bindQuantityStepper,
     * but these two were carved out). stepperItemQtyValue is an EditText only because it's the
     * same view_hotel_stepper_item.xml layout the Reserve tab's own room card reuses (which does
     * support typing) — disabling focus here keeps it a plain read-only label.
     */
    private void disableManualQuantityEntry(EditText valueField) {
        valueField.setFocusable(false);
        valueField.setFocusableInTouchMode(false);
        valueField.setClickable(false);
        valueField.setLongClickable(false);
    }

    /** Loads the real food/beverage menu from the shared backend, then advances to Order Food. */
    private void fetchFood(View amenityView) {
        if (isLoadingFood) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }

        Button nextButton = amenityView.findViewById(R.id.hotelAmenityNextButton);
        Button skipButton = amenityView.findViewById(R.id.hotelAmenitySkipButton);
        isLoadingFood = true;
        nextButton.setEnabled(false);
        skipButton.setEnabled(false);
        nextButton.setText(R.string.button_loading);

        ApiClient.bookingApi().getFood("Bearer " + token).enqueue(new Callback<FoodListResponse>() {
            @Override
            public void onResponse(Call<FoodListResponse> call, Response<FoodListResponse> response) {
                isLoadingFood = false;
                nextButton.setEnabled(true);
                skipButton.setEnabled(true);
                nextButton.setText(R.string.button_next);

                if (response.isSuccessful() && response.body() != null) {
                    availableFood = response.body().menu != null ? response.body().menu : new ArrayList<>();
                    for (MenuItemAvailability food : availableFood) {
                        foodQuantities.putIfAbsent(food.id, 0);
                    }
                    showScreen(SCREEN_FOOD);
                } else if (response.code() == 401) {
                    Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(activity, R.string.toast_food_load_failed, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<FoodListResponse> call, Throwable t) {
                isLoadingFood = false;
                nextButton.setEnabled(true);
                skipButton.setEnabled(true);
                nextButton.setText(R.string.button_next);
                Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---- Screen 5: Order Food ----------------------------------------------

    private void bindFood(View v) {
        bindHeader(v, R.id.hotelFoodHeaderBar, R.string.hotel_food_title, () -> showScreen(SCREEN_AMENITY));

        LinearLayout container = v.findViewById(R.id.hotelFoodContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        String lastCategory = null;
        for (MenuItemAvailability food : availableFood) {
            if (!food.category_name.equals(lastCategory)) {
                TextView header = (TextView) inflater.inflate(
                        R.layout.view_hotel_food_category_header, container, false);
                header.setText(food.category_name);
                container.addView(header);
                lastCategory = food.category_name;
            }

            View card = inflater.inflate(R.layout.view_hotel_stepper_item, container, false);
            loadImage(card.findViewById(R.id.stepperItemImage), food.image_path, R.drawable.ic_food);
            ((TextView) card.findViewById(R.id.stepperItemName)).setText(food.name);
            TextView description = card.findViewById(R.id.stepperItemDescription);
            if (food.description != null && !food.description.trim().isEmpty()) {
                description.setText(food.description);
                description.setVisibility(View.VISIBLE);
            } else {
                description.setVisibility(View.GONE);
            }
            int unitPrice = (int) Math.round(food.price);
            ((TextView) card.findViewById(R.id.stepperItemPrice))
                    .setText(activity.getString(R.string.format_currency, formatMoney(unitPrice)));

            EditText qtyValue = card.findViewById(R.id.stepperItemQtyValue);
            disableManualQuantityEntry(qtyValue);
            qtyValue.setText(String.valueOf(foodQuantities.get(food.id)));

            card.findViewById(R.id.stepperItemMinus).setOnClickListener(view -> {
                int qty = foodQuantities.get(food.id);
                if (qty > 0) {
                    foodQuantities.put(food.id, qty - 1);
                    qtyValue.setText(String.valueOf(qty - 1));
                }
            });
            card.findViewById(R.id.stepperItemPlus).setOnClickListener(view -> {
                int qty = foodQuantities.get(food.id);
                foodQuantities.put(food.id, qty + 1);
                qtyValue.setText(String.valueOf(qty + 1));
            });

            container.addView(card);
        }

        // Skip/Next both hand off to billingScreenForContinuation(), which already routes a
        // Reserve run (any category — see reserveMode) to the dedicated Reservation Summary
        // screen instead of that category's own Billing.
        v.findViewById(R.id.hotelFoodSkipButton).setOnClickListener(view -> showScreen(billingScreenForContinuation()));
        v.findViewById(R.id.hotelFoodNextButton).setOnClickListener(view -> {
            if (totalFromMap(foodQuantities) == 0) {
                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle(R.string.dialog_no_food_title)
                        .setMessage(R.string.dialog_no_food_message)
                        .setPositiveButton(R.string.button_ok, null)
                        .create();
                ThemeManager.applyGlassEffect(dialog.getWindow());
                dialog.show();
                return;
            }
            showScreen(billingScreenForContinuation());
        });
    }

    // ---- Screen 6: Billing Summary ------------------------------------------

    private void bindBilling(View v) {
        bindHeader(v, R.id.hotelBillingHeaderBar, R.string.hotel_billing_title, () -> showScreen(SCREEN_FOOD));

        bindRow(v.findViewById(R.id.billingRowFullName), R.string.label_full_name, ProfileStore.getFullName(activity));
        bindRow(v.findViewById(R.id.billingRowEmail), R.string.label_email_address, ProfileStore.getEmail(activity));
        bindRow(v.findViewById(R.id.billingRowMobile), R.string.label_mobile_number, ProfileStore.getContactNumber(activity));
        bindRow(v.findViewById(R.id.billingRowAddress), R.string.label_address, ProfileStore.getAddress(activity));

        int nights = nights();
        bindRow(v.findViewById(R.id.billingRowCheckIn), R.string.label_stay_check_in, formatDate(checkInMillis));
        bindRow(v.findViewById(R.id.billingRowCheckOut), R.string.label_stay_check_out, formatDate(checkOutMillis));
        bindRow(v.findViewById(R.id.billingRowNights), R.string.label_number_of_nights, String.valueOf(nights));
        bindRow(v.findViewById(R.id.billingRowAdults), R.string.label_billing_adults, String.valueOf(adults));
        bindRow(v.findViewById(R.id.billingRowChildren), R.string.label_billing_children, String.valueOf(children));
        bindRow(v.findViewById(R.id.billingRowTotalGuests), R.string.label_total_guests,
                String.valueOf(adults + children));

        LinearLayout accommodationContainer = v.findViewById(R.id.billingAccommodationContainer);
        accommodationContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        int roomTotal = 0;
        for (RoomTypeAvailability room : availableRoomTypes) {
            int qty = roomQuantities.get(room.group_id);
            if (qty <= 0) {
                continue;
            }
            int pricePerNight = (int) Math.round(room.price_per_night);
            int subtotal = pricePerNight * qty * nights;
            roomTotal += subtotal;
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, accommodationContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(room.category_name + " · " + room.variant_name);
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText(activity.getString(R.string.format_capacity, room.capacity) + " · Qty " + qty + " · "
                    + activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight))
                    + " × " + nights);
            meta.setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(subtotal));
            accommodationContainer.addView(row);
        }

        View amenitiesCard = v.findViewById(R.id.billingAmenitiesCard);
        LinearLayout amenitiesContainer = v.findViewById(R.id.billingAmenitiesContainer);
        amenitiesContainer.removeAllViews();
        int amenitiesTotal = 0;
        for (AmenityAvailability amenity : availableAmenities) {
            int qty = amenityQuantities.get(amenity.id);
            if (qty <= 0) {
                continue;
            }
            int unitPrice = (int) Math.round(amenity.price);
            int subtotal = unitPrice * qty;
            amenitiesTotal += subtotal;
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, amenitiesContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(amenity.name);
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText("Qty " + qty + " · " + formatCurrency(unitPrice));
            meta.setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(subtotal));
            amenitiesContainer.addView(row);
        }
        amenitiesCard.setVisibility(amenitiesTotal > 0 ? View.VISIBLE : View.GONE);

        View foodCard = v.findViewById(R.id.billingFoodCard);
        LinearLayout foodContainer = v.findViewById(R.id.billingFoodContainer);
        foodContainer.removeAllViews();
        int foodTotal = 0;
        for (MenuItemAvailability food : availableFood) {
            int qty = foodQuantities.get(food.id);
            if (qty <= 0) {
                continue;
            }
            int unitPrice = (int) Math.round(food.price);
            int subtotal = unitPrice * qty;
            foodTotal += subtotal;
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, foodContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(food.name);
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText("Qty " + qty + " · " + formatCurrency(unitPrice));
            meta.setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(subtotal));
            foodContainer.addView(row);
        }
        foodCard.setVisibility(foodTotal > 0 ? View.VISIBLE : View.GONE);

        bindLine(v.findViewById(R.id.billingRowRoomCharges), R.string.label_room_charges, roomTotal);
        bindLine(v.findViewById(R.id.billingRowAmenitiesTotal), R.string.label_amenities_total, amenitiesTotal);
        bindLine(v.findViewById(R.id.billingRowFoodTotal), R.string.label_food_total, foodTotal);

        int grandTotal = roomTotal + amenitiesTotal + foodTotal;
        ((TextView) v.findViewById(R.id.billingGrandTotal)).setText(formatCurrency(grandTotal));

        View fullCard = v.findViewById(R.id.paymentOptionFullCard);
        View partialCard = v.findViewById(R.id.paymentOptionPartialCard);
        ((TextView) v.findViewById(R.id.paymentOptionFullPrice)).setText(formatCurrency(grandTotal));
        int partialAmount = computePartialAmount(grandTotal);
        ((TextView) v.findViewById(R.id.paymentOptionPartialPrice)).setText(formatCurrency(partialAmount));
        ((TextView) v.findViewById(R.id.paymentOptionPartialRemaining)).setText(
                activity.getString(R.string.label_remaining_balance) + ": "
                        + formatCurrency(grandTotal - partialAmount));
        updatePaymentOptionCards(fullCard, partialCard);
        fullCard.setOnClickListener(view -> {
            paymentOption = PAYMENT_FULL;
            updatePaymentOptionCards(fullCard, partialCard);
        });
        partialCard.setOnClickListener(view -> {
            paymentOption = PAYMENT_PARTIAL;
            updatePaymentOptionCards(fullCard, partialCard);
        });

        CheckBox termsBox = v.findViewById(R.id.checkboxTerms);
        CheckBox cancellationBox = v.findViewById(R.id.checkboxCancellation);
        termsBox.setChecked(termsChecked);
        cancellationBox.setChecked(cancellationChecked);
        termsBox.setOnCheckedChangeListener((button, checked) -> termsChecked = checked);
        cancellationBox.setOnCheckedChangeListener((button, checked) -> cancellationChecked = checked);

        v.findViewById(R.id.hotelBillingProceedButton).setOnClickListener(view -> {
            if (paymentOption == PAYMENT_NONE) {
                Toast.makeText(activity, R.string.toast_select_payment_option, Toast.LENGTH_LONG).show();
                return;
            }
            if (!termsChecked || !cancellationChecked) {
                Toast.makeText(activity, R.string.toast_agree_terms_required, Toast.LENGTH_LONG).show();
                return;
            }
            showScreen(SCREEN_PAYMENT);
        });
    }

    private void updatePaymentOptionCards(View fullCard, View partialCard) {
        fullCard.setBackgroundResource(paymentOption == PAYMENT_FULL
                ? R.drawable.bg_selectable_card_selected : R.drawable.bg_selectable_card);
        partialCard.setBackgroundResource(paymentOption == PAYMENT_PARTIAL
                ? R.drawable.bg_selectable_card_selected : R.drawable.bg_selectable_card);
    }

    // ---- Screen 7: Payment ---------------------------------------------------

    private void bindPayment(View v) {
        bindHeader(v, R.id.hotelPaymentHeaderBar, R.string.hotel_payment_title, () -> showScreen(SCREEN_BILLING));

        int grandTotal = computeGrandTotal();
        int amountDue = paymentOption == PAYMENT_PARTIAL ? computePartialAmount(grandTotal) : grandTotal;
        ((TextView) v.findViewById(R.id.paymentAmountDue)).setText(formatCurrency(amountDue));

        EditText amountPaidField = v.findViewById(R.id.paymentAmountPaidField);
        EditText referenceField = v.findViewById(R.id.paymentReferenceField);
        amountPaidField.setFilters(new InputFilter[]{AMOUNT_INPUT_FILTER});
        referenceField.setFilters(new InputFilter[]{DIGITS_ONLY_FILTER, new InputFilter.LengthFilter(REFERENCE_NUMBER_LENGTH)});
        amountPaidField.setText(amountPaidText);
        referenceField.setText(referenceNumberText);
        AuthUiUtils.afterTextChanged(amountPaidField, () -> amountPaidText = amountPaidField.getText().toString());
        amountPaidField.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                return;
            }
            String typed = amountPaidField.getText().toString().trim();
            if (typed.isEmpty()) {
                return;
            }
            try {
                String formatted = String.format(Locale.US, "%.2f", Double.parseDouble(typed));
                amountPaidField.setText(formatted);
                amountPaidText = formatted;
            } catch (NumberFormatException ignored) {
                // Leave whatever the guest typed as-is; the Complete Payment button's own
                // required-field check catches an empty result, nothing further to validate here.
            }
        });
        AuthUiUtils.afterTextChanged(referenceField, () -> referenceNumberText = referenceField.getText().toString());

        v.findViewById(R.id.paymentScreenshotDropzone).setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            activity.startActivityForResult(intent, REQUEST_PAYMENT_SCREENSHOT);
        });
        if (screenshotUri != null) {
            showScreenshotPreview(v);
        }

        v.findViewById(R.id.hotelPaymentCompleteButton).setOnClickListener(view -> {
            if (amountPaidText.trim().isEmpty()) {
                Toast.makeText(activity, R.string.toast_amount_paid_required, Toast.LENGTH_LONG).show();
                return;
            }
            if (referenceNumberText.trim().isEmpty()) {
                Toast.makeText(activity, R.string.toast_reference_number_required, Toast.LENGTH_LONG).show();
                return;
            }
            if (referenceNumberText.trim().length() != REFERENCE_NUMBER_LENGTH) {
                Toast.makeText(activity, R.string.toast_reference_number_invalid_length, Toast.LENGTH_LONG).show();
                return;
            }
            if (screenshotUri == null) {
                Toast.makeText(activity, R.string.toast_screenshot_required, Toast.LENGTH_LONG).show();
                return;
            }
            submitReservation(v);
        });
    }

    /**
     * Creates the real reservation on the shared backend (same tables/models the
     * website's own booking wizard writes to), so it shows up in the receptionist's
     * Booking List immediately — no separate sync step, just one database.
     */
    private void submitReservation(View paymentView) {
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }

        RequestBody proofBody;
        try {
            proofBody = readImagePart(screenshotUri);
        } catch (IOException e) {
            Toast.makeText(activity, R.string.toast_screenshot_required, Toast.LENGTH_LONG).show();
            return;
        }
        if (proofBody == null) {
            Toast.makeText(activity, R.string.toast_screenshot_required, Toast.LENGTH_LONG).show();
            return;
        }

        Button completeButton = paymentView.findViewById(R.id.hotelPaymentCompleteButton);
        completeButton.setEnabled(false);

        MultipartBody.Part proofPart = MultipartBody.Part.createFormData("proof_image", "payment_proof.jpg", proofBody);
        String checkInStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(checkInMillis));
        String checkOutStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(checkOutMillis));
        String paymentOptionValue = paymentOption == PAYMENT_PARTIAL ? "partial" : "full";

        Map<String, RequestBody> quantities = new LinkedHashMap<>();
        putQuantityParts(quantities, "room_quantities", roomQuantities);
        putQuantityParts(quantities, "amenity_quantities", amenityQuantities);
        putQuantityParts(quantities, "menu_quantities", foodQuantities);

        ApiClient.bookingApi().reserve(
                "Bearer " + token,
                textPart(checkInStr),
                textPart(checkOutStr),
                textPart(String.valueOf(adults)),
                textPart(String.valueOf(children)),
                textPart(specialRequest),
                textPart(paymentOptionValue),
                textPart(referenceNumberText),
                proofPart,
                quantities
        ).enqueue(new Callback<ReserveResponse>() {
            @Override
            public void onResponse(Call<ReserveResponse> call, Response<ReserveResponse> response) {
                completeButton.setEnabled(true);
                if (response.isSuccessful() && response.body() != null) {
                    bookingReference = response.body().booking_reference;
                    showScreen(SCREEN_SUCCESS);
                } else if (response.code() == 401) {
                    Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                } else {
                    ErrorResponse error = ApiClient.parseError(response.errorBody());
                    String message = error.firstMessage() != null
                            ? error.firstMessage() : activity.getString(R.string.toast_booking_failed);
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<ReserveResponse> call, Throwable t) {
                completeButton.setEnabled(true);
                Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void putQuantityParts(Map<String, RequestBody> target, String fieldName, Map<Integer, Integer> quantities) {
        for (Map.Entry<Integer, Integer> entry : quantities.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                target.put(fieldName + "[" + entry.getKey() + "]", textPart(String.valueOf(entry.getValue())));
            }
        }
    }

    private static RequestBody textPart(String value) {
        return RequestBody.create(value != null ? value : "", MediaType.parse("text/plain"));
    }

    /** Reads the picked gallery screenshot into an upload-ready request body. */
    private RequestBody readImagePart(Uri uri) throws IOException {
        if (uri == null) {
            return null;
        }
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            String mime = activity.getContentResolver().getType(uri);
            return RequestBody.create(out.toByteArray(), MediaType.parse(mime != null ? mime : "image/jpeg"));
        }
    }

    private void showScreenshotPreview(View screenView) {
        ImageView preview = screenView.findViewById(R.id.paymentScreenshotPreview);
        View hint = screenView.findViewById(R.id.paymentScreenshotHint);
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(activity.getContentResolver(), screenshotUri);
            preview.setImageBitmap(bitmap);
            preview.setVisibility(View.VISIBLE);
            hint.setVisibility(View.GONE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ---- Screen 8: Booking Success --------------------------------------------

    private void bindSuccess(View v) {
        ((TextView) v.findViewById(R.id.successBookingReference)).setText(bookingReference);
        v.findViewById(R.id.successViewBookingButton).setOnClickListener(view ->
                Toast.makeText(activity, R.string.toast_view_booking_placeholder, Toast.LENGTH_LONG).show());
        v.findViewById(R.id.successBackToHomeButton).setOnClickListener(view -> {
            resetFlow();
            onBackToHome.run();
        });
    }

    private static final class StatusPresentation {
        final int colorAttr;
        final int labelRes;
        final int descRes;

        StatusPresentation(int colorAttr, int labelRes, int descRes) {
            this.colorAttr = colorAttr;
            this.labelRes = labelRes;
            this.descRes = descRes;
        }
    }

    /** One row in Reservation Details' progress timeline — see buildReservationTimeline. */
    private static final class TimelineStep {
        final String label;
        final long millis;
        final String description;
        final int state;

        TimelineStep(String label, long millis, String description, int state) {
            this.label = label;
            this.millis = millis;
            this.description = description;
            this.state = state;
        }
    }

    private String formatDateTime(long millis) {
        if (millis < 0) {
            return "";
        }
        return new SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.US).format(new Date(millis));
    }

    private void resetFlow() {
        checkInMillis = -1;
        checkOutMillis = -1;
        adults = 0;
        children = 0;
        selectionOriginVariantId = -1;
        selectionShowOthers = false;
        onBackToOverview = null;
        activeContinuation = CONTINUATION_NONE;
        reserveMode = false;
        cottageRateType = "";
        cottageDateMillis = -1;
        cottageTimeHour = -1;
        cottageTimeMinute = -1;
        cottageAdults = 1;
        cottageChildren = 0;
        ktvRateType = "";
        ktvDateMillis = -1;
        ktvStartHour = -1;
        ktvStartMinute = -1;
        ktvGuestCount = 1;
        cottageItems = new ArrayList<>();
        ktvItems = new ArrayList<>();
        isLoadingCottageKtv = false;
        cottageKtvLoaded = false;
        availableRoomTypes = new ArrayList<>();
        roomQuantities.clear();
        specialRequest = "";
        availableAmenities = new ArrayList<>();
        amenityQuantities.clear();
        availableFood = new ArrayList<>();
        foodQuantities.clear();
        paymentOption = PAYMENT_NONE;
        termsChecked = false;
        cancellationChecked = false;
        amountPaidText = "";
        referenceNumberText = "";
        screenshotUri = null;
        bookingReference = "";
        reservationReferenceNo = "";
        reservationStatus = RES_STATUS_PENDING;
        reservationPaymentSubmitted = false;
        reservationSubmittedAtMillis = -1;
        reservationPaymentSubmittedAtMillis = -1;
        reservationBookedAtMillis = -1;
        reservationCancelledAtMillis = -1;
        reservationCancelReason = "";
        // Every caller pairs resetFlow() with onBackToHome.run() right after (which re-arms the
        // owning controller's own picker screen) — nothing here needs to render, so root is just
        // left empty rather than showing a screen no one will ever look at.
        currentScreen = -1;
        currentScreenView = null;
        root.removeAllViews();
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

    private void showError(TextView errorView, int messageRes) {
        errorView.setText(messageRes);
        errorView.setVisibility(View.VISIBLE);
    }

    private int totalFromMap(Map<?, Integer> map) {
        int total = 0;
        for (int qty : map.values()) {
            total += qty;
        }
        return total;
    }

    private int nights() {
        if (checkInMillis < 0 || checkOutMillis < 0 || checkOutMillis <= checkInMillis) {
            return 0;
        }
        return (int) ((checkOutMillis - checkInMillis) / ONE_DAY_MS);
    }

    private String formatGuests() {
        String guests = adults + (adults == 1 ? " Adult" : " Adults");
        if (children > 0) {
            guests += ", " + children + (children == 1 ? " Child" : " Children");
        }
        return guests;
    }

    private String formatNights(int nights) {
        return nights + (nights == 1 ? " night" : " nights");
    }

    private int computeGrandTotal() {
        int nights = nights();
        int total = 0;
        for (RoomTypeAvailability room : availableRoomTypes) {
            total += (int) Math.round(room.price_per_night) * roomQuantities.get(room.group_id) * nights;
        }
        for (AmenityAvailability amenity : availableAmenities) {
            total += (int) Math.round(amenity.price) * amenityQuantities.get(amenity.id);
        }
        for (MenuItemAvailability food : availableFood) {
            total += (int) Math.round(food.price) * foodQuantities.get(food.id);
        }
        return total;
    }

    /** Loads a room/amenity/food thumbnail from the backend, falling back to a placeholder icon. */
    private void loadImage(ImageView imageView, String imagePath, int placeholderRes) {
        String url = ApiClient.imageUrl(imagePath);
        if (url == null) {
            imageView.setImageResource(placeholderRes);
            return;
        }
        Glide.with(activity)
                .load(url)
                .placeholder(placeholderRes)
                .error(placeholderRes)
                .centerCrop()
                .into(imageView);
    }

    private int computePartialAmount(int grandTotal) {
        return (int) Math.round(grandTotal * PARTIAL_PAYMENT_RATE);
    }

    private String formatMoney(int amount) {
        return NumberFormat.getInstance(Locale.US).format(amount);
    }

    private String formatCurrency(int amount) {
        return activity.getString(R.string.format_currency, formatMoney(amount));
    }

    private String formatDate(long millis) {
        if (millis < 0) {
            return "";
        }
        return new SimpleDateFormat("MMM d, yyyy", Locale.US).format(new Date(millis));
    }

}
