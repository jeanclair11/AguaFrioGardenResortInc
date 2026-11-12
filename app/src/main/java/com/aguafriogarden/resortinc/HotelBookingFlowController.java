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
    private long ktvDateMillis = -1;
    private int ktvStartHour = -1;
    private int ktvStartMinute = -1;
    private int ktvGuestCount = 1;

    private List<CottageKtvOption> cottageItems = new ArrayList<>();
    private List<CottageKtvOption> ktvItems = new ArrayList<>();
    /** The KTV session length (Book and Reserve). The guest never picks it: it is taken from the
     *  session length HR configured in KTV Room Management (the price packages' hours — see
     *  #loadKtvSessionHours) and feeds availability/pricing lookups, the end-time calc, the
     *  Review/Billing rows and the submitted ktv[duration_hours]. 0 until loaded. */
    private int ktvDurationHours = 0;
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
        activePackage = null;
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
        activePackage = null;
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
        selectionShowOthers = false;
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
            case SCREEN_PACKAGE_DATES:
                onBackToOverview.run();
                return true;
            case SCREEN_PACKAGE_BILLING:
                showScreen(SCREEN_AMENITY);
                return true;
            case SCREEN_PACKAGE_PAYMENT:
                showScreen(SCREEN_PACKAGE_BILLING);
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
    }

    // ---- Live sync: screens that show server data keep it current while on screen -----------

    private static boolean isLiveSyncScreen(int screen) {
        switch (screen) {
            case SCREEN_ROOM_SELECTION:
            case SCREEN_ROOM_BROWSE:
            case SCREEN_COTTAGE_SELECTION:
            case SCREEN_COTTAGE_BROWSE:
            case SCREEN_KTV_SELECTION:
            case SCREEN_KTV_BROWSE:
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
            case SCREEN_ROOM_SELECTION:
            case SCREEN_ROOM_BROWSE:
                syncRoomAvailability(bearer, screen, screenView);
                break;
            case SCREEN_COTTAGE_SELECTION:
            case SCREEN_COTTAGE_BROWSE:
                syncCottageAvailability(bearer, screen, screenView);
                break;
            case SCREEN_KTV_SELECTION:
            case SCREEN_KTV_BROWSE:
                syncKtvUnits(screen, screenView);
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
        boolean adjusted = false;
        for (Map.Entry<Integer, Integer> pick : picks.entrySet()) {
            Integer available = availableById.get(pick.getKey());
            if (available == null) {
                available = 0;
            }
            if (pick.getValue() > available) {
                pick.setValue(available);
                adjusted = true;
            }
        }
        for (Integer id : availableById.keySet()) {
            if (!picks.containsKey(id)) {
                picks.put(id, 0);
            }
        }
        return adjusted;
    }

    private void applyLiveUpdate(int screen, View screenView, boolean selectionAdjusted) {
        bindScreen(screen, screenView);
        if (selectionAdjusted) {
            Toast.makeText(activity, R.string.toast_availability_changed, Toast.LENGTH_LONG).show();
        }
    }

    private void syncRoomAvailability(String bearer, int screen, View screenView) {
        if (checkInMillis < 0 || checkOutMillis < 0) {
            return;
        }
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        liveSyncInFlight = true;
        ApiClient.bookingApi().checkAvailability(bearer, day.format(new Date(checkInMillis)),
                day.format(new Date(checkOutMillis)), adults, children)
                .enqueue(new Callback<AvailabilityResponse>() {
                    @Override
                    public void onResponse(Call<AvailabilityResponse> call, Response<AvailabilityResponse> response) {
                        liveSyncInFlight = false;
                        if (!response.isSuccessful() || response.body() == null
                                || response.body().room_types == null
                                || !liveScreenStillCurrent(screen, screenView)
                                || LiveSync.sameData(availableRoomTypes, response.body().room_types)) {
                            return;
                        }
                        availableRoomTypes = response.body().room_types;
                        Map<Integer, Integer> available = new LinkedHashMap<>();
                        for (RoomTypeAvailability type : availableRoomTypes) {
                            available.put(type.group_id, type.available_quantity);
                        }
                        applyLiveUpdate(screen, screenView, clampPicks(roomQuantities, available));
                    }

                    @Override
                    public void onFailure(Call<AvailabilityResponse> call, Throwable t) {
                        liveSyncInFlight = false;
                    }
                });
    }

    private void syncCottageAvailability(String bearer, int screen, View screenView) {
        if (cottageDateMillis < 0) {
            return;
        }
        String dateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(cottageDateMillis));
        liveSyncInFlight = true;
        ApiClient.bookingApi().checkCottageAvailability(bearer, dateStr, cottageUseKey(), cottageAdults, cottageChildren)
                .enqueue(new Callback<CottageAvailabilityResponse>() {
                    @Override
                    public void onResponse(Call<CottageAvailabilityResponse> call, Response<CottageAvailabilityResponse> response) {
                        liveSyncInFlight = false;
                        if (!response.isSuccessful() || response.body() == null
                                || response.body().cottages == null
                                || !liveScreenStillCurrent(screen, screenView)
                                || LiveSync.sameData(cottageItems, response.body().cottages)) {
                            return;
                        }
                        cottageItems = new ArrayList<>(response.body().cottages);
                        applyLiveUpdate(screen, screenView, clampPicks(roomQuantities, availabilityOf(cottageItems)));
                    }

                    @Override
                    public void onFailure(Call<CottageAvailabilityResponse> call, Throwable t) {
                        liveSyncInFlight = false;
                    }
                });
    }

    private void syncKtvUnits(int screen, View screenView) {
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String eventDate = ktvDateMillis >= 0 ? day.format(new Date(ktvDateMillis)) : null;
        String startTime = ktvStartHour >= 0
                ? String.format(Locale.US, "%02d:%02d", ktvStartHour, ktvStartMinute) : null;
        liveSyncInFlight = true;
        ApiClient.bookingApi().getKtvUnitsForParty(eventDate, startTime, null, ktvGuestCount).enqueue(new Callback<KtvUnitListResponse>() {
            @Override
            public void onResponse(Call<KtvUnitListResponse> call, Response<KtvUnitListResponse> response) {
                liveSyncInFlight = false;
                if (!response.isSuccessful() || response.body() == null || response.body().ktv_units == null
                        || !liveScreenStillCurrent(screen, screenView)) {
                    return;
                }
                List<CottageKtvOption> latest = new ArrayList<>(response.body().toOptions(ktvDurationHours));
                if (LiveSync.sameData(ktvItems, latest)) {
                    return;
                }
                ktvItems = latest;
                applyLiveUpdate(screen, screenView, clampPicks(roomQuantities, availabilityOf(ktvItems)));
            }

            @Override
            public void onFailure(Call<KtvUnitListResponse> call, Throwable t) {
                liveSyncInFlight = false;
            }
        });
    }

    private static Map<Integer, Integer> availabilityOf(List<CottageKtvOption> items) {
        Map<Integer, Integer> available = new LinkedHashMap<>();
        for (CottageKtvOption item : items) {
            available.put(item.group_id, item.available_quantity);
        }
        return available;
    }

    private void syncAmenities(String bearer, int screen, View screenView) {
        liveSyncInFlight = true;
        ApiClient.bookingApi().getAmenities(bearer, amenityFacilityType()).enqueue(new Callback<AmenityListResponse>() {
            @Override
            public void onResponse(Call<AmenityListResponse> call, Response<AmenityListResponse> response) {
                liveSyncInFlight = false;
                if (!response.isSuccessful() || response.body() == null || response.body().amenities == null
                        || !liveScreenStillCurrent(screen, screenView)
                        || LiveSync.sameData(availableAmenities, response.body().amenities)) {
                    return;
                }
                availableAmenities = response.body().amenities;
                // Unlimited / unspecified-stock amenities have no ceiling to clamp against.
                Map<Integer, Integer> available = new LinkedHashMap<>();
                for (AmenityAvailability amenity : availableAmenities) {
                    available.put(amenity.id, amenity.unlimited || amenity.available_quantity == null
                            ? Integer.MAX_VALUE : amenity.available_quantity);
                }
                applyLiveUpdate(screen, screenView, clampPicks(amenityQuantities, available));
            }

            @Override
            public void onFailure(Call<AmenityListResponse> call, Throwable t) {
                liveSyncInFlight = false;
            }
        });
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
        v.findViewById(R.id.roomDatesCheckInField).setOnClickListener(ClickGuard.wrap(openDatePicker));
        v.findViewById(R.id.roomDatesCheckOutField).setOnClickListener(ClickGuard.wrap(openDatePicker));

        AuthUiUtils.bindQuantityStepper(adultsValue, v.findViewById(R.id.roomDatesAdultsMinus),
                v.findViewById(R.id.roomDatesAdultsPlus), adults, 0, Integer.MAX_VALUE, value -> adults = value);
        AuthUiUtils.bindQuantityStepper(childrenValue, v.findViewById(R.id.roomDatesChildrenMinus),
                v.findViewById(R.id.roomDatesChildrenPlus), children, 0, Integer.MAX_VALUE, value -> children = value);

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
            if (adults + children < 1) {
                Toast.makeText(activity, R.string.toast_guests_required, Toast.LENGTH_LONG).show();
                valid = false;
            }
            if (valid) {
                fetchAvailabilityForRoomSelection(v);
            }
        }));
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
                                // Capacity no longer hides a room here: a party may take several rooms,
                                // so Next on the Room Selection screen checks the party against the
                                // total capacity of the rooms picked instead (blockIfRoomsExceedCapacity).
                                if (type.variant_id == selectionOriginVariantId && type.available_quantity > 0) {
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
            {ReservationCatalog.RATE_TYPE_DAY, ReservationCatalog.RATE_TYPE_NIGHT};

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

        View dateField = v.findViewById(R.id.cottageDatesDateField);
        TextView dateText = v.findViewById(R.id.cottageDatesDateText);
        TextView dateError = v.findViewById(R.id.cottageDatesDateError);
        Spinner useSpinner = v.findViewById(R.id.cottageDatesUseSpinner);
        EditText adultsValue = v.findViewById(R.id.cottageDatesAdultsValue);
        EditText childrenValue = v.findViewById(R.id.cottageDatesChildrenValue);
        TextView totalGuestValue = v.findViewById(R.id.cottageDatesTotalGuestValue);

        updateCottageDateField(dateText);
        totalGuestValue.setText(String.valueOf(cottageAdults + cottageChildren));

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
                cottageRateType = COTTAGE_USE_OPTIONS[position];
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // A Spinner backed by a non-empty adapter always has a selection once shown —
                // nothing to do here.
            }
        });

        dateField.setOnClickListener(ClickGuard.wrap(view -> GlassDatePicker.showDatePickerDialog(
                activity, cottageDateMillis, System.currentTimeMillis() - 1000L, millis -> {
                    cottageDateMillis = millis;
                    updateCottageDateField(dateText);
                    dateError.setVisibility(View.GONE);
                })));

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

        v.findViewById(R.id.cottageDatesNextButton).setOnClickListener(ClickGuard.wrap(view -> {
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
        }));
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
        ApiClient.bookingApi().checkCottageAvailability("Bearer " + token, dateStr, cottageUseKey(), cottageAdults, cottageChildren)
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

        // The party size is already part of the availability result (cottage-availability only
        // reports a type as in stock when its free units together can seat everyone), so only
        // in-stock status matters here; Next then checks the chosen cottages seat the party.
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
     * shared ktvDateMillis/ktvStartHour/ktvStartMinute/ktvGuestCount fields that tab
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
        EditText guestCountValue = v.findViewById(R.id.ktvDatesGuestCountValue);

        updateKtvDateField(dateText);
        updateKtvStartTimeField(startTimeText);
        updateKtvDatesEndTimeAndDuration(v);
        loadKtvSessionHours(v);
        // In the multi-facility flow the date is carried over from the first facility and isn't
        // asked for again — only the session details (rate type, start time, guests) are.
        if (multiMode && multiIndex > 0) {
            dateField.setAlpha(0.6f);
        } else {
            dateField.setOnClickListener(ClickGuard.wrap(view -> GlassDatePicker.showDatePickerDialog(
                    activity, ktvDateMillis, System.currentTimeMillis() - 1000L, millis -> {
                        ktvDateMillis = millis;
                        updateKtvDateField(dateText);
                        dateError.setVisibility(View.GONE);
                    })));
        }

        startTimeField.setOnClickListener(ClickGuard.wrap(view -> {
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
        }));

        AuthUiUtils.bindQuantityStepper(guestCountValue, v.findViewById(R.id.ktvDatesGuestCountMinus),
                v.findViewById(R.id.ktvDatesGuestCountPlus), ktvGuestCount, 1, Integer.MAX_VALUE, value -> ktvGuestCount = value);

        v.findViewById(R.id.ktvDatesNextButton).setOnClickListener(ClickGuard.wrap(view -> {
            boolean valid = true;
            if (ktvDurationHours <= 0) {
                Toast.makeText(activity, R.string.toast_ktv_no_rates, Toast.LENGTH_LONG).show();
                valid = false;
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
        }));
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

        RoomTypeAvailability origin = null;
        for (RoomTypeAvailability room : availableRoomTypes) {
            if (room.variant_id == selectionOriginVariantId) {
                origin = room;
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
        // An unavailable choice is not shown at all (no greyed-out card) — only what can be booked.
        if (origin != null && originAvailable) {
            int pricePerNight = (int) Math.round(origin.price_per_night);
            View card = buildSelectableCard(inflater, originSlot, origin.image_path,
                    formatShowcaseName(origin.variant_name, origin.category_name), origin.capacity, false, origin.description,
                    activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)),
                    origin.group_id, origin.available_quantity, R.string.unit_label_room, originAvailable, !originAvailable,
                    R.string.unavailable_room_message, () -> onRoomQuantityChanged(() -> bindRoomSelection(v)));
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
                if (room.variant_id == selectionOriginVariantId || room.available_quantity <= 0) {
                    continue;
                }
                int pricePerNight = (int) Math.round(room.price_per_night);
                View card = buildSelectableCard(inflater, otherContainer, room.image_path,
                        formatShowcaseName(room.variant_name, room.category_name), room.capacity, false, room.description,
                        activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)),
                        room.group_id, room.available_quantity, R.string.unit_label_room, false, false,
                        R.string.unavailable_room_message, () -> onRoomQuantityChanged(() -> bindRoomSelection(v)));
                otherContainer.addView(card);
            }
            if (otherContainer.getChildCount() == 0) {
                addNothingAvailableNotice(otherContainer, R.string.empty_room_selection);
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
                        R.string.unavailable_room_message, () -> onRoomQuantityChanged(() -> bindRoomSelection(v)));
                otherContainer.addView(card);
            }
            addAnotherButton.setVisibility(View.VISIBLE);
            addAnotherButton.setOnClickListener(ClickGuard.wrap(view -> showScreen(SCREEN_ROOM_BROWSE)));
        }

        v.findViewById(R.id.itemSelectionNextButton).setOnClickListener(ClickGuard.wrap(view -> {
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
            // The party can't be larger than the rooms picked can hold: no moving on to the
            // review, confirmation or payment until the guest adds rooms or reduces participants.
            if (blockIfRoomsExceedCapacity(roomQuantities)) {
                return;
            }
            if (multiMode) {
                multiAdvance(v.findViewById(R.id.itemSelectionNextButton));
                return;
            }
            showScreen(SCREEN_REVIEW);
        }));
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

        LinearLayout container = v.findViewById(R.id.itemBrowseContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (RoomTypeAvailability room : availableRoomTypes) {
            if (room.variant_id == selectionOriginVariantId || room.available_quantity <= 0) {
                continue;
            }
            int pricePerNight = (int) Math.round(room.price_per_night);
            View card = buildSelectableCard(inflater, container, room.image_path,
                    formatShowcaseName(room.variant_name, room.category_name), room.capacity, false, room.description,
                    activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)),
                    room.group_id, room.available_quantity, R.string.unit_label_room, false, false,
                    R.string.unavailable_room_message, () -> onRoomQuantityChanged(() -> bindRoomBrowse(v)));
            container.addView(card);
        }

        v.findViewById(R.id.itemBrowseNextButton).setOnClickListener(ClickGuard.wrap(view -> showScreen(SCREEN_ROOM_SELECTION)));
    }

    /**
     * Shows the cottage the guest originally chose pre-selected with a quantity control if it's
     * in stock, or grayed out with an unavailable message otherwise — plus other available
     * cottage types below, same pattern as {@link #bindRoomSelection}. Cottages have no per-
     * date/time availability check on the backend (only overall stock, see
     * fetchCottagesKtvData) so "available" here means in-stock, not conflict-checked against the
     * guest's chosen date/schedule. The party size does count: the backend reports a type as in
     * stock only when its free units together can seat everyone (a party can book several
     * units — see "Add Another Cottage" below — and Next checks the chosen ones seat the whole
     * party). Capacity is shown on the card (see R.string.cottage_capacity_format). Next is a placeholder for now — Cottage bookings don't
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
        // An unavailable choice is not shown at all (no greyed-out card) — only what can be booked.
        if (origin != null && originAvailable) {
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
            if (otherContainer.getChildCount() == 0) {
                addNothingAvailableNotice(otherContainer, R.string.empty_cottage_selection);
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
            addAnotherButton.setOnClickListener(ClickGuard.wrap(view -> showScreen(SCREEN_COTTAGE_BROWSE)));
        }

        v.findViewById(R.id.itemSelectionNextButton).setOnClickListener(ClickGuard.wrap(view -> {
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
            // The chosen cottages together must seat the whole party (the server enforces the same
            // rule); a type with no capacity configured can't be judged, so it never blocks.
            int guests = cottageAdults + cottageChildren;
            int seats = 0;
            boolean capacityUnknown = false;
            for (CottageKtvOption item : cottageItems) {
                int qty = roomQuantities.containsKey(item.group_id) ? roomQuantities.get(item.group_id) : 0;
                if (qty > 0) {
                    capacityUnknown |= item.capacity <= 0;
                    seats += qty * item.capacity;
                }
            }
            if (!capacityUnknown && seats < guests) {
                Toast.makeText(activity, activity.getString(R.string.toast_cottage_seats_needed, guests, seats),
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (multiMode) {
                multiAdvance(v.findViewById(R.id.itemSelectionNextButton));
                return;
            }
            showScreen(SCREEN_COTTAGE_REVIEW);
        }));
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

        v.findViewById(R.id.itemBrowseNextButton).setOnClickListener(ClickGuard.wrap(view -> showScreen(SCREEN_COTTAGE_SELECTION)));
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

        v.findViewById(R.id.hotelReviewNextButton).setOnClickListener(ClickGuard.wrap(view -> {
            activeContinuation = CONTINUATION_COTTAGE;
            fetchAmenities(v);
        }));
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
            submitSingleFacilityBooking(v, FAC_COTTAGE);
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
        // An unavailable choice is not shown at all (no greyed-out card) — only what can be booked.
        if (origin != null && originAvailable) {
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
            if (otherContainer.getChildCount() == 0) {
                addNothingAvailableNotice(otherContainer, R.string.empty_ktv_selection);
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
            addAnotherButton.setOnClickListener(ClickGuard.wrap(view -> showScreen(SCREEN_KTV_BROWSE)));
        }

        v.findViewById(R.id.itemSelectionNextButton).setOnClickListener(ClickGuard.wrap(view -> {
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
            for (CottageKtvOption item : ktvItems) {
                Integer picked = roomQuantities.get(item.group_id);
                if (picked != null && picked > 0 && item.capacity > 0 && ktvGuestCount > item.capacity) {
                    Toast.makeText(activity, activity.getString(R.string.toast_ktv_over_capacity,
                            item.variant_name, item.capacity), Toast.LENGTH_LONG).show();
                    return;
                }
            }
            if (multiMode) {
                multiAdvance(v.findViewById(R.id.itemSelectionNextButton));
                return;
            }
            showScreen(SCREEN_KTV_REVIEW);
        }));
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

        v.findViewById(R.id.itemBrowseNextButton).setOnClickListener(ClickGuard.wrap(view -> showScreen(SCREEN_KTV_SELECTION)));
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
            submitSingleFacilityBooking(v, FAC_KTV);
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
        // Last line of defence — the Room step already blocks this, and the server re-checks it.
        if (blockIfRoomsExceedCapacity(roomQuantities)) {
            return;
        }
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
                couponFields(quantityFieldMap("room_quantities", roomQuantities))
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
                    ErrorResponse error = ApiClient.parseError(response.errorBody());
                    String message = error.firstMessage() != null
                            ? error.firstMessage() : activity.getString(R.string.toast_booking_failed);
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
                couponFields(quantityFieldMap("cottage_quantities", roomQuantities))
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
                    ErrorResponse error = ApiClient.parseError(response.errorBody());
                    String message = error.firstMessage() != null
                            ? error.firstMessage() : activity.getString(R.string.toast_booking_failed);
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
            submitReservationPayment(v);
        }));
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
                for (CottageKtvOption item : cottageItems) {
                    if (!roomQuantities.containsKey(item.group_id)) {
                        roomQuantities.put(item.group_id, 0);
                    }
                }
                // KTV is shown only as its actual units (booking/ktv-units, checked against the chosen
                // slot) — never grouped by Type — so ktvItems stays empty if that call fails.
                SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                String eventDate = ktvDateMillis >= 0 ? day.format(new Date(ktvDateMillis)) : null;
                String startTime = ktvStartHour >= 0
                        ? String.format(Locale.US, "%02d:%02d", ktvStartHour, ktvStartMinute) : null;
                ApiClient.bookingApi().getKtvUnitsForParty(eventDate, startTime, null, ktvGuestCount).enqueue(new Callback<KtvUnitListResponse>() {
                    @Override
                    public void onResponse(Call<KtvUnitListResponse> c, Response<KtvUnitListResponse> r) {
                        if (r.isSuccessful() && r.body() != null && r.body().ktv_units != null) {
                            ktvItems = new ArrayList<>(r.body().toOptions(ktvDurationHours));
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

    /** Shown on a selection screen when nothing at all is available for the guest's dates/schedule
     *  (unavailable options are never listed, so the screen would otherwise be blank). */
    private void addNothingAvailableNotice(LinearLayout container, int messageRes) {
        TextView notice = new TextView(activity);
        notice.setText(messageRes);
        notice.setTextSize(14f);
        notice.setTextColor(ThemeManager.color(activity, R.attr.textMuted));
        notice.setGravity(android.view.Gravity.CENTER);
        notice.setPadding(0, 32, 0, 32);
        container.addView(notice, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    // ---- Hotel Room capacity: total capacity = capacity per room x rooms picked -------------------
    //
    // Each Room Type's capacity comes from Admin Room Management (RoomTypeAvailability.capacity).
    // The party (adults + children) must fit the rooms picked together; picking more rooms raises
    // the limit, picking fewer lowers it, and the participant count is never changed for the
    // guest. The server recalculates the same rule on every submit (it never trusts this check).

    /** Rooms picked in {@code quantities} (group_id → quantity), counting only room types on offer. */
    private int roomCountOf(Map<Integer, Integer> quantities) {
        int total = 0;
        for (RoomTypeAvailability room : availableRoomTypes) {
            Integer qty = quantities.get(room.group_id);
            if (qty != null && qty > 0) {
                total += qty;
            }
        }
        return total;
    }

    /** Total seats of the rooms picked: each type's capacity × its quantity, summed. -1 when a
     *  picked type has no capacity configured — that can't be judged, so it never blocks (same as
     *  the server). */
    private int roomCapacityOf(Map<Integer, Integer> quantities) {
        int seats = 0;
        for (RoomTypeAvailability room : availableRoomTypes) {
            Integer qty = quantities.get(room.group_id);
            if (qty == null || qty <= 0) {
                continue;
            }
            if (room.capacity <= 0) {
                return -1;
            }
            seats += room.capacity * qty;
        }
        return seats;
    }

    private boolean roomsExceedCapacity(Map<Integer, Integer> quantities) {
        if (roomCountOf(quantities) <= 0) {
            return false;
        }
        int seats = roomCapacityOf(quantities);
        return seats >= 0 && adults + children > seats;
    }

    /** "The selected rooms can accommodate a maximum of X participants. Please reduce the number
     *  of participants or add more rooms." */
    private String roomCapacityError(Map<Integer, Integer> quantities) {
        int seats = roomCapacityOf(quantities);
        return activity.getResources().getQuantityString(R.plurals.error_room_capacity_exceeded, seats, seats);
    }

    /** A room quantity stepper moved: refreshes the screen, and tells the guest straight away if the
     *  party no longer fits the rooms picked (the limit follows the quantity; the participant count
     *  is never changed for them). */
    private void onRoomQuantityChanged(Runnable rebind) {
        rebind.run();
        blockIfRoomsExceedCapacity(roomQuantities);
    }

    /** Shows the capacity message and returns true when the party doesn't fit the rooms in
     *  {@code quantities} (null — no hotel rooms in this submission — never blocks). */
    private boolean blockIfRoomsExceedCapacity(Map<Integer, Integer> quantities) {
        if (quantities == null || !roomsExceedCapacity(quantities)) {
            return false;
        }
        Toast.makeText(activity, roomCapacityError(quantities), Toast.LENGTH_LONG).show();
        return true;
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

        ApiClient.bookingApi().getAmenities("Bearer " + token, amenityFacilityType()).enqueue(new Callback<AmenityListResponse>() {
            @Override
            public void onResponse(Call<AmenityListResponse> call, Response<AmenityListResponse> response) {
                isLoadingAmenities = false;
                nextButton.setEnabled(true);
                nextButton.setText(R.string.button_next);

                if (response.isSuccessful() && response.body() != null) {
                    availableAmenities = response.body().amenities != null
                            ? response.body().amenities : new ArrayList<>();
                    // Drop picks for amenities no longer offered for this facility type, so a
                    // stale quantity can never be billed or submitted for a hidden amenity.
                    Set<Integer> offeredIds = new HashSet<>();
                    for (AmenityAvailability amenity : availableAmenities) {
                        offeredIds.add(amenity.id);
                    }
                    amenityQuantities.keySet().retainAll(offeredIds);
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
            multiAdvance((Button) sourceButton);
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
        }));
    }

    /**
     * Creates the real reservation on the shared backend (same tables/models the
     * website's own booking wizard writes to), so it shows up in the receptionist's
     * Booking List immediately — no separate sync step, just one database.
     */
    private void submitReservation(View paymentView) {
        // Last line of defence — the Room step already blocks this, and the server re-checks it.
        if (blockIfRoomsExceedCapacity(roomQuantities)) {
            return;
        }
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
                    ErrorResponse error = ApiClient.parseError(response.errorBody());
                    String message = error.firstMessage() != null
                            ? error.firstMessage() : activity.getString(R.string.toast_booking_failed);
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
        selectionShowOthers = false;
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
        cottageItems = new ArrayList<>();
        ktvDurationHours = 0;
        ktvItems = new ArrayList<>();
        isLoadingCottageKtv = false;
        cottageKtvLoaded = false;
        availableRoomTypes = new ArrayList<>();
        roomQuantities.clear();
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
        ArrayAdapter<String> useAdapter = new AuthSpinnerAdapter(activity,
                Arrays.asList(activity.getResources().getStringArray(R.array.cottage_use_options)),
                false);
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
            if (activePackage != null) {
                submitPackageBooking(v);
            } else {
                submitMultiBooking(v);
            }
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
        if (multi && blockIfRoomsExceedCapacity(multiQuantities.get(FAC_ROOM))) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }
        Button reserveButton = summaryView.findViewById(R.id.reservationSummaryReserveButton);
        reserveButton.setEnabled(false);

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
                            ErrorResponse error = ApiClient.parseError(response.errorBody());
                            String message = error.firstMessage() != null
                                    ? error.firstMessage() : activity.getString(R.string.toast_booking_failed);
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
        if (multiMode && blockIfRoomsExceedCapacity(multiQuantities.get(FAC_ROOM))) {
            return;
        }
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
                    ErrorResponse error = ApiClient.parseError(response.errorBody());
                    String message = error.firstMessage() != null
                            ? error.firstMessage() : activity.getString(R.string.toast_booking_failed);
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
                    ErrorResponse error = ApiClient.parseError(response.errorBody());
                    Toast.makeText(activity, error.firstMessage() != null ? error.firstMessage()
                            : activity.getString(R.string.toast_booking_failed), Toast.LENGTH_LONG).show();
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
