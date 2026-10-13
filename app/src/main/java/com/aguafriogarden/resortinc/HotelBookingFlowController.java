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
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.aguafriogarden.resortinc.network.AmenityAvailability;
import com.aguafriogarden.resortinc.network.AmenityListResponse;
import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.AvailabilityResponse;
import com.aguafriogarden.resortinc.network.CancelReservationResponse;
import com.aguafriogarden.resortinc.network.CottageAvailabilityResponse;
import com.aguafriogarden.resortinc.network.CottageKtvListResponse;
import com.aguafriogarden.resortinc.network.CottageKtvOption;
import com.aguafriogarden.resortinc.network.ErrorResponse;
import com.aguafriogarden.resortinc.network.KtvUnitListResponse;
import com.aguafriogarden.resortinc.network.MyBookingsResponse;
import com.aguafriogarden.resortinc.network.PriceBreakdownEntry;
import com.aguafriogarden.resortinc.network.ReservationRequestResponse;
import com.aguafriogarden.resortinc.network.ReservationStatusResponse;
import com.aguafriogarden.resortinc.network.ReservationSummary;
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
import java.util.Collections;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Drives the Hotel Rooms booking flow reached from the Book tab: stay dates,
 * room selection, review, optional amenities, billing summary, mock
 * GCash payment and a success screen. Rooms and amenities both come
 * from the shared backend (see {@link #fetchAvailability}, {@link #fetchAmenities}) with real photos loaded via Glide. One instance is
 * kept alive by {@link BookingCatalogController} for as long as the Book tab
 * exists, so answers survive navigating to another module and back;
 * {@link #resetFlow()} clears them once a booking is completed.
 */
final class HotelBookingFlowController {

    private static final int SCREEN_REVIEW = 2;
    private static final int SCREEN_AMENITY = 3;
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
    // Selection screens sharing view_item_selection.xml. Amenity itself stays fully
    // shared (see activeContinuation).
    private static final int SCREEN_COTTAGE_BILLING = 20;
    private static final int SCREEN_COTTAGE_PAYMENT = 21;
    private static final int SCREEN_KTV_BILLING = 23;
    private static final int SCREEN_KTV_PAYMENT = 24;
    // Dedicated Summary screen for a Reserve run (any category — see reserveMode), reached from
    // Choose Amenity instead of that category's own Billing/Payment. One shared layout/bind method
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
    // My Reservations/My Bookings on Choose Your Stay Dates — a real, server-backed list (see
    // bindMyReservationsList), always re-fetched on entry so it survives app restarts and
    // re-logins. Reserve and Book share this same screen, filtered to opposite kinds (see
    // #matchesListMode). Tapping a row loads that entry into SCREEN_RESERVATION_DETAILS.
    private static final int SCREEN_MY_RESERVATIONS_LIST = 31;
    // Multi-facility flow (see startMultiFlow): one combined Review/Billing/Payment (Book) or
    // Reservation Summary (Reserve) for every facility the guest picked, reached after the last
    // facility's own selection screen (and Choose Amenity, when Hotel Rooms are included).
    private static final int SCREEN_MULTI_REVIEW = 32;
    private static final int SCREEN_MULTI_BILLING = 33;
    private static final int SCREEN_MULTI_PAYMENT = 34;
    private static final int SCREEN_MULTI_SUMMARY = 35;

    private static final int FAC_ROOM = PendingRoomSelection.TYPE_ROOM;
    private static final int FAC_COTTAGE = PendingRoomSelection.TYPE_COTTAGE;
    private static final int FAC_KTV = PendingRoomSelection.TYPE_KTV;

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
    /** LandingActivity's own bottom-nav visibility (true = hidden) — the wizard's own Dates
     *  screen (Choose Your Stay Dates/Visit Date/Plan Your KTV Session) shows it, since the guest
     *  hasn't committed to a booking/reservation yet and may still want to jump to another tab;
     *  every screen past that point hides it, matching a focused wizard with its own per-screen
     *  header/back button instead of the bottom tabs. Applied centrally in showScreen() so this
     *  is consistent no matter which entry point (Landing Overview or the Book/Reserve tab's own
     *  picker) got the guest here. */
    private final Consumer<Boolean> setBottomNavHidden;
    private final FrameLayout root;

    private int currentScreen = -1;
    private View currentScreenView;

    // Set from Cottage/KTV Review's Next onward (through Amenity, which is otherwise
    // shared with the Hotel Rooms flow) until that category's own Payment screen or back past
    // its Review — lets those shared screens know to route back/forward into the Cottage/KTV
    // continuation instead of the Hotel Rooms one. Reset by resetFlow().
    private static final int CONTINUATION_NONE = 0;
    private static final int CONTINUATION_COTTAGE = 1;
    private static final int CONTINUATION_KTV = 2;
    private int activeContinuation = CONTINUATION_NONE;

    // True for a Room/Cottage/KTV entered via its Overview's "Reserve Now" (see
    // showRoomDatesEntry/showCottageDatesEntry/showKtvDatesEntry) — the flow is otherwise
    // identical to Book (same Dates/Selection/Review/Amenity screens), but Amenity leads to
    // the dedicated Reservation Summary screen (see SCREEN_RESERVATION_SUMMARY) instead of the
    // category's own Billing/Payment continuation. Reset by resetFlow().
    private boolean reserveMode = false;

    // ---- Multi-facility flow state (see startMultiFlow) -------------------------------------
    // Facilities in processing order (Hotel Rooms, then Cottage, then KTV), the index of the one
    // currently being selected, and each facility's confirmed quantities (group_id -> qty) —
    // roomQuantities itself only ever holds the facility currently on screen, since the three
    // facilities' group_ids aren't guaranteed to be disjoint.
    private boolean multiMode = false;
    private final List<Integer> multiQueue = new ArrayList<>();
    private int multiIndex = 0;
    private final Map<Integer, Map<Integer, Integer>> multiQuantities = new LinkedHashMap<>();
    /** Pre-chosen item of the first queued facility (-1 if none); selectionOriginVariantId only
     *  holds it while that first facility is on screen, since variant ids aren't unique across
     *  facilities. */
    private int multiAnchorVariantId = -1;

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

    // ---- Cottage Use / date / guests (Cottage tab) ------------------------
    // No separate "time" field — Cottage Use (Day Use/Night Use/Overnight) already stands in
    // for it (see the dropdown in bindCottageDatesEntry), matching the receptionist side's own
    // Create Reservation > Cottage screen, which has never had a raw time picker either. Always
    // a real value (never empty), since the dropdown has no blank/unselected state.
    private String cottageRateType = ReservationCatalog.RATE_TYPE_DAY;
    private long cottageDateMillis = -1;
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
    /** Guards fetchCottageAvailability() (Cottage's own live, date+Cottage Use-scoped check —
     *  see that method) — separate from isLoadingCottageKtv/cottageKtvLoaded above, which is
     *  KTV's own dateless, cached-once fetch and no longer touched by the Cottage flow. */
    private boolean isLoadingCottageAvailability = false;
    private List<RoomTypeAvailability> availableRoomTypes = new ArrayList<>();
    private final Map<Integer, Integer> roomQuantities = new LinkedHashMap<>();
    private boolean isLoadingAvailability = false;
    private String specialRequest = "";
    private List<AmenityAvailability> availableAmenities = new ArrayList<>();
    private final Map<Integer, Integer> amenityQuantities = new LinkedHashMap<>();
    private boolean isLoadingAmenities = false;
    private int paymentOption = PAYMENT_NONE;
    private boolean termsChecked;
    private boolean cancellationChecked;
    private String amountPaidText = "";
    private String referenceNumberText = "";
    private Uri screenshotUri;
    private String bookingReference = "";
    private String reservationReferenceNo = "";
    // Server id of the Pending AdvanceReservation created by submitReservationRequest() — targets
    // the payReservation() call. Hotel Rooms only (see activeContinuation guard at both call
    // sites); Cottage/KTV Reserve has no backend endpoint yet so this stays -1 for them.
    private int reservationAdvanceReservationId = -1;
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
    /** "guest" or "receptionist" — drives which of reservation_cancelled_by_guest/_staff shows
     *  on Reservation Details; empty means unknown (falls back to a generic statement). */
    private String reservationCancelledBy = "";
    // Frozen at submission time (see snapshotReservationDisplayData) rather than recomputed live
    // from checkInMillis/roomQuantities/activeContinuation/etc. — resetFlow() (run by every
    // "Return Home" after submitting) clears those same fields to let the guest start a fresh
    // attempt, but the guest should still be able to open My Reservations/View Reservation and see
    // the reservation they already made, not garbage from a since-cleared form.
    private String reservationSnapshotAccommodation = "";
    private String reservationSnapshotDateRange = "";
    private boolean reservationSnapshotShowNights = false;
    private int reservationSnapshotNights = 0;
    private String reservationSnapshotGuestSummary = "";
    private int reservationSnapshotGrandTotal = 0;
    // My Reservations list state — myReservationsData is re-fetched from the server every time
    // the screen is entered (see bindMyReservationsList), never persisted locally, so the list
    // always reflects the guest's real current state regardless of what happened to the app in
    // between (killed process, re-login, switching tabs).
    private boolean myReservationsShowingPast = false;
    private List<ReservationSummary> myReservationsData = new ArrayList<>();
    // Reservation Details now has two distinct entry points — "View Reservation" right after
    // reserving (see bindReservationConfirmed), and tapping a card in My Reservations (see
    // buildReservationListCard) — so its back target can no longer be a single hardcoded screen;
    // whichever entry point navigates in sets this first.
    private int reservationDetailsBackTarget = SCREEN_ROOM_DATES;
    /** Set when Reservation Details was opened from the Profile page's history (see
     *  openReservationFromHistory); Back then returns there instead of to a flow screen. */
    private Runnable reservationDetailsExternalBack = null;
    /** My Reservations/My Bookings is reachable from more than one category's own Dates screen
     *  (Hotel Rooms' and now Cottage's — see bindRoomDates/bindCottageDatesEntry) sharing this
     *  one screen, so its back target is likewise set by whichever entry point navigates in,
     *  not hardcoded to one screen. */
    private int myReservationsBackTarget = SCREEN_ROOM_DATES;
    /** Which category's My Reservations/My Bookings is currently showing — "hotel" or "cottage",
     *  matching ReservationSummary#service_type — set by whichever entry point navigates in (see
     *  bindRoomDates/bindCottageDatesEntry), same idea as myReservationsBackTarget above. Hotel
     *  Rooms and Cottages must never show each other's reservations in this shared screen. */
    private String myReservationsServiceType = "hotel";

    HotelBookingFlowController(Activity activity, Runnable onBookNowOtherCategory, Runnable onBackToHome,
                                Consumer<Boolean> setBottomNavHidden) {
        this.activity = activity;
        this.onBookNowOtherCategory = onBookNowOtherCategory;
        this.onBackToHome = onBackToHome;
        this.setBottomNavHidden = setBottomNavHidden;
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
     * screen's own Cottage/KTV tabs' default. reserveOnly routes Choose Amenity into the
     * Reservation Summary screen (see reserveMode) instead of Billing/Payment.
     */
    void showRoomDatesEntry(int variantId, Runnable onBackToOverview, boolean reserveOnly) {
        multiMode = false;
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
     * dates+search screen. reserveOnly routes Choose Amenity into the Reservation Summary screen
     * (see reserveMode) instead of Cottage Billing/Payment.
     */
    void showCottageDatesEntry(int variantId, Runnable onBackToOverview, boolean reserveOnly) {
        multiMode = false;
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
     * screen. reserveOnly routes Choose Amenity into the Reservation Summary screen (see
     * reserveMode) instead of KTV Billing/Payment.
     */
    void showKtvDatesEntry(int variantId, Runnable onBackToOverview, boolean reserveOnly) {
        multiMode = false;
        selectionOriginVariantId = variantId;
        selectionShowOthers = false;
        this.onBackToOverview = onBackToOverview;
        this.reserveMode = reserveOnly;
        showScreen(SCREEN_KTV_DATES);
    }

    /**
     * Entry point for the Book/Reserve tabs' "Book/Reserve Multiple Facilities" option: processes
     * every chosen facility (Hotel Rooms, Cottage, KTV Room — always in that order) one after the
     * other inside this one flow. The first facility's own dates screen collects the date(s);
     * later facilities reuse them (see enterMultiFacility). The combined Review/Amenity/Billing/
     * Payment (Book) or Reservation Summary (Reserve) screens follow the last facility.
     */
    void startMultiFlow(List<Integer> facilities, Runnable onBackToOverview, boolean reserveOnly) {
        multiAnchorVariantId = -1;
        multiMode = true;
        multiQueue.clear();
        for (int facility : new int[]{FAC_ROOM, FAC_COTTAGE, FAC_KTV}) {
            if (facilities.contains(facility)) {
                multiQueue.add(facility);
            }
        }
        multiIndex = 0;
        multiQuantities.clear();
        selectionOriginVariantId = -1;
        selectionShowOthers = false;
        activeContinuation = CONTINUATION_NONE;
        this.onBackToOverview = onBackToOverview;
        this.reserveMode = reserveOnly;
        if (multiQueue.get(0) == FAC_ROOM && adults == 0 && children == 0) {
            adults = 1;
        }
        showScreen(datesScreenForFacility(multiQueue.get(0)));
    }

    private int datesScreenForFacility(int facility) {
        return facility == FAC_COTTAGE ? SCREEN_COTTAGE_DATES
                : facility == FAC_KTV ? SCREEN_KTV_DATES : SCREEN_ROOM_DATES;
    }

    private int selectionScreenForFacility(int facility) {
        return facility == FAC_COTTAGE ? SCREEN_COTTAGE_SELECTION
                : facility == FAC_KTV ? SCREEN_KTV_SELECTION : SCREEN_ROOM_SELECTION;
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
                if (multiMode && multiIndex > 0) {
                    multiStepBack();
                    return true;
                }
                onBackToOverview.run();
                return true;
            case SCREEN_ROOM_SELECTION:
                showScreen(SCREEN_ROOM_DATES);
                return true;
            case SCREEN_COTTAGE_SELECTION:
                if (multiMode && multiIndex > 0) {
                    multiStepBack();
                    return true;
                }
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
            case SCREEN_BILLING:
                showScreen(SCREEN_AMENITY);
                return true;
            case SCREEN_PAYMENT:
                showScreen(SCREEN_BILLING);
                return true;
            case SCREEN_COTTAGE_BILLING:
                showScreen(SCREEN_AMENITY);
                return true;
            case SCREEN_COTTAGE_PAYMENT:
                showScreen(SCREEN_COTTAGE_BILLING);
                return true;
            case SCREEN_KTV_BILLING:
                showScreen(SCREEN_AMENITY);
                return true;
            case SCREEN_KTV_PAYMENT:
                showScreen(SCREEN_KTV_BILLING);
                return true;
            case SCREEN_RESERVATION_SUMMARY:
                showScreen(SCREEN_AMENITY);
                return true;
            case SCREEN_MULTI_REVIEW:
                multiShowSelection(multiQueue.get(multiIndex));
                return true;
            case SCREEN_MULTI_BILLING:
            case SCREEN_MULTI_SUMMARY:
                showScreen(multiQueue.contains(FAC_ROOM) ? SCREEN_AMENITY : SCREEN_MULTI_REVIEW);
                return true;
            case SCREEN_MULTI_PAYMENT:
                showScreen(SCREEN_MULTI_BILLING);
                return true;
            case SCREEN_SUCCESS:
            case SCREEN_RESERVATION_CONFIRMED:
            case SCREEN_RESERVATION_PAYMENT_CONFIRMED:
                resetFlow();
                onBackToHome.run();
                return true;
            case SCREEN_RESERVATION_DETAILS:
                leaveReservationDetails();
                return true;
            case SCREEN_RESERVATION_PAYMENT:
                showScreen(SCREEN_RESERVATION_DETAILS);
                return true;
            case SCREEN_MY_RESERVATIONS_LIST:
                showScreen(myReservationsBackTarget);
                return true;
            default:
                return true;
        }
    }

    /** Which Review screen the shared Amenity screen should step back to. */
    private int reviewScreenForContinuation() {
        if (multiMode) {
            return SCREEN_MULTI_REVIEW;
        }
        switch (activeContinuation) {
            case CONTINUATION_COTTAGE:
                return SCREEN_COTTAGE_REVIEW;
            case CONTINUATION_KTV:
                return SCREEN_KTV_REVIEW;
            default:
                return SCREEN_REVIEW;
        }
    }

    /** Which Dates screen Reservation Details' back button returns to — matches whichever
     *  category's flow reached it, whether that's right after reserving (View Reservation on
     *  Reservation Confirmed) or via My Reservations on that category's own Dates screen. */
    private int datesScreenForContinuation() {
        switch (activeContinuation) {
            case CONTINUATION_COTTAGE:
                return SCREEN_COTTAGE_DATES;
            case CONTINUATION_KTV:
                return SCREEN_KTV_DATES;
            default:
                return SCREEN_ROOM_DATES;
        }
    }

    /** Which Billing screen Amenity's Skip/Next should advance to. */
    private int billingScreenForContinuation() {
        if (multiMode) {
            return reserveMode ? SCREEN_MULTI_SUMMARY : SCREEN_MULTI_BILLING;
        }
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
                || currentScreen == SCREEN_KTV_PAYMENT || currentScreen == SCREEN_RESERVATION_PAYMENT
                || currentScreen == SCREEN_MULTI_PAYMENT)
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

        // Only the Dates screen (the wizard's own "first interface") shows the bottom nav —
        // every other screen it connects to hides it (see setBottomNavHidden's own doc).
        boolean isDatesScreen = (screen == SCREEN_ROOM_DATES || screen == SCREEN_COTTAGE_DATES || screen == SCREEN_KTV_DATES)
                && !(multiMode && multiIndex > 0);
        setBottomNavHidden.accept(!isDatesScreen);
    }

    private int layoutFor(int screen) {
        switch (screen) {
            case SCREEN_REVIEW:
                return R.layout.view_hotel_review;
            case SCREEN_AMENITY:
                return R.layout.view_hotel_amenity;
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
            case SCREEN_MY_RESERVATIONS_LIST:
                return R.layout.view_my_reservations_list;
            case SCREEN_MULTI_REVIEW:
                return R.layout.view_multi_review;
            case SCREEN_MULTI_BILLING:
                return R.layout.view_hotel_billing;
            case SCREEN_MULTI_PAYMENT:
                return R.layout.view_hotel_payment;
            case SCREEN_MULTI_SUMMARY:
                return R.layout.view_reservation_summary_flow;
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
            case SCREEN_MY_RESERVATIONS_LIST:
                bindMyReservationsList(v);
                break;
            case SCREEN_MULTI_REVIEW:
                bindMultiReview(v);
                break;
            case SCREEN_MULTI_BILLING:
                bindMultiBilling(v);
                break;
            case SCREEN_MULTI_PAYMENT:
                bindMultiPayment(v);
                break;
            case SCREEN_MULTI_SUMMARY:
                bindMultiSummary(v);
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

        // Real, server-backed list either way (see bindMyReservationsList) — it handles its own
        // empty state. Reserve and Book both read the same /my-bookings response, just filtered to
        // opposite kinds (see #matchesListMode): Reserve shows still-pending/cancelled advance
        // reservations, Book shows already-booked/paid ones.
        TextView myReservationsButton = v.findViewById(R.id.roomDatesMyReservationsButton);
        myReservationsButton.setText(reserveMode ? R.string.button_my_reservations : R.string.button_my_bookings);
        myReservationsButton.setOnClickListener(view -> {
            myReservationsBackTarget = SCREEN_ROOM_DATES;
            myReservationsServiceType = "hotel";
            showScreen(SCREEN_MY_RESERVATIONS_LIST);
        });

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
     * Real, server-backed My Reservations/My Bookings list — always re-fetched here (not cached
     * in a field that survives across screen visits) so it reflects the guest's actual current
     * state no matter what happened to the app in between (process killed, re-login, switching
     * tabs). Hotel Rooms only for real submissions, but any category the guest reserved through
     * the website under the same account will still show up here too, best-effort rendered (see
     * loadReservationFromSummary's own note on this).
     */
    private void bindMyReservationsList(View v) {
        bindHeader(v, R.id.myReservationsHeaderBar,
                reserveMode ? R.string.my_reservations_title : R.string.my_bookings_title,
                () -> showScreen(myReservationsBackTarget));
        myReservationsShowingPast = false;

        v.findViewById(R.id.myReservationsCurrentTab).setOnClickListener(view -> {
            myReservationsShowingPast = false;
            renderMyReservationsList(v);
        });
        v.findViewById(R.id.myReservationsPastTab).setOnClickListener(view -> {
            myReservationsShowingPast = true;
            renderMyReservationsList(v);
        });

        // Colors the tabs and shows the empty state immediately (myReservationsData is whatever
        // was left over from the last visit, or empty on a first-ever visit) rather than leaving
        // the tabs uncolored until the fetch below completes.
        renderMyReservationsList(v);
        fetchMyReservationsList(v);
    }

    private void fetchMyReservationsList(View v) {
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            myReservationsData = new ArrayList<>();
            renderMyReservationsList(v);
            return;
        }
        ApiClient.bookingApi().getMyBookings("Bearer " + token).enqueue(new Callback<MyBookingsResponse>() {
            @Override
            public void onResponse(Call<MyBookingsResponse> call, Response<MyBookingsResponse> response) {
                if (currentScreen != SCREEN_MY_RESERVATIONS_LIST) {
                    return;
                }
                myReservationsData = response.isSuccessful() && response.body() != null
                        && response.body().reservations != null
                        ? response.body().reservations : new ArrayList<>();
                sortMyReservationsNewestFirst();
                renderMyReservationsList(v);
            }

            @Override
            public void onFailure(Call<MyBookingsResponse> call, Throwable t) {
                if (currentScreen != SCREEN_MY_RESERVATIONS_LIST) {
                    return;
                }
                myReservationsData = new ArrayList<>();
                Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
                renderMyReservationsList(v);
            }
        });
    }

    /** Newest-first (both Current and Past) — the backend already sorts this way, but that's a
     *  string comparison over ISO8601 timestamps server-side; sorting again here from parsed
     *  millis guarantees the order regardless of that. */
    private void sortMyReservationsNewestFirst() {
        Collections.sort(myReservationsData, (a, b) ->
                Long.compare(parseIsoMillis(b.created_at), parseIsoMillis(a.created_at)));
    }

    /** Redraws the tab highlight + card list from myReservationsData — does not touch the
     *  network, so switching Current/Past never re-fetches. */
    private void renderMyReservationsList(View v) {
        TextView currentTab = v.findViewById(R.id.myReservationsCurrentTab);
        TextView pastTab = v.findViewById(R.id.myReservationsPastTab);
        currentTab.setTextColor(ThemeManager.color(activity,
                myReservationsShowingPast ? R.attr.textMuted : R.attr.accentPrimary));
        pastTab.setTextColor(ThemeManager.color(activity,
                myReservationsShowingPast ? R.attr.accentPrimary : R.attr.textMuted));

        List<ReservationSummary> bucket = new ArrayList<>();
        for (ReservationSummary summary : myReservationsData) {
            if (matchesListMode(summary) && isPastReservation(summary) == myReservationsShowingPast) {
                bucket.add(summary);
            }
        }

        LinearLayout container = v.findViewById(R.id.myReservationsContainer);
        container.removeAllViews();
        TextView emptyState = v.findViewById(R.id.myReservationsEmptyState);
        emptyState.setVisibility(bucket.isEmpty() ? View.VISIBLE : View.GONE);
        emptyState.setText(reserveMode
                ? (myReservationsShowingPast ? R.string.label_no_past_reservations : R.string.label_no_current_reservations)
                : (myReservationsShowingPast ? R.string.label_no_past_bookings : R.string.label_no_current_bookings));

        LayoutInflater inflater = LayoutInflater.from(activity);
        for (ReservationSummary summary : bucket) {
            container.addView(buildReservationListCard(inflater, container, summary));
        }
    }

    /** Reserve shows still-pending/cancelled advance reservations; Book shows already-booked/paid
     *  ones — the two kinds the shared /my-bookings response can contain (see ReservationSummary
     *  #kind) — and, on top of that, only the category (see #myReservationsServiceType) whichever
     *  Dates screen the guest opened this list from actually belongs to, so Hotel Rooms and
     *  Cottages never show each other's reservations here. Applied before the Current/Past split
     *  in renderMyReservationsList. */
    private boolean matchesListMode(ReservationSummary summary) {
        boolean isRealReservation = "reservation".equals(summary.kind);
        boolean kindMatches = reserveMode ? !isRealReservation : isRealReservation;
        return kindMatches && myReservationsServiceType.equals(summary.service_type);
    }

    /**
     * Current = still Pending/Confirmed/Ongoing and not cancelled/expired; everything else
     * (Cancelled, expired-while-still-Pending, or Checked Out) is Past. Expiry for a still-Pending
     * advance reservation prefers the real payment_deadline column; falls back to the same 7-day
     * heuristic isReservationExpired() uses if the server ever omits it.
     */
    private boolean isPastReservation(ReservationSummary summary) {
        if (summary.cancelled) {
            return true;
        }
        if (summary.current_step >= 3) {
            return true;
        }
        if ("advance_reservation".equals(summary.kind)) {
            // parseIsoMillis falls back to "now" for a null/malformed string (fine for its
            // original cancelled_at caller, where the field should always be present) — checked
            // explicitly here first instead, since "field absent" must NOT be treated as "deadline
            // is right now" (that would mark every still-pending reservation expired immediately).
            if (summary.payment_deadline != null && !summary.payment_deadline.isEmpty()) {
                return System.currentTimeMillis() > parseIsoMillis(summary.payment_deadline);
            }
            if (summary.created_at != null && !summary.created_at.isEmpty()) {
                return System.currentTimeMillis() - parseIsoMillis(summary.created_at) > RESERVATION_PAYMENT_WINDOW_MS;
            }
        }
        return false;
    }

    private StatusPresentation resolveSummaryStatus(ReservationSummary summary) {
        if (summary.cancelled) {
            return new StatusPresentation(R.attr.errorText, R.string.reservation_status_cancelled,
                    R.string.reservation_desc_cancelled_default);
        }
        if ("advance_reservation".equals(summary.kind) && isPastReservation(summary)) {
            return new StatusPresentation(R.attr.textMuted, R.string.reservation_status_expired,
                    R.string.reservation_desc_expired);
        }
        if ("reservation".equals(summary.kind)) {
            return new StatusPresentation(R.attr.successText, R.string.reservation_status_booked,
                    R.string.reservation_desc_booked);
        }
        return new StatusPresentation(R.attr.accentPrimary, R.string.reservation_status_pending,
                R.string.reservation_desc_awaiting_payment);
    }

    private View buildReservationListCard(LayoutInflater inflater, ViewGroup parent, ReservationSummary summary) {
        View card = inflater.inflate(R.layout.view_my_reservation_list_card, parent, false);

        String title = summary.room_type_name != null && !summary.room_type_name.isEmpty()
                ? formatShowcaseName(summary.room_type_name, summary.item_name) : summary.item_name;
        ((TextView) card.findViewById(R.id.reservationCardTitle)).setText(title);

        StatusPresentation status = resolveSummaryStatus(summary);
        card.findViewById(R.id.reservationCardStatusDot).setBackgroundTintList(
                ColorStateList.valueOf(ThemeManager.color(activity, status.colorAttr)));
        TextView statusLabel = card.findViewById(R.id.reservationCardStatusLabel);
        statusLabel.setText(status.labelRes);
        statusLabel.setTextColor(ThemeManager.color(activity, status.colorAttr));

        long checkInMillisLocal = parseDateOnlyMillis(summary.check_in);
        long checkOutMillisLocal = parseDateOnlyMillis(summary.check_out);
        ((TextView) card.findViewById(R.id.reservationCardDates)).setText(
                formatDate(checkInMillisLocal) + " – " + formatDate(checkOutMillisLocal));

        ((TextView) card.findViewById(R.id.reservationCardReference)).setText(summary.booking_reference);
        ((TextView) card.findViewById(R.id.reservationCardTotal)).setText(
                formatCurrency((int) Math.round(summary.subtotal)));

        card.setOnClickListener(view -> {
            loadReservationFromSummary(summary);
            reservationDetailsBackTarget = SCREEN_MY_RESERVATIONS_LIST;
            showScreen(SCREEN_RESERVATION_DETAILS);
        });
        return card;
    }

    /**
     * Populates the same fields bindReservationDetails/bindReservationPayment already read
     * (normally set live by submitReservationRequest/snapshotReservationDisplayData) directly
     * from a My Reservations list item, so tapping any past or current reservation reuses the
     * existing Details screen, its live status-refresh polling, Pay Now, and Cancel unchanged.
     *
     * <p>service_type only distinguishes hotel/cottage/hall from cottage today (see
     * Api\BookingController::summarizeAdvanceReservation on the backend, which maps KTV to
     * "hotel" too) — Cottage/KTV/Function Hall rows only reach this list at all if the same guest
     * reserved one through the website (mobile's own real Reserve submission is Hotel Rooms
     * only), and render with Hotel Rooms' date-range/nights shape as a best-effort fallback since
     * Reservation Details never had dedicated history rendering for those categories.
     */
    /** Entry point for the Profile page's My Reservations / My Bookings lists: shows this record in
     *  the normal Reservation Details screen (live status refresh, Pay Now and Cancel included);
     *  Back runs onBack instead of returning into the booking flow. */
    void openReservationFromHistory(ReservationSummary summary, Runnable onBack) {
        loadReservationFromSummary(summary);
        reservationDetailsExternalBack = onBack;
        showScreen(SCREEN_RESERVATION_DETAILS);
    }

    private void leaveReservationDetails() {
        if (reservationDetailsExternalBack != null) {
            Runnable back = reservationDetailsExternalBack;
            reservationDetailsExternalBack = null;
            back.run();
        } else {
            showScreen(reservationDetailsBackTarget);
        }
    }

    private void loadReservationFromSummary(ReservationSummary summary) {
        boolean isRealReservation = "reservation".equals(summary.kind);

        // A real Reservation's id is an online_bookingID, a different id space than what Pay
        // Now/Cancel/status-refresh expect (they operate on AdvanceReservation ids) — and none of
        // those actions apply to an already-booked reservation anyway.
        reservationAdvanceReservationId = isRealReservation ? -1 : summary.id;
        reservationReferenceNo = summary.booking_reference != null ? summary.booking_reference : "";
        reservationCancelReason = summary.cancel_reason != null ? summary.cancel_reason : "";
        reservationCancelledAtMillis = summary.cancelled ? parseIsoMillis(summary.cancelled_at) : -1;
        reservationCancelledBy = summary.cancelled_by != null ? summary.cancelled_by : "";

        long createdAtMillis = parseIsoMillis(summary.created_at);
        reservationSubmittedAtMillis = createdAtMillis > 0 ? createdAtMillis : System.currentTimeMillis();
        reservationPaymentSubmitted = isRealReservation;
        reservationBookedAtMillis = isRealReservation ? reservationSubmittedAtMillis : -1;

        if (summary.cancelled) {
            reservationStatus = RES_STATUS_CANCELLED;
        } else if (isRealReservation) {
            reservationStatus = RES_STATUS_BOOKED;
        } else {
            reservationStatus = RES_STATUS_PENDING;
        }

        activeContinuation = "cottage".equals(summary.service_type) ? CONTINUATION_COTTAGE
                : "ktv".equals(summary.service_type) ? CONTINUATION_KTV : CONTINUATION_NONE;

        long checkInMillisLocal = parseDateOnlyMillis(summary.check_in);
        long checkOutMillisLocal = parseDateOnlyMillis(summary.check_out);

        reservationSnapshotAccommodation = summary.service_lines != null && !summary.service_lines.isEmpty()
                ? TextUtils.join(", ", summary.service_lines)
                : (summary.item_name != null ? summary.item_name : "");
        reservationSnapshotDateRange = formatDate(checkInMillisLocal) + " – " + formatDate(checkOutMillisLocal);
        reservationSnapshotShowNights = "hotel".equals(summary.service_type);
        reservationSnapshotNights = checkInMillisLocal > 0 && checkOutMillisLocal > checkInMillisLocal
                ? (int) ((checkOutMillisLocal - checkInMillisLocal) / ONE_DAY_MS) : 0;
        reservationSnapshotGuestSummary = formatGuestsCount(summary.adults, summary.children);
        reservationSnapshotGrandTotal = (int) Math.round(summary.subtotal);
    }

    /** "yyyy-MM-dd" (check_in/check_out from the my-bookings response) — decode counterpart of
     *  the "yyyy-MM-dd" strings this file encodes elsewhere (e.g. submitReservationRequest). */
    private long parseDateOnlyMillis(String yyyyMmDd) {
        if (yyyyMmDd == null || yyyyMmDd.isEmpty()) {
            return -1;
        }
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(yyyyMmDd).getTime();
        } catch (java.text.ParseException e) {
            return -1;
        }
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
                            if (multiMode) {
                                multiResetProgress();
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

    /** Cottage Use dropdown order — index must match R.array.cottage_use_options exactly. */
    private static final String[] COTTAGE_USE_OPTIONS =
            {ReservationCatalog.RATE_TYPE_DAY, ReservationCatalog.RATE_TYPE_NIGHT, ReservationCatalog.RATE_TYPE_OVERNIGHT};

    /**
     * Focused Cottage Use/date/adults/children/Next form for Cottage Overview's "Book Now" (see
     * #showCottageDatesEntry). No raw time picker — Cottage Use (Day Use/Night Use/Overnight)
     * already captures the relevant period, same concept as the receptionist side's own Create
     * Reservation > Cottage screen. Feeds the same shared cottageRateType/cottageDateMillis/
     * cottageAdults/cottageChildren fields the tabbed Book-tab screen's Cottage tab uses, so
     * tapping Next just switches to that screen (with the Cottage tab forced active) and
     * collapses its search panel to reveal the already-loaded cottage catalog below — reusing
     * its browse/select logic rather than duplicating it here.
     */
    private void bindCottageDatesEntry(View v) {
        bindHeader(v, R.id.cottageDatesHeaderBar, R.string.cottage_dates_title, onBackToOverview);

        // Real, server-backed list either way (see bindMyReservationsList) — same screen Hotel
        // Rooms uses, reading the same /my-bookings response filtered to opposite kinds (see
        // #matchesListMode): Reserve shows still-pending/cancelled advance reservations, Book
        // shows already-booked/paid ones (Cottage Book submission is still local-mock, so that
        // list is legitimately empty for now — see submitCottageReservationRequest's own doc).
        TextView myReservationsButton = v.findViewById(R.id.cottageDatesMyReservationsButton);
        myReservationsButton.setText(reserveMode ? R.string.button_my_reservations : R.string.button_my_bookings);
        myReservationsButton.setOnClickListener(view -> {
            myReservationsBackTarget = SCREEN_COTTAGE_DATES;
            myReservationsServiceType = "cottage";
            showScreen(SCREEN_MY_RESERVATIONS_LIST);
        });

        View dateField = v.findViewById(R.id.cottageDatesDateField);
        TextView dateText = v.findViewById(R.id.cottageDatesDateText);
        TextView dateError = v.findViewById(R.id.cottageDatesDateError);
        Spinner useSpinner = v.findViewById(R.id.cottageDatesUseSpinner);
        EditText adultsValue = v.findViewById(R.id.cottageDatesAdultsValue);
        EditText childrenValue = v.findViewById(R.id.cottageDatesChildrenValue);
        TextView totalGuestValue = v.findViewById(R.id.cottageDatesTotalGuestValue);

        updateCottageDateField(dateText);
        totalGuestValue.setText(String.valueOf(cottageAdults + cottageChildren));

        ArrayAdapter<CharSequence> useAdapter = ArrayAdapter.createFromResource(
                activity, R.array.cottage_use_options, android.R.layout.simple_spinner_item);
        useAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        useSpinner.setAdapter(useAdapter);
        int selectedIndex = 0;
        for (int i = 0; i < COTTAGE_USE_OPTIONS.length; i++) {
            if (COTTAGE_USE_OPTIONS[i].equals(cottageRateType)) {
                selectedIndex = i;
                break;
            }
        }
        useSpinner.setSelection(selectedIndex);
        useSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View item, int position, long id) {
                cottageRateType = COTTAGE_USE_OPTIONS[position];
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // A Spinner backed by a non-empty adapter always has a selection once shown —
                // nothing to do here.
            }
        });

        dateField.setOnClickListener(view -> GlassDatePicker.showDatePickerDialog(
                activity, cottageDateMillis, System.currentTimeMillis() - 1000L, millis -> {
                    cottageDateMillis = millis;
                    updateCottageDateField(dateText);
                    dateError.setVisibility(View.GONE);
                }));

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
            if (cottageDateMillis < 0) {
                showError(dateError, R.string.error_date_required);
                valid = false;
            } else if (cottageDateMillis < System.currentTimeMillis() - ONE_DAY_MS) {
                showError(dateError, R.string.error_date_past);
                valid = false;
            } else {
                dateError.setVisibility(View.GONE);
            }
            if (valid) {
                proceedToCottageSelection(v);
            }
        });
    }

    /** Maps the guest's Cottage Use choice (see cottageRateType) to the backend's day/night/
     *  overnight key (CottageVariant::COTTAGE_USES) for booking/cottage-availability. */
    private String cottageUseKey() {
        if (ReservationCatalog.RATE_TYPE_NIGHT.equals(cottageRateType)) {
            return "night";
        }
        if (ReservationCatalog.RATE_TYPE_OVERNIGHT.equals(cottageRateType)) {
            return "overnight";
        }
        return "day";
    }

    /** Advances to Your Selected Cottage/s via a real, live availability check (see
     *  #fetchCottageAvailability) for the guest's chosen Date and Cottage Use. */
    private void proceedToCottageSelection(View datesView) {
        fetchCottageAvailability(datesView.findViewById(R.id.cottageDatesNextButton), () -> {
            if (multiMode) {
                multiResetProgress();
                roomQuantities.clear();
            }
        });
    }

    /**
     * Real, live availability check for the guest's chosen Date and Cottage Use — the Cottage
     * equivalent of fetchAvailabilityForRoomSelection() for Hotel Rooms: checked against actual
     * Bookings and other guests'/staff's Pending holds for that exact date and period (see the
     * backend's Concerns\HandlesCottageAvailability, shared with the Receptionist's own Create
     * Reservation > Cottage wizard so both sides can never disagree), unlike getCottagesKtv()'s
     * static in-stock count. Never cached client-side — changing the Date or Cottage Use and
     * tapping Next again always re-checks for real, replacing cottageItems on success.
     */
    private void fetchCottageAvailability(Button nextButton, Runnable onResultAccepted) {
        if (isLoadingCottageAvailability) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }

        isLoadingCottageAvailability = true;
        nextButton.setEnabled(false);
        nextButton.setText(R.string.button_checking_availability);

        String dateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(cottageDateMillis));
        ApiClient.bookingApi().checkCottageAvailability("Bearer " + token, dateStr, cottageUseKey())
                .enqueue(new Callback<CottageAvailabilityResponse>() {
                    @Override
                    public void onResponse(Call<CottageAvailabilityResponse> call, Response<CottageAvailabilityResponse> response) {
                        isLoadingCottageAvailability = false;
                        nextButton.setEnabled(true);
                        nextButton.setText(R.string.button_next);
                        if (!response.isSuccessful() || response.body() == null) {
                            if (response.code() == 401) {
                                Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(activity, R.string.toast_availability_check_failed, Toast.LENGTH_LONG).show();
                            }
                            return;
                        }
                        if (onResultAccepted != null) {
                            onResultAccepted.run();
                        }
                        cottageItems = response.body().cottages != null
                                ? new ArrayList<>(response.body().cottages) : new ArrayList<>();
                        for (CottageKtvOption item : cottageItems) {
                            if (!roomQuantities.containsKey(item.group_id)) {
                                roomQuantities.put(item.group_id, 0);
                            }
                        }
                        resetAndPreselectCottageSelection();
                        restoreMultiQuantities(FAC_COTTAGE);
                        showScreen(SCREEN_COTTAGE_SELECTION);
                    }

                    @Override
                    public void onFailure(Call<CottageAvailabilityResponse> call, Throwable t) {
                        isLoadingCottageAvailability = false;
                        nextButton.setEnabled(true);
                        nextButton.setText(R.string.button_next);
                        Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
                    }
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

        // Guest count never gates a Cottage's availability (see bindCottageSelection) — a party
        // can always book more than one unit of a cottage to fit everyone, so only in-stock
        // status matters here.
        for (CottageKtvOption item : cottageItems) {
            if (item.variant_id == selectionOriginVariantId && item.available_quantity > 0) {
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
        bindHeader(v, R.id.ktvDatesHeaderBar, R.string.ktv_dates_title, this::handleBackPressed);

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

        // In the multi-facility flow the date is carried over from the first facility and isn't
        // asked for again — only the session details (rate type, start time, guests) are.
        if (multiMode && multiIndex > 0) {
            dateField.setAlpha(0.6f);
        } else {
            dateField.setOnClickListener(view -> GlassDatePicker.showDatePickerDialog(
                    activity, ktvDateMillis, System.currentTimeMillis() - 1000L, millis -> {
                        ktvDateMillis = millis;
                        updateKtvDateField(dateText);
                        dateError.setVisibility(View.GONE);
                    }));
        }

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
        if (multiMode && multiIndex == 0) {
            multiResetProgress();
            roomQuantities.clear();
        }
        nextButton.setEnabled(false);
        nextButton.setText(R.string.button_checking_availability);
        fetchCottagesKtvData(nextButton, () -> {
            nextButton.setEnabled(true);
            nextButton.setText(R.string.button_next);
            resetAndPreselectKtvSelection();
            restoreMultiQuantities(FAC_KTV);
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
                    formatShowcaseName(origin.variant_name, origin.category_name), origin.capacity, false, origin.description,
                    activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)),
                    origin.group_id, origin.available_quantity, R.string.unit_label_room, originAvailable, !originAvailable,
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
                        formatShowcaseName(room.variant_name, room.category_name), room.capacity, false, room.description,
                        activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)),
                        room.group_id, room.available_quantity, R.string.unit_label_room, false, false,
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
                        formatShowcaseName(room.variant_name, room.category_name), room.capacity, false, room.description,
                        activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)),
                        room.group_id, room.available_quantity, R.string.unit_label_room, false, false,
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
            if (multiMode) {
                multiAdvance(v.findViewById(R.id.itemSelectionNextButton));
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
                    formatShowcaseName(room.variant_name, room.category_name), room.capacity, false, room.description,
                    activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)),
                    room.group_id, room.available_quantity, R.string.unit_label_room, false, false,
                    R.string.unavailable_room_message, () -> bindRoomBrowse(v));
            container.addView(card);
        }

        v.findViewById(R.id.itemBrowseNextButton).setOnClickListener(view -> showScreen(SCREEN_ROOM_SELECTION));
    }

    /**
     * Shows the cottage the guest originally chose pre-selected with a quantity control if it's
     * in stock, or grayed out with an unavailable message otherwise — plus other available
     * cottage types below, same pattern as {@link #bindRoomSelection}. Cottages have no per-
     * date/time availability check on the backend (only overall stock, see
     * fetchCottagesKtvData) so "available" here means in-stock, not conflict-checked against the
     * guest's chosen date/schedule. Guest count never gates availability either — a party that
     * outnumbers one cottage's capacity can simply book more than one unit of it (see "Add
     * Another Cottage" below); capacity is shown on the card purely as information (see
     * R.string.cottage_capacity_format). Next is a placeholder for now — Cottage bookings don't
     * have a Review/Billing/Payment continuation yet.
     */
    private void bindCottageSelection(View v) {
        bindHeader(v, R.id.itemSelectionHeaderBar, R.string.cottage_selection_title,
                this::handleBackPressed);
        if (multiMode) {
            bindMultiCottageUseRow(v);
        }

        CottageKtvOption origin = null;
        for (CottageKtvOption item : cottageItems) {
            if (item.variant_id == selectionOriginVariantId) {
                origin = item;
                break;
            }
        }
        boolean originAvailable = origin != null && origin.available_quantity > 0;
        if (!originAvailable) {
            selectionShowOthers = true;
        }

        LayoutInflater inflater = LayoutInflater.from(activity);
        FrameLayout originSlot = v.findViewById(R.id.itemSelectionOriginSlot);
        originSlot.removeAllViews();
        if (origin != null) {
            View card = buildSelectableCard(inflater, originSlot, origin.image_path,
                    formatShowcaseName(origin.variant_name, null), origin.capacity, true, origin.description,
                    activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(resolvedItemPrice(origin))), resolvedItemPriceUnit(origin)),
                    origin.group_id, origin.available_quantity, R.string.unit_label_cottage, originAvailable, !originAvailable,
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
                if (item.variant_id == selectionOriginVariantId || item.available_quantity <= 0) {
                    continue;
                }
                View card = buildSelectableCard(inflater, otherContainer, item.image_path,
                        formatShowcaseName(item.variant_name, null), item.capacity, true, item.description,
                        activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(resolvedItemPrice(item))), resolvedItemPriceUnit(item)),
                        item.group_id, item.available_quantity, R.string.unit_label_cottage, false, false,
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
                        formatShowcaseName(item.variant_name, null), item.capacity, true, item.description,
                        activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(resolvedItemPrice(item))), resolvedItemPriceUnit(item)),
                        item.group_id, item.available_quantity, R.string.unit_label_cottage, false, false,
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
            if (multiMode) {
                multiAdvance(v.findViewById(R.id.itemSelectionNextButton));
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

        LinearLayout container = v.findViewById(R.id.itemBrowseContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (CottageKtvOption item : cottageItems) {
            if (item.variant_id == selectionOriginVariantId || item.available_quantity <= 0) {
                continue;
            }
            View card = buildSelectableCard(inflater, container, item.image_path,
                    formatShowcaseName(item.variant_name, null), item.capacity, true, item.description,
                    activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(resolvedItemPrice(item))), resolvedItemPriceUnit(item)),
                    item.group_id, item.available_quantity, R.string.unit_label_cottage, false, false,
                    R.string.unavailable_cottage_message, () -> bindCottageBrowse(v));
            container.addView(card);
        }

        v.findViewById(R.id.itemBrowseNextButton).setOnClickListener(view -> showScreen(SCREEN_COTTAGE_SELECTION));
    }

    /**
     * Cottage flow's own Review Your Selection (see view_cottage_review.xml) — Cottage Use/date/
     * total guest, every cottage the guest added on Your Selected Cottage/s, and a special
     * request note. Separate from bindReview, which is Hotel Rooms-only (check-in/out, nights,
     * room rows); Next is a placeholder for now, since cottage bookings don't have an Amenity/
     * Billing/Payment continuation yet.
     */
    private void bindCottageReview(View v) {
        bindHeader(v, R.id.cottageReviewHeaderBar, R.string.hotel_review_title,
                () -> showScreen(SCREEN_COTTAGE_SELECTION));

        bindRow(v.findViewById(R.id.cottageReviewRowRateType), R.string.label_selected_rate_type,
                cottageRateTypeLabel());
        bindRow(v.findViewById(R.id.cottageReviewRowDate), R.string.label_date, formatDate(cottageDateMillis));
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
                    .setText(formatShowcaseName(item.variant_name, null));
            ((TextView) row.findViewById(R.id.cottageReviewRowMeta)).setText(activity.getString(
                    R.string.cottage_capacity_format, item.capacity) + " · " + activity.getString(R.string.format_quantity, qty));
            ((TextView) row.findViewById(R.id.cottageReviewRowPrice)).setText(activity.getString(
                    R.string.format_price_with_unit, formatMoney((int) Math.round(resolvedItemPrice(item))), resolvedItemPriceUnit(item)));
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
        if (ReservationCatalog.RATE_TYPE_OVERNIGHT.equals(cottageRateType)) {
            return activity.getString(R.string.rate_type_overnight);
        }
        return activity.getString(R.string.rate_type_day);
    }

    /**
     * The price to display/charge for one unit of a Cottage/KTV/Hall item at the currently
     * selected Cottage Use (see cottageRateType) — looks up the matching Day Use/Night Use/
     * Overnight entry in item.price_breakdown (Cottages only; see CottageVariant::priceBreakdown
     * on the backend), falling back to the flat item.price when there's no breakdown at all
     * (KTV/Halls) or the breakdown has just the one Single Rate entry (a 24-Hour Cottage Type,
     * where every Cottage Use resolves to the same rate regardless of what was picked upfront).
     */
    private double resolvedItemPrice(CottageKtvOption item) {
        if (item.price_breakdown == null || item.price_breakdown.size() != 3) {
            return item.price_breakdown != null && item.price_breakdown.size() == 1
                    ? item.price_breakdown.get(0).amount : item.price;
        }
        String label = cottageRateTypeLabel();
        for (PriceBreakdownEntry entry : item.price_breakdown) {
            if (label.equals(entry.label)) {
                return entry.amount;
            }
        }
        return item.price;
    }

    /** The unit label to show next to resolvedItemPrice(item) — "Day Use"/"Night Use"/
     *  "Overnight"/"Single Rate" for a Cottage (replacing the generic, no-longer-accurate
     *  "day use" price_unit the backend still sends for backward compatibility), or the plain
     *  flat price_unit ("hour"/"event") for KTV/Halls, which have no breakdown to react to. */
    private String resolvedItemPriceUnit(CottageKtvOption item) {
        if (item.price_breakdown == null || item.price_breakdown.isEmpty()) {
            return item.price_unit;
        }
        if (item.price_breakdown.size() == 1) {
            return item.price_breakdown.get(0).label;
        }
        return cottageRateTypeLabel();
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
     * nights) and cottage line items instead of room ones; guest info, amenities and
     * payment option sections are identical to bindBilling since none of those are Hotel
     * Rooms-specific.
     */
    private void bindCottageBilling(View v) {
        bindHeader(v, R.id.hotelBillingHeaderBar, R.string.hotel_billing_title, () -> showScreen(SCREEN_AMENITY));

        bindRow(v.findViewById(R.id.billingRowFullName), R.string.label_full_name, ProfileStore.getFullName(activity));
        bindRow(v.findViewById(R.id.billingRowEmail), R.string.label_email_address, ProfileStore.getEmail(activity));
        bindRow(v.findViewById(R.id.billingRowMobile), R.string.label_mobile_number, ProfileStore.getContactNumber(activity));
        bindRow(v.findViewById(R.id.billingRowAddress), R.string.label_address, ProfileStore.getAddress(activity));

        bindRow(v.findViewById(R.id.billingRowCheckIn), R.string.label_selected_rate_type, cottageRateTypeLabel());
        bindRow(v.findViewById(R.id.billingRowCheckOut), R.string.label_date, formatDate(cottageDateMillis));
        // No separate Time field for Cottages (see bindCottageDatesEntry) — this row is shared
        // with Hotel Rooms' own Nights display, so it's hidden here rather than removed outright.
        v.findViewById(R.id.billingRowNights).setVisibility(View.GONE);
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
            int unitPrice = (int) Math.round(resolvedItemPrice(item));
            int subtotal = unitPrice * qty;
            cottageTotal += subtotal;
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, accommodationContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(formatShowcaseName(item.variant_name, null));
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText(activity.getString(R.string.cottage_capacity_format, item.capacity) + " · Qty " + qty + " · "
                    + activity.getString(R.string.format_price_with_unit, formatMoney(unitPrice), resolvedItemPriceUnit(item)));
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

        bindLine(v.findViewById(R.id.billingRowRoomCharges), R.string.label_cottage_charges, cottageTotal);
        bindLine(v.findViewById(R.id.billingRowAmenitiesTotal), R.string.label_amenities_total, amenitiesTotal);

        int grandTotal = cottageTotal + amenitiesTotal;
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
            total += (int) Math.round(resolvedItemPrice(item)) * roomQuantities.get(item.group_id);
        }
        for (AmenityAvailability amenity : availableAmenities) {
            total += (int) Math.round(amenity.price) * amenityQuantities.get(amenity.id);
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
                    formatShowcaseName(origin.variant_name, null), origin.capacity, false, origin.description,
                    activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(resolvedItemPrice(origin))), resolvedItemPriceUnit(origin)),
                    origin.group_id, origin.available_quantity, R.string.unit_label_ktv_room, originAvailable, !originAvailable,
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
                        formatShowcaseName(item.variant_name, null), item.capacity, false, item.description,
                        activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(resolvedItemPrice(item))), resolvedItemPriceUnit(item)),
                        item.group_id, item.available_quantity, R.string.unit_label_ktv_room, false, false,
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
                        formatShowcaseName(item.variant_name, null), item.capacity, false, item.description,
                        activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(resolvedItemPrice(item))), resolvedItemPriceUnit(item)),
                        item.group_id, item.available_quantity, R.string.unit_label_ktv_room, false, false,
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
            if (multiMode) {
                multiAdvance(v.findViewById(R.id.itemSelectionNextButton));
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
                    formatShowcaseName(item.variant_name, null), item.capacity, false, item.description,
                    activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(resolvedItemPrice(item))), resolvedItemPriceUnit(item)),
                    item.group_id, item.available_quantity, R.string.unit_label_ktv_room, false, false,
                    R.string.unavailable_ktv_message, () -> bindKtvBrowse(v));
            container.addView(card);
        }

        v.findViewById(R.id.itemBrowseNextButton).setOnClickListener(view -> showScreen(SCREEN_KTV_SELECTION));
    }

    /**
     * KTV flow's own Review Your Selection (see view_ktv_review.xml) — rate type/date/start
     * time/end time/total guests, every KTV room the guest added on Your Selected KTV Room/s,
     * and a special request note. Separate from bindReview, which is Hotel Rooms-only; Next is a
     * placeholder for now, since KTV bookings don't have an Amenity/Billing/Payment
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
                    .setText(formatShowcaseName(item.variant_name, null));
            ((TextView) row.findViewById(R.id.ktvReviewRowMeta)).setText(activity.getString(
                    R.string.format_capacity, item.capacity) + " · " + activity.getString(R.string.format_quantity, qty));
            ((TextView) row.findViewById(R.id.ktvReviewRowPrice)).setText(activity.getString(
                    R.string.format_price_with_unit, formatMoney((int) Math.round(resolvedItemPrice(item))), resolvedItemPriceUnit(item)));
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
     * and payment option sections are identical to bindBilling since none of those are
     * Hotel Rooms-specific.
     */
    private void bindKtvBilling(View v) {
        bindHeader(v, R.id.hotelBillingHeaderBar, R.string.hotel_billing_title, () -> showScreen(SCREEN_AMENITY));

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
            int unitPrice = (int) Math.round(resolvedItemPrice(item));
            int subtotal = unitPrice * qty;
            ktvTotal += subtotal;
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, accommodationContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(formatShowcaseName(item.variant_name, null));
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText(activity.getString(R.string.format_capacity, item.capacity) + " · Qty " + qty + " · "
                    + activity.getString(R.string.format_price_with_unit, formatMoney(unitPrice), resolvedItemPriceUnit(item)));
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

        bindLine(v.findViewById(R.id.billingRowRoomCharges), R.string.label_ktv_charges, ktvTotal);
        bindLine(v.findViewById(R.id.billingRowAmenitiesTotal), R.string.label_amenities_total, amenitiesTotal);

        int grandTotal = ktvTotal + amenitiesTotal;
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
            total += (int) Math.round(resolvedItemPrice(item)) * roomQuantities.get(item.group_id);
        }
        for (AmenityAvailability amenity : availableAmenities) {
            total += (int) Math.round(amenity.price) * amenityQuantities.get(amenity.id);
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
     * Details fields, selected item list, and grand total; Guest Information, Amenities,
     * and the two policy checkboxes are identical across categories. Amount to Pay shows Full
     * Payment/30% Down Payment as plain informational rows, not Billing's clickable Payment
     * Option cards, since Reserve has nothing to select toward. The RESERVE button is a
     * placeholder for now — no backend endpoint yet for a reservation request shaped like this.
     */
    private void bindReservationSummary(View v) {
        bindHeader(v, R.id.reservationSummaryHeaderBar, R.string.hotel_billing_title, () -> showScreen(SCREEN_AMENITY));

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
            // No separate Time field for Cottages (see bindCottageDatesEntry) — row3 is shared
            // with Hotel/KTV's own Time-ish rows, so it's hidden here rather than removed outright.
            row3.setVisibility(View.GONE);
            bindRow(row4, R.string.label_number_of_guests, formatCottageGuests());
            row5.setVisibility(View.GONE);

            selectedTitle.setText(R.string.section_selected_cottage);
            for (CottageKtvOption item : cottageItems) {
                int qty = roomQuantities.get(item.group_id);
                if (qty <= 0) {
                    continue;
                }
                int unitPrice = (int) Math.round(resolvedItemPrice(item));
                View row = inflater.inflate(R.layout.view_hotel_billing_line_row, itemsContainer, false);
                ((TextView) row.findViewById(R.id.lineName)).setText(formatShowcaseName(item.variant_name, null));
                TextView meta = row.findViewById(R.id.lineMeta);
                meta.setText(activity.getString(R.string.cottage_capacity_format, item.capacity) + " · Qty " + qty + " · "
                        + activity.getString(R.string.format_price_with_unit, formatMoney(unitPrice), resolvedItemPriceUnit(item)));
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
                int unitPrice = (int) Math.round(resolvedItemPrice(item));
                View row = inflater.inflate(R.layout.view_hotel_billing_line_row, itemsContainer, false);
                ((TextView) row.findViewById(R.id.lineName)).setText(formatShowcaseName(item.variant_name, null));
                TextView meta = row.findViewById(R.id.lineMeta);
                meta.setText(activity.getString(R.string.format_capacity, item.capacity) + " · Qty " + qty + " · "
                        + activity.getString(R.string.format_price_with_unit, formatMoney(unitPrice), resolvedItemPriceUnit(item)));
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

        // Amenities are category-agnostic — identical loop across Room/Cottage/KTV, same as
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

        bindLine(v.findViewById(R.id.reservationSummaryFullPaymentRow), R.string.label_full_payment, grandTotal);
        bindLine(v.findViewById(R.id.reservationSummaryDownPaymentRow), R.string.label_down_payment_30,
                computePartialAmount(grandTotal));

        CheckBox termsBox = v.findViewById(R.id.checkboxTerms);
        CheckBox cancellationBox = v.findViewById(R.id.checkboxCancellation);
        termsBox.setChecked(termsChecked);
        cancellationBox.setChecked(cancellationChecked);
        termsBox.setOnCheckedChangeListener((button, checked) -> termsChecked = checked);
        cancellationBox.setOnCheckedChangeListener((button, checked) -> cancellationChecked = checked);

        // Hotel Rooms and Cottages both submit a real reservation request to the shared backend
        // (see submitReservationRequest/submitCottageReservationRequest) — KTV has no such
        // endpoint yet, so it keeps the mock "request submitted" confirmation this screen
        // originally shipped with.
        v.findViewById(R.id.reservationSummaryReserveButton).setOnClickListener(view -> {
            if (activeContinuation == CONTINUATION_COTTAGE) {
                submitCottageReservationRequest(v);
                return;
            }
            if (activeContinuation != CONTINUATION_NONE) {
                reservationReferenceNo = generateReservationReference();
                reservationStatus = RES_STATUS_PENDING;
                reservationPaymentSubmitted = false;
                reservationSubmittedAtMillis = System.currentTimeMillis();
                reservationPaymentSubmittedAtMillis = -1;
                reservationBookedAtMillis = -1;
                reservationCancelledAtMillis = -1;
                reservationCancelReason = "";
                snapshotReservationDisplayData();
                showScreen(SCREEN_RESERVATION_CONFIRMED);
                return;
            }
            submitReservationRequest(v);
        });
    }

    /**
     * Creates the real Pending reservation on the shared backend (same tables the website's own
     * guest submission and the Receptionist's staff wizard write to), so it shows up in
     * Reservation Management's Hotel list immediately. See submitCottageReservationRequest()
     * for Cottage's own equivalent (KTV is still local-mock, see that method's own class doc).
     */
    private void submitReservationRequest(View summaryView) {
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }

        Button reserveButton = summaryView.findViewById(R.id.reservationSummaryReserveButton);
        reserveButton.setEnabled(false);

        String checkInStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(checkInMillis));
        String checkOutStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(checkOutMillis));

        ApiClient.bookingApi().createReservation(
                "Bearer " + token,
                "0",
                checkInStr,
                checkOutStr,
                adults,
                children,
                specialRequest,
                quantityFieldMap("room_quantities", roomQuantities)
        ).enqueue(new Callback<ReservationRequestResponse>() {
            @Override
            public void onResponse(Call<ReservationRequestResponse> call, Response<ReservationRequestResponse> response) {
                reserveButton.setEnabled(true);
                if (response.isSuccessful() && response.body() != null) {
                    reservationAdvanceReservationId = response.body().advance_reservation_id;
                    reservationReferenceNo = response.body().booking_reference;
                    reservationStatus = RES_STATUS_PENDING;
                    // Persisted so ChatPollingService (a separate component with no reference to
                    // this in-memory instance) can keep checking on it in the background and
                    // notify the guest of a receptionist-side change — see
                    // ProfileStore#getActiveReservationId.
                    ProfileStore.setActiveReservationId(activity, reservationAdvanceReservationId);
                    ProfileStore.setActiveReservationStatus(activity, "pending");
                    reservationPaymentSubmitted = false;
                    reservationSubmittedAtMillis = System.currentTimeMillis();
                    reservationPaymentSubmittedAtMillis = -1;
                    reservationBookedAtMillis = -1;
                    reservationCancelledAtMillis = -1;
                    reservationCancelReason = "";
                    snapshotReservationDisplayData();
                    showScreen(SCREEN_RESERVATION_CONFIRMED);
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
            public void onFailure(Call<ReservationRequestResponse> call, Throwable t) {
                reserveButton.setEnabled(true);
                Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Cottage's own "Reserve" submission — same lifecycle as submitReservationRequest() above,
     * just calling createCottageReservation() (date+Cottage Use instead of a check-in/check-out
     * range) instead of createReservation(). Creates a real Pending AdvanceReservation on the
     * shared backend, so it shows up in Reservation Management's Cottage list immediately,
     * identically to a receptionist-created walk-in reservation (see that endpoint's own
     * docblock) — and reservationAdvanceReservationId being real from here on is also what makes
     * Reservation Details' status refresh/Cancel (below) work for Cottages with no further
     * change, exactly as they already do for Hotel Rooms.
     */
    private void submitCottageReservationRequest(View summaryView) {
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }

        Button reserveButton = summaryView.findViewById(R.id.reservationSummaryReserveButton);
        reserveButton.setEnabled(false);

        String dateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(cottageDateMillis));

        ApiClient.bookingApi().createCottageReservation(
                "Bearer " + token,
                "0",
                dateStr,
                cottageUseKey(),
                cottageAdults,
                cottageChildren,
                specialRequest,
                quantityFieldMap("cottage_quantities", roomQuantities)
        ).enqueue(new Callback<ReservationRequestResponse>() {
            @Override
            public void onResponse(Call<ReservationRequestResponse> call, Response<ReservationRequestResponse> response) {
                reserveButton.setEnabled(true);
                if (response.isSuccessful() && response.body() != null) {
                    reservationAdvanceReservationId = response.body().advance_reservation_id;
                    reservationReferenceNo = response.body().booking_reference;
                    reservationStatus = RES_STATUS_PENDING;
                    ProfileStore.setActiveReservationId(activity, reservationAdvanceReservationId);
                    ProfileStore.setActiveReservationStatus(activity, "pending");
                    reservationPaymentSubmitted = false;
                    reservationSubmittedAtMillis = System.currentTimeMillis();
                    reservationPaymentSubmittedAtMillis = -1;
                    reservationBookedAtMillis = -1;
                    reservationCancelledAtMillis = -1;
                    reservationCancelReason = "";
                    snapshotReservationDisplayData();
                    showScreen(SCREEN_RESERVATION_CONFIRMED);
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
            public void onFailure(Call<ReservationRequestResponse> call, Throwable t) {
                reserveButton.setEnabled(true);
                Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void bindReservationConfirmed(View v) {
        v.findViewById(R.id.reservationConfirmedViewButton).setOnClickListener(view -> {
            reservationDetailsBackTarget = datesScreenForContinuation();
            showScreen(SCREEN_RESERVATION_DETAILS);
        });
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

    /** "You cancelled this Reservation."/"The Reservation has been cancelled by our staff."
     *  depending on reservationCancelledBy (falls back to the old generic statement if that's
     *  ever unknown — e.g. a cancellation that predates this field existing), with the specific
     *  typed reason appended when one was given. */
    private String cancelledStatement() {
        String base;
        if ("guest".equals(reservationCancelledBy)) {
            base = activity.getString(R.string.reservation_cancelled_by_guest);
        } else if ("receptionist".equals(reservationCancelledBy)) {
            base = activity.getString(R.string.reservation_cancelled_by_staff);
        } else {
            base = activity.getString(R.string.reservation_desc_cancelled_default);
        }
        if (reservationCancelReason != null && !reservationCancelReason.isEmpty()) {
            base += activity.getString(R.string.format_reservation_cancelled_reason_suffix, reservationCancelReason);
        }
        return base;
    }

    /** Reservation Confirmed's "View Reservation" — a standalone status screen for the reservation
     *  just submitted (see SCREEN_RESERVATION_DETAILS). Reuses the same category-branching data
     *  already gathered for the Reservation Summary screen (see bindReservationSummary). */
    private void bindReservationDetails(View v) {
        bindHeader(v, R.id.reservationDetailsHeaderBar, R.string.reservation_details_title,
                this::leaveReservationDetails);

        refreshReservationStatus(v);

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
                    cancelledStatement());
        }
        v.findViewById(R.id.reservationDetailsExpiredContainer).setVisibility(
                isReservationExpired() ? View.VISIBLE : View.GONE);

        bindRow(v.findViewById(R.id.reservationDetailsRowReference), R.string.label_reference_no, reservationReferenceNo);
        bindRow(v.findViewById(R.id.reservationDetailsRowAccommodation), R.string.label_accommodation, reservationSnapshotAccommodation);
        bindRow(v.findViewById(R.id.reservationDetailsRowDate), R.string.label_date, reservationSnapshotDateRange);
        View nightsRow = v.findViewById(R.id.reservationDetailsRowNights);
        if (reservationSnapshotShowNights) {
            bindRow(nightsRow, R.string.label_nights_short, String.valueOf(reservationSnapshotNights));
            nightsRow.setVisibility(View.VISIBLE);
        } else {
            nightsRow.setVisibility(View.GONE);
        }
        bindRow(v.findViewById(R.id.reservationDetailsRowGuests), R.string.label_stay_guests, reservationSnapshotGuestSummary);
        int reservationGrandTotal = reservationSnapshotGrandTotal;
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

    /**
     * Hotel Rooms only (see reservationAdvanceReservationId) — there's no push notification
     * system, so this is what lets a receptionist-side change (Reservation Management's own
     * Cancel) actually reach the guest's device: every time Reservation Details is opened, it
     * asks the server for the real current status and re-renders if anything changed. Silently
     * no-ops on failure — the screen just keeps showing whatever it already had.
     */
    private void refreshReservationStatus(View v) {
        if (reservationAdvanceReservationId <= 0) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            return;
        }
        ApiClient.bookingApi().getReservationStatus("Bearer " + token, reservationAdvanceReservationId)
                .enqueue(new Callback<ReservationStatusResponse>() {
                    @Override
                    public void onResponse(Call<ReservationStatusResponse> call, Response<ReservationStatusResponse> response) {
                        if (currentScreen != SCREEN_RESERVATION_DETAILS || !response.isSuccessful() || response.body() == null) {
                            return;
                        }
                        ReservationStatusResponse body = response.body();
                        int newStatus = mapServerReservationStatus(body.status);
                        boolean changed = newStatus != reservationStatus;
                        reservationStatus = newStatus;
                        // Keeps ChatPollingService's own background check from re-notifying about
                        // something the guest already just saw here in the foreground.
                        ProfileStore.setActiveReservationStatus(activity, body.status);
                        if (newStatus == RES_STATUS_CANCELLED) {
                            reservationCancelReason = body.cancel_reason != null ? body.cancel_reason : "";
                            reservationCancelledBy = body.cancelled_by != null ? body.cancelled_by : "";
                            if (reservationCancelledAtMillis <= 0) {
                                reservationCancelledAtMillis = parseIsoMillis(body.cancelled_at);
                            }
                        } else if (newStatus == RES_STATUS_BOOKED && reservationBookedAtMillis <= 0) {
                            reservationBookedAtMillis = System.currentTimeMillis();
                        }
                        if (changed) {
                            bindReservationDetails(v);
                        }
                    }

                    @Override
                    public void onFailure(Call<ReservationStatusResponse> call, Throwable t) {
                        // Silently ignore — the screen just keeps showing its last known state.
                    }
                });
    }

    private static int mapServerReservationStatus(String status) {
        if ("cancelled".equals(status)) {
            return RES_STATUS_CANCELLED;
        }
        if ("booked".equals(status)) {
            return RES_STATUS_BOOKED;
        }
        return RES_STATUS_PENDING;
    }

    /** Best-effort ISO8601 parse (Laravel's toIso8601String, e.g. "2026-09-16T07:38:34+08:00") —
     *  falls back to now on anything unexpected, since this only feeds a timeline timestamp, not
     *  the status itself. */
    private long parseIsoMillis(String iso) {
        if (iso == null || iso.isEmpty()) {
            return System.currentTimeMillis();
        }
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(iso).getTime();
        } catch (java.text.ParseException e) {
            return System.currentTimeMillis();
        }
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
            if (reservationAdvanceReservationId > 0) {
                // A real reservation (Hotel Rooms or Cottage — see submitReservationRequest/
                // submitCottageReservationRequest) — cancel it on the shared backend so it
                // actually takes effect (Reservation Management's own list, and a re-fetch of
                // this same reservation's status, both need to see it), not just a local status
                // flip that the very next refreshReservationStatus() call would overwrite right
                // back to Pending.
                submitReservationCancellation(dialog, reason);
                return;
            }
            // KTV Reserve is still local-mock (see submitReservationRequest's own class doc) —
            // nothing real exists server-side to cancel.
            reservationStatus = RES_STATUS_CANCELLED;
            reservationCancelReason = reason;
            reservationCancelledAtMillis = System.currentTimeMillis();
            reservationCancelledBy = "guest";
            dialog.dismiss();
            showScreen(SCREEN_RESERVATION_DETAILS);
        }));
        dialog.show();
    }

    /**
     * Submits the guest's own cancellation to the shared backend (same cancelReservation()
     * endpoint the website's guest self-serve cancel uses) — works for any real reservation
     * (Hotel Rooms or Cottage; see reservationAdvanceReservationId), so it's reflected
     * everywhere: Reservation Management's list, and any later status refresh of this same
     * reservation.
     */
    private void submitReservationCancellation(AlertDialog dialog, String reason) {
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }

        Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positiveButton.setEnabled(false);

        ApiClient.bookingApi().cancelReservation("Bearer " + token, reservationAdvanceReservationId, reason)
                .enqueue(new Callback<CancelReservationResponse>() {
                    @Override
                    public void onResponse(Call<CancelReservationResponse> call, Response<CancelReservationResponse> response) {
                        positiveButton.setEnabled(true);
                        if (response.isSuccessful()) {
                            reservationStatus = RES_STATUS_CANCELLED;
                            reservationCancelReason = reason;
                            reservationCancelledAtMillis = System.currentTimeMillis();
                            reservationCancelledBy = "guest";
                            // Keeps ChatPollingService's own background check from re-notifying
                            // about a cancellation the guest just made themselves.
                            ProfileStore.setActiveReservationStatus(activity, "cancelled");
                            dialog.dismiss();
                            showScreen(SCREEN_RESERVATION_DETAILS);
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
                    public void onFailure(Call<CancelReservationResponse> call, Throwable t) {
                        positiveButton.setEnabled(true);
                        Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
                    }
                });
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
        int grandTotal = reservationSnapshotGrandTotal;
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
            if (activeContinuation != CONTINUATION_NONE) {
                reservationPaymentSubmitted = true;
                reservationPaymentSubmittedAtMillis = System.currentTimeMillis();
                showScreen(SCREEN_RESERVATION_PAYMENT_CONFIRMED);
                return;
            }
            submitReservationPayment(v);
        });
    }

    /**
     * Hotel Rooms only (see reservationAdvanceReservationId) — submits payment for the Pending
     * reservation submitReservationRequest() created, promoting it into a real booking on the
     * shared backend (visible in Booking Management). Always the fixed 30% down payment, matching
     * bindReservationPayment's own doc comment.
     */
    private void submitReservationPayment(View paymentView) {
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
        Map<String, RequestBody> quantities = new LinkedHashMap<>();
        putQuantityParts(quantities, "amenity_quantities", amenityQuantities);

        ApiClient.bookingApi().payReservation(
                "Bearer " + token,
                reservationAdvanceReservationId,
                textPart("partial"),
                textPart(referenceNumberText),
                proofPart,
                quantities
        ).enqueue(new Callback<ReserveResponse>() {
            @Override
            public void onResponse(Call<ReserveResponse> call, Response<ReserveResponse> response) {
                completeButton.setEnabled(true);
                if (response.isSuccessful() && response.body() != null) {
                    reservationPaymentSubmitted = true;
                    reservationPaymentSubmittedAtMillis = System.currentTimeMillis();
                    showScreen(SCREEN_RESERVATION_PAYMENT_CONFIRMED);
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

    private void bindReservationPaymentConfirmed(View v) {
        v.findViewById(R.id.reservationPaymentConfirmedReceiptButton).setOnClickListener(view ->
                showScreen(SCREEN_RESERVATION_DETAILS));
        v.findViewById(R.id.reservationPaymentConfirmedHomeButton).setOnClickListener(view -> {
            resetFlow();
            onBackToHome.run();
        });
    }

    /** Captures the four reservationXxx() helpers' current output into the reservationSnapshotXxx
     *  fields — called once, right when a reservation is actually submitted (see the Reserve
     *  button handlers in bindReservationSummary), while checkInMillis/roomQuantities/
     *  activeContinuation/etc. still hold the real values that were just submitted. */
    private void snapshotReservationDisplayData() {
        reservationSnapshotAccommodation = reservationAccommodationSummary();
        reservationSnapshotDateRange = reservationDateSummary();
        reservationSnapshotShowNights = !multiMode && activeContinuation == CONTINUATION_NONE;
        reservationSnapshotNights = nights();
        reservationSnapshotGuestSummary = reservationGuestSummary();
        reservationSnapshotGrandTotal = reservationGrandTotal();
    }

    /** Comma-joined "Category · Variant (x qty)" for every item with a quantity above zero in
     *  whichever list applies to the active category — mirrors bindReservationSummary's own
     *  per-category item loop, just condensed to one line for the Details screen. */
    private String reservationAccommodationSummary() {
        if (multiMode) {
            StringBuilder lines = new StringBuilder();
            for (int facility : multiQueue) {
                if (lines.length() > 0) {
                    lines.append("\n");
                }
                lines.append(facilityLabel(facility)).append(": ").append(multiFacilityItemsSummary(facility));
            }
            return lines.toString();
        }
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
                sb.append(formatShowcaseName(item.variant_name, null)).append(" (x").append(qty).append(")");
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
                sb.append(formatShowcaseName(item.variant_name, null)).append(" (x").append(qty).append(")");
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
        if (multiMode) {
            StringBuilder lines = new StringBuilder();
            for (int facility : multiQueue) {
                if (lines.length() > 0) {
                    lines.append("\n");
                }
                lines.append(facilityLabel(facility)).append(": ").append(multiFacilityWhen(facility));
            }
            return lines.toString();
        }
        if (activeContinuation == CONTINUATION_COTTAGE) {
            return formatDate(cottageDateMillis) + " · " + cottageRateTypeLabel();
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
        if (multiMode) {
            return multiGrandTotal();
        }
        if (activeContinuation == CONTINUATION_COTTAGE) {
            return computeCottageGrandTotal();
        }
        if (activeContinuation == CONTINUATION_KTV) {
            return computeKtvGrandTotal();
        }
        return computeGrandTotal();
    }

    private String reservationGuestSummary() {
        if (multiMode) {
            StringBuilder lines = new StringBuilder();
            for (int facility : multiQueue) {
                if (lines.length() > 0) {
                    lines.append("\n");
                }
                lines.append(facilityLabel(facility)).append(": ").append(multiFacilityGuests(facility));
            }
            return lines.toString();
        }
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
     * unitLabelRes is the "X {Room/Cottage/KTV Room} Available" house-icon pill's unit noun (see
     * R.string.format_available_units) — matches the receptionist web admin's own "Total
     * Available Units" chip; each caller passes its own category's label. onChanged is called
     * after every +/- tap so the caller can rebind and reflect the new count.
     */
    private boolean isKtvUnit(int groupId) {
        for (CottageKtvOption item : ktvItems) {
            if (item.unit_id > 0 && item.group_id == groupId) {
                return true;
            }
        }
        return false;
    }

    private View buildSelectableCard(LayoutInflater inflater, ViewGroup parent, String imagePath,
                                      String typeLabel, int capacity, boolean isCottage, String description, String priceText,
                                      int groupId, int availableQty, int unitLabelRes, boolean isSelectedBadge,
                                      boolean isUnavailable, int unavailableMessageRes, Runnable onChanged) {
        View card = inflater.inflate(R.layout.view_selectable_item_card, parent, false);
        loadImage(card.findViewById(R.id.itemImage), imagePath, R.drawable.ic_bed);
        ((TextView) card.findViewById(R.id.itemType)).setText(typeLabel);
        TextView itemCapacityView = card.findViewById(R.id.itemCapacity);
        itemCapacityView.setText(activity.getString(
                isCottage ? R.string.cottage_capacity_format : R.string.format_capacity, capacity));
        itemCapacityView.setTextColor(ThemeManager.color(activity,
                isCottage ? R.attr.warningText : R.attr.textMuted));
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
        View availableBadge = card.findViewById(R.id.itemAvailableBadgeContainer);
        View quantityRow = card.findViewById(R.id.itemQuantityRow);

        if (isUnavailable) {
            banner.setText(unavailableMessageRes);
            banner.setVisibility(View.VISIBLE);
            badge.setVisibility(View.GONE);
            availableBadge.setVisibility(View.GONE);
            quantityRow.setVisibility(View.GONE);
            card.setAlpha(0.5f);
            return card;
        }

        banner.setVisibility(View.GONE);
        badge.setVisibility(isSelectedBadge ? View.VISIBLE : View.GONE);
        availableBadge.setVisibility(View.VISIBLE);
        ((TextView) card.findViewById(R.id.itemAvailableBadge)).setText(activity.getString(
                R.string.format_available_units, availableQty, activity.getString(unitLabelRes)));
        quantityRow.setVisibility(View.VISIBLE);
        card.setAlpha(1f);

        // An individual KTV unit is one physical room: a plain "Available" tag and a SELECT
        // toggle instead of an "N available" count and a quantity stepper.
        if (unitLabelRes == R.string.unit_label_ktv_room && isKtvUnit(groupId)) {
            ((TextView) card.findViewById(R.id.itemAvailableBadge)).setText(R.string.status_available);
            quantityRow.setVisibility(View.GONE);
            Button selectButton = card.findViewById(R.id.itemSelectButton);
            selectButton.setVisibility(View.VISIBLE);
            boolean picked = roomQuantities.containsKey(groupId) && roomQuantities.get(groupId) > 0;
            selectButton.setText(picked ? R.string.button_selected : R.string.button_select);
            selectButton.setOnClickListener(view -> {
                roomQuantities.put(groupId, picked ? 0 : 1);
                onChanged.run();
            });
            return card;
        }

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
     * already loaded it first). cottageKtvLoaded is only ever set true on a genuine successful
     * response — a failed/errored attempt resets nextButton and shows a Toast instead (matching
     * fetchAvailabilityForRoomSelection's Hotel Rooms equivalent) so the guest can just tap Next
     * again, rather than silently caching an empty catalog for the rest of the session with no
     * way to retry (the bug that made "Available Cottages" render permanently blank after any
     * one transient failure).
     */
    private void fetchCottagesKtvData(Button nextButton, Runnable onLoaded) {
        // Always re-fetched on entering KTV selection (never served from cottageKtvLoaded) so a
        // KTV room the admin adds/edits mid-session shows up without restarting the flow.
        if (isLoadingCottageKtv) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            nextButton.setEnabled(true);
            nextButton.setText(R.string.button_next);
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }
        isLoadingCottageKtv = true;

        ApiClient.bookingApi().getCottagesKtv("Bearer " + token).enqueue(new Callback<CottageKtvListResponse>() {
            @Override
            public void onResponse(Call<CottageKtvListResponse> call, Response<CottageKtvListResponse> response) {
                isLoadingCottageKtv = false;
                if (!response.isSuccessful() || response.body() == null) {
                    nextButton.setEnabled(true);
                    nextButton.setText(R.string.button_next);
                    if (response.code() == 401) {
                        Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(activity, R.string.toast_availability_check_failed, Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                cottageKtvLoaded = true;
                cottageItems = new ArrayList<>();
                ktvItems = new ArrayList<>();
                if (response.body().cottages != null) {
                    cottageItems.addAll(response.body().cottages);
                }
                if (response.body().ktv_rooms != null) {
                    ktvItems.addAll(response.body().ktv_rooms);
                }
                for (CottageKtvOption item : cottageItems) {
                    if (!roomQuantities.containsKey(item.group_id)) {
                        roomQuantities.put(item.group_id, 0);
                    }
                }
                // KTV is shown as its actual units (booking/ktv-units, checked against the chosen
                // slot), not Types; the catalog's Type list stays only as a fallback if that call
                // fails or the route isn't live yet.
                SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                String eventDate = ktvDateMillis >= 0 ? day.format(new Date(ktvDateMillis)) : null;
                String startTime = ktvStartHour >= 0
                        ? String.format(Locale.US, "%02d:%02d", ktvStartHour, ktvStartMinute) : null;
                String rate = ktvRateType.isEmpty() ? null : ktvRateTypeLabel();
                ApiClient.bookingApi().getKtvUnits(eventDate, startTime, rate).enqueue(new Callback<KtvUnitListResponse>() {
                    @Override
                    public void onResponse(Call<KtvUnitListResponse> c, Response<KtvUnitListResponse> r) {
                        if (r.isSuccessful() && r.body() != null && r.body().ktv_units != null) {
                            ktvItems = new ArrayList<>(r.body().toOptions());
                        }
                        finishKtvLoad(onLoaded);
                    }

                    @Override
                    public void onFailure(Call<KtvUnitListResponse> c, Throwable t) {
                        finishKtvLoad(onLoaded);
                    }
                });
            }

            @Override
            public void onFailure(Call<CottageKtvListResponse> call, Throwable t) {
                isLoadingCottageKtv = false;
                nextButton.setEnabled(true);
                nextButton.setText(R.string.button_next);
                Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void finishKtvLoad(Runnable onLoaded) {
        for (CottageKtvOption item : ktvItems) {
            if (!roomQuantities.containsKey(item.group_id)) {
                roomQuantities.put(item.group_id, 0);
            }
        }
        onLoaded.run();
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

        v.findViewById(R.id.hotelAmenitySkipButton).setOnClickListener(view -> finishAmenityStep(view));
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
            finishAmenityStep(view);
        });
    }

    /**
     * Skip/Next on Choose Amenity. In a single-facility flow this is where the guest is asked
     * whether to also add a later-ordered facility (see {@link AddOnFacilityPrompt}): Yes turns
     * the run into a multi-facility one — the current facility becomes the queue's first, its
     * date and quantities carried over, and multiAdvance moves on to the first added facility —
     * while No (or nothing to offer) goes straight on to Billing/Summary as before.
     */
    private void finishAmenityStep(View sourceButton) {
        if (multiMode) {
            showScreen(billingScreenForContinuation());
            return;
        }
        int current = activeContinuation == CONTINUATION_COTTAGE ? FAC_COTTAGE
                : activeContinuation == CONTINUATION_KTV ? FAC_KTV : FAC_ROOM;
        AddOnFacilityPrompt.showFor(activity, reserveMode, current, extras -> {
            if (extras.isEmpty()) {
                showScreen(billingScreenForContinuation());
                return;
            }
            multiMode = true;
            multiQueue.clear();
            multiQueue.add(current);
            for (int facility : new int[]{FAC_COTTAGE, FAC_KTV}) {
                if (extras.contains(facility)) {
                    multiQueue.add(facility);
                }
            }
            multiIndex = 0;
            multiQuantities.clear();
            multiAnchorVariantId = selectionOriginVariantId;
            activeContinuation = CONTINUATION_NONE;
            multiAdvance((Button) sourceButton);
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
     * Amenity quantities are +/- only, not manually typed (per explicit instruction — every
     * other quantity stepper in the app does support typing, see AuthUiUtils#bindQuantityStepper,
     * but this one was carved out). stepperItemQtyValue is an EditText only because it's the
     * same view_hotel_stepper_item.xml layout the Reserve tab's own room card reuses (which does
     * support typing) — disabling focus here keeps it a plain read-only label.
     */
    private void disableManualQuantityEntry(EditText valueField) {
        valueField.setFocusable(false);
        valueField.setFocusableInTouchMode(false);
        valueField.setClickable(false);
        valueField.setLongClickable(false);
    }

    // ---- Screen 6: Billing Summary ------------------------------------------

    private void bindBilling(View v) {
        bindHeader(v, R.id.hotelBillingHeaderBar, R.string.hotel_billing_title, () -> showScreen(SCREEN_AMENITY));

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

        bindLine(v.findViewById(R.id.billingRowRoomCharges), R.string.label_room_charges, roomTotal);
        bindLine(v.findViewById(R.id.billingRowAmenitiesTotal), R.string.label_amenities_total, amenitiesTotal);

        int grandTotal = roomTotal + amenitiesTotal;
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

        ApiClient.bookingApi().reserve(
                "Bearer " + token,
                textPart("0"),
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

    /** Same bracket-notation keys as {@link #putQuantityParts}, for a @FormUrlEncoded @FieldMap
     *  call (submitReservationRequest) instead of a multipart one. */
    private static Map<String, String> quantityFieldMap(String fieldName, Map<Integer, Integer> quantities) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : quantities.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                result.put(fieldName + "[" + entry.getKey() + "]", String.valueOf(entry.getValue()));
            }
        }
        return result;
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

    void resetFlow() {
        reservationDetailsExternalBack = null;
        checkInMillis = -1;
        checkOutMillis = -1;
        adults = 0;
        children = 0;
        selectionOriginVariantId = -1;
        selectionShowOthers = false;
        onBackToOverview = null;
        activeContinuation = CONTINUATION_NONE;
        reserveMode = false;
        multiMode = false;
        multiQueue.clear();
        multiIndex = 0;
        multiQuantities.clear();
        cottageRateType = ReservationCatalog.RATE_TYPE_DAY;
        cottageDateMillis = -1;
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
        paymentOption = PAYMENT_NONE;
        termsChecked = false;
        cancellationChecked = false;
        amountPaidText = "";
        referenceNumberText = "";
        screenshotUri = null;
        // Deliberately NOT reset: bookingReference, reservationReferenceNo,
        // reservationAdvanceReservationId, reservationStatus, reservationPaymentSubmitted,
        // reservationSubmittedAtMillis, reservationPaymentSubmittedAtMillis,
        // reservationBookedAtMillis, reservationCancelledAtMillis, reservationCancelReason,
        // reservationCancelledBy, and the reservationSnapshotXxx fields — these describe a
        // reservation/booking that's already been
        // submitted (Hotel Rooms: really persisted server-side) and continues to exist regardless
        // of the guest tapping "Return Home" to start a fresh attempt. Clearing them here was the
        // cause of My Reservations/My Bookings wrongly reporting "you don't have one yet" right
        // after a guest went home from the very confirmation screen for the one they just made. A
        // genuinely new submission overwrites all of these fresh anyway (see
        // snapshotReservationDisplayData and its two call sites), so there's nothing to leak
        // between one reservation and the next.
        // Every caller pairs resetFlow() with onBackToHome.run() right after (which re-arms the
        // owning controller's own picker screen) — nothing here needs to render, so root is just
        // left empty rather than showing a screen no one will ever look at.
        currentScreen = -1;
        currentScreenView = null;
        root.removeAllViews();
    }

    // ---- Multi-facility flow ----------------------------------------------------------------

    private void multiResetProgress() {
        multiQuantities.clear();
        multiIndex = 0;
        selectionOriginVariantId = multiAnchorVariantId;
    }

    private void saveMultiQuantities() {
        multiQuantities.put(multiQueue.get(multiIndex), new LinkedHashMap<>(roomQuantities));
    }

    /** Re-applies a facility's previously confirmed quantities onto the freshly (re)built
     *  roomQuantities of its selection screen — only for items still present in that list. */
    private void restoreMultiQuantities(int facility) {
        if (!multiMode) {
            return;
        }
        Map<Integer, Integer> saved = multiQuantities.get(facility);
        if (saved == null) {
            return;
        }
        for (Map.Entry<Integer, Integer> entry : saved.entrySet()) {
            if (roomQuantities.containsKey(entry.getKey())) {
                roomQuantities.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Shows an already-visited facility's selection screen again, exactly as the guest left it. */
    private void multiShowSelection(int facility) {
        roomQuantities.clear();
        Map<Integer, Integer> saved = multiQuantities.get(facility);
        if (saved != null) {
            roomQuantities.putAll(saved);
        }
        showScreen(selectionScreenForFacility(facility));
    }

    private void multiStepBack() {
        saveMultiQuantities();
        multiIndex--;
        selectionOriginVariantId = multiIndex == 0 ? multiAnchorVariantId : -1;
        multiShowSelection(multiQueue.get(multiIndex));
    }

    /** The one date every later facility reuses — the first facility's own date (a Hotel Room
     *  stay's check-in day, or the Cottage/KTV visit date). */
    private long multiAnchorDateMillis() {
        switch (multiQueue.get(0)) {
            case FAC_ROOM:
                return checkInMillis;
            case FAC_COTTAGE:
                return cottageDateMillis;
            default:
                return ktvDateMillis;
        }
    }

    private int multiAnchorGuestCount() {
        return multiQueue.get(0) == FAC_COTTAGE ? cottageAdults + cottageChildren : adults + children;
    }

    /**
     * Next on a facility's selection screen in the multi-facility flow: remembers its quantities,
     * then moves on to the next facility with the already-entered date and guest count carried
     * over (Cottage goes straight to its availability-filtered selection; KTV shows its session
     * details screen, since start time and rate type still need answering) — or, after the last
     * facility, to the combined Review.
     */
    private void multiAdvance(Button nextButton) {
        saveMultiQuantities();
        if (multiIndex + 1 >= multiQueue.size()) {
            showScreen(SCREEN_MULTI_REVIEW);
            return;
        }
        int next = multiQueue.get(multiIndex + 1);
        if (next == FAC_COTTAGE) {
            cottageDateMillis = multiAnchorDateMillis();
            cottageAdults = adults > 0 ? adults : 1;
            cottageChildren = children;
            fetchCottageAvailability(nextButton, () -> {
                multiIndex++;
                selectionOriginVariantId = -1;
                roomQuantities.clear();
            });
            return;
        }
        multiIndex++;
        selectionOriginVariantId = -1;
        roomQuantities.clear();
        ktvDateMillis = multiAnchorDateMillis();
        ktvGuestCount = Math.max(1, multiAnchorGuestCount());
        showScreen(SCREEN_KTV_DATES);
    }

    /** Cottage Use chooser on the multi-facility Cottage step (see view_item_selection.xml).
     *  Changing it re-runs the live availability check for the carried-over date, so the list and
     *  every price shown always match the chosen use. */
    private void bindMultiCottageUseRow(View v) {
        v.findViewById(R.id.itemSelectionUseRow).setVisibility(View.VISIBLE);
        Spinner useSpinner = v.findViewById(R.id.itemSelectionUseSpinner);
        ArrayAdapter<CharSequence> useAdapter = ArrayAdapter.createFromResource(
                activity, R.array.cottage_use_options, android.R.layout.simple_spinner_item);
        useAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        useSpinner.setAdapter(useAdapter);
        for (int i = 0; i < COTTAGE_USE_OPTIONS.length; i++) {
            if (COTTAGE_USE_OPTIONS[i].equals(cottageRateType)) {
                useSpinner.setSelection(i);
                break;
            }
        }
        useSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View item, int position, long id) {
                String use = COTTAGE_USE_OPTIONS[position];
                if (use.equals(cottageRateType)) {
                    return;
                }
                cottageRateType = use;
                multiQuantities.remove(FAC_COTTAGE);
                fetchCottageAvailability(v.findViewById(R.id.itemSelectionNextButton), roomQuantities::clear);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // A Spinner backed by a non-empty adapter always has a selection once shown.
            }
        });
    }

    private int multiQty(int facility, int groupId) {
        Map<Integer, Integer> quantities = multiQuantities.get(facility);
        Integer qty = quantities == null ? null : quantities.get(groupId);
        return qty == null ? 0 : qty;
    }

    private String facilityLabel(int facility) {
        return activity.getString(facility == FAC_COTTAGE ? R.string.booking_type_cottage
                : facility == FAC_KTV ? R.string.service_type_ktv : R.string.service_type_hotel);
    }

    private String multiFacilityWhen(int facility) {
        if (facility == FAC_COTTAGE) {
            return formatDate(cottageDateMillis) + " · " + cottageRateTypeLabel();
        }
        if (facility == FAC_KTV) {
            return formatDate(ktvDateMillis) + " · " + formatHourMinute(ktvStartHour, ktvStartMinute)
                    + "–" + formatKtvEndTime();
        }
        return formatDate(checkInMillis) + " – " + formatDate(checkOutMillis);
    }

    private String multiFacilityGuests(int facility) {
        return facility == FAC_COTTAGE ? formatCottageGuests()
                : facility == FAC_KTV ? formatKtvGuests() : formatGuests();
    }

    private String multiFacilityHeader(int facility) {
        if (facility == FAC_COTTAGE) {
            return activity.getString(R.string.multi_facility_header_cottage,
                    formatDate(cottageDateMillis), cottageRateTypeLabel(), formatCottageGuests());
        }
        if (facility == FAC_KTV) {
            return activity.getString(R.string.multi_facility_header_ktv, formatDate(ktvDateMillis),
                    formatHourMinute(ktvStartHour, ktvStartMinute), formatKtvEndTime(), ktvRateTypeLabel(),
                    formatKtvGuests());
        }
        return activity.getString(R.string.multi_facility_header_room, formatDate(checkInMillis),
                formatDate(checkOutMillis), formatNights(nights()), formatGuests());
    }

    /** Comma-joined "Name (xN)" of everything the guest picked for one facility. */
    private String multiFacilityItemsSummary(int facility) {
        StringBuilder sb = new StringBuilder();
        if (facility == FAC_ROOM) {
            for (RoomTypeAvailability room : availableRoomTypes) {
                int qty = multiQty(FAC_ROOM, room.group_id);
                if (qty > 0) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(formatShowcaseName(room.variant_name, room.category_name)).append(" (x").append(qty).append(")");
                }
            }
        } else {
            for (CottageKtvOption item : facility == FAC_COTTAGE ? cottageItems : ktvItems) {
                int qty = multiQty(facility, item.group_id);
                if (qty > 0) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(formatShowcaseName(item.variant_name, null)).append(" (x").append(qty).append(")");
                }
            }
        }
        return sb.toString();
    }

    /** What one facility's selection costs — each facility keeps its own pricing rules (Hotel
     *  Rooms per night, Cottages per chosen Cottage Use, KTV per its listed rate). */
    private int multiFacilityTotal(int facility) {
        int total = 0;
        if (facility == FAC_ROOM) {
            int nights = nights();
            for (RoomTypeAvailability room : availableRoomTypes) {
                total += (int) Math.round(room.price_per_night) * multiQty(FAC_ROOM, room.group_id) * nights;
            }
        } else {
            for (CottageKtvOption item : facility == FAC_COTTAGE ? cottageItems : ktvItems) {
                total += (int) Math.round(resolvedItemPrice(item)) * multiQty(facility, item.group_id);
            }
        }
        return total;
    }

    /** Amenities belong to the Hotel Room booking, so they only count when Hotel Rooms are in. */
    private int multiAmenityTotal() {
        if (!multiQueue.contains(FAC_ROOM)) {
            return 0;
        }
        int total = 0;
        for (AmenityAvailability amenity : availableAmenities) {
            Integer qty = amenityQuantities.get(amenity.id);
            total += (int) Math.round(amenity.price) * (qty == null ? 0 : qty);
        }
        return total;
    }

    private int multiFacilitiesTotal() {
        int total = 0;
        for (int facility : multiQueue) {
            total += multiFacilityTotal(facility);
        }
        return total;
    }

    private int multiGrandTotal() {
        return multiFacilitiesTotal() + multiAmenityTotal();
    }

    private void addLineRow(LinearLayout container, LayoutInflater inflater, String name, String meta, int price) {
        View row = inflater.inflate(R.layout.view_hotel_billing_line_row, container, false);
        ((TextView) row.findViewById(R.id.lineName)).setText(name);
        TextView metaView = row.findViewById(R.id.lineMeta);
        metaView.setText(meta);
        metaView.setVisibility(View.VISIBLE);
        ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(price));
        container.addView(row);
    }

    /** One facility's section (its header line, then a line per selected item) on the combined
     *  Review/Billing/Reservation Summary screens. */
    private void appendFacilityLines(LinearLayout container, LayoutInflater inflater, int facility) {
        TextView header = (TextView) inflater.inflate(R.layout.view_multi_facility_header, container, false);
        header.setText(multiFacilityHeader(facility));
        container.addView(header);
        if (facility == FAC_ROOM) {
            int nights = nights();
            for (RoomTypeAvailability room : availableRoomTypes) {
                int qty = multiQty(FAC_ROOM, room.group_id);
                if (qty <= 0) {
                    continue;
                }
                int pricePerNight = (int) Math.round(room.price_per_night);
                addLineRow(container, inflater, room.category_name + " · " + room.variant_name,
                        activity.getString(R.string.format_capacity, room.capacity) + " · Qty " + qty + " · "
                                + activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight))
                                + " × " + nights,
                        pricePerNight * qty * nights);
            }
            return;
        }
        for (CottageKtvOption item : facility == FAC_COTTAGE ? cottageItems : ktvItems) {
            int qty = multiQty(facility, item.group_id);
            if (qty <= 0) {
                continue;
            }
            int unitPrice = (int) Math.round(resolvedItemPrice(item));
            addLineRow(container, inflater, formatShowcaseName(item.variant_name, null),
                    activity.getString(facility == FAC_COTTAGE ? R.string.cottage_capacity_format
                            : R.string.format_capacity, item.capacity) + " · Qty " + qty + " · "
                            + activity.getString(R.string.format_price_with_unit, formatMoney(unitPrice),
                            resolvedItemPriceUnit(item)),
                    unitPrice * qty);
        }
    }

    /** Amenity lines shared by the combined Billing and Reservation Summary screens. */
    private boolean appendAmenityLines(LinearLayout container, LayoutInflater inflater) {
        boolean any = false;
        for (AmenityAvailability amenity : availableAmenities) {
            Integer qty = amenityQuantities.get(amenity.id);
            if (qty == null || qty <= 0) {
                continue;
            }
            any = true;
            int unitPrice = (int) Math.round(amenity.price);
            addLineRow(container, inflater, amenity.name, "Qty " + qty + " · " + formatCurrency(unitPrice),
                    unitPrice * qty);
        }
        return any;
    }

    /** Combined Review Your Selection for every facility picked, plus one shared special request. */
    private void bindMultiReview(View v) {
        bindHeader(v, R.id.multiReviewHeaderBar, R.string.hotel_review_title, this::handleBackPressed);

        LinearLayout container = v.findViewById(R.id.multiReviewContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (int facility : multiQueue) {
            appendFacilityLines(container, inflater, facility);
        }

        EditText specialRequestField = v.findViewById(R.id.multiReviewSpecialRequestField);
        specialRequestField.setText(specialRequest);
        AuthUiUtils.afterTextChanged(specialRequestField,
                () -> specialRequest = specialRequestField.getText().toString());

        v.findViewById(R.id.hotelReviewNextButton).setOnClickListener(view -> {
            if (multiQueue.contains(FAC_ROOM)) {
                fetchAmenities(v);
            } else {
                showScreen(billingScreenForContinuation());
            }
        });
    }

    /** Combined Book-flow Billing Summary: one Accommodation card holding every facility's own
     *  section, one set of totals and one payment option for the whole transaction. */
    private void bindMultiBilling(View v) {
        bindHeader(v, R.id.hotelBillingHeaderBar, R.string.hotel_billing_title, this::handleBackPressed);

        bindRow(v.findViewById(R.id.billingRowFullName), R.string.label_full_name, ProfileStore.getFullName(activity));
        bindRow(v.findViewById(R.id.billingRowEmail), R.string.label_email_address, ProfileStore.getEmail(activity));
        bindRow(v.findViewById(R.id.billingRowMobile), R.string.label_mobile_number, ProfileStore.getContactNumber(activity));
        bindRow(v.findViewById(R.id.billingRowAddress), R.string.label_address, ProfileStore.getAddress(activity));

        // Each facility's own dates/guests are shown in its section header below, so the single-
        // facility Stay Details card doesn't apply here.
        ((View) v.findViewById(R.id.billingRowCheckIn).getParent()).setVisibility(View.GONE);

        ((TextView) v.findViewById(R.id.billingAccommodationLabel)).setText(R.string.section_accommodation);
        LinearLayout accommodationContainer = v.findViewById(R.id.billingAccommodationContainer);
        accommodationContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (int facility : multiQueue) {
            appendFacilityLines(accommodationContainer, inflater, facility);
        }

        LinearLayout amenitiesContainer = v.findViewById(R.id.billingAmenitiesContainer);
        amenitiesContainer.removeAllViews();
        boolean anyAmenity = multiQueue.contains(FAC_ROOM) && appendAmenityLines(amenitiesContainer, inflater);
        v.findViewById(R.id.billingAmenitiesCard).setVisibility(anyAmenity ? View.VISIBLE : View.GONE);

        bindLine(v.findViewById(R.id.billingRowRoomCharges), R.string.label_facility_charges, multiFacilitiesTotal());
        bindLine(v.findViewById(R.id.billingRowAmenitiesTotal), R.string.label_amenities_total, multiAmenityTotal());

        int grandTotal = multiGrandTotal();
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
            showScreen(SCREEN_MULTI_PAYMENT);
        });
    }

    /**
     * Combined Book-flow Payment screen. Field validation matches every other Payment screen;
     * Complete Booking submits the whole selection in one request (see submitMultiBooking).
     */
    private void bindMultiPayment(View v) {
        bindHeader(v, R.id.hotelPaymentHeaderBar, R.string.hotel_payment_title, this::handleBackPressed);

        int grandTotal = multiGrandTotal();
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
            submitMultiBooking(v);
        });
    }

    /** Combined Reserve-flow Summary: every facility in one list, one total, one RESERVE button. */
    private void bindMultiSummary(View v) {
        bindHeader(v, R.id.reservationSummaryHeaderBar, R.string.hotel_billing_title, this::handleBackPressed);

        bindRow(v.findViewById(R.id.reservationSummaryRowFullName), R.string.label_full_name, ProfileStore.getFullName(activity));
        bindRow(v.findViewById(R.id.reservationSummaryRowEmail), R.string.label_email_address, ProfileStore.getEmail(activity));
        bindRow(v.findViewById(R.id.reservationSummaryRowMobile), R.string.label_mobile_number, ProfileStore.getContactNumber(activity));
        bindRow(v.findViewById(R.id.reservationSummaryRowAddress), R.string.label_address, ProfileStore.getAddress(activity));

        // Each facility's own dates/guests are shown in its section header below.
        ((View) v.findViewById(R.id.reservationSummaryDetailsTitle).getParent()).setVisibility(View.GONE);

        ((TextView) v.findViewById(R.id.reservationSummarySelectedTitle)).setText(R.string.section_accommodation);
        LinearLayout itemsContainer = v.findViewById(R.id.reservationSummaryItemsContainer);
        itemsContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (int facility : multiQueue) {
            appendFacilityLines(itemsContainer, inflater, facility);
        }

        LinearLayout amenitiesContainer = v.findViewById(R.id.reservationSummaryAmenitiesContainer);
        amenitiesContainer.removeAllViews();
        boolean anyAmenity = multiQueue.contains(FAC_ROOM) && appendAmenityLines(amenitiesContainer, inflater);
        v.findViewById(R.id.reservationSummaryAmenitiesCard).setVisibility(anyAmenity ? View.VISIBLE : View.GONE);

        int grandTotal = multiGrandTotal();
        bindLine(v.findViewById(R.id.reservationSummaryFullPaymentRow), R.string.label_full_payment, grandTotal);
        bindLine(v.findViewById(R.id.reservationSummaryDownPaymentRow), R.string.label_down_payment_30,
                computePartialAmount(grandTotal));

        CheckBox termsBox = v.findViewById(R.id.checkboxTerms);
        CheckBox cancellationBox = v.findViewById(R.id.checkboxCancellation);
        termsBox.setChecked(termsChecked);
        cancellationBox.setChecked(cancellationChecked);
        termsBox.setOnCheckedChangeListener((button, checked) -> termsChecked = checked);
        cancellationBox.setOnCheckedChangeListener((button, checked) -> cancellationChecked = checked);

        v.findViewById(R.id.reservationSummaryReserveButton).setOnClickListener(view -> submitMultiReservation(v));
    }

    /** Adds one facility's block to a multi-facility request, as bracket-notation keys
     *  ("hotel[check_in]", "cottage[cottage_quantities][7]", ...). */
    private Map<String, String> multiFacilityFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        fields.put("multiple_facilities", "1");
        fields.put("special_request", specialRequest);
        for (int facility : multiQueue) {
            if (facility == FAC_ROOM) {
                fields.put("hotel[check_in]", day.format(new Date(checkInMillis)));
                fields.put("hotel[check_out]", day.format(new Date(checkOutMillis)));
                fields.put("hotel[adults]", String.valueOf(adults));
                fields.put("hotel[children]", String.valueOf(children));
                putNestedQuantities(fields, "hotel[room_quantities]", multiQuantities.get(FAC_ROOM));
            } else if (facility == FAC_COTTAGE) {
                fields.put("cottage[date]", day.format(new Date(cottageDateMillis)));
                fields.put("cottage[use]", cottageUseKey());
                fields.put("cottage[adults]", String.valueOf(cottageAdults));
                fields.put("cottage[children]", String.valueOf(cottageChildren));
                putNestedQuantities(fields, "cottage[cottage_quantities]", multiQuantities.get(FAC_COTTAGE));
            } else {
                fields.put("ktv[event_date]", day.format(new Date(ktvDateMillis)));
                fields.put("ktv[start_time]", String.format(Locale.US, "%02d:%02d", ktvStartHour, ktvStartMinute));
                fields.put("ktv[guest_count]", String.valueOf(ktvGuestCount));
                fields.put("ktv[rate_label]", ktvRateTypeLabel());
                putKtvSelection(fields, multiQuantities.get(FAC_KTV));
            }
        }
        return fields;
    }

    /** Selected KTV units go as "ktv[unit_ids][n]"; if the list fell back to Types (no unit ids),
     *  the older per-Type quantities are sent instead. */
    private void putKtvSelection(Map<String, String> fields, Map<Integer, Integer> quantities) {
        if (quantities == null) {
            return;
        }
        int n = 0;
        boolean unitMode = false;
        for (CottageKtvOption item : ktvItems) {
            if (item.unit_id > 0) {
                unitMode = true;
                Integer qty = quantities.get(item.group_id);
                if (qty != null && qty > 0) {
                    fields.put("ktv[unit_ids][" + n++ + "]", String.valueOf(item.unit_id));
                }
            }
        }
        if (!unitMode) {
            putNestedQuantities(fields, "ktv[ktv_quantities]", quantities);
        }
    }

    private static void putNestedQuantities(Map<String, String> target, String prefix, Map<Integer, Integer> quantities) {
        if (quantities == null) {
            return;
        }
        for (Map.Entry<Integer, Integer> entry : quantities.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                target.put(prefix + "[" + entry.getKey() + "]", String.valueOf(entry.getValue()));
            }
        }
    }

    /**
     * RESERVE on the multi-facility Summary: one POST booking/reservations with multiple_facilities
     * set and a block per selected facility; the server creates a single Pending reservation (one
     * reference) for all of them, with no payment — same lifecycle as the single-facility Reserve.
     * A 422 is shown to the guest as the server's own message.
     */
    private void submitMultiReservation(View summaryView) {
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }
        Button reserveButton = summaryView.findViewById(R.id.reservationSummaryReserveButton);
        reserveButton.setEnabled(false);

        ApiClient.bookingApi().createMultiReservation("Bearer " + token, multiFacilityFields())
                .enqueue(new Callback<ReservationRequestResponse>() {
                    @Override
                    public void onResponse(Call<ReservationRequestResponse> call, Response<ReservationRequestResponse> response) {
                        reserveButton.setEnabled(true);
                        if (response.isSuccessful() && response.body() != null) {
                            reservationAdvanceReservationId = response.body().advance_reservation_id;
                            reservationReferenceNo = response.body().booking_reference;
                            finishMultiReservation();
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
                    public void onFailure(Call<ReservationRequestResponse> call, Throwable t) {
                        reserveButton.setEnabled(true);
                        Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void finishMultiReservation() {
        reservationStatus = RES_STATUS_PENDING;
        ProfileStore.setActiveReservationId(activity, reservationAdvanceReservationId);
        ProfileStore.setActiveReservationStatus(activity, "pending");
        reservationPaymentSubmitted = false;
        reservationSubmittedAtMillis = System.currentTimeMillis();
        reservationPaymentSubmittedAtMillis = -1;
        reservationBookedAtMillis = -1;
        reservationCancelledAtMillis = -1;
        reservationCancelReason = "";
        activeContinuation = CONTINUATION_NONE;
        snapshotReservationDisplayData();
        multiMode = false;
        showScreen(SCREEN_RESERVATION_CONFIRMED);
    }

    /**
     * Complete Booking on the multi-facility Payment screen: one multipart POST booking/reserve
     * with multiple_facilities set, a block per selected facility, and the usual payment fields.
     * Amenities (Hotel Rooms only) go in the same request as amenity_quantities.
     */
    private void submitMultiBooking(View paymentView) {
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

        Map<String, RequestBody> fields = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : multiFacilityFields().entrySet()) {
            if (!entry.getKey().equals("multiple_facilities")) {
                fields.put(entry.getKey(), textPart(entry.getValue()));
            }
        }
        if (multiQueue.contains(FAC_ROOM)) {
            putQuantityParts(fields, "amenity_quantities", amenityQuantities);
        }

        MultipartBody.Part proofPart = MultipartBody.Part.createFormData("proof_image", "payment_proof.jpg", proofBody);
        ApiClient.bookingApi().reserveMulti("Bearer " + token, textPart("1"),
                textPart(paymentOption == PAYMENT_PARTIAL ? "partial" : "full"),
                textPart(referenceNumberText), proofPart, fields).enqueue(new Callback<ReserveResponse>() {
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
        return formatGuestsCount(adults, children);
    }

    private static String formatGuestsCount(int adults, int children) {
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
        return total;
    }

    /** Loads a room/amenity thumbnail from the backend, falling back to a placeholder icon. */
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
