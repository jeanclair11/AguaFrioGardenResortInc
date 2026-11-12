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
import com.aguafriogarden.resortinc.network.PackageCheckResponse;
import com.aguafriogarden.resortinc.network.PackageInclusion;
import com.aguafriogarden.resortinc.network.PackagePromo;
import com.aguafriogarden.resortinc.network.PriceBreakdownEntry;
import com.aguafriogarden.resortinc.network.PromoCoupon;
import com.aguafriogarden.resortinc.network.PromoCouponListResponse;
import com.aguafriogarden.resortinc.network.ReservationRequestResponse;
import com.aguafriogarden.resortinc.network.ReservationStatusResponse;
import com.aguafriogarden.resortinc.network.ReservationSummary;
import com.aguafriogarden.resortinc.network.ReserveResponse;
import com.aguafriogarden.resortinc.network.StatusDetail;
import com.aguafriogarden.resortinc.network.RoomListResponse;
import com.aguafriogarden.resortinc.network.RoomSummary;
import com.aguafriogarden.resortinc.network.RoomTypeAvailability;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

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
 * from the shared backend (see {@link #fetchAvailability}, {@link #fetchAmenities}) with real photos loaded via {@link ImageLoader} (skeleton while loading). One instance is
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
    // Hotel Rooms, Cottage and KTV share one shape: the dates (or schedule) screen lists what is free
    // for it itself (see bindRoomDates / bindCottageDatesEntry / bindKtvDatesEntry), then a Number of
    // Guests screen (SCREEN_xxx_GUESTS, see bindGuests) comes before Review Your Selection.
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
    // Multi-facility flow (see startMultiFlow): one combined Review/Billing/Payment (Book) or
    // Reservation Summary (Reserve) for every facility the guest picked, reached after the last
    // facility's own selection screen (and Choose Amenity, when Hotel Rooms are included).
    private static final int SCREEN_MULTI_REVIEW = 32;
    private static final int SCREEN_MULTI_BILLING = 33;
    private static final int SCREEN_MULTI_PAYMENT = 34;
    private static final int SCREEN_MULTI_SUMMARY = 35;
    // Package Promo booking (see showPackageDatesEntry): date + guests, then the shared Choose
    // Amenity for extras, then a Package Summary (the Billing layout) and the shared Payment screen.
    private static final int SCREEN_PACKAGE_DATES = 36;
    private static final int SCREEN_PACKAGE_BILLING = 37;
    private static final int SCREEN_PACKAGE_PAYMENT = 38;
    // Hotel Rooms: Number of Guests, between Choose Your Stay Dates (dates + rooms) and Review Your
    // Selection. The party never limits the booking — see RoomSelectionRules.
    private static final int SCREEN_ROOM_GUESTS = 39;
    private static final int SCREEN_COTTAGE_GUESTS = 40;
    private static final int SCREEN_KTV_GUESTS = 41;

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

    // Reservation Details' booking-progress row states — see buildReservationTimeline/
    // bindReservationTimeline. The statuses themselves are never defined here: they come from
    // the backend (StatusCatalog / the record's StatusDetail).
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

    // ---- Package Promo booking state (see showPackageDatesEntry) ----------------------------
    // The package being booked (null outside a package booking) and the guest's date and party.
    private PackagePromo activePackage = null;
    private long packageDateMillis = -1;
    private int packageAdults = 1;
    private int packageChildren = 0;
    private boolean isCheckingPackage = false;
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
    private long ktvDateMillis = -1;
    private int ktvStartHour = -1;
    private int ktvStartMinute = -1;
    private int ktvGuestCount = 1;
    // The party as entered on Number of Guests; KTV itself is booked on ktvGuestCount (their sum).
    private int ktvAdults = 1;
    private int ktvChildren = 0;

    private List<CottageKtvOption> cottageItems = new ArrayList<>();
    private List<CottageKtvOption> ktvItems = new ArrayList<>();
    /** The KTV session length (Book and Reserve). The guest never picks it: it is taken from the
     *  session length HR configured in KTV Room Management (the price packages' hours — see
     *  #loadKtvSessionHours) and feeds availability/pricing lookups, the end-time calc, the
     *  Review/Billing rows and the submitted ktv[duration_hours]. 0 until loaded. */
    private int ktvDurationHours = 0;
    private List<RoomTypeAvailability> availableRoomTypes = new ArrayList<>();
    private final Map<Integer, Integer> roomQuantities = new LinkedHashMap<>();
    // Choose Your Stay Dates (see bindRoomDates): availableRoomTypes is what the server said was free
    // for exactly these dates ("checkIn|checkOut", null = nothing fetched yet or the dates changed).
    private String roomAvailabilityKey = null;
    private boolean roomAvailabilityLoading = false;
    private boolean roomAvailabilityFailed = false;
    private Call<AvailabilityResponse> roomAvailabilityCall = null;
    // The room the guest came in on is set to quantity 1 once (when it is free for the dates and they
    // haven't picked anything themselves); after that the guest's own picks are never overridden.
    private boolean originAutoSelected = false;
    private String specialRequest = "";
    private List<AmenityAvailability> availableAmenities = new ArrayList<>();
    private final Map<Integer, Integer> amenityQuantities = new LinkedHashMap<>();
    private boolean isLoadingAmenities = false;
    /** Set by the code that has just fetched the amenity list itself, so showScreen does not
     *  fetch it a second time; every other way of opening Choose Amenity (e.g. Back from Billing)
     *  re-fetches, because stock is never cached. */
    private boolean amenitiesFreshForNextShow = false;
    // Promo Coupons (see bindPromoCoupons): the one coupon the guest applied on the Summary page
    // (null = none), the coupons the server says this exact booking qualifies for, and the request
    // that list was fetched for, so it is only re-fetched when the booking itself changes. A coupon
    // that was applied for a different booking is held back as couponPendingId until the server has
    // confirmed it still applies, so stale discounts never show. Reset by resetFlow().
    private PromoCoupon appliedCoupon;
    private int couponPendingId = 0;
    private List<PromoCoupon> eligibleCoupons = new ArrayList<>();
    private String couponQueryKey = null;
    private boolean couponsLoading = false;
    /** How often the on-screen selection/amenity/reservation-status screens re-check the server
     *  for changes made elsewhere (see {@link #liveSyncTick}). */
    private static final long LIVE_SYNC_MS = 8000L;
    private LiveSync liveSync;
    private boolean liveSyncInFlight = false;
    private int paymentOption = PAYMENT_NONE;
    // The Reserve flow's own Full Payment / 30% Downpayment choice on its Pay Now screen. Kept
    // apart from paymentOption (the Book flow's) so a choice left over from an abandoned booking
    // can never pre-select a payment amount on a reservation; reset on every Pay Now tap.
    private int reservationPaymentOption = PAYMENT_NONE;
    private boolean termsChecked;
    private boolean cancellationChecked;
    private String amountPaidText = "";
    /** Recomputes the live payment summary of the Payment screen on show (null on screens without one). */
    private Runnable paymentSummaryRefresh;
    private String referenceNumberText = "";
    private Uri screenshotUri;
    private String bookingReference = "";
    private String reservationReferenceNo = "";
    // Server id of the Pending AdvanceReservation created by submitReservationRequest() — targets
    // the payReservation() call. Set for every category (Hotel, Cottage, KTV and multi-facility
    // Reserve); stays -1 only for a reservation opened from history that is already paid.
    private int reservationAdvanceReservationId = -1;
    // What Reservation Details shows is one record of the backend's, and its status is exactly
    // what the backend says (label, colour, definition): set from the create/pay/cancel
    // response's status_detail, from the record opened out of history, and from every feed
    // update after that. Nothing here derives a status from dates, flags or steps. Null until the
    // backend has reported one.
    private StatusDetail reservationStatus = null;
    // The record's key in the live feed (ReservationFeed): its row kind and the id in that kind's
    // id space. For a request that has since been paid, the feed row is found by reference instead
    // (reservationReferenceNo becomes the booking's reference).
    private String reservationFeedKind = ReservationSummary.KIND_REQUEST;
    private int reservationRecordId = -1;
    private String reservationCancelReason = "";
    private boolean reservationStatusInFlight = false;
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
    // Reservation Details' back target, set by whichever entry point navigates in ("View
    // Reservation" right after reserving — see bindReservationConfirmed; the Profile history
    // goes through reservationDetailsExternalBack instead).
    private int reservationDetailsBackTarget = SCREEN_ROOM_DATES;
    /** Set when Reservation Details was opened from the Profile page's history (see
     *  openReservationFromHistory); Back then returns there instead of to a flow screen. */
    private Runnable reservationDetailsExternalBack = null;
    /** Success screen's View Booking: receives the new booking's reference and should open it in
     *  the guest's history (set by BookingCatalogController; null = button does nothing). */
    private Consumer<String> onViewBooking = null;

    void setOnViewBooking(Consumer<String> onViewBooking) {
        this.onViewBooking = onViewBooking;
    }

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
        activePackage = null;
        if (adults == 0 && children == 0) {
            adults = 1;
        }
        selectionOriginVariantId = variantId;
        resetRoomSelection();
        this.onBackToOverview = onBackToOverview;
        this.reserveMode = reserveOnly;
        showScreen(SCREEN_ROOM_DATES);
    }

    /** A fresh Hotel Rooms step: no rooms picked and no availability fetched yet. The dates and party
     *  the guest already entered are kept. */
    private void resetRoomSelection() {
        if (roomAvailabilityCall != null) {
            roomAvailabilityCall.cancel();
            roomAvailabilityCall = null;
        }
        availableRoomTypes = new ArrayList<>();
        roomQuantities.clear();
        roomAvailabilityKey = null;
        roomAvailabilityLoading = false;
        roomAvailabilityFailed = false;
        originAutoSelected = false;
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
        activePackage = null;
        selectionOriginVariantId = variantId;
        resetFacilitySelection();
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
        activePackage = null;
        selectionOriginVariantId = variantId;
        resetFacilitySelection();
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
        activePackage = null;
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
        resetRoomSelection();
        resetFacilitySelection();
        activeContinuation = CONTINUATION_NONE;
        this.onBackToOverview = onBackToOverview;
        this.reserveMode = reserveOnly;
        if (multiQueue.get(0) == FAC_ROOM && adults == 0 && children == 0) {
            adults = 1;
        }
        showScreen(datesScreenForFacility(multiQueue.get(0)));
    }

    /**
     * Entry point for Package Details' "Avail Package": the Book flow for one Package Promo. The
     * guest picks the date and party, the server confirms the package is still active, valid that
     * day and fully available, extras not in the package can be added on Choose Amenity, and the
     * Package Summary shows the fixed package price (not each included item) before the usual Full
     * Payment / 30% Partial Payment screen.
     */
    void showPackageDatesEntry(PackagePromo promo, Runnable onBackToDetails) {
        resetFlow();
        activePackage = promo;
        packageDateMillis = -1;
        packageAdults = 1;
        packageChildren = 0;
        this.onBackToOverview = onBackToDetails;
        showScreen(SCREEN_PACKAGE_DATES);
    }

    private int datesScreenForFacility(int facility) {
        return facility == FAC_COTTAGE ? SCREEN_COTTAGE_DATES
                : facility == FAC_KTV ? SCREEN_KTV_DATES : SCREEN_ROOM_DATES;
    }

    /** The last screen of a facility's own step — where Next moves on to the next facility: Number of
     *  Guests for the first facility of a booking (it is the one that asks for the party), the
     *  facility's own dates screen for a later one of a multi-facility booking (the party was
     *  carried over from the first). */
    private int selectionScreenForFacility(int facility) {
        boolean asksForGuests = multiQueue.indexOf(facility) <= 0;
        if (facility == FAC_COTTAGE) {
            return asksForGuests ? SCREEN_COTTAGE_GUESTS : SCREEN_COTTAGE_DATES;
        }
        if (facility == FAC_KTV) {
            return asksForGuests ? SCREEN_KTV_GUESTS : SCREEN_KTV_DATES;
        }
        return SCREEN_ROOM_GUESTS;
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
            case SCREEN_ROOM_GUESTS:
                showScreen(SCREEN_ROOM_DATES);
                return true;
            case SCREEN_COTTAGE_GUESTS:
                showScreen(SCREEN_COTTAGE_DATES);
                return true;
            case SCREEN_KTV_GUESTS:
                showScreen(SCREEN_KTV_DATES);
                return true;
            case SCREEN_PACKAGE_DATES:
                onBackToOverview.run();
                return true;
            case SCREEN_PACKAGE_BILLING:
                showScreen(SCREEN_AMENITY);
                return true;
            case SCREEN_PACKAGE_PAYMENT:
                showScreen(SCREEN_PACKAGE_BILLING);
                return true;
            case SCREEN_COTTAGE_REVIEW:
                showScreen(SCREEN_COTTAGE_GUESTS);
                return true;
            case SCREEN_KTV_REVIEW:
                showScreen(SCREEN_KTV_GUESTS);
                return true;
            case SCREEN_REVIEW:
                // SCREEN_REVIEW is only ever reached from Number of Guests' Next button.
                showScreen(SCREEN_ROOM_GUESTS);
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
            default:
                return true;
        }
    }

    /** Which Review screen the shared Amenity screen should step back to. */
    private int reviewScreenForContinuation() {
        if (activePackage != null) {
            return SCREEN_PACKAGE_DATES;
        }
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
        if (activePackage != null) {
            return SCREEN_PACKAGE_BILLING;
        }
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
                || currentScreen == SCREEN_MULTI_PAYMENT || currentScreen == SCREEN_PACKAGE_PAYMENT)
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
        boolean isDatesScreen = (screen == SCREEN_ROOM_DATES || screen == SCREEN_COTTAGE_DATES || screen == SCREEN_KTV_DATES
                || screen == SCREEN_PACKAGE_DATES)
                && !(multiMode && multiIndex > 0);
        setBottomNavHidden.accept(!isDatesScreen);
        updateLiveSync(screen);

        if (screen == SCREEN_AMENITY) {
            if (amenitiesFreshForNextShow) {
                amenitiesFreshForNextShow = false;
            } else {
                String token = ProfileStore.getAuthToken(activity);
                if (!token.isEmpty()) {
                    syncAmenities("Bearer " + token, screen, v);
                }
            }
        }
    }

    // ---- Live sync: screens that show server data keep it current while on screen -----------

    private static boolean isLiveSyncScreen(int screen) {
        switch (screen) {
            case SCREEN_ROOM_DATES:
            case SCREEN_COTTAGE_DATES:
            case SCREEN_KTV_DATES:
            case SCREEN_AMENITY:
                return true;
            default:
                return false;
        }
    }

    private void updateLiveSync(int screen) {
        if (isLiveSyncScreen(screen)) {
            if (liveSync == null) {
                liveSync = new LiveSync(root, LIVE_SYNC_MS, this::liveSyncTick);
            }
            liveSync.start();
        } else if (liveSync != null) {
            liveSync.stop();
        }
    }

    /**
     * Silent background re-fetch for whichever live screen is showing. The guest's own picks
     * (quantities, dates, origin) are never reset — only the server-owned data is replaced, picks
     * are clamped to what is still available, and the screen is re-bound in place only when
     * something actually changed and the guest isn't mid-typing. Failures are ignored; the next
     * tick retries.
     */
    private void liveSyncTick() {
        if (liveSyncInFlight || currentScreenView == null || LiveSync.isUserTyping(currentScreenView)) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            return;
        }
        String bearer = "Bearer " + token;
        int screen = currentScreen;
        View screenView = currentScreenView;
        switch (screen) {
            case SCREEN_ROOM_DATES:
                loadRoomAvailability(screenView, false);
                break;
            case SCREEN_COTTAGE_DATES:
                loadFacilityAvailability(screenView, FAC_COTTAGE, false);
                break;
            case SCREEN_KTV_DATES:
                loadFacilityAvailability(screenView, FAC_KTV, false);
                break;
            case SCREEN_AMENITY:
                syncAmenities(bearer, screen, screenView);
                break;
            default:
                break;
        }
    }

    /** True while the screen a sync request was issued for is still the one on display. */
    private boolean liveScreenStillCurrent(int screen, View screenView) {
        return currentScreen == screen && currentScreenView == screenView
                && !LiveSync.isUserTyping(screenView);
    }

    /** Lowers any pick above what the server now offers (or for an item that disappeared) and
     *  seeds newly appeared items at 0. Returns whether a pick had to be changed. */
    private static boolean clampPicks(Map<Integer, Integer> picks, Map<Integer, Integer> availableById) {
        return RoomSelectionRules.clampPicks(picks, availableById);
    }

    private void applyLiveUpdate(int screen, View screenView, boolean selectionAdjusted) {
        bindScreen(screen, screenView);
        if (selectionAdjusted) {
            Toast.makeText(activity, R.string.toast_availability_changed, Toast.LENGTH_LONG).show();
        }
    }

    private void syncAmenities(String bearer, int screen, View screenView) {
        liveSyncInFlight = true;
        requestAmenities(bearer).enqueue(new Callback<AmenityListResponse>() {
            @Override
            public void onResponse(Call<AmenityListResponse> call, Response<AmenityListResponse> response) {
                liveSyncInFlight = false;
                if (!response.isSuccessful() || response.body() == null || response.body().amenities == null
                        || !liveScreenStillCurrent(screen, screenView)
                        || LiveSync.sameData(availableAmenities, response.body().amenities)) {
                    return;
                }
                applyLiveUpdate(screen, screenView, applyAmenityList(response.body().amenities));
            }

            @Override
            public void onFailure(Call<AmenityListResponse> call, Throwable t) {
                liveSyncInFlight = false;
            }
        });
    }

    /**
     * GET booking/amenities for the facility being booked, with the guest's dates once they have
     * picked them so the stock is for their stay. Always a fresh request: stock is never cached.
     */
    private Call<AmenityListResponse> requestAmenities(String bearer) {
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String checkIn = null;
        String checkOut = null;
        if (activePackage != null) {
            if (packageDateMillis >= 0) {
                // A package is booked for one date, same as the single-visit Cottage/KTV.
                checkIn = checkOut = day.format(new Date(packageDateMillis));
            }
        } else if (!multiMode && activeContinuation == CONTINUATION_COTTAGE) {
            if (cottageDateMillis >= 0) {
                checkIn = checkOut = day.format(new Date(cottageDateMillis));
            }
        } else if (!multiMode && activeContinuation == CONTINUATION_KTV) {
            if (ktvDateMillis >= 0) {
                checkIn = checkOut = day.format(new Date(ktvDateMillis));
            }
        } else if (checkInMillis >= 0 && checkOutMillis >= 0) {
            checkIn = day.format(new Date(checkInMillis));
            checkOut = day.format(new Date(checkOutMillis));
        }
        return ApiClient.bookingApi().getAmenities(bearer, amenityFacilityType(), checkIn, checkOut);
    }

    /**
     * Makes a freshly fetched amenity list the one on screen: picks for amenities no longer
     * offered are dropped, new ones start at 0, and any pick above what the backend now allows
     * (max_quantity, or 0 when out of stock) is lowered to it. Returns whether a pick had to be
     * lowered, so the caller can tell the guest.
     */
    private boolean applyAmenityList(List<AmenityAvailability> fresh) {
        availableAmenities = fresh != null ? fresh : new ArrayList<>();
        Set<Integer> offeredIds = new HashSet<>();
        for (AmenityAvailability amenity : availableAmenities) {
            offeredIds.add(amenity.id);
        }
        // A stale quantity must never be billed or submitted for a hidden amenity.
        amenityQuantities.keySet().retainAll(offeredIds);
        boolean lowered = false;
        for (AmenityAvailability amenity : availableAmenities) {
            Integer picked = amenityQuantities.get(amenity.id);
            int ceiling = amenity.pickerMax();
            if (picked == null) {
                amenityQuantities.put(amenity.id, 0);
            } else if (picked > ceiling) {
                amenityQuantities.put(amenity.id, ceiling);
                lowered = true;
            }
        }
        return lowered;
    }

    /**
     * After the server rejected a submit with a 422 (e.g. "Not enough stock — Extra Pillow: only 2
     * left."): re-fetch the amenity list so the stock shown is current. If a pick had to be lowered
     * the guest is taken to Choose Amenity to see the new stock and quantities; the server's message
     * has already been shown by the caller.
     */
    private void refreshAmenitiesAfterRejection() {
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            return;
        }
        requestAmenities("Bearer " + token).enqueue(new Callback<AmenityListResponse>() {
            @Override
            public void onResponse(Call<AmenityListResponse> call, Response<AmenityListResponse> response) {
                if (!response.isSuccessful() || response.body() == null || response.body().amenities == null) {
                    return;
                }
                boolean lowered = applyAmenityList(response.body().amenities);
                if (currentScreen == SCREEN_AMENITY || lowered) {
                    amenitiesFreshForNextShow = true;
                    showScreen(SCREEN_AMENITY);
                }
            }

            @Override
            public void onFailure(Call<AmenityListResponse> call, Throwable t) {
                // The next time Choose Amenity opens it re-fetches anyway.
            }
        });
    }

    /** The message to show when a submit that carries amenities is rejected: the server's own text.
     *  A 422 also re-fetches the amenity list (see {@link #refreshAmenitiesAfterRejection}). */
    private String submitFailureMessage(Response<?> response) {
        ErrorResponse error = ApiClient.parseError(response.errorBody());
        if (response.code() == 422) {
            refreshAmenitiesAfterRejection();
        }
        return error.firstMessage() != null ? error.firstMessage() : activity.getString(R.string.toast_booking_failed);
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
            case SCREEN_ROOM_GUESTS:
            case SCREEN_COTTAGE_GUESTS:
            case SCREEN_KTV_GUESTS:
                return R.layout.view_room_guests;
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
            case SCREEN_MULTI_REVIEW:
                return R.layout.view_multi_review;
            case SCREEN_MULTI_BILLING:
                return R.layout.view_hotel_billing;
            case SCREEN_MULTI_PAYMENT:
                return R.layout.view_hotel_payment;
            case SCREEN_MULTI_SUMMARY:
                return R.layout.view_reservation_summary_flow;
            case SCREEN_PACKAGE_DATES:
                return R.layout.view_package_dates;
            case SCREEN_PACKAGE_BILLING:
                return R.layout.view_hotel_billing;
            case SCREEN_PACKAGE_PAYMENT:
                return R.layout.view_hotel_payment;
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
            case SCREEN_ROOM_GUESTS:
                bindGuests(v, FAC_ROOM);
                break;
            case SCREEN_COTTAGE_GUESTS:
                bindGuests(v, FAC_COTTAGE);
                break;
            case SCREEN_KTV_GUESTS:
                bindGuests(v, FAC_KTV);
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
            case SCREEN_PACKAGE_DATES:
                bindPackageDates(v);
                break;
            case SCREEN_PACKAGE_BILLING:
                bindPackageBilling(v);
                break;
            case SCREEN_PACKAGE_PAYMENT:
                bindMultiPayment(v);
                break;
            default:
                throw new IllegalStateException("Unknown screen: " + screen);
        }
    }

    // ---- Screen 0b: Choose Your Stay Dates + the rooms free for them (Hotel Rooms) ------

    /**
     * "Choose Your Stay Dates": check-in and check-out at the top and, below them, every room type
     * that has a room free for those dates, each with its own quantity stepper (see
     * #renderRoomList). A type with nothing free is not listed at all. Availability depends on the
     * dates and the real inventory only — how many guests are coming is asked on the next screen
     * (see #bindRoomGuests) and never filters or limits the rooms here.
     *
     * <p>The list follows the dates: picking new ones fetches it again, drops picks that are no
     * longer free and lowers any that now exceed what is left (see #applyRoomAvailability). The
     * room the guest came in on starts at quantity 1 when it is free. Next needs at least one room.
     */
    private void bindRoomDates(View v) {
        bindHeader(v, R.id.roomDatesHeaderBar, R.string.room_dates_title, onBackToOverview);

        TextView checkInDateText = v.findViewById(R.id.roomDatesCheckInDateText);
        TextView checkInDayText = v.findViewById(R.id.roomDatesCheckInDayText);
        TextView checkOutDateText = v.findViewById(R.id.roomDatesCheckOutDateText);
        TextView checkOutDayText = v.findViewById(R.id.roomDatesCheckOutDayText);
        TextView checkInError = v.findViewById(R.id.roomDatesCheckInError);
        TextView checkOutError = v.findViewById(R.id.roomDatesCheckOutError);

        updateDateField(checkInDateText, checkInDayText, checkInMillis);
        updateDateField(checkOutDateText, checkOutDayText, checkOutMillis);

        View.OnClickListener openDatePicker = view -> GlassDatePicker.showRangePicker(activity, checkInMillis, checkOutMillis, System.currentTimeMillis() - 1000L, (start, end) -> {
            boolean changed = start != checkInMillis || end != checkOutMillis;
            checkInMillis = start;
            checkOutMillis = end;
            updateDateField(checkInDateText, checkInDayText, checkInMillis);
            updateDateField(checkOutDateText, checkOutDayText, checkOutMillis);
            checkInError.setVisibility(View.GONE);
            checkOutError.setVisibility(View.GONE);
            if (changed) {
                if (multiMode) {
                    // The later facilities were set up from these dates (see multiAdvance), so
                    // they start over.
                    multiResetProgress();
                }
                loadRoomAvailability(v, true);
            }
        });
        v.findViewById(R.id.roomDatesCheckInField).setOnClickListener(ClickGuard.wrap(openDatePicker));
        v.findViewById(R.id.roomDatesCheckOutField).setOnClickListener(ClickGuard.wrap(openDatePicker));
        v.findViewById(R.id.roomDatesRetryButton).setOnClickListener(ClickGuard.wrap(view -> loadRoomAvailability(v, true)));

        v.findViewById(R.id.roomDatesNextButton).setOnClickListener(ClickGuard.wrap(view -> {
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
            if (!valid || roomAvailabilityLoading) {
                return;
            }
            if (!Objects.equals(roomDatesKey(), roomAvailabilityKey)) {
                // The rooms for these dates never loaded (the last check failed): try again.
                loadRoomAvailability(v, true);
                return;
            }
            // The only requirement: at least one room. The party is asked for next.
            if (RoomSelectionRules.roomCount(availableRoomTypes, roomQuantities) <= 0) {
                Toast.makeText(activity, R.string.dialog_no_room_message, Toast.LENGTH_LONG).show();
                return;
            }
            showScreen(SCREEN_ROOM_GUESTS);
        }));

        renderRoomList(v);
        // Always re-checked on opening, so coming back from a later screen never shows stale rooms;
        // the cached list stays on screen while that happens when the dates are unchanged.
        loadRoomAvailability(v, true);
    }

    /** "yyyy-MM-dd|yyyy-MM-dd" for the chosen stay, or null until both dates are chosen and
     *  check-out is after check-in. */
    private String roomDatesKey() {
        if (checkInMillis < 0 || checkOutMillis <= checkInMillis) {
            return null;
        }
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return day.format(new Date(checkInMillis)) + "|" + day.format(new Date(checkOutMillis));
    }

    /** Draws the room list for the current state: a hint until there are dates, "checking" while
     *  the rooms for them load, a retry after a failure, a note when nothing is free, otherwise one
     *  card per room type that has a room free (the guest's original pick first). */
    private void renderRoomList(View v) {
        LinearLayout container = v.findViewById(R.id.roomDatesRoomsContainer);
        TextView message = v.findViewById(R.id.roomDatesRoomsMessage);
        View retryButton = v.findViewById(R.id.roomDatesRetryButton);
        Button nextButton = v.findViewById(R.id.roomDatesNextButton);
        container.removeAllViews();
        message.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
        nextButton.setEnabled(!roomAvailabilityLoading);
        nextButton.setText(roomAvailabilityLoading ? R.string.button_checking_availability : R.string.button_next);

        String key = roomDatesKey();
        if (key == null) {
            showRoomListMessage(message, R.string.room_dates_pick_dates_hint);
            return;
        }
        if (!key.equals(roomAvailabilityKey)) {
            if (roomAvailabilityFailed) {
                showRoomListMessage(message, R.string.toast_availability_check_failed);
                retryButton.setVisibility(View.VISIBLE);
            } else {
                showRoomListMessage(message, R.string.room_dates_loading);
            }
            return;
        }

        List<RoomTypeAvailability> bookable = RoomSelectionRules.bookable(availableRoomTypes, selectionOriginVariantId);
        if (bookable.isEmpty()) {
            showRoomListMessage(message, R.string.empty_room_selection);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (RoomTypeAvailability room : bookable) {
            int qty = RoomSelectionRules.quantityOf(roomQuantities, room.group_id);
            // The stepper tops out at the rooms free for these dates, never above.
            container.addView(buildSelectableCard(inflater, container, room.image_path,
                    formatShowcaseName(room.variant_name, room.category_name), room.capacity, false, room.description,
                    activity.getString(R.string.format_price_per_night, formatMoney(RoomSelectionRules.pricePerNight(room))),
                    room.group_id, room.available_quantity, R.string.unit_label_room, qty > 0, false,
                    R.string.unavailable_room_message, () -> renderRoomList(v)));
        }
    }

    private void showRoomListMessage(TextView message, int messageRes) {
        message.setText(messageRes);
        message.setVisibility(View.VISIBLE);
    }

    /**
     * Fetches the rooms free for the chosen dates (the same backend call and inventory rules the
     * website uses, by dates only — see RoomSelectionRules.AVAILABILITY_PROBE_ADULTS). A newer
     * request replaces an older one, and an answer for dates the guest has since changed, or for a
     * screen they have left, is ignored.
     *
     * @param foreground true when the guest caused it (screen opened, dates changed, retry): the
     *                   list shows "checking" until the answer arrives unless it is already
     *                   current, and a failure is reported. False for the live-sync tick, which is
     *                   silent and never interrupts a fetch already running.
     */
    private void loadRoomAvailability(View v, boolean foreground) {
        String key = roomDatesKey();
        if (key == null) {
            // No usable dates: nothing to look up, and nothing fetched for earlier dates stays on offer.
            cancelRoomAvailabilityCall();
            roomAvailabilityKey = null;
            roomAvailabilityLoading = false;
            roomAvailabilityFailed = false;
            if (foreground) {
                renderRoomList(v);
            }
            return;
        }
        if (!foreground && roomAvailabilityCall != null) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            if (foreground) {
                Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            }
            return;
        }
        cancelRoomAvailabilityCall();
        if (foreground) {
            roomAvailabilityFailed = false;
            roomAvailabilityLoading = !key.equals(roomAvailabilityKey);
            renderRoomList(v);
        }

        String[] dates = key.split("\\|");
        Call<AvailabilityResponse> call = ApiClient.bookingApi().checkAvailability("Bearer " + token, dates[0], dates[1],
                RoomSelectionRules.AVAILABILITY_PROBE_ADULTS, RoomSelectionRules.AVAILABILITY_PROBE_CHILDREN);
        roomAvailabilityCall = call;
        call.enqueue(new Callback<AvailabilityResponse>() {
            @Override
            public void onResponse(Call<AvailabilityResponse> call, Response<AvailabilityResponse> response) {
                if (roomAvailabilityCall == call) {
                    roomAvailabilityCall = null;
                }
                if (!roomDatesScreenStillShows(v, key)) {
                    return;
                }
                if (response.isSuccessful() && response.body() != null) {
                    applyRoomAvailability(v, key, response.body().room_types != null
                            ? response.body().room_types : new ArrayList<>());
                } else {
                    roomAvailabilityLookupFailed(v, key, foreground, response.code() == 401
                            ? R.string.toast_session_expired : R.string.toast_availability_check_failed);
                }
            }

            @Override
            public void onFailure(Call<AvailabilityResponse> call, Throwable t) {
                if (call.isCanceled()) {
                    return;
                }
                if (roomAvailabilityCall == call) {
                    roomAvailabilityCall = null;
                }
                if (roomDatesScreenStillShows(v, key)) {
                    roomAvailabilityLookupFailed(v, key, foreground, R.string.toast_network_error);
                }
            }
        });
    }

    private void cancelRoomAvailabilityCall() {
        if (roomAvailabilityCall != null) {
            roomAvailabilityCall.cancel();
            roomAvailabilityCall = null;
        }
    }

    private boolean roomDatesScreenStillShows(View v, String key) {
        return currentScreen == SCREEN_ROOM_DATES && currentScreenView == v && key.equals(roomDatesKey());
    }

    /** What the server says is free for {@code key}: replaces the list, drops the picks that are no
     *  longer free and lowers the ones above what is left (telling the guest), and starts the room
     *  the guest came in on at quantity 1 when they haven't picked anything yet. */
    private void applyRoomAvailability(View v, String key, List<RoomTypeAvailability> latest) {
        boolean unchanged = key.equals(roomAvailabilityKey) && LiveSync.sameData(availableRoomTypes, latest);
        roomAvailabilityLoading = false;
        roomAvailabilityFailed = false;
        if (unchanged) {
            return;
        }
        availableRoomTypes = latest;
        roomAvailabilityKey = key;
        boolean adjusted = RoomSelectionRules.clampPicks(roomQuantities, RoomSelectionRules.availableByGroup(latest));
        selectOriginRoomIfNothingPicked();
        renderRoomList(v);
        if (adjusted) {
            Toast.makeText(activity, R.string.toast_availability_changed, Toast.LENGTH_LONG).show();
        }
    }

    private void roomAvailabilityLookupFailed(View v, String key, boolean foreground, int messageRes) {
        roomAvailabilityLoading = false;
        if (!key.equals(roomAvailabilityKey)) {
            // Nothing current to show for these dates: the list offers a retry.
            roomAvailabilityFailed = true;
            renderRoomList(v);
        }
        if (foreground) {
            Toast.makeText(activity, messageRes, Toast.LENGTH_LONG).show();
        }
    }

    /** The room the guest chose before the dates is set to quantity 1 — once, and only while they
     *  haven't picked anything — provided it is free for these dates. If it isn't, it is simply not
     *  listed and they choose among the rest. */
    private void selectOriginRoomIfNothingPicked() {
        if (originAutoSelected || selectionOriginVariantId < 0
                || RoomSelectionRules.roomCount(availableRoomTypes, roomQuantities) > 0) {
            return;
        }
        for (RoomTypeAvailability room : availableRoomTypes) {
            if (room.variant_id == selectionOriginVariantId && room.available_quantity > 0) {
                roomQuantities.put(room.group_id, 1);
                originAutoSelected = true;
                return;
            }
        }
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
        boolean isRealReservation = summary.isBooking();

        // A booking's id is an online_bookingID, a different id space than what Pay Now/Cancel
        // expect (they operate on AdvanceReservation ids) — and neither applies to a booking.
        reservationAdvanceReservationId = isRealReservation ? -1 : summary.id;
        reservationFeedKind = summary.kind;
        reservationRecordId = summary.id;
        reservationReferenceNo = summary.booking_reference != null ? summary.booking_reference : "";
        reservationCancelReason = summary.cancel_reason != null ? summary.cancel_reason : "";
        reservationCancelledBy = summary.cancelled_by != null ? summary.cancelled_by : "";
        // The status is whatever the backend reports for this record, untouched.
        reservationStatus = summary.status;

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

    // ---- Screen 0c: Choose Your Visit Date + the cottages free for it (Cottage) ---------

    /** Cottage Use dropdown order — index must match R.array.cottage_use_options exactly. */
    private static final String[] COTTAGE_USE_OPTIONS =
            {ReservationCatalog.RATE_TYPE_DAY, ReservationCatalog.RATE_TYPE_NIGHT};

    /**
     * "Choose Your Visit Date": the date and Cottage Use at the top and, below them, every cottage
     * type with a unit free for that date and use, each with its own quantity stepper (see
     * #renderFacilityList). A type with nothing free is not listed. The party is asked for on the
     * next screen (see #bindGuests) and never filters or limits the cottages here. Like the Hotel
     * Rooms screen, the list follows the schedule (see #loadFacilityAvailability).
     *
     * <p>No raw time picker — Cottage Use (Day Use/Night Use/Overnight) already captures the
     * period, same concept as the receptionist side's own Create Reservation > Cottage screen. In a
     * multi-facility booking after the first facility the date is carried over and locked.
     */
    private void bindCottageDatesEntry(View v) {
        bindHeader(v, R.id.cottageDatesHeaderBar, R.string.cottage_dates_title, this::handleBackPressed);

        View dateField = v.findViewById(R.id.cottageDatesDateField);
        TextView dateText = v.findViewById(R.id.cottageDatesDateText);
        TextView dateError = v.findViewById(R.id.cottageDatesDateError);
        Spinner useSpinner = v.findViewById(R.id.cottageDatesUseSpinner);

        updateCottageDateField(dateText);

        ArrayAdapter<String> useAdapter = new AuthSpinnerAdapter(activity,
                Arrays.asList(activity.getResources().getStringArray(R.array.cottage_use_options)),
                false);
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
                String use = COTTAGE_USE_OPTIONS[position];
                // The initial selection reports the value already in force: nothing to refresh.
                if (!use.equals(cottageRateType)) {
                    cottageRateType = use;
                    onFacilityScheduleChanged(v, FAC_COTTAGE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // A Spinner backed by a non-empty adapter always has a selection once shown.
            }
        });

        // In the multi-facility flow the date is carried over from the first facility and isn't
        // asked for again — only the Cottage Use is.
        if (multiMode && multiIndex > 0) {
            dateField.setAlpha(0.6f);
        } else {
            dateField.setOnClickListener(ClickGuard.wrap(view -> GlassDatePicker.showDatePickerDialog(
                    activity, cottageDateMillis, System.currentTimeMillis() - 1000L, millis -> {
                        boolean changed = millis != cottageDateMillis;
                        cottageDateMillis = millis;
                        updateCottageDateField(dateText);
                        dateError.setVisibility(View.GONE);
                        if (changed) {
                            onFacilityScheduleChanged(v, FAC_COTTAGE);
                        }
                    })));
        }
        v.findViewById(R.id.facilityListRetryButton)
                .setOnClickListener(ClickGuard.wrap(view -> loadFacilityAvailability(v, FAC_COTTAGE, true)));

        v.findViewById(R.id.cottageDatesNextButton).setOnClickListener(ClickGuard.wrap(view -> {
            if (cottageDateMillis < 0) {
                showError(dateError, R.string.error_date_required);
                return;
            }
            if (!cottageDateIsCurrent()) {
                showError(dateError, R.string.error_date_past);
                return;
            }
            dateError.setVisibility(View.GONE);
            continueFromFacilityDates(v, FAC_COTTAGE);
        }));

        renderFacilityList(v, FAC_COTTAGE);
        loadFacilityAvailability(v, FAC_COTTAGE, true);
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

    private boolean cottageDateIsCurrent() {
        return cottageDateMillis >= System.currentTimeMillis() - ONE_DAY_MS;
    }

    /** "yyyy-MM-dd|use" for the chosen visit, or null until there is a usable date. */
    private String cottageDatesKey() {
        if (cottageDateMillis < 0 || !cottageDateIsCurrent()) {
            return null;
        }
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(cottageDateMillis)) + "|" + cottageUseKey();
    }

    // ---- Screen 0d: Plan Your KTV Session + the KTV rooms free for it (KTV) -------------

    /**
     * "Plan Your KTV Session": the date and start time at the top (end time and duration read-only,
     * the duration being what HR configured in KTV Room Management) and, below them, every KTV room
     * that is free for that slot, each a SELECT toggle (a KTV room is one physical room). A room
     * that is taken is not listed. The party is asked for on the next screen (see #bindGuests) and
     * never filters the rooms here. In a multi-facility booking the date is carried over and locked.
     */
    private void bindKtvDatesEntry(View v) {
        bindHeader(v, R.id.ktvDatesHeaderBar, R.string.ktv_dates_title, this::handleBackPressed);

        View dateField = v.findViewById(R.id.ktvDatesDateField);
        TextView dateText = v.findViewById(R.id.ktvDatesDateText);
        TextView dateError = v.findViewById(R.id.ktvDatesDateError);
        View startTimeField = v.findViewById(R.id.ktvDatesStartTimeField);
        TextView startTimeText = v.findViewById(R.id.ktvDatesStartTimeText);
        TextView startTimeError = v.findViewById(R.id.ktvDatesStartTimeError);

        updateKtvDateField(dateText);
        updateKtvStartTimeField(startTimeText);
        updateKtvDatesEndTimeAndDuration(v);
        loadKtvSessionHours(v);
        // In the multi-facility flow the date is carried over from the first facility and isn't
        // asked for again — only the session details (start time) are.
        if (multiMode && multiIndex > 0) {
            dateField.setAlpha(0.6f);
        } else {
            dateField.setOnClickListener(ClickGuard.wrap(view -> GlassDatePicker.showDatePickerDialog(
                    activity, ktvDateMillis, System.currentTimeMillis() - 1000L, millis -> {
                        boolean changed = millis != ktvDateMillis;
                        ktvDateMillis = millis;
                        updateKtvDateField(dateText);
                        dateError.setVisibility(View.GONE);
                        if (changed) {
                            onFacilityScheduleChanged(v, FAC_KTV);
                        }
                    })));
        }

        startTimeField.setOnClickListener(ClickGuard.wrap(view -> {
            Calendar now = Calendar.getInstance();
            int initialHour = ktvStartHour >= 0 ? ktvStartHour : now.get(Calendar.HOUR_OF_DAY);
            int initialMinute = ktvStartMinute >= 0 ? ktvStartMinute : now.get(Calendar.MINUTE);
            TimePickerDialog dialog = new TimePickerDialog(activity, (picker, hour, minute) -> {
                boolean changed = hour != ktvStartHour || minute != ktvStartMinute;
                ktvStartHour = hour;
                ktvStartMinute = minute;
                updateKtvStartTimeField(startTimeText);
                updateKtvDatesEndTimeAndDuration(v);
                startTimeError.setVisibility(View.GONE);
                if (changed) {
                    onFacilityScheduleChanged(v, FAC_KTV);
                }
            }, initialHour, initialMinute, false);
            ThemeManager.applyGlassEffect(dialog.getWindow());
            dialog.show();
        }));
        v.findViewById(R.id.facilityListRetryButton)
                .setOnClickListener(ClickGuard.wrap(view -> loadFacilityAvailability(v, FAC_KTV, true)));

        v.findViewById(R.id.ktvDatesNextButton).setOnClickListener(ClickGuard.wrap(view -> {
            boolean valid = true;
            if (ktvDurationHours <= 0) {
                Toast.makeText(activity, R.string.toast_ktv_no_rates, Toast.LENGTH_LONG).show();
                valid = false;
            }
            if (ktvDateMillis < 0) {
                showError(dateError, R.string.error_date_required);
                valid = false;
            } else if (!ktvDateIsCurrent()) {
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
                continueFromFacilityDates(v, FAC_KTV);
            }
        }));

        renderFacilityList(v, FAC_KTV);
        loadFacilityAvailability(v, FAC_KTV, true);
    }

    private boolean ktvDateIsCurrent() {
        return ktvDateMillis >= System.currentTimeMillis() - ONE_DAY_MS;
    }

    /** "yyyy-MM-dd|HH:mm" for the chosen session, or null until there is a usable date and start time. */
    private String ktvScheduleKey() {
        if (ktvDateMillis < 0 || !ktvDateIsCurrent() || ktvStartHour < 0) {
            return null;
        }
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ktvDateMillis)) + "|"
                + String.format(Locale.US, "%02d:%02d", ktvStartHour, ktvStartMinute);
    }

    /** Sets the session length from what HR configured in KTV Room Management: the hours of the
     *  KTV price packages, fetched live each time this screen opens (so admin changes show up).
     *  Shown read-only — there is no control to change it. If the configured packages don't all
     *  share one length, the shortest (the base session) is used; with none configured the guest
     *  is told rates are unavailable and Next is blocked. */
    private void loadKtvSessionHours(View v) {
        TextView info = v.findViewById(R.id.ktvDatesDurationInfo);
        Runnable render = () -> {
            info.setText(ktvDurationHours > 0
                    ? activity.getString(R.string.format_ktv_session_hours, ktvDurationHours)
                    : activity.getString(R.string.label_ktv_no_rates));
            updateKtvDatesEndTimeAndDuration(v);
        };
        render.run();

        ApiClient.bookingApi().getKtvUnits(null, null, null).enqueue(new Callback<KtvUnitListResponse>() {
            @Override
            public void onResponse(Call<KtvUnitListResponse> call, Response<KtvUnitListResponse> response) {
                if (!response.isSuccessful() || response.body() == null || !v.isAttachedToWindow()) {
                    return;
                }
                List<Integer> configured = response.body().durationOptions(); // ascending
                ktvDurationHours = configured.isEmpty() ? 0 : configured.get(0);
                render.run();
            }

            @Override
            public void onFailure(Call<KtvUnitListResponse> call, Throwable t) {
                // Keeps whatever was last loaded; nothing is invented.
            }
        });
    }

    private void updateKtvDatesEndTimeAndDuration(View v) {
        TextView endTimeText = v.findViewById(R.id.ktvDatesEndTimeText);
        int hours = ktvDurationHours;

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

    // ---- Cottage / KTV: the schedule's available list ------------------------------------

    /** Where one facility's schedule-based availability stands (see loadFacilityAvailability). */
    private static final class FacilityLoad {
        /** The schedule the facility's items were fetched for; null until fetched, or once the
         *  schedule changed. */
        String key;
        boolean loading;
        boolean failed;
        Call<?> call;

        void cancel() {
            if (call != null) {
                call.cancel();
                call = null;
            }
        }

        void reset() {
            cancel();
            key = null;
            loading = false;
            failed = false;
        }
    }

    private final FacilityLoad cottageLoad = new FacilityLoad();
    private final FacilityLoad ktvLoad = new FacilityLoad();

    private FacilityLoad loadOf(int facility) {
        return facility == FAC_COTTAGE ? cottageLoad : ktvLoad;
    }

    private List<CottageKtvOption> itemsOf(int facility) {
        return facility == FAC_COTTAGE ? cottageItems : ktvItems;
    }

    private String facilityKey(int facility) {
        return facility == FAC_COTTAGE ? cottageDatesKey() : ktvScheduleKey();
    }

    private int facilityDatesScreen(int facility) {
        return facility == FAC_COTTAGE ? SCREEN_COTTAGE_DATES : SCREEN_KTV_DATES;
    }

    /** A fresh Cottage/KTV step: nothing picked and nothing fetched yet. The schedule the guest
     *  already entered is kept. */
    private void resetFacilitySelection() {
        cottageLoad.reset();
        ktvLoad.reset();
        cottageItems = new ArrayList<>();
        ktvItems = new ArrayList<>();
        roomQuantities.clear();
        originAutoSelected = false;
    }

    /** The date, Cottage Use or start time changed: the list is fetched again for the new schedule. */
    private void onFacilityScheduleChanged(View v, int facility) {
        if (multiMode && multiIndex == 0) {
            // The later facilities were set up from this first one's date, so they start over.
            multiResetProgress();
        }
        loadFacilityAvailability(v, facility, true);
    }

    /** Next on a Cottage/KTV dates screen once the schedule is valid: needs at least one item, then
     *  asks for the party (or, after the first facility of a multi-facility booking, where the party
     *  was already given, carries on). */
    private void continueFromFacilityDates(View v, int facility) {
        FacilityLoad load = loadOf(facility);
        if (load.loading) {
            return;
        }
        if (!Objects.equals(facilityKey(facility), load.key)) {
            // The items for this schedule never loaded (the last check failed): try again.
            loadFacilityAvailability(v, facility, true);
            return;
        }
        if (FacilitySelectionRules.itemCount(itemsOf(facility), roomQuantities) <= 0) {
            Toast.makeText(activity, facility == FAC_COTTAGE ? R.string.toast_select_cottage
                    : R.string.toast_select_ktv, Toast.LENGTH_LONG).show();
            return;
        }
        if (multiMode && multiIndex > 0) {
            multiAdvance();
            return;
        }
        showScreen(facility == FAC_COTTAGE ? SCREEN_COTTAGE_GUESTS : SCREEN_KTV_GUESTS);
    }

    /** Draws the list for the current state: a hint until there is a schedule, "checking" while it
     *  loads, a retry after a failure, a note when nothing is free, otherwise one card per item that
     *  has availability (the guest's original pick first). */
    private void renderFacilityList(View v, int facility) {
        boolean cottage = facility == FAC_COTTAGE;
        FacilityLoad load = loadOf(facility);
        LinearLayout container = v.findViewById(R.id.facilityListContainer);
        TextView title = v.findViewById(R.id.facilityListTitle);
        TextView message = v.findViewById(R.id.facilityListMessage);
        View retryButton = v.findViewById(R.id.facilityListRetryButton);
        Button nextButton = v.findViewById(cottage ? R.id.cottageDatesNextButton : R.id.ktvDatesNextButton);
        title.setText(cottage ? R.string.cottage_selection_title : R.string.ktv_selection_title);
        container.removeAllViews();
        message.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
        nextButton.setEnabled(!load.loading);
        nextButton.setText(load.loading ? R.string.button_checking_availability : R.string.button_next);
        bindFacilityCarriedNotice(v, facility);

        String key = facilityKey(facility);
        if (key == null) {
            showRoomListMessage(message, cottage ? R.string.cottage_dates_pick_hint : R.string.ktv_dates_pick_hint);
            return;
        }
        if (!key.equals(load.key)) {
            if (load.failed) {
                showRoomListMessage(message, R.string.toast_availability_check_failed);
                retryButton.setVisibility(View.VISIBLE);
            } else {
                showRoomListMessage(message, cottage ? R.string.facility_loading_cottages : R.string.facility_loading_ktv);
            }
            return;
        }

        List<CottageKtvOption> bookable = FacilitySelectionRules.bookable(itemsOf(facility), selectionOriginVariantId);
        if (bookable.isEmpty()) {
            showRoomListMessage(message, cottage ? R.string.empty_cottage_selection : R.string.empty_ktv_selection);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (CottageKtvOption item : bookable) {
            int qty = RoomSelectionRules.quantityOf(roomQuantities, item.group_id);
            // The stepper tops out at what is free for this schedule, never above.
            container.addView(buildSelectableCard(inflater, container, item.image_path,
                    formatShowcaseName(item.variant_name, null), item.capacity, cottage, item.description,
                    activity.getString(R.string.format_price_with_unit, formatMoney((int) Math.round(resolvedItemPrice(item))), resolvedItemPriceUnit(item)),
                    item.group_id, item.available_quantity,
                    cottage ? R.string.unit_label_cottage : R.string.unit_label_ktv_room, qty > 0, false,
                    cottage ? R.string.unavailable_cottage_message : R.string.unavailable_ktv_message,
                    () -> renderFacilityList(v, facility)));
        }
    }

    /**
     * Fetches what is free for the chosen schedule: a Cottage's date and Cottage Use (the same
     * backend rules the receptionist's Create Reservation uses), a KTV room's date and slot. The party
     * is never sent, so it never hides an item. A newer request replaces an older one, and an answer
     * for a schedule the guest has since changed, or for a screen they have left, is ignored.
     *
     * @param foreground true when the guest caused it (screen opened, schedule changed, retry): the
     *                   list shows "checking" until the answer arrives unless it is already current,
     *                   and a failure is reported. False for the live-sync tick, which is silent and
     *                   never interrupts a fetch already running.
     */
    private void loadFacilityAvailability(View v, int facility, boolean foreground) {
        FacilityLoad load = loadOf(facility);
        String key = facilityKey(facility);
        if (key == null) {
            // No usable schedule: nothing to look up, and nothing fetched for an earlier one stays on offer.
            load.reset();
            if (foreground) {
                renderFacilityList(v, facility);
            }
            return;
        }
        if (!foreground && load.call != null) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            if (foreground) {
                Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            }
            return;
        }
        load.cancel();
        if (foreground) {
            load.failed = false;
            load.loading = !key.equals(load.key);
            renderFacilityList(v, facility);
        }

        String[] parts = key.split("\\|");
        if (facility == FAC_COTTAGE) {
            Call<CottageAvailabilityResponse> call = ApiClient.bookingApi()
                    .checkCottageAvailability("Bearer " + token, parts[0], parts[1], null, null);
            load.call = call;
            call.enqueue(new Callback<CottageAvailabilityResponse>() {
                @Override
                public void onResponse(Call<CottageAvailabilityResponse> call, Response<CottageAvailabilityResponse> response) {
                    onFacilityResponse(v, facility, key, call, foreground, response.code(),
                            response.isSuccessful() && response.body() != null
                                    ? (response.body().cottages != null
                                    ? new ArrayList<>(response.body().cottages) : new ArrayList<>()) : null);
                }

                @Override
                public void onFailure(Call<CottageAvailabilityResponse> call, Throwable t) {
                    onFacilityFailure(v, facility, key, call, foreground);
                }
            });
        } else {
            Call<KtvUnitListResponse> call = ApiClient.bookingApi().getKtvUnitsForParty(parts[0], parts[1], null, null);
            load.call = call;
            call.enqueue(new Callback<KtvUnitListResponse>() {
                @Override
                public void onResponse(Call<KtvUnitListResponse> call, Response<KtvUnitListResponse> response) {
                    List<CottageKtvOption> items = null;
                    if (response.isSuccessful() && response.body() != null) {
                        if (ktvDurationHours <= 0) {
                            // Before the session length has loaded, take it from these same packages.
                            List<Integer> configured = response.body().durationOptions();
                            ktvDurationHours = configured.isEmpty() ? 0 : configured.get(0);
                            if (v.isAttachedToWindow()) {
                                updateKtvDatesEndTimeAndDuration(v);
                            }
                        }
                        items = new ArrayList<>(response.body().toOptions(ktvDurationHours));
                    }
                    onFacilityResponse(v, facility, key, call, foreground, response.code(), items);
                }

                @Override
                public void onFailure(Call<KtvUnitListResponse> call, Throwable t) {
                    onFacilityFailure(v, facility, key, call, foreground);
                }
            });
        }
    }

    /** {@code items} is null when the request failed (HTTP error {@code httpCode}). */
    private void onFacilityResponse(View v, int facility, String key, Call<?> call, boolean foreground,
                                    int httpCode, List<CottageKtvOption> items) {
        FacilityLoad load = loadOf(facility);
        if (load.call == call) {
            load.call = null;
        }
        if (!facilityScreenStillShows(v, facility, key)) {
            return;
        }
        if (items != null) {
            applyFacilityAvailability(v, facility, key, items);
        } else {
            facilityLookupFailed(v, facility, key, foreground,
                    httpCode == 401 ? R.string.toast_session_expired : R.string.toast_availability_check_failed);
        }
    }

    private void onFacilityFailure(View v, int facility, String key, Call<?> call, boolean foreground) {
        if (call.isCanceled()) {
            return;
        }
        FacilityLoad load = loadOf(facility);
        if (load.call == call) {
            load.call = null;
        }
        if (facilityScreenStillShows(v, facility, key)) {
            facilityLookupFailed(v, facility, key, foreground, R.string.toast_network_error);
        }
    }

    private boolean facilityScreenStillShows(View v, int facility, String key) {
        return currentScreen == facilityDatesScreen(facility) && currentScreenView == v
                && key.equals(facilityKey(facility));
    }

    /** What the server says is free for {@code key}: replaces the list, drops the picks that are no
     *  longer free and lowers the ones above what is left (telling the guest), and starts the item
     *  the guest came in on at quantity 1 when they haven't picked anything yet. */
    private void applyFacilityAvailability(View v, int facility, String key, List<CottageKtvOption> latest) {
        FacilityLoad load = loadOf(facility);
        boolean unchanged = key.equals(load.key) && LiveSync.sameData(itemsOf(facility), latest);
        load.loading = false;
        load.failed = false;
        if (unchanged) {
            return;
        }
        if (facility == FAC_COTTAGE) {
            cottageItems = latest;
        } else {
            ktvItems = latest;
        }
        load.key = key;
        boolean adjusted = RoomSelectionRules.clampPicks(roomQuantities, FacilitySelectionRules.availableByGroup(latest));
        selectOriginItemIfNothingPicked(facility);
        renderFacilityList(v, facility);
        if (adjusted) {
            Toast.makeText(activity, R.string.toast_availability_changed, Toast.LENGTH_LONG).show();
        }
    }

    private void facilityLookupFailed(View v, int facility, String key, boolean foreground, int messageRes) {
        FacilityLoad load = loadOf(facility);
        load.loading = false;
        if (!key.equals(load.key)) {
            // Nothing current to show for this schedule: the list offers a retry.
            load.failed = true;
            renderFacilityList(v, facility);
        }
        if (foreground) {
            Toast.makeText(activity, messageRes, Toast.LENGTH_LONG).show();
        }
    }

    /** The cottage / KTV room the guest chose before the schedule is set to quantity 1 — once, and
     *  only while they haven't picked anything — provided it is free for that schedule. */
    private void selectOriginItemIfNothingPicked(int facility) {
        List<CottageKtvOption> items = itemsOf(facility);
        if (originAutoSelected || selectionOriginVariantId < 0
                || FacilitySelectionRules.itemCount(items, roomQuantities) > 0) {
            return;
        }
        for (CottageKtvOption item : items) {
            if (item.variant_id == selectionOriginVariantId && item.available_quantity > 0) {
                roomQuantities.put(item.group_id, 1);
                originAutoSelected = true;
                return;
            }
        }
    }

    /** After the first facility of a multi-facility booking the party isn't asked for again; if the
     *  carried party is larger than this selection's capacity the notice shows right here. */
    private void bindFacilityCarriedNotice(View v, int facility) {
        View notice = v.findViewById(R.id.facilityListCapacityNotice);
        boolean show = multiMode && multiIndex > 0 && partyExceedsCapacity(facility);
        notice.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            setCapacityNoticeText(notice, facility);
        }
    }

    // ---- Number of Guests (Hotel Rooms, Cottage and KTV) --------------------------------

    private int guestAdults(int facility) {
        return facility == FAC_COTTAGE ? cottageAdults : facility == FAC_KTV ? ktvAdults : adults;
    }

    private int guestChildren(int facility) {
        return facility == FAC_COTTAGE ? cottageChildren : facility == FAC_KTV ? ktvChildren : children;
    }

    private void setGuests(int facility, int adultCount, int childCount) {
        if (facility == FAC_COTTAGE) {
            cottageAdults = adultCount;
            cottageChildren = childCount;
        } else if (facility == FAC_KTV) {
            ktvAdults = adultCount;
            ktvChildren = childCount;
            // KTV is booked and priced on the party's total (ktv[guest_count]).
            ktvGuestCount = adultCount + childCount;
        } else {
            adults = adultCount;
            children = childCount;
        }
    }

    /** Whether the party is larger than the standard capacity of what is picked — drives the
     *  informational notice only. */
    private boolean partyExceedsCapacity(int facility) {
        if (facility == FAC_COTTAGE) {
            return FacilitySelectionRules.exceedsCapacity(cottageItems, roomQuantities, cottageAdults + cottageChildren);
        }
        if (facility == FAC_KTV) {
            return FacilitySelectionRules.exceedsCapacity(ktvItems, roomQuantities, ktvGuestCount);
        }
        return RoomSelectionRules.exceedsCapacity(availableRoomTypes, roomQuantities, adults, children);
    }

    /** The notice's wording for the facility: rooms, cottages or KTV rooms. */
    private void setCapacityNoticeText(View notice, int facility) {
        ((TextView) notice.findViewById(R.id.capacityNoticeText)).setText(
                facility == FAC_COTTAGE ? R.string.notice_guests_exceed_capacity_cottage
                        : facility == FAC_KTV ? R.string.notice_guests_exceed_capacity_ktv
                        : R.string.notice_guests_exceed_capacity);
    }

    /**
     * "Number of Guests", asked once the schedule and the items are chosen — the same screen for
     * Hotel Rooms, Cottage and KTV. The party never limits the booking: when it is larger than the
     * combined standard capacity of what was picked (capacity x quantity, summed) an informational
     * notice appears and disappears as the numbers change, and Next stays available either way. A
     * multi-facility booking carries on to the next facility from here instead of Review.
     */
    private void bindGuests(View v, int facility) {
        bindHeader(v, R.id.roomGuestsHeaderBar, R.string.room_guests_title,
                () -> showScreen(datesScreenForFacility(facility)));

        if (facility == FAC_COTTAGE) {
            ((TextView) v.findViewById(R.id.roomGuestsSubtitle)).setText(R.string.cottage_guests_subtitle);
            TextView note = v.findViewById(R.id.roomGuestsNote);
            note.setText(R.string.note_entrance_fee_calculation);
            note.setVisibility(View.VISIBLE);
        } else if (facility == FAC_KTV) {
            ((TextView) v.findViewById(R.id.roomGuestsSubtitle)).setText(R.string.ktv_guests_subtitle);
            if (ktvAdults + ktvChildren != ktvGuestCount) {
                // The total was set elsewhere (carried over in a multi-facility booking).
                ktvAdults = Math.max(1, ktvGuestCount);
                ktvChildren = 0;
            }
        }

        EditText adultsValue = v.findViewById(R.id.roomGuestsAdultsValue);
        EditText childrenValue = v.findViewById(R.id.roomGuestsChildrenValue);
        View notice = v.findViewById(R.id.roomGuestsCapacityNotice);
        setCapacityNoticeText(notice, facility);
        Runnable updateNotice = () -> notice.setVisibility(partyExceedsCapacity(facility) ? View.VISIBLE : View.GONE);

        // At least one adult: the booking is made under an adult (the server requires it too).
        setGuests(facility, Math.min(Math.max(1, guestAdults(facility)), RoomSelectionRules.MAX_ADULTS),
                Math.min(guestChildren(facility), RoomSelectionRules.MAX_CHILDREN));
        AuthUiUtils.bindQuantityStepper(adultsValue, v.findViewById(R.id.roomGuestsAdultsMinus),
                v.findViewById(R.id.roomGuestsAdultsPlus), guestAdults(facility), 1, RoomSelectionRules.MAX_ADULTS, value -> {
                    setGuests(facility, value, guestChildren(facility));
                    updateNotice.run();
                });
        AuthUiUtils.bindQuantityStepper(childrenValue, v.findViewById(R.id.roomGuestsChildrenMinus),
                v.findViewById(R.id.roomGuestsChildrenPlus), guestChildren(facility), 0, RoomSelectionRules.MAX_CHILDREN, value -> {
                    setGuests(facility, guestAdults(facility), value);
                    updateNotice.run();
                });
        // Typing a number updates the notice as it is typed, not only once the field loses focus.
        AuthUiUtils.afterTextChanged(adultsValue, () -> {
            setGuests(facility, readGuestCount(adultsValue, RoomSelectionRules.MAX_ADULTS), guestChildren(facility));
            updateNotice.run();
        });
        AuthUiUtils.afterTextChanged(childrenValue, () -> {
            setGuests(facility, guestAdults(facility), readGuestCount(childrenValue, RoomSelectionRules.MAX_CHILDREN));
            updateNotice.run();
        });
        updateNotice.run();

        v.findViewById(R.id.roomGuestsNextButton).setOnClickListener(ClickGuard.wrap(view -> {
            // Read what is in the fields: a number typed just before tapping Next hasn't lost focus yet.
            int adultCount = readGuestCount(adultsValue, RoomSelectionRules.MAX_ADULTS);
            int childCount = readGuestCount(childrenValue, RoomSelectionRules.MAX_CHILDREN);
            if (adultCount < 1) {
                Toast.makeText(activity, R.string.toast_adults_required, Toast.LENGTH_LONG).show();
                return;
            }
            setGuests(facility, adultCount, childCount);
            // Never blocked by the party being larger than the selection: the notice is all there is.
            if (multiMode) {
                multiAdvance();
                return;
            }
            showScreen(facility == FAC_COTTAGE ? SCREEN_COTTAGE_REVIEW
                    : facility == FAC_KTV ? SCREEN_KTV_REVIEW : SCREEN_REVIEW);
        }));
    }

    private static int readGuestCount(EditText field, int max) {
        try {
            return Math.max(0, Math.min(max, Integer.parseInt(field.getText().toString().trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
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
                () -> showScreen(SCREEN_COTTAGE_GUESTS));

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

        bindReviewCapacityNotice(v, FAC_COTTAGE);

        EditText specialRequestField = v.findViewById(R.id.cottageReviewSpecialRequestField);
        specialRequestField.setText(specialRequest);
        AuthUiUtils.afterTextChanged(specialRequestField,
                () -> specialRequest = specialRequestField.getText().toString());

        v.findViewById(R.id.hotelReviewNextButton).setOnClickListener(ClickGuard.wrap(view -> {
            activeContinuation = CONTINUATION_COTTAGE;
            fetchAmenities(v);
        }));
    }

    /** The informational notice on Review Your Selection, shown only while the party is larger than
     *  the combined standard capacity of what was picked. */
    private void bindReviewCapacityNotice(View v, int facility) {
        View notice = v.findViewById(R.id.reviewCapacityNotice);
        setCapacityNoticeText(notice, facility);
        notice.setVisibility(partyExceedsCapacity(facility) ? View.VISIBLE : View.GONE);
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
        bindPromoCoupons(v);
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

        bindCouponRow(v.findViewById(R.id.billingRowCoupon));
        int grandTotal = Math.max(0, cottageTotal + amenitiesTotal - couponDiscount());
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

        bindPolicyAgreement(v, R.id.hotelBillingProceedButton);

        v.findViewById(R.id.hotelBillingProceedButton).setOnClickListener(ClickGuard.wrap(view -> {
            if (paymentOption == PAYMENT_NONE) {
                Toast.makeText(activity, R.string.toast_select_payment_option, Toast.LENGTH_LONG).show();
                return;
            }
            if (!termsChecked || !cancellationChecked) {
                Toast.makeText(activity, R.string.toast_agree_terms_required, Toast.LENGTH_LONG).show();
                return;
            }
            showScreen(SCREEN_COTTAGE_PAYMENT);
        }));
    }

    /**
     * Cottage flow's own Payment screen — reuses view_hotel_payment.xml. Amount Paid/Reference
     * Number/screenshot validation is identical to bindPayment; Complete Booking submits through
     * submitSingleFacilityBooking (the same real multipart booking request as the other Book flows).
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
        wirePaymentSummary(v, grandTotal, () -> amountDue);
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

        v.findViewById(R.id.paymentScreenshotDropzone).setOnClickListener(ClickGuard.wrap(view -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            activity.startActivityForResult(intent, REQUEST_PAYMENT_SCREENSHOT);
        }));
        if (screenshotUri != null) {
            showScreenshotPreview(v);
        }

        v.findViewById(R.id.hotelPaymentCompleteButton).setOnClickListener(ClickGuard.wrap(view -> {
            if (!validateAmountPaid(amountDue)) {
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
            confirmOverpayment(v, amountDue, () -> submitSingleFacilityBooking(v, FAC_COTTAGE));
        }));
    }

    private int computeCottageGrandTotal() {
        int total = 0;
        for (CottageKtvOption item : cottageItems) {
            total += (int) Math.round(resolvedItemPrice(item)) * roomQuantities.get(item.group_id);
        }
        for (AmenityAvailability amenity : availableAmenities) {
            total += (int) Math.round(amenity.price) * amenityQuantities.get(amenity.id);
        }
        return Math.max(0, total - couponDiscount());
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
                () -> showScreen(SCREEN_KTV_GUESTS));

        bindRow(v.findViewById(R.id.ktvReviewRowRateType), R.string.label_duration, ktvDurationLabel());
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

        bindReviewCapacityNotice(v, FAC_KTV);

        EditText specialRequestField = v.findViewById(R.id.ktvReviewSpecialRequestField);
        specialRequestField.setText(specialRequest);
        AuthUiUtils.afterTextChanged(specialRequestField,
                () -> specialRequest = specialRequestField.getText().toString());

        v.findViewById(R.id.hotelReviewNextButton).setOnClickListener(ClickGuard.wrap(view -> {
            activeContinuation = CONTINUATION_KTV;
            fetchAmenities(v);
        }));
    }

    private String ktvDurationLabel() {
        return activity.getString(R.string.format_duration_hours, ktvDurationHours);
    }

    private String formatKtvGuests() {
        return ktvGuestCount + (ktvGuestCount == 1 ? " Guest" : " Guests");
    }

    /** Same calculation as updateKtvDatesEndTimeAndDuration, as a plain String for the Review
     *  screen's read-only row instead of writing straight into a TextView. */
    private String formatKtvEndTime() {
        int hours = ktvDurationHours;
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
        bindPromoCoupons(v);
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
        bindRow(v.findViewById(R.id.billingRowCheckIn), R.string.label_duration, ktvDurationLabel());
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
            int baseUnit = (int) Math.round(resolvedItemPrice(item));
            int unitPrice = baseUnit + ktvExtraCharge(item);
            int subtotal = unitPrice * qty;
            ktvTotal += subtotal;
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, accommodationContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(formatShowcaseName(item.variant_name, null));
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText(activity.getString(R.string.format_capacity, item.capacity) + " · Qty " + qty + " · "
                    + activity.getString(R.string.format_price_with_unit, formatMoney(baseUnit), resolvedItemPriceUnit(item)) + ktvExtraNote(item));
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

        bindCouponRow(v.findViewById(R.id.billingRowCoupon));
        int grandTotal = Math.max(0, ktvTotal + amenitiesTotal - couponDiscount());
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

        bindPolicyAgreement(v, R.id.hotelBillingProceedButton);

        v.findViewById(R.id.hotelBillingProceedButton).setOnClickListener(ClickGuard.wrap(view -> {
            if (paymentOption == PAYMENT_NONE) {
                Toast.makeText(activity, R.string.toast_select_payment_option, Toast.LENGTH_LONG).show();
                return;
            }
            if (!termsChecked || !cancellationChecked) {
                Toast.makeText(activity, R.string.toast_agree_terms_required, Toast.LENGTH_LONG).show();
                return;
            }
            showScreen(SCREEN_KTV_PAYMENT);
        }));
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
        wirePaymentSummary(v, grandTotal, () -> amountDue);
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

        v.findViewById(R.id.paymentScreenshotDropzone).setOnClickListener(ClickGuard.wrap(view -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            activity.startActivityForResult(intent, REQUEST_PAYMENT_SCREENSHOT);
        }));
        if (screenshotUri != null) {
            showScreenshotPreview(v);
        }

        v.findViewById(R.id.hotelPaymentCompleteButton).setOnClickListener(ClickGuard.wrap(view -> {
            if (!validateAmountPaid(amountDue)) {
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
            confirmOverpayment(v, amountDue, () -> submitSingleFacilityBooking(v, FAC_KTV));
        }));
    }

    private int computeKtvGrandTotal() {
        int total = 0;
        for (CottageKtvOption item : ktvItems) {
            total += ((int) Math.round(resolvedItemPrice(item)) + ktvExtraCharge(item)) * roomQuantities.get(item.group_id);
        }
        for (AmenityAvailability amenity : availableAmenities) {
            total += (int) Math.round(amenity.price) * amenityQuantities.get(amenity.id);
        }
        return Math.max(0, total - couponDiscount());
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
        bindPromoCoupons(v);
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
            bindRow(row1, R.string.label_duration, ktvDurationLabel());
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
                int baseUnit = (int) Math.round(resolvedItemPrice(item));
                int unitPrice = baseUnit + ktvExtraCharge(item);
                View row = inflater.inflate(R.layout.view_hotel_billing_line_row, itemsContainer, false);
                ((TextView) row.findViewById(R.id.lineName)).setText(formatShowcaseName(item.variant_name, null));
                TextView meta = row.findViewById(R.id.lineMeta);
                meta.setText(activity.getString(R.string.format_capacity, item.capacity) + " · Qty " + qty + " · "
                        + activity.getString(R.string.format_price_with_unit, formatMoney(baseUnit), resolvedItemPriceUnit(item)) + ktvExtraNote(item));
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

        bindCouponRow(v.findViewById(R.id.reservationSummaryCouponRow));
        bindLine(v.findViewById(R.id.reservationSummaryFullPaymentRow), R.string.label_full_payment, grandTotal);
        bindLine(v.findViewById(R.id.reservationSummaryDownPaymentRow), R.string.label_down_payment_30,
                computePartialAmount(grandTotal));

        bindPolicyAgreement(v, R.id.reservationSummaryReserveButton);

        // Hotel Rooms, Cottages and KTV Rooms all submit a real reservation request to the shared
        // backend (submitReservationRequest / submitCottageReservationRequest /
        // submitSingleFacilityReservation).
        v.findViewById(R.id.reservationSummaryReserveButton).setOnClickListener(ClickGuard.wrap(view -> {
            if (activeContinuation == CONTINUATION_COTTAGE) {
                submitCottageReservationRequest(v);
                return;
            }
            if (activeContinuation == CONTINUATION_KTV) {
                submitSingleFacilityReservation(v, FAC_KTV);
                return;
            }
            submitReservationRequest(v);
        }));
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
                couponFields(withAmenityFields(quantityFieldMap("room_quantities", roomQuantities)))
        ).enqueue(new Callback<ReservationRequestResponse>() {
            @Override
            public void onResponse(Call<ReservationRequestResponse> call, Response<ReservationRequestResponse> response) {
                reserveButton.setEnabled(true);
                if (response.isSuccessful() && response.body() != null) {
                    // Persisted (inside trackNewReservation) so ChatPollingService (a separate
                    // component with no reference to this in-memory instance) can keep checking
                    // on it in the background and notify the guest of a receptionist-side change
                    // — see ProfileStore#getActiveReservationId.
                    trackNewReservation(response.body().advance_reservation_id,
                            response.body().booking_reference, response.body().status_detail);
                    snapshotReservationDisplayData();
                    showReservationSubmitted();
                } else if (response.code() == 401) {
                    Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                } else {
                    String message = submitFailureMessage(response);
                    recheckCouponsAfterFailure();
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
                couponFields(withAmenityFields(quantityFieldMap("cottage_quantities", roomQuantities)))
        ).enqueue(new Callback<ReservationRequestResponse>() {
            @Override
            public void onResponse(Call<ReservationRequestResponse> call, Response<ReservationRequestResponse> response) {
                reserveButton.setEnabled(true);
                if (response.isSuccessful() && response.body() != null) {
                    trackNewReservation(response.body().advance_reservation_id,
                            response.body().booking_reference, response.body().status_detail);
                    snapshotReservationDisplayData();
                    showReservationSubmitted();
                } else if (response.code() == 401) {
                    Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                } else {
                    String message = submitFailureMessage(response);
                    recheckCouponsAfterFailure();
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
        v.findViewById(R.id.reservationConfirmedViewButton).setOnClickListener(ClickGuard.wrap(view -> {
            reservationDetailsBackTarget = datesScreenForContinuation();
            showScreen(SCREEN_RESERVATION_DETAILS);
        }));
        v.findViewById(R.id.reservationConfirmedHomeButton).setOnClickListener(ClickGuard.wrap(view -> {
            resetFlow();
            onBackToHome.run();
        }));
    }

    /** Starts tracking a request the backend just created (Hotel, Cottage, KTV or multi-facility
     *  Reserve): Reservation Details will show the status from the response's status_detail, the
     *  background poller is pointed at it, and the live lists are told to refetch so the new record
     *  appears in them. */
    private void trackNewReservation(int advanceReservationId, String reference, StatusDetail status) {
        reservationAdvanceReservationId = advanceReservationId;
        reservationFeedKind = ReservationSummary.KIND_REQUEST;
        reservationRecordId = advanceReservationId;
        reservationReferenceNo = reference != null ? reference : "";
        reservationStatus = status;
        reservationCancelReason = "";
        reservationCancelledBy = "";
        ProfileStore.setActiveReservationId(activity, advanceReservationId);
        ProfileStore.setActiveReservationStatus(activity, status != null ? status.token() : "");
        ReservationFeed.refresh();
    }

    /** Keeps ChatPollingService's own background check from re-notifying about a status the guest
     *  has already just seen here, for the reservation it watches. */
    private void rememberActiveReservationStatus() {
        if (reservationStatus != null && reservationAdvanceReservationId > 0
                && reservationAdvanceReservationId == ProfileStore.getActiveReservationId(activity)) {
            ProfileStore.setActiveReservationStatus(activity, reservationStatus.token());
        }
    }

    /** True while the record on Reservation Details is an unpaid request in the pending status —
     *  the only time Pay Now and Cancel exist. The backend's status says both things: its kind
     *  (a request's statuses are of kind "reservation"; once paid it is a "booking") and its key. */
    private boolean canPayOrCancelReservation() {
        return reservationAdvanceReservationId > 0 && reservationStatus != null
                && StatusDetail.KIND_RESERVATION.equals(reservationStatus.kind)
                && reservationStatus.hasKey(StatusDetail.KEY_PENDING);
    }

    /** Who cancelled and why, when the backend gave either; empty when it gave neither. */
    private String cancelledStatement() {
        String base = "";
        if ("guest".equals(reservationCancelledBy)) {
            base = activity.getString(R.string.reservation_cancelled_by_guest);
        } else if ("receptionist".equals(reservationCancelledBy)) {
            base = activity.getString(R.string.reservation_cancelled_by_staff);
        }
        if (reservationCancelReason != null && !reservationCancelReason.isEmpty()) {
            base += activity.getString(R.string.format_reservation_cancelled_reason_suffix, reservationCancelReason);
        }
        return base.trim();
    }

    /** Reservation Confirmed's "View Reservation" — a standalone status screen for the reservation
     *  just submitted (see SCREEN_RESERVATION_DETAILS). Reuses the same category-branching data
     *  already gathered for the Reservation Summary screen (see bindReservationSummary). */
    private void bindReservationDetails(View v) {
        bindHeader(v, R.id.reservationDetailsHeaderBar, R.string.reservation_details_title,
                this::leaveReservationDetails);

        // While this screen is open the live feed polls the backend every few seconds and calls
        // back whenever a status (or the record's presence in the list) changed.
        ReservationFeed.watch(this, this::isReservationDetailsVisible, this::onReservationFeedChanged);

        renderReservationDetails(v);
    }

    private boolean isReservationDetailsVisible() {
        return currentScreen == SCREEN_RESERVATION_DETAILS && currentScreenView != null
                && root.isAttachedToWindow();
    }

    /** Draws everything on Reservation Details that depends on the record's status. Called on
     *  open and again, in place, whenever the backend reports a different status. */
    private void renderReservationDetails(View v) {
        StatusDetail status = reservationStatus;
        View statusDot = v.findViewById(R.id.reservationDetailsStatusDot);
        TextView statusLabel = v.findViewById(R.id.reservationDetailsStatusLabel);
        TextView statusDesc = v.findViewById(R.id.reservationDetailsStatusDesc);
        if (status == null) {
            statusDot.setVisibility(View.GONE);
            statusLabel.setVisibility(View.GONE);
            statusDesc.setVisibility(View.GONE);
        } else {
            int color = StatusCatalog.color(activity, status);
            statusDot.setVisibility(View.VISIBLE);
            statusDot.setBackgroundTintList(ColorStateList.valueOf(color));
            statusLabel.setVisibility(View.VISIBLE);
            statusLabel.setText(status.label);
            statusLabel.setTextColor(color);
            boolean hasDefinition = status.definition != null && !status.definition.isEmpty();
            statusDesc.setVisibility(hasDefinition ? View.VISIBLE : View.GONE);
            statusDesc.setText(status.definition);
        }

        String cancelledText = status != null && status.hasKey(StatusDetail.KEY_CANCELLED)
                ? cancelledStatement() : "";
        v.findViewById(R.id.reservationDetailsCancelledContainer).setVisibility(
                cancelledText.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) v.findViewById(R.id.reservationDetailsCancelledReason)).setText(cancelledText);

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

        // Pay Now and Cancel exist only for an unpaid request that is still pending (see
        // canPayOrCancelReservation) — gone the moment the backend reports anything else, e.g.
        // once the request has been paid and is a booking.
        boolean canAct = canPayOrCancelReservation();
        Button payNowButton = v.findViewById(R.id.reservationDetailsPayNowButton);
        payNowButton.setVisibility(canAct ? View.VISIBLE : View.GONE);
        payNowButton.setOnClickListener(ClickGuard.wrap(view -> {
            reservationPaymentOption = PAYMENT_NONE;
            showScreen(SCREEN_RESERVATION_PAYMENT);
        }));

        Button cancelButton = v.findViewById(R.id.reservationDetailsCancelButton);
        cancelButton.setVisibility(canAct ? View.VISIBLE : View.GONE);
        cancelButton.setOnClickListener(ClickGuard.wrap(view -> showCancelConfirmDialog()));
    }

    /** The live feed changed: re-read this record's status from it and re-draw if it differs. A
     *  request that is no longer in the list has been paid (here or on another device) and is a
     *  booking now, which the backend reports under the same request id. */
    private void onReservationFeedChanged() {
        if (!isReservationDetailsVisible()) {
            return;
        }
        ReservationSummary row = findReservationRow();
        if (row != null) {
            applyReservationRow(row);
        } else if (reservationAdvanceReservationId > 0 && ReservationFeed.isLoaded()) {
            fetchReservationStatus();
        }
    }

    /** This record in the live feed: by its kind and id, or — once a request has been paid and its
     *  id is no longer meaningful — by its reference. */
    private ReservationSummary findReservationRow() {
        if (reservationRecordId > 0) {
            ReservationSummary row = ReservationFeed.find(reservationFeedKind, reservationRecordId);
            if (row != null) {
                return row;
            }
        }
        if (reservationReferenceNo.isEmpty()) {
            return null;
        }
        for (ReservationSummary candidate : ReservationFeed.rows()) {
            if (reservationReferenceNo.equals(candidate.booking_reference)) {
                return candidate;
            }
        }
        return null;
    }

    private void applyReservationRow(ReservationSummary row) {
        StatusDetail previousStatus = reservationStatus;
        String previousReason = reservationCancelReason;
        String previousBy = reservationCancelledBy;

        // Follows the record if it changed identity (a paid request is a booking with its own id).
        reservationFeedKind = row.kind;
        reservationRecordId = row.id;
        if (row.booking_reference != null && !row.booking_reference.isEmpty()) {
            reservationReferenceNo = row.booking_reference;
        }
        if (row.status != null) {
            reservationStatus = row.status;
        }
        // A row patched in place (by a status poll or our own cancel) may not carry the reason yet;
        // never blank what is already known.
        if (row.cancel_reason != null) {
            reservationCancelReason = row.cancel_reason;
        }
        if (row.cancelled_by != null) {
            reservationCancelledBy = row.cancelled_by;
        }
        rememberActiveReservationStatus();

        boolean changed = !Objects.equals(previousStatus, reservationStatus)
                || !Objects.equals(previousReason, reservationCancelReason)
                || !Objects.equals(previousBy, reservationCancelledBy);
        if (changed) {
            renderReservationDetails(currentScreenView);
        }
    }

    /** GET /booking/reservations/{id}: the current status of a request, or of the booking it
     *  became once paid. Used when the request has dropped out of the live list. */
    private void fetchReservationStatus() {
        if (reservationAdvanceReservationId <= 0 || reservationStatusInFlight) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            return;
        }
        reservationStatusInFlight = true;
        ApiClient.bookingApi().getReservationStatus("Bearer " + token, reservationAdvanceReservationId)
                .enqueue(new Callback<ReservationStatusResponse>() {
                    @Override
                    public void onResponse(Call<ReservationStatusResponse> call, Response<ReservationStatusResponse> response) {
                        reservationStatusInFlight = false;
                        if (!isReservationDetailsVisible() || !response.isSuccessful()
                                || response.body() == null || response.body().status_detail == null) {
                            return;
                        }
                        ReservationStatusResponse body = response.body();
                        boolean changed = !body.status_detail.equals(reservationStatus);
                        reservationStatus = body.status_detail;
                        if (body.cancel_reason != null) {
                            reservationCancelReason = body.cancel_reason;
                        }
                        if (body.cancelled_by != null) {
                            reservationCancelledBy = body.cancelled_by;
                        }
                        rememberActiveReservationStatus();
                        if (changed) {
                            renderReservationDetails(currentScreenView);
                        }
                    }

                    @Override
                    public void onFailure(Call<ReservationStatusResponse> call, Throwable t) {
                        // Silently ignore — the screen keeps its last known state; the next poll retries.
                        reservationStatusInFlight = false;
                    }
                });
    }

    /**
     * Every Reserve submission (single-facility and multi-facility) ends here: the Payment
     * Reminder is shown first, and the "Reservation Request Submitted" screen only appears once
     * the guest has acknowledged it. Not cancelable, so the reminder can't be skipped by tapping
     * outside the dialog; Back is likewise absorbed by the dialog.
     */
    private void showReservationSubmitted() {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.reservation_payment_reminder_title)
                .setMessage(R.string.reservation_payment_reminder_message)
                .setCancelable(false)
                .setPositiveButton(R.string.button_ok, (d, which) -> showScreen(SCREEN_RESERVATION_CONFIRMED))
                .create();
        ThemeManager.applyGlassEffect(dialog.getWindow());
        dialog.show();
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
     * Cancel button, step 2: collects a required reason, then cancels the request on the backend
     * (see submitReservationCancellation). Only reachable while the backend reports the record as
     * an unpaid, pending request (canPayOrCancelReservation), so there is always a real request
     * to cancel.
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
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ClickGuard.wrap(view -> {
            String reason = reasonField.getText().toString().trim();
            if (reason.isEmpty()) {
                Toast.makeText(activity, R.string.toast_cancellation_reason_required, Toast.LENGTH_LONG).show();
                return;
            }
            submitReservationCancellation(dialog, reason);
        })));
        dialog.show();
    }

    /**
     * Submits the guest's own cancellation to the shared backend (same cancelReservation()
     * endpoint the website's guest self-serve cancel uses), then shows the status the backend
     * answers with (status_detail) — so it is reflected everywhere: Reservation Management's list,
     * the live lists here, and any later status refresh of this same reservation.
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
                            StatusDetail updated = response.body() != null ? response.body().status_detail : null;
                            if (updated != null) {
                                reservationStatus = updated;
                            }
                            reservationCancelReason = reason;
                            reservationCancelledBy = "guest";
                            rememberActiveReservationStatus();
                            // The lists show the new status at once, then refetch for the rest.
                            ReservationFeed.applyStatus(ReservationSummary.KIND_REQUEST,
                                    reservationAdvanceReservationId, updated);
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
     * Booking progress for Reservation Details: the backend's booking statuses in its own order
     * (GET /booking/statuses), with cancelled left out — a cancelled booking is shown by its status
     * banner, not as a step. Steps up to the booking's current status are filled, that one is the
     * current stage, the rest are still ahead. A reservation request has no progress to show.
     */
    private List<TimelineStep> buildReservationTimeline() {
        List<TimelineStep> steps = new ArrayList<>();
        StatusDetail status = reservationStatus;
        if (status == null || !StatusDetail.KIND_BOOKING.equals(status.kind)
                || status.hasKey(StatusDetail.KEY_CANCELLED)) {
            return steps;
        }
        List<StatusDetail> lifecycle = StatusCatalog.bookingProgress();
        int current = -1;
        for (int i = 0; i < lifecycle.size(); i++) {
            if (lifecycle.get(i).hasKey(status.key)) {
                current = i;
            }
        }
        if (current < 0) {
            return steps;
        }
        for (int i = 0; i < lifecycle.size(); i++) {
            steps.add(new TimelineStep(lifecycle.get(i),
                    i < current ? TL_FILLED : i == current ? TL_CURRENT : TL_UPCOMING));
        }
        return steps;
    }

    /** Renders buildReservationTimeline() into reservationDetailsTimelineContainer using the same
     *  dot/line row (view_reservation_timeline_row.xml) and coloring rules as bindBookingTimeline,
     *  plus a staggered fade/rise-in on each row so the progress reads as something that just
     *  advanced rather than a screen that was simply redrawn. Hidden when there is no progress. */
    private void bindReservationTimeline(View v) {
        LinearLayout container = v.findViewById(R.id.reservationDetailsTimelineContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        List<TimelineStep> steps = buildReservationTimeline();
        int visibility = steps.isEmpty() ? View.GONE : View.VISIBLE;
        v.findViewById(R.id.reservationDetailsTimelineLabel).setVisibility(visibility);
        container.setVisibility(visibility);

        for (int i = 0; i < steps.size(); i++) {
            TimelineStep step = steps.get(i);
            boolean isLast = i == steps.size() - 1;
            boolean filled = step.state != TL_UPCOMING;

            View row = inflater.inflate(R.layout.view_reservation_timeline_row, container, false);
            TextView label = row.findViewById(R.id.timelineLabel);
            label.setText(step.status.label);
            label.setTextColor(ThemeManager.color(activity, filled ? R.attr.textPrimary : R.attr.textMuted));
            label.setTypeface(null, step.state == TL_CURRENT ? Typeface.BOLD : Typeface.NORMAL);

            TextView meta = row.findViewById(R.id.timelineMeta);
            String metaText = step.status.definition != null ? step.status.definition : "";
            meta.setText(metaText);
            meta.setVisibility(metaText.isEmpty() ? View.GONE : View.VISIBLE);

            View dot = row.findViewById(R.id.timelineDot);
            dot.setBackgroundResource(filled ? R.drawable.bg_progress_dot_filled : R.drawable.bg_progress_dot_hollow);
            if (filled) {
                dot.setBackgroundTintList(ColorStateList.valueOf(StatusCatalog.color(activity, step.status)));
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
     * Reservation flow's own Payment screen (Pay Now on Reservation Details) — reuses the
     * Book flow's view_hotel_payment.xml (GCash QR + amount paid/reference/screenshot fields),
     * adding a Payment Option section the guest must pick from first — Full Payment or the 30%
     * Downpayment — which drives the Amount Due shown and the payment_option sent to the server.
     * Completing it submits the payment (see submitReservationPayment); the response says the
     * request is now a booking, and Reservation Details then shows the booking's status (from the
     * response's status_detail) with Pay Now and Cancel gone.
     */
    private void bindReservationPayment(View v) {
        bindHeader(v, R.id.hotelPaymentHeaderBar, R.string.hotel_payment_title, () -> showScreen(SCREEN_RESERVATION_DETAILS));
        paymentSummaryRefresh = null;
        ((TextView) v.findViewById(R.id.hotelPaymentSubtitle)).setText(R.string.reservation_payment_subtitle);

        int grandTotal = reservationSnapshotGrandTotal;
        int downPayment = computePartialAmount(grandTotal);
        v.findViewById(R.id.reservationPaymentOptionSection).setVisibility(View.VISIBLE);
        View fullCard = v.findViewById(R.id.reservationPayOptionFullCard);
        View downCard = v.findViewById(R.id.reservationPayOptionDownCard);
        ((TextView) v.findViewById(R.id.reservationPayOptionFullPrice)).setText(formatCurrency(grandTotal));
        ((TextView) v.findViewById(R.id.reservationPayOptionFullRemaining)).setText(remainingBalanceText(0));
        ((TextView) v.findViewById(R.id.reservationPayOptionDownPrice)).setText(formatCurrency(downPayment));
        ((TextView) v.findViewById(R.id.reservationPayOptionDownRemaining)).setText(
                remainingBalanceText(grandTotal - downPayment));

        TextView amountDueView = v.findViewById(R.id.paymentAmountDue);
        Runnable refreshPaymentOption = () -> {
            updatePaymentOptionCards(fullCard, downCard, reservationPaymentOption);
            bindReservationAmountDue(amountDueView, grandTotal, downPayment);
            if (paymentSummaryRefresh != null) {
                paymentSummaryRefresh.run();
            }
        };
        refreshPaymentOption.run();
        fullCard.setOnClickListener(view -> {
            reservationPaymentOption = PAYMENT_FULL;
            refreshPaymentOption.run();
        });
        downCard.setOnClickListener(view -> {
            reservationPaymentOption = PAYMENT_PARTIAL;
            refreshPaymentOption.run();
        });

        EditText amountPaidField = v.findViewById(R.id.paymentAmountPaidField);
        EditText referenceField = v.findViewById(R.id.paymentReferenceField);
        amountPaidField.setFilters(new InputFilter[]{AMOUNT_INPUT_FILTER});
        referenceField.setFilters(new InputFilter[]{DIGITS_ONLY_FILTER, new InputFilter.LengthFilter(REFERENCE_NUMBER_LENGTH)});
        amountPaidField.setText(amountPaidText);
        referenceField.setText(referenceNumberText);
        AuthUiUtils.afterTextChanged(amountPaidField, () -> amountPaidText = amountPaidField.getText().toString());
        wirePaymentSummary(v, grandTotal, () -> reservationPaymentOption == PAYMENT_PARTIAL ? downPayment : grandTotal);
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

        v.findViewById(R.id.paymentScreenshotDropzone).setOnClickListener(ClickGuard.wrap(view -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            activity.startActivityForResult(intent, REQUEST_PAYMENT_SCREENSHOT);
        }));
        if (screenshotUri != null) {
            showScreenshotPreview(v);
        }

        Button completeButton = v.findViewById(R.id.hotelPaymentCompleteButton);
        completeButton.setText(R.string.button_complete_reservation_payment);
        completeButton.setOnClickListener(ClickGuard.wrap(view -> {
            if (reservationPaymentOption == PAYMENT_NONE) {
                Toast.makeText(activity, R.string.toast_select_payment_option, Toast.LENGTH_LONG).show();
                return;
            }
            if (!validateAmountPaid(reservationPaymentOption == PAYMENT_PARTIAL ? downPayment : grandTotal)) {
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
            confirmOverpayment(v, reservationPaymentOption == PAYMENT_PARTIAL ? downPayment : grandTotal, () -> submitReservationPayment(v));
        }));
    }

    // ---- Amount paid: validation, overpayment confirmation, live summary -----------------------

    /**
     * Whether the selected payment method hands out cash change. Every Payment screen pays through
     * GCash, an online provider: only the amount it actually received is recorded, and an excess is
     * labelled "Excess payment" instead of cash "Change". Flip this when a cash method is added.
     */
    private static final boolean PAYMENT_METHOD_SUPPORTS_CASH = false;

    private static final double AMOUNT_TOLERANCE = 0.005;

    /** The Amount Paid field as a number, or -1 when it is empty or not a number. */
    private double enteredAmountPaid() {
        try {
            return Double.parseDouble(amountPaidText.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String formatCurrencyExact(double amount) {
        return activity.getString(R.string.format_currency, String.format(Locale.US, "%,.2f", amount));
    }

    /**
     * Complete-button check on the amount: it must be a real amount and at least the amount due
     * (the 30% down payment, or the full total). More than that is allowed, but only after
     * {@link #confirmOverpayment}.
     */
    private boolean validateAmountPaid(int amountDue) {
        double paid = enteredAmountPaid();
        if (paid <= 0) {
            Toast.makeText(activity, R.string.toast_amount_paid_required, Toast.LENGTH_LONG).show();
            return false;
        }
        if (paid + AMOUNT_TOLERANCE < amountDue) {
            Toast.makeText(activity, activity.getString(R.string.toast_amount_paid_too_low, formatCurrency(amountDue)),
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    /**
     * Runs {@code proceed} (the real submit) — straight away when the guest paid exactly the amount
     * due, otherwise after asking "greater than the required payment — proceed?". No closes the
     * dialog and puts the guest back in the Amount Paid field; nothing is submitted or recorded.
     */
    private void confirmOverpayment(View paymentView, int amountDue, Runnable proceed) {
        if (enteredAmountPaid() <= amountDue + AMOUNT_TOLERANCE) {
            proceed.run();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setMessage(R.string.dialog_overpayment_message)
                .setNegativeButton(R.string.button_no, (d, which) -> {
                    EditText field = paymentView.findViewById(R.id.paymentAmountPaidField);
                    field.requestFocus();
                    field.setSelection(field.getText().length());
                })
                .setPositiveButton(R.string.button_yes, (d, which) -> proceed.run())
                .create();
        ThemeManager.applyGlassEffect(dialog.getWindow());
        dialog.show();
    }

    /**
     * Live "what happens to this payment" card under the Amount Paid field: the part applied to the
     * booking (never more than the total), the balance left, and anything beyond the total shown in
     * green. A 30% down payment of 1,500 on 5,000 that is paid as 2,000 leaves 3,000, not 3,500.
     * {@code amountDue} is a supplier because the Reservation screen's due amount follows its
     * Full / 30% cards.
     */
    private void wirePaymentSummary(View v, int grandTotal, IntSupplier amountDue) {
        View card = v.findViewById(R.id.paymentSummaryCard);
        EditText amountPaidField = v.findViewById(R.id.paymentAmountPaidField);
        Runnable refresh = () -> {
            double paid = enteredAmountPaid();
            int due = amountDue.getAsInt();
            if (paid <= 0 || due <= 0 || paid + AMOUNT_TOLERANCE < due) {
                card.setVisibility(View.GONE);
                return;
            }
            double applied = Math.min(paid, grandTotal);
            double change = Math.max(paid - grandTotal, 0);
            ((TextView) v.findViewById(R.id.paymentSummaryApplied)).setText(formatCurrencyExact(applied));
            ((TextView) v.findViewById(R.id.paymentSummaryRemaining)).setText(formatCurrencyExact(grandTotal - applied));
            ((TextView) v.findViewById(R.id.paymentSummaryChangeLabel)).setText(
                    PAYMENT_METHOD_SUPPORTS_CASH ? R.string.label_change : R.string.label_excess_payment);
            ((TextView) v.findViewById(R.id.paymentSummaryChange)).setText(formatCurrencyExact(change));
            v.findViewById(R.id.paymentSummaryChangeRow).setVisibility(change > AMOUNT_TOLERANCE ? View.VISIBLE : View.GONE);
            card.setVisibility(View.VISIBLE);
        };
        paymentSummaryRefresh = refresh;
        AuthUiUtils.afterTextChanged(amountPaidField, refresh);
        refresh.run();
    }

    /** Sends what the guest actually paid; the server records it (capped at the total, the rest as
     *  change) and refuses anything below the amount due. */
    private void putAmountPaidPart(Map<String, RequestBody> target) {
        target.put("amount_paid", textPart(String.format(Locale.US, "%.2f", enteredAmountPaid())));
    }

    /** "Remaining Balance: PHP x" line shown under each Payment Option card. */
    private String remainingBalanceText(int remaining) {
        return activity.getString(R.string.label_remaining_balance) + ": " + formatCurrency(remaining);
    }

    /** Amount Due card on the Reservation Payment screen: the figure for whichever option is
     *  selected, or a muted prompt until the guest picks one. */
    private void bindReservationAmountDue(TextView amountDueView, int grandTotal, int downPayment) {
        if (reservationPaymentOption == PAYMENT_NONE) {
            amountDueView.setText(R.string.reservation_pay_amount_due_placeholder);
            amountDueView.setTextSize(14f);
            amountDueView.setTextColor(ThemeManager.color(activity, R.attr.textMuted));
            return;
        }
        amountDueView.setText(formatCurrency(reservationPaymentOption == PAYMENT_PARTIAL ? downPayment : grandTotal));
        amountDueView.setTextSize(26f);
        amountDueView.setTextColor(ThemeManager.color(activity, R.attr.textPrimary));
    }

    /**
     * Submits payment for the Pending reservation on the shared backend (reservationAdvanceReservationId),
     * promoting it into a real booking visible in Booking Management. Used for every category —
     * Hotel Rooms, Cottage, KTV and multi-facility — since the server's payment endpoint works on any
     * reservation by ID. Sends whichever of Full Payment / 30% Downpayment the guest chose on
     * bindReservationPayment's Payment Option cards (the server computes the amount due from it).
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
        putAmountPaidPart(quantities);

        ApiClient.bookingApi().payReservation(
                "Bearer " + token,
                reservationAdvanceReservationId,
                textPart(reservationPaymentOption == PAYMENT_PARTIAL ? "partial" : "full"),
                textPart(referenceNumberText),
                proofPart,
                quantities
        ).enqueue(new Callback<ReserveResponse>() {
            @Override
            public void onResponse(Call<ReserveResponse> call, Response<ReserveResponse> response) {
                completeButton.setEnabled(true);
                if (response.isSuccessful() && response.body() != null) {
                    ReserveResponse paid = response.body();
                    // The pay response's status is "pending" now; whether the payment went through
                    // is the payment_submitted flag, and what the record is now (a booking) is in
                    // status_detail.
                    boolean submitted = paid.payment_submitted || (paid.status_detail != null
                            && StatusDetail.KIND_BOOKING.equals(paid.status_detail.kind));
                    if (!submitted) {
                        Toast.makeText(activity, R.string.toast_booking_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    applyPaymentResponse(paid);
                    showScreen(SCREEN_RESERVATION_PAYMENT_CONFIRMED);
                } else if (response.code() == 401) {
                    Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                } else {
                    String message = submitFailureMessage(response);
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

    /** A paid request is a booking now: take its status (pending, as a booking) and its booking
     *  reference from the response, and let the lists refetch — the request leaves the
     *  reservations list and the booking appears in the bookings list. */
    private void applyPaymentResponse(ReserveResponse paid) {
        if (paid.status_detail != null) {
            reservationStatus = paid.status_detail;
        }
        if (paid.booking_reference != null && !paid.booking_reference.isEmpty()) {
            reservationReferenceNo = paid.booking_reference;
        }
        // The request's id no longer identifies a row in the feed (a booking has its own); the
        // booking is found by its reference until the next feed update supplies its id.
        reservationFeedKind = ReservationSummary.KIND_BOOKING;
        reservationRecordId = -1;
        rememberActiveReservationStatus();
        ReservationFeed.refresh();
    }

    private void bindReservationPaymentConfirmed(View v) {
        v.findViewById(R.id.reservationPaymentConfirmedReceiptButton).setOnClickListener(ClickGuard.wrap(view ->
                showScreen(SCREEN_RESERVATION_DETAILS)));
        v.findViewById(R.id.reservationPaymentConfirmedHomeButton).setOnClickListener(ClickGuard.wrap(view -> {
            resetFlow();
            onBackToHome.run();
        }));
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
    /** Extra-person charge for one KTV unit: guests beyond its base capacity, each at the Type's
     *  extra-person price (configured in KTV Room Management). */
    private int ktvExtraCharge(CottageKtvOption item) {
        if (item.unit_id <= 0) {
            return 0;
        }
        int extraGuests = Math.max(0, ktvGuestCount - item.base_capacity);
        return (int) Math.round(extraGuests * item.extra_person_price);
    }

    /** " · +N extra guest/s: PHP X" for a unit whose base capacity is exceeded, else "". */
    private String ktvExtraNote(CottageKtvOption item) {
        int charge = ktvExtraCharge(item);
        if (charge <= 0) {
            return "";
        }
        return " · " + activity.getString(R.string.format_ktv_extra_guests,
                Math.max(0, ktvGuestCount - item.base_capacity), formatMoney(charge));
    }

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
        ((TextView) card.findViewById(R.id.itemAvailableBadge)).setText(unitLabelRes == R.string.unit_label_room
                ? activity.getResources().getQuantityString(R.plurals.format_rooms_available, availableQty, availableQty)
                : unitLabelRes == R.string.unit_label_cottage
                ? activity.getResources().getQuantityString(R.plurals.format_cottages_available, availableQty, availableQty)
                : activity.getString(R.string.format_available_units, availableQty, activity.getString(unitLabelRes)));
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

    // Capacity (total = capacity x quantity picked, see RoomSelectionRules / FacilitySelectionRules)
    // never blocks a booking for Hotel Rooms, Cottage or KTV: the party is compared with it only to
    // show an informational notice (Number of Guests and Review Your Selection).

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
                () -> showScreen(SCREEN_ROOM_GUESTS));

        bindRow(v.findViewById(R.id.reviewRowCheckIn), R.string.label_stay_check_in, formatDate(checkInMillis));
        bindRow(v.findViewById(R.id.reviewRowCheckOut), R.string.label_stay_check_out, formatDate(checkOutMillis));
        bindRow(v.findViewById(R.id.reviewRowGuests), R.string.label_stay_guests, formatGuests());
        bindRow(v.findViewById(R.id.reviewRowNights), R.string.label_stay_nights, formatNights(nights()));

        LinearLayout container = v.findViewById(R.id.reviewRoomsContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        int nights = nights();
        for (RoomTypeAvailability room : availableRoomTypes) {
            int qty = RoomSelectionRules.quantityOf(roomQuantities, room.group_id);
            if (qty <= 0) {
                continue;
            }
            int pricePerNight = RoomSelectionRules.pricePerNight(room);
            View row = inflater.inflate(R.layout.view_hotel_review_room_row, container, false);
            loadImage(row.findViewById(R.id.reviewRoomImage), room.image_path, R.drawable.ic_bed);
            ((TextView) row.findViewById(R.id.reviewRoomName)).setText(room.category_name + " · " + room.variant_name);
            ((TextView) row.findViewById(R.id.reviewRoomMeta))
                    .setText(activity.getString(R.string.format_capacity, room.capacity));
            ((TextView) row.findViewById(R.id.reviewRoomPrice))
                    .setText(activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)));
            ((TextView) row.findViewById(R.id.reviewRoomQtyNights)).setText(
                    activity.getString(R.string.format_quantity, qty) + " · " + formatNights(nights));
            int subtotal = RoomSelectionRules.roomSubtotal(room, qty, nights);
            ((TextView) row.findViewById(R.id.reviewRoomSubtotal))
                    .setText(activity.getString(R.string.format_subtotal, formatMoney(subtotal)));
            container.addView(row);
        }

        // Total booking amount = the sum of the room subtotals above (amenities come on later screens).
        ((TextView) v.findViewById(R.id.reviewTotalAmount)).setText(
                formatCurrency(RoomSelectionRules.roomsTotal(availableRoomTypes, roomQuantities, nights)));
        // Informational only: the booking carries on either way.
        bindReviewCapacityNotice(v, FAC_ROOM);

        EditText specialRequestField = v.findViewById(R.id.reviewSpecialRequestField);
        specialRequestField.setText(specialRequest);
        AuthUiUtils.afterTextChanged(specialRequestField,
                () -> specialRequest = specialRequestField.getText().toString());

        v.findViewById(R.id.hotelReviewNextButton).setOnClickListener(ClickGuard.wrap(view -> {
            activeContinuation = CONTINUATION_NONE;
            // reserveMode is already set correctly by showRoomDatesEntry right before this
            // screen, whether entered from a scoped Room Overview (selectionOriginVariantId >= 0)
            // or the Book/Reserve tabs' picker (selectionOriginVariantId == -1, no pre-chosen
            // room) — every entry point sets it explicitly now, so nothing here should touch it.
            fetchAmenities(v);
        }));
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

        requestAmenities("Bearer " + token).enqueue(new Callback<AmenityListResponse>() {
            @Override
            public void onResponse(Call<AmenityListResponse> call, Response<AmenityListResponse> response) {
                isLoadingAmenities = false;
                nextButton.setEnabled(true);
                nextButton.setText(R.string.button_next);

                if (response.isSuccessful() && response.body() != null) {
                    boolean lowered = applyAmenityList(response.body().amenities);
                    amenitiesFreshForNextShow = true;
                    showScreen(SCREEN_AMENITY);
                    if (lowered) {
                        Toast.makeText(activity, R.string.toast_availability_changed, Toast.LENGTH_LONG).show();
                    }
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
            // The backend's ready-made stock text ("5 available" / "Unlimited" / "Out of Stock")
            // sits under the name.
            TextView stockText = card.findViewById(R.id.stepperItemDescription);
            String stockLabel = amenity.stock_label;
            if (amenity.out_of_stock && (stockLabel == null || stockLabel.trim().isEmpty())) {
                stockLabel = activity.getString(R.string.amenity_out_of_stock);
            }
            if (stockLabel == null || stockLabel.trim().isEmpty()) {
                stockText.setVisibility(View.GONE);
            } else {
                stockText.setText(stockLabel);
                stockText.setVisibility(View.VISIBLE);
                if (amenity.out_of_stock) {
                    stockText.setTextColor(ThemeManager.color(activity, R.attr.errorText));
                    stockText.setTypeface(stockText.getTypeface(), Typeface.BOLD);
                }
            }
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

        v.findViewById(R.id.hotelAmenitySkipButton).setOnClickListener(ClickGuard.wrap(view -> finishAmenityStep(view)));
        v.findViewById(R.id.hotelAmenityNextButton).setOnClickListener(ClickGuard.wrap(view -> {
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
        }));
    }

    /**
     * Skip/Next on Choose Amenity. In a single-facility flow this is where the guest is asked
     * whether to also add a later-ordered facility (see {@link AddOnFacilityPrompt}): Yes turns
     * the run into a multi-facility one — the current facility becomes the queue's first, its
     * date and quantities carried over, and multiAdvance moves on to the first added facility —
     * while No (or nothing to offer) goes straight on to Billing/Summary as before.
     */
    private void finishAmenityStep(View sourceButton) {
        if (activePackage != null) {
            showScreen(SCREEN_PACKAGE_BILLING);
            return;
        }
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
            multiAdvance();
        });
    }

    /**
     * The Admin Amenity Management facility type the Choose Amenity list must match: the
     * backend returns only amenities tagged for it (or untagged ones). Amenities in a
     * multi-facility run belong to the Hotel Room booking, so that is always "room".
     */
    private String amenityFacilityType() {
        if (activePackage != null) {
            return activePackage.includes_room ? "room" : "cottage";
        }
        if (!multiMode) {
            if (activeContinuation == CONTINUATION_COTTAGE) {
                return "cottage";
            }
            if (activeContinuation == CONTINUATION_KTV) {
                return "ktv_room";
            }
        }
        return "room";
    }

    private boolean amenityCanIncrement(AmenityAvailability amenity, int currentQty) {
        return currentQty < amenity.pickerMax();
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
        bindPromoCoupons(v);
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

        bindCouponRow(v.findViewById(R.id.billingRowCoupon));
        int grandTotal = Math.max(0, roomTotal + amenitiesTotal - couponDiscount());
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

        bindPolicyAgreement(v, R.id.hotelBillingProceedButton);

        v.findViewById(R.id.hotelBillingProceedButton).setOnClickListener(ClickGuard.wrap(view -> {
            if (paymentOption == PAYMENT_NONE) {
                Toast.makeText(activity, R.string.toast_select_payment_option, Toast.LENGTH_LONG).show();
                return;
            }
            if (!termsChecked || !cancellationChecked) {
                Toast.makeText(activity, R.string.toast_agree_terms_required, Toast.LENGTH_LONG).show();
                return;
            }
            showScreen(SCREEN_PAYMENT);
        }));
    }

    private void updatePaymentOptionCards(View fullCard, View partialCard) {
        updatePaymentOptionCards(fullCard, partialCard, paymentOption);
    }

    private void updatePaymentOptionCards(View fullCard, View partialCard, int selectedOption) {
        fullCard.setBackgroundResource(selectedOption == PAYMENT_FULL
                ? R.drawable.bg_selectable_card_selected : R.drawable.bg_selectable_card);
        partialCard.setBackgroundResource(selectedOption == PAYMENT_PARTIAL
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
        wirePaymentSummary(v, grandTotal, () -> amountDue);
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

        v.findViewById(R.id.paymentScreenshotDropzone).setOnClickListener(ClickGuard.wrap(view -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            activity.startActivityForResult(intent, REQUEST_PAYMENT_SCREENSHOT);
        }));
        if (screenshotUri != null) {
            showScreenshotPreview(v);
        }

        v.findViewById(R.id.hotelPaymentCompleteButton).setOnClickListener(ClickGuard.wrap(view -> {
            if (!validateAmountPaid(amountDue)) {
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
            confirmOverpayment(v, amountDue, () -> submitReservation(v));
        }));
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
        putAmountPaidPart(quantities);
        putQuantityParts(quantities, "amenity_quantities", amenityQuantities);
        putCouponPart(quantities);

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
                    // A new booking now exists on the backend: the live lists refetch so it is in
                    // My Bookings (with its status) by the time the guest opens them.
                    ReservationFeed.refresh();
                    showScreen(SCREEN_SUCCESS);
                } else if (response.code() == 401) {
                    Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                } else {
                    String message = submitFailureMessage(response);
                    recheckCouponsAfterFailure();
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
        v.findViewById(R.id.successViewBookingButton).setOnClickListener(ClickGuard.wrap(view -> {
            if (onViewBooking == null) {
                return;
            }
            // Same reset-then-leave pairing as Back to Home; capture the reference first since
            // resetFlow() clears it.
            String reference = bookingReference;
            resetFlow();
            onViewBooking.accept(reference);
        }));
        v.findViewById(R.id.successBackToHomeButton).setOnClickListener(ClickGuard.wrap(view -> {
            resetFlow();
            onBackToHome.run();
        }));
    }

    /** One row in Reservation Details' booking progress — see buildReservationTimeline. */
    private static final class TimelineStep {
        final StatusDetail status;
        final int state;

        TimelineStep(StatusDetail status, int state) {
            this.status = status;
            this.state = state;
        }
    }

    void resetFlow() {
        if (liveSync != null) {
            liveSync.stop();
        }
        liveSyncInFlight = false;
        reservationDetailsExternalBack = null;
        checkInMillis = -1;
        checkOutMillis = -1;
        adults = 0;
        children = 0;
        selectionOriginVariantId = -1;
        onBackToOverview = null;
        activeContinuation = CONTINUATION_NONE;
        reserveMode = false;
        multiMode = false;
        activePackage = null;
        packageDateMillis = -1;
        packageAdults = 1;
        packageChildren = 0;
        isCheckingPackage = false;
        multiQueue.clear();
        multiIndex = 0;
        multiQuantities.clear();
        cottageRateType = ReservationCatalog.RATE_TYPE_DAY;
        cottageDateMillis = -1;
        cottageAdults = 1;
        cottageChildren = 0;
        ktvDateMillis = -1;
        ktvStartHour = -1;
        ktvStartMinute = -1;
        ktvGuestCount = 1;
        ktvAdults = 1;
        ktvChildren = 0;
        ktvDurationHours = 0;
        resetFacilitySelection();
        resetRoomSelection();
        specialRequest = "";
        availableAmenities = new ArrayList<>();
        amenityQuantities.clear();
        appliedCoupon = null;
        couponPendingId = 0;
        eligibleCoupons = new ArrayList<>();
        couponQueryKey = null;
        couponFetchKey = null;
        couponsFailed = false;
        couponsLoading = false;
        paymentOption = PAYMENT_NONE;
        termsChecked = false;
        cancellationChecked = false;
        amountPaidText = "";
        referenceNumberText = "";
        screenshotUri = null;
        // Deliberately NOT reset: bookingReference, reservationReferenceNo,
        // reservationAdvanceReservationId, reservationStatus, reservationFeedKind,
        // reservationRecordId, reservationCancelReason,
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
     * Next on a facility's last screen in the multi-facility flow (Number of Guests for the first
     * facility, its own dates screen for a later one): remembers its quantities, then moves on to
     * the next facility with the already-entered date and party carried over, showing that
     * facility's own dates/schedule screen (which lists what is free and asks for the Cottage Use or
     * KTV start time) — or, after the last facility, to the combined Review.
     */
    private void multiAdvance() {
        saveMultiQuantities();
        if (multiIndex + 1 >= multiQueue.size()) {
            showScreen(SCREEN_MULTI_REVIEW);
            return;
        }
        int next = multiQueue.get(multiIndex + 1);
        multiIndex++;
        selectionOriginVariantId = -1;
        // What the guest picked for this facility on an earlier visit comes back with it; the list
        // then lowers or drops whatever is no longer free.
        Map<Integer, Integer> saved = multiQuantities.get(next);
        roomQuantities.clear();
        if (saved != null) {
            roomQuantities.putAll(saved);
        }
        if (next == FAC_COTTAGE) {
            cottageDateMillis = multiAnchorDateMillis();
            cottageAdults = adults > 0 ? adults : 1;
            cottageChildren = children;
            showScreen(SCREEN_COTTAGE_DATES);
            return;
        }
        ktvDateMillis = multiAnchorDateMillis();
        ktvGuestCount = Math.max(1, multiAnchorGuestCount());
        showScreen(SCREEN_KTV_DATES);
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
                    formatHourMinute(ktvStartHour, ktvStartMinute), formatKtvEndTime(), ktvDurationLabel(),
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
                int unit = (int) Math.round(resolvedItemPrice(item)) + (facility == FAC_KTV ? ktvExtraCharge(item) : 0);
                total += unit * multiQty(facility, item.group_id);
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
        return Math.max(0, multiFacilitiesTotal() + multiAmenityTotal() - couponDiscount());
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
            int baseUnit = (int) Math.round(resolvedItemPrice(item));
            int unitPrice = baseUnit + (facility == FAC_KTV ? ktvExtraCharge(item) : 0);
            addLineRow(container, inflater, formatShowcaseName(item.variant_name, null),
                    activity.getString(facility == FAC_COTTAGE ? R.string.cottage_capacity_format
                            : R.string.format_capacity, item.capacity) + " · Qty " + qty + " · "
                            + activity.getString(R.string.format_price_with_unit, formatMoney(baseUnit),
                            resolvedItemPriceUnit(item)) + (facility == FAC_KTV ? ktvExtraNote(item) : ""),
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

        v.findViewById(R.id.hotelReviewNextButton).setOnClickListener(ClickGuard.wrap(view -> {
            if (multiQueue.contains(FAC_ROOM)) {
                fetchAmenities(v);
            } else {
                showScreen(billingScreenForContinuation());
            }
        }));
    }

    /** Combined Book-flow Billing Summary: one Accommodation card holding every facility's own
     *  section, one set of totals and one payment option for the whole transaction. */
    private void bindMultiBilling(View v) {
        bindPromoCoupons(v);
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
        bindCouponRow(v.findViewById(R.id.billingRowCoupon));

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

        bindPolicyAgreement(v, R.id.hotelBillingProceedButton);

        v.findViewById(R.id.hotelBillingProceedButton).setOnClickListener(ClickGuard.wrap(view -> {
            if (paymentOption == PAYMENT_NONE) {
                Toast.makeText(activity, R.string.toast_select_payment_option, Toast.LENGTH_LONG).show();
                return;
            }
            if (!termsChecked || !cancellationChecked) {
                Toast.makeText(activity, R.string.toast_agree_terms_required, Toast.LENGTH_LONG).show();
                return;
            }
            showScreen(SCREEN_MULTI_PAYMENT);
        }));
    }

    /**
     * Combined Book-flow Payment screen. Field validation matches every other Payment screen;
     * Complete Booking submits the whole selection in one request (see submitMultiBooking).
     */
    private void bindMultiPayment(View v) {
        bindHeader(v, R.id.hotelPaymentHeaderBar, R.string.hotel_payment_title, this::handleBackPressed);

        int grandTotal = activePackage != null ? packageGrandTotal() : multiGrandTotal();
        int amountDue = paymentOption == PAYMENT_PARTIAL ? computePartialAmount(grandTotal) : grandTotal;
        ((TextView) v.findViewById(R.id.paymentAmountDue)).setText(formatCurrency(amountDue));

        EditText amountPaidField = v.findViewById(R.id.paymentAmountPaidField);
        EditText referenceField = v.findViewById(R.id.paymentReferenceField);
        amountPaidField.setFilters(new InputFilter[]{AMOUNT_INPUT_FILTER});
        referenceField.setFilters(new InputFilter[]{DIGITS_ONLY_FILTER, new InputFilter.LengthFilter(REFERENCE_NUMBER_LENGTH)});
        amountPaidField.setText(amountPaidText);
        referenceField.setText(referenceNumberText);
        AuthUiUtils.afterTextChanged(amountPaidField, () -> amountPaidText = amountPaidField.getText().toString());
        wirePaymentSummary(v, grandTotal, () -> amountDue);
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

        v.findViewById(R.id.paymentScreenshotDropzone).setOnClickListener(ClickGuard.wrap(view -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            activity.startActivityForResult(intent, REQUEST_PAYMENT_SCREENSHOT);
        }));
        if (screenshotUri != null) {
            showScreenshotPreview(v);
        }

        v.findViewById(R.id.hotelPaymentCompleteButton).setOnClickListener(ClickGuard.wrap(view -> {
            if (!validateAmountPaid(amountDue)) {
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
            confirmOverpayment(v, amountDue, () -> {
                if (activePackage != null) {
                    submitPackageBooking(v);
                } else {
                    submitMultiBooking(v);
                }
            });
        }));
    }

    /** Combined Reserve-flow Summary: every facility in one list, one total, one RESERVE button. */
    private void bindMultiSummary(View v) {
        bindPromoCoupons(v);
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
        bindCouponRow(v.findViewById(R.id.reservationSummaryCouponRow));
        bindLine(v.findViewById(R.id.reservationSummaryFullPaymentRow), R.string.label_full_payment, grandTotal);
        bindLine(v.findViewById(R.id.reservationSummaryDownPaymentRow), R.string.label_down_payment_30,
                computePartialAmount(grandTotal));

        bindPolicyAgreement(v, R.id.reservationSummaryReserveButton);

        v.findViewById(R.id.reservationSummaryReserveButton).setOnClickListener(ClickGuard.wrap(view -> submitMultiReservation(v)));
    }

    // ---- Promo Coupons -------------------------------------------------------

    /** Pesos the applied Promo Coupon takes off (0 when none) — subtracted in every grand total,
     *  so Billing, Reservation Summary, the 30% Down Payment, Remaining Balance and the Payment
     *  screens all move together. */
    private int couponDiscount() {
        return appliedCoupon == null ? 0 : (int) Math.round(appliedCoupon.discount_amount);
    }

    /** The fields the matching submit would send (minus anything personal), so the server judges
     *  the coupons on exactly what is being booked. */
    private Map<String, String> couponQuoteFields() {
        Map<String, String> fields;
        if (multiMode) {
            fields = multiFacilityFields();
        } else {
            int facility = activeContinuation == CONTINUATION_COTTAGE ? FAC_COTTAGE
                    : activeContinuation == CONTINUATION_KTV ? FAC_KTV : FAC_ROOM;
            fields = singleFacilityFields(facility);
        }
        fields.remove("special_request");
        return fields;
    }

    /**
     * The "Promo Coupons" card above Payment Summary. Keeps the coupon state in step with the
     * booking (fetching the eligible coupons from the Promo Management module whenever the booking
     * differs from the one last checked) and shows its status; tapping opens the picker. Call it
     * first in a Billing / Reservation Summary binder, before any total is computed, so a
     * discount that no longer applies never reaches the numbers.
     */
    private void bindPromoCoupons(View v) {
        String key = couponQuoteFields().toString();
        // couponFetchKey stays on the last request's booking even after it finished or failed, so a
        // failed lookup is not retried on every rebind — only when the booking changes or the guest
        // taps the card to retry.
        if (!key.equals(couponFetchKey)) {
            // A different booking than the coupons were fetched for: hold the applied coupon back
            // until the server confirms it still applies to this one.
            if (appliedCoupon != null) {
                couponPendingId = appliedCoupon.promo_id;
                appliedCoupon = null;
            }
            eligibleCoupons = new ArrayList<>();
            couponsFailed = false;
            fetchPromoCoupons(v, key);
        }

        View card = v.findViewById(R.id.promoCouponCard);
        TextView subtitle = v.findViewById(R.id.promoCouponSubtitle);
        if (card == null || subtitle == null) {
            return;
        }
        if (appliedCoupon != null) {
            subtitle.setText(activity.getString(R.string.promo_coupons_applied, appliedCoupon.name, appliedCoupon.label));
        } else if (couponsLoading) {
            subtitle.setText(R.string.promo_coupons_checking);
        } else if (couponsFailed) {
            subtitle.setText(R.string.promo_coupons_failed);
        } else if (eligibleCoupons.isEmpty()) {
            subtitle.setText(R.string.promo_coupons_none);
        } else {
            subtitle.setText(activity.getResources().getQuantityString(
                    R.plurals.promo_coupons_available, eligibleCoupons.size(), eligibleCoupons.size()));
        }

        card.setOnClickListener(ClickGuard.wrap(view -> {
            if (couponsLoading) {
                return;
            }
            if (couponsFailed) {
                couponsFailed = false;
                couponQueryKey = null;
                couponFetchKey = null;
                bindScreen(currentScreen, v);
                return;
            }
            PromoCouponPicker.show(activity, eligibleCoupons, appliedCoupon, coupon -> {
                appliedCoupon = coupon;
                couponPendingId = 0;
                bindScreen(currentScreen, v);
            });
        }));
    }

    private String couponFetchKey = null;
    private boolean couponsFailed = false;

    private void fetchPromoCoupons(View screenView, String key) {
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            return;
        }
        couponsLoading = true;
        couponFetchKey = key;
        ApiClient.bookingApi().getPromoCoupons("Bearer " + token, couponQuoteFields())
                .enqueue(new Callback<PromoCouponListResponse>() {
                    @Override
                    public void onResponse(Call<PromoCouponListResponse> call, Response<PromoCouponListResponse> response) {
                        if (!key.equals(couponFetchKey)) {
                            return; // a newer booking superseded this request
                        }
                        couponsLoading = false;
                        if (response.isSuccessful() && response.body() != null) {
                            eligibleCoupons = response.body().coupons != null
                                    ? response.body().coupons : new ArrayList<>();
                            couponQueryKey = key;
                            couponsFailed = false;
                            // Restore the coupon the guest had applied, refreshed with this
                            // booking's own discount, if it still applies.
                            int wanted = couponPendingId;
                            couponPendingId = 0;
                            appliedCoupon = null;
                            for (PromoCoupon coupon : eligibleCoupons) {
                                if (coupon.promo_id == wanted) {
                                    appliedCoupon = coupon;
                                }
                            }
                        } else {
                            eligibleCoupons = new ArrayList<>();
                            couponsFailed = true;
                        }
                        rebindIfStillShown(screenView);
                    }

                    @Override
                    public void onFailure(Call<PromoCouponListResponse> call, Throwable t) {
                        if (!key.equals(couponFetchKey)) {
                            return;
                        }
                        couponsLoading = false;
                        couponsFailed = true;
                        rebindIfStillShown(screenView);
                    }
                });
    }

    private void rebindIfStillShown(View screenView) {
        if (currentScreenView == screenView) {
            bindScreen(currentScreen, screenView);
        }
    }

    /** After a submit failed while a coupon was applied (the server re-validates it and may
     *  reject it): re-check the coupons so a coupon that stopped applying drops off the screen
     *  and the totals. */
    private void recheckCouponsAfterFailure() {
        if (appliedCoupon == null) {
            return;
        }
        couponQueryKey = null;
        couponFetchKey = null;
        // A Reserve fails on the Summary screen itself, so refresh it in place; a Book fails on the
        // Payment screen, where the next visit to Billing re-checks the coupon before showing it.
        if (currentScreenView != null
                && (currentScreen == SCREEN_RESERVATION_SUMMARY || currentScreen == SCREEN_MULTI_SUMMARY)) {
            bindScreen(currentScreen, currentScreenView);
        }
    }

    /** Pay-as-shown line in a Payment Summary: "Promo Coupon (₱100 OFF)  -₱100", hidden when no
     *  coupon is applied. */
    private void bindCouponRow(View row) {
        if (row == null) {
            return;
        }
        if (appliedCoupon == null) {
            row.setVisibility(View.GONE);
            return;
        }
        row.setVisibility(View.VISIBLE);
        ((TextView) row.findViewById(R.id.lineName))
                .setText(activity.getString(R.string.label_promo_coupon_discount, appliedCoupon.label));
        ((TextView) row.findViewById(R.id.linePrice)).setText("-" + formatCurrency(couponDiscount()));
    }

    /** Sends the applied coupon with a submit; the server re-validates it before saving. */
    private void putCouponField(Map<String, String> fields) {
        if (appliedCoupon != null) {
            fields.put("promo_id", String.valueOf(appliedCoupon.promo_id));
        }
    }

    private Map<String, String> couponFields(Map<String, String> fields) {
        putCouponField(fields);
        return fields;
    }

    /** Adds the amenities picked on Choose Amenity (loaded from the live booking/amenities list) to a
     *  Reserve request as "amenity_quantities[id]" fields, so they are saved with the reservation
     *  itself and counted in its total, not only held in the app until the guest pays. */
    private Map<String, String> withAmenityFields(Map<String, String> fields) {
        fields.putAll(quantityFieldMap("amenity_quantities", amenityQuantities));
        return fields;
    }

    private void putCouponPart(Map<String, RequestBody> parts) {
        if (appliedCoupon != null) {
            parts.put("promo_id", textPart(String.valueOf(appliedCoupon.promo_id)));
        }
    }

    /**
     * Wires the Terms and Conditions + Cancellation Policy checkboxes of a Billing or
     * Reservation Summary screen to its action button: the button stays disabled (greyed, by
     * bg_button_primary's disabled state) until both boxes are ticked, and re-evaluates on
     * every tick/untick. The ticks live in termsChecked/cancellationChecked so they survive
     * stepping back and forward between screens.
     */
    private void bindPolicyAgreement(View v, int actionButtonId) {
        CheckBox termsBox = v.findViewById(R.id.checkboxTerms);
        CheckBox cancellationBox = v.findViewById(R.id.checkboxCancellation);
        Button actionButton = v.findViewById(actionButtonId);
        Runnable refresh = () -> actionButton.setEnabled(termsChecked && cancellationChecked);

        termsBox.setChecked(termsChecked);
        cancellationBox.setChecked(cancellationChecked);
        termsBox.setOnCheckedChangeListener((button, checked) -> {
            termsChecked = checked;
            refresh.run();
        });
        cancellationBox.setOnCheckedChangeListener((button, checked) -> {
            cancellationChecked = checked;
            refresh.run();
        });
        refresh.run();
    }

    /** Adds one facility's block to a multi-facility request, as bracket-notation keys
     *  ("hotel[check_in]", "cottage[cottage_quantities][7]", ...). */
    private Map<String, String> multiFacilityFields() {
        return facilityFields(multiQueue, multiQuantities::get);
    }

    /** A single Cottage or KTV Room submission, sent through the same multi-facility endpoints
     *  with exactly one facility block (the server accepts a lone block) — so it creates a real
     *  record the receptionist sees, with the quantities from the on-screen selection. */
    private Map<String, String> singleFacilityFields(int facility) {
        return facilityFields(java.util.Collections.singletonList(facility), f -> roomQuantities);
    }

    private Map<String, String> facilityFields(List<Integer> facilities,
                                               java.util.function.Function<Integer, Map<Integer, Integer>> quantitiesFor) {
        Map<String, String> fields = new LinkedHashMap<>();
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        fields.put("multiple_facilities", "1");
        fields.put("special_request", specialRequest);
        for (int facility : facilities) {
            if (facility == FAC_ROOM) {
                fields.put("hotel[check_in]", day.format(new Date(checkInMillis)));
                fields.put("hotel[check_out]", day.format(new Date(checkOutMillis)));
                fields.put("hotel[adults]", String.valueOf(adults));
                fields.put("hotel[children]", String.valueOf(children));
                putNestedQuantities(fields, "hotel[room_quantities]", quantitiesFor.apply(FAC_ROOM));
            } else if (facility == FAC_COTTAGE) {
                fields.put("cottage[date]", day.format(new Date(cottageDateMillis)));
                fields.put("cottage[use]", cottageUseKey());
                fields.put("cottage[adults]", String.valueOf(cottageAdults));
                fields.put("cottage[children]", String.valueOf(cottageChildren));
                putNestedQuantities(fields, "cottage[cottage_quantities]", quantitiesFor.apply(FAC_COTTAGE));
            } else {
                fields.put("ktv[event_date]", day.format(new Date(ktvDateMillis)));
                fields.put("ktv[start_time]", String.format(Locale.US, "%02d:%02d", ktvStartHour, ktvStartMinute));
                fields.put("ktv[guest_count]", String.valueOf(ktvGuestCount));
                fields.put("ktv[duration_hours]", String.valueOf(ktvDurationHours));
                putKtvSelection(fields, quantitiesFor.apply(FAC_KTV));
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
        submitFacilityReservation(summaryView, multiFacilityFields(), true);
    }

    /** Reserve for a facility-only (Cottage/KTV) flow: a real Pending reservation on the server,
     *  keeping the facility's own continuation so its details/summary screens still render. */
    private void submitSingleFacilityReservation(View summaryView, int facility) {
        submitFacilityReservation(summaryView, singleFacilityFields(facility), false);
    }

    private void submitFacilityReservation(View summaryView, Map<String, String> fields, boolean multi) {
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }
        Button reserveButton = summaryView.findViewById(R.id.reservationSummaryReserveButton);
        reserveButton.setEnabled(false);

        // Amenities are part of the reservation. In the multi-facility flow they belong to the Hotel
        // Rooms booking, so they only go when Hotel Rooms are included (as the Summary lists them).
        if (!multi || multiQueue.contains(FAC_ROOM)) {
            withAmenityFields(fields);
        }
        putCouponField(fields);
        ApiClient.bookingApi().createMultiReservation("Bearer " + token, fields)
                .enqueue(new Callback<ReservationRequestResponse>() {
                    @Override
                    public void onResponse(Call<ReservationRequestResponse> call, Response<ReservationRequestResponse> response) {
                        reserveButton.setEnabled(true);
                        if (response.isSuccessful() && response.body() != null) {
                            trackNewReservation(response.body().advance_reservation_id,
                                    response.body().booking_reference, response.body().status_detail);
                            finishMultiReservation(multi);
                        } else if (response.code() == 401) {
                            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                        } else {
                            String message = submitFailureMessage(response);
                            recheckCouponsAfterFailure();
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

    private void finishMultiReservation(boolean multi) {
        if (multi) {
            activeContinuation = CONTINUATION_NONE;
        }
        snapshotReservationDisplayData();
        multiMode = false;
        showReservationSubmitted();
    }

    /**
     * Complete Booking on the multi-facility Payment screen: one multipart POST booking/reserve
     * with multiple_facilities set, a block per selected facility, and the usual payment fields.
     * Amenities (Hotel Rooms only) go in the same request as amenity_quantities.
     */
    private void submitMultiBooking(View paymentView) {
        submitFacilityBooking(paymentView, multiFacilityFields(), multiQueue.contains(FAC_ROOM));
    }

    /** Complete Booking for a facility-only (Cottage/KTV) flow: the same multipart booking
     *  request as the multi-facility Book, with that one facility's block. Amenities chosen on
     *  the shared Amenity step ride along. */
    private void submitSingleFacilityBooking(View paymentView, int facility) {
        submitFacilityBooking(paymentView, singleFacilityFields(facility), true);
    }

    private void submitFacilityBooking(View paymentView, Map<String, String> fieldSource, boolean includeAmenities) {
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
        for (Map.Entry<String, String> entry : fieldSource.entrySet()) {
            if (!entry.getKey().equals("multiple_facilities")) {
                fields.put(entry.getKey(), textPart(entry.getValue()));
            }
        }
        if (includeAmenities) {
            putQuantityParts(fields, "amenity_quantities", amenityQuantities);
        }
        putCouponPart(fields);
        putAmountPaidPart(fields);

        MultipartBody.Part proofPart = MultipartBody.Part.createFormData("proof_image", "payment_proof.jpg", proofBody);
        ApiClient.bookingApi().reserveMulti("Bearer " + token, textPart("1"),
                textPart(paymentOption == PAYMENT_PARTIAL ? "partial" : "full"),
                textPart(referenceNumberText), proofPart, fields).enqueue(new Callback<ReserveResponse>() {
            @Override
            public void onResponse(Call<ReserveResponse> call, Response<ReserveResponse> response) {
                completeButton.setEnabled(true);
                if (response.isSuccessful() && response.body() != null) {
                    bookingReference = response.body().booking_reference;
                    // A new booking now exists on the backend: the live lists refetch so it is in
                    // My Bookings (with its status) by the time the guest opens them.
                    ReservationFeed.refresh();
                    showScreen(SCREEN_SUCCESS);
                } else if (response.code() == 401) {
                    Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                } else {
                    String message = submitFailureMessage(response);
                    recheckCouponsAfterFailure();
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

    // ---- Package Promo booking ---------------------------------------------------------------

    /** Choose Your Date: the package's check-in (or visit) date plus adults and children. Next asks
     *  the server whether the package can be booked for them before anything else is shown. */
    private void bindPackageDates(View v) {
        bindHeader(v, R.id.packageDatesHeaderBar, R.string.package_dates_title, onBackToOverview);

        boolean stay = activePackage.includes_room;
        ((TextView) v.findViewById(R.id.packageDatesPackageName)).setText(activePackage.name);
        ((TextView) v.findViewById(R.id.packageDatesSubtitle)).setText(
                stay ? R.string.package_dates_subtitle_stay : R.string.package_dates_subtitle_day);
        ((TextView) v.findViewById(R.id.packageDatesDateLabel)).setText(
                stay ? R.string.package_label_date_stay : R.string.package_label_date_day);

        TextView dateText = v.findViewById(R.id.packageDatesDateText);
        TextView dayText = v.findViewById(R.id.packageDatesDayText);
        TextView dateError = v.findViewById(R.id.packageDatesDateError);
        updateDateField(dateText, dayText, packageDateMillis);

        v.findViewById(R.id.packageDatesDateField).setOnClickListener(ClickGuard.wrap(view ->
                GlassDatePicker.showDatePickerDialog(activity, packageDateMillis, System.currentTimeMillis() - 1000L, millis -> {
                    packageDateMillis = millis;
                    updateDateField(dateText, dayText, packageDateMillis);
                    dateError.setVisibility(View.GONE);
                })));

        AuthUiUtils.bindQuantityStepper(v.findViewById(R.id.packageDatesAdultsValue), v.findViewById(R.id.packageDatesAdultsMinus),
                v.findViewById(R.id.packageDatesAdultsPlus), packageAdults, 1, Integer.MAX_VALUE, value -> packageAdults = value);
        AuthUiUtils.bindQuantityStepper(v.findViewById(R.id.packageDatesChildrenValue), v.findViewById(R.id.packageDatesChildrenMinus),
                v.findViewById(R.id.packageDatesChildrenPlus), packageChildren, 0, Integer.MAX_VALUE, value -> packageChildren = value);

        Button nextButton = v.findViewById(R.id.hotelReviewNextButton);
        nextButton.setOnClickListener(ClickGuard.wrap(view -> {
            if (packageDateMillis < 0) {
                showError(dateError, R.string.error_date_required);
                return;
            }
            if (packageDateMillis < System.currentTimeMillis() - ONE_DAY_MS) {
                showError(dateError, R.string.error_date_past);
                return;
            }
            dateError.setVisibility(View.GONE);
            checkPackageThenContinue(v, nextButton);
        }));
    }

    /** Before payment: is the package still active, valid on the date, and are all of its rooms,
     *  cottages, amenities and food available for the party? Only if so does the guest go on. */
    private void checkPackageThenContinue(View datesView, Button nextButton) {
        if (isCheckingPackage) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }
        isCheckingPackage = true;
        nextButton.setEnabled(false);
        nextButton.setText(R.string.package_checking);

        PackagePromo checking = activePackage;
        ApiClient.bookingApi().checkPackagePromo("Bearer " + token, checking.id, packageDateString(),
                packageAdults, packageChildren).enqueue(new Callback<PackageCheckResponse>() {
            @Override
            public void onResponse(Call<PackageCheckResponse> call, Response<PackageCheckResponse> response) {
                isCheckingPackage = false;
                nextButton.setEnabled(true);
                nextButton.setText(R.string.button_next);
                if (activePackage != checking) {
                    return; // the guest left this booking meanwhile
                }
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body().available) {
                        fetchAmenities(datesView);
                    } else {
                        showPackageUnavailable(response.body().reasons);
                    }
                } else if (response.code() == 401) {
                    Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                } else {
                    ErrorResponse error = ApiClient.parseError(response.errorBody());
                    Toast.makeText(activity, error.firstMessage() != null ? error.firstMessage()
                            : activity.getString(R.string.toast_network_error), Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<PackageCheckResponse> call, Throwable t) {
                isCheckingPackage = false;
                nextButton.setEnabled(true);
                nextButton.setText(R.string.button_next);
                Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showPackageUnavailable(List<String> reasons) {
        String message = reasons == null || reasons.isEmpty()
                ? activity.getString(R.string.toast_package_not_available) : TextUtils.join("\n\n", reasons);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.package_unavailable)
                .setMessage(message)
                .setPositiveButton(R.string.button_ok, null)
                .create();
        ThemeManager.applyGlassEffect(dialog.getWindow());
        dialog.show();
    }

    private String packageDateString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(packageDateMillis));
    }

    /** The package's fixed price plus any extra amenities the guest added on top. */
    private int packageExtrasTotal() {
        int total = 0;
        for (AmenityAvailability amenity : availableAmenities) {
            Integer qty = amenityQuantities.get(amenity.id);
            total += (int) Math.round(amenity.price) * (qty == null ? 0 : qty);
        }
        return total;
    }

    private int packageGrandTotal() {
        return (int) Math.round(activePackage.price) + packageExtrasTotal();
    }

    /**
     * Package Summary (the Billing layout): the package name, everything it includes, and the
     * fixed package price as one line, instead of a charge per included item. Anything the guest
     * added on Choose Amenity is listed and charged separately. Full Payment / 30% Partial Payment
     * are worked out from the package price plus those extras.
     */
    private void bindPackageBilling(View v) {
        bindHeader(v, R.id.hotelBillingHeaderBar, R.string.package_billing_title, this::handleBackPressed);
        View couponCard = v.findViewById(R.id.promoCouponCard);
        if (couponCard != null) {
            couponCard.setVisibility(View.GONE);
        }

        bindRow(v.findViewById(R.id.billingRowFullName), R.string.label_full_name, ProfileStore.getFullName(activity));
        bindRow(v.findViewById(R.id.billingRowEmail), R.string.label_email_address, ProfileStore.getEmail(activity));
        bindRow(v.findViewById(R.id.billingRowMobile), R.string.label_mobile_number, ProfileStore.getContactNumber(activity));
        bindRow(v.findViewById(R.id.billingRowAddress), R.string.label_address, ProfileStore.getAddress(activity));

        boolean stay = activePackage.includes_room;
        bindRow(v.findViewById(R.id.billingRowCheckIn),
                stay ? R.string.label_package_date_stay : R.string.label_package_date_day, formatDate(packageDateMillis));
        if (stay) {
            bindRow(v.findViewById(R.id.billingRowCheckOut), R.string.label_package_checkout, formatDate(packageDateMillis + ONE_DAY_MS));
            bindRow(v.findViewById(R.id.billingRowNights), R.string.label_number_of_nights, "1");
        } else {
            v.findViewById(R.id.billingRowCheckOut).setVisibility(View.GONE);
            v.findViewById(R.id.billingRowNights).setVisibility(View.GONE);
        }
        bindRow(v.findViewById(R.id.billingRowAdults), R.string.label_billing_adults, String.valueOf(packageAdults));
        bindRow(v.findViewById(R.id.billingRowChildren), R.string.label_billing_children, String.valueOf(packageChildren));
        bindRow(v.findViewById(R.id.billingRowTotalGuests), R.string.label_total_guests, String.valueOf(packageAdults + packageChildren));

        ((TextView) v.findViewById(R.id.billingAccommodationLabel)).setText(R.string.package_banner_badge);
        LinearLayout accommodationContainer = v.findViewById(R.id.billingAccommodationContainer);
        accommodationContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        int packagePrice = (int) Math.round(activePackage.price);
        addLineRow(accommodationContainer, inflater, activePackage.name,
                activity.getString(R.string.label_package_included_note), packagePrice);
        if (activePackage.inclusions != null) {
            for (PackageInclusion inclusion : activePackage.inclusions) {
                View row = inflater.inflate(R.layout.view_package_inclusion_row, accommodationContainer, false);
                ((ImageView) row.findViewById(R.id.inclusionIcon)).setImageResource(PackageBanners.iconFor(inclusion.kind));
                ((TextView) row.findViewById(R.id.inclusionName)).setText(inclusion.name);
                ((TextView) row.findViewById(R.id.inclusionQuantity)).setText(activity.getString(R.string.package_quantity_format, inclusion.quantity));
                accommodationContainer.addView(row);
            }
        }

        LinearLayout amenitiesContainer = v.findViewById(R.id.billingAmenitiesContainer);
        amenitiesContainer.removeAllViews();
        boolean anyExtra = appendAmenityLines(amenitiesContainer, inflater);
        v.findViewById(R.id.billingAmenitiesCard).setVisibility(anyExtra ? View.VISIBLE : View.GONE);

        bindLine(v.findViewById(R.id.billingRowRoomCharges), R.string.label_package_price, packagePrice);
        bindLine(v.findViewById(R.id.billingRowAmenitiesTotal), R.string.label_package_additional, packageExtrasTotal());
        v.findViewById(R.id.billingRowCoupon).setVisibility(View.GONE);

        int grandTotal = packageGrandTotal();
        ((TextView) v.findViewById(R.id.billingGrandTotal)).setText(formatCurrency(grandTotal));

        View fullCard = v.findViewById(R.id.paymentOptionFullCard);
        View partialCard = v.findViewById(R.id.paymentOptionPartialCard);
        ((TextView) v.findViewById(R.id.paymentOptionFullPrice)).setText(formatCurrency(grandTotal));
        int partialAmount = computePartialAmount(grandTotal);
        ((TextView) v.findViewById(R.id.paymentOptionPartialPrice)).setText(formatCurrency(partialAmount));
        ((TextView) v.findViewById(R.id.paymentOptionPartialRemaining)).setText(
                activity.getString(R.string.label_remaining_balance) + ": " + formatCurrency(grandTotal - partialAmount));
        updatePaymentOptionCards(fullCard, partialCard);
        fullCard.setOnClickListener(view -> {
            paymentOption = PAYMENT_FULL;
            updatePaymentOptionCards(fullCard, partialCard);
        });
        partialCard.setOnClickListener(view -> {
            paymentOption = PAYMENT_PARTIAL;
            updatePaymentOptionCards(fullCard, partialCard);
        });

        bindPolicyAgreement(v, R.id.hotelBillingProceedButton);
        v.findViewById(R.id.hotelBillingProceedButton).setOnClickListener(ClickGuard.wrap(view -> {
            if (paymentOption == PAYMENT_NONE) {
                Toast.makeText(activity, R.string.toast_select_payment_option, Toast.LENGTH_LONG).show();
                return;
            }
            if (!termsChecked || !cancellationChecked) {
                Toast.makeText(activity, R.string.toast_agree_terms_required, Toast.LENGTH_LONG).show();
                return;
            }
            showScreen(SCREEN_PACKAGE_PAYMENT);
        }));
    }

    /** Complete Booking on a package's Payment screen: one multipart POST that re-checks the
     *  package on the server (active, valid that date, everything available) before saving. */
    private void submitPackageBooking(View paymentView) {
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
        fields.put("date", textPart(packageDateString()));
        fields.put("adults", textPart(String.valueOf(packageAdults)));
        fields.put("children", textPart(String.valueOf(packageChildren)));
        fields.put("special_request", textPart(specialRequest));
        putQuantityParts(fields, "amenity_quantities", amenityQuantities);
        putAmountPaidPart(fields);

        MultipartBody.Part proofPart = MultipartBody.Part.createFormData("proof_image", "payment_proof.jpg", proofBody);
        ApiClient.bookingApi().reservePackagePromo("Bearer " + token, activePackage.id,
                textPart(paymentOption == PAYMENT_PARTIAL ? "partial" : "full"),
                textPart(referenceNumberText), proofPart, fields).enqueue(new Callback<ReserveResponse>() {
            @Override
            public void onResponse(Call<ReserveResponse> call, Response<ReserveResponse> response) {
                completeButton.setEnabled(true);
                if (response.isSuccessful() && response.body() != null) {
                    bookingReference = response.body().booking_reference;
                    // A new booking now exists on the backend: the live lists refetch so it is in
                    // My Bookings (with its status) by the time the guest opens them.
                    ReservationFeed.refresh();
                    showScreen(SCREEN_SUCCESS);
                } else if (response.code() == 401) {
                    Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(activity, submitFailureMessage(response), Toast.LENGTH_LONG).show();
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
        header.findViewById(R.id.headerBackButton).setOnClickListener(ClickGuard.wrap(view -> onBack.run()));
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
        return Math.max(0, total - couponDiscount());
    }

    /** Loads a room/amenity thumbnail from the backend, falling back to a placeholder icon. */
    private void loadImage(ImageView imageView, String imagePath, int placeholderRes) {
        ImageLoader.load(imageView, ApiClient.imageUrl(imagePath), placeholderRes);
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
