package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Drives the Book tab: a "What do you want to book?" gate (Hotel Room/Cottage/KTV Room), each
 * leading into {@link HotelBookingFlowController}'s flow at that category's own focused
 * dates-entry gate screen — the exact same flow reached from a Room/Cottage/KTV Overview's
 * "Book Now" button (see {@link #showHotelFlowFromRoomOverview} etc.), just without a specific
 * pre-chosen variant. Function Halls has no flow here or on the Reserve tab (see
 * ReserveCatalogController) — HotelBookingFlowController only ever handled Hotel/Cottage/KTV, and
 * the old four-category catalog/browse/detail screens that used to cover Halls (backed by
 * placeholder {@code BookingCatalogStore} data, with "Book Now" just handing off to the Reserve
 * tab) were removed along with it; Halls stays browsable from the top nav only.
 */
final class BookingCatalogController {

    private static final int SCREEN_PICKER = 0;
    private static final int SCREEN_HOTEL_FLOW = 1;

    private final Activity activity;
    private final Runnable onBookNow;
    private final Runnable onBackToHome;
    private final FrameLayout root;

    private int currentScreen = -1;
    private HotelBookingFlowController hotelBookingFlow;

    BookingCatalogController(Activity activity, Runnable onBookNow, Runnable onBackToHome) {
        this.activity = activity;
        this.onBookNow = onBookNow;
        this.onBackToHome = onBackToHome;
        root = new FrameLayout(activity);
        showPicker();
    }

    View getRootView() {
        return root;
    }

    /** Delegates to the active flow's own back-stack; the picker itself is the tab's root screen. */
    boolean handleBackPressed() {
        if (currentScreen == SCREEN_HOTEL_FLOW) {
            return hotelBookingFlow.handleBackPressed();
        }
        return false;
    }

    /** Forwards the payment-screenshot picker result to the hotel booking flow, if active. */
    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (hotelBookingFlow != null) {
            hotelBookingFlow.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void showPicker() {
        currentScreen = SCREEN_PICKER;
        View v = LayoutInflater.from(activity).inflate(R.layout.view_reserve_picker, root, false);
        ((TextView) v.findViewById(R.id.reservePickerTitleText)).setText(R.string.booking_picker_title);

        LayoutInflater inflater = LayoutInflater.from(activity);
        LinearLayout container = v.findViewById(R.id.reservePickerContainer);
        container.removeAllViews();
        container.addView(buildOption(inflater, container, R.string.service_type_hotel,
                () -> showHotelFlowFromRoomOverview(-1, this::showPicker, false)));
        container.addView(buildOption(inflater, container, R.string.booking_type_cottage,
                () -> showHotelFlowFromCottageOverview(-1, this::showPicker, false)));
        container.addView(buildOption(inflater, container, R.string.service_type_ktv,
                () -> showHotelFlowFromKtvOverview(-1, this::showPicker, false)));

        root.removeAllViews();
        root.addView(v);
    }

    private View buildOption(LayoutInflater inflater, LinearLayout container, int nameRes, Runnable onChosen) {
        View card = inflater.inflate(R.layout.view_booking_category_card, container, false);
        ((TextView) card.findViewById(R.id.categoryName)).setText(nameRes);
        card.setOnClickListener(view -> onChosen.run());
        return card;
    }

    // ---- Hotel Rooms/Cottage/KTV booking flow: dates -> ... -> success ---------

    /**
     * Lazily creates the shared HotelBookingFlowController instance. onBackToHome is wrapped
     * (not passed straight through) because resetFlow() — run by every "Return Home"/Cancel
     * button before this callback fires — leaves hotelBookingFlow's own root empty rather than
     * showing anything (see resetFlow()'s own comment). Without re-arming the picker here, the
     * next tap on the Book tab would show that empty root instead of this gate, since nothing
     * else ever tells this controller to go back to it.
     */
    private HotelBookingFlowController ensureHotelBookingFlow() {
        if (hotelBookingFlow == null) {
            hotelBookingFlow = new HotelBookingFlowController(activity, onBookNow, () -> {
                showPicker();
                onBackToHome.run();
            });
        }
        return hotelBookingFlow;
    }

    /**
     * Enters the Hotel Rooms flow at its own focused "Choose Your Stay Dates" gate screen (see
     * HotelBookingFlowController#showRoomDatesEntry). Used both by the picker above (variantId -1,
     * no pre-chosen room) and by LandingActivity's Room Overview "Book Now"/"Reserve Now" for a
     * specific Hotel Room selection — reserveOnly (true for Reserve, false for Book) makes the
     * shared flow end after Order Food instead of continuing to Billing/Payment.
     */
    void showHotelFlowFromRoomOverview(int variantId, Runnable onBackToOverview, boolean reserveOnly) {
        currentScreen = SCREEN_HOTEL_FLOW;
        ensureHotelBookingFlow().showRoomDatesEntry(variantId, onBackToOverview, reserveOnly);
        root.removeAllViews();
        root.addView(hotelBookingFlow.getRootView());
    }

    /**
     * Same as {@link #showHotelFlowFromRoomOverview}, but the focused "Choose Your Visit Date"
     * gate screen (see HotelBookingFlowController#showCottageDatesEntry) for Cottages.
     */
    void showHotelFlowFromCottageOverview(int variantId, Runnable onBackToOverview, boolean reserveOnly) {
        currentScreen = SCREEN_HOTEL_FLOW;
        ensureHotelBookingFlow().showCottageDatesEntry(variantId, onBackToOverview, reserveOnly);
        root.removeAllViews();
        root.addView(hotelBookingFlow.getRootView());
    }

    /**
     * Same as {@link #showHotelFlowFromRoomOverview}, but the focused "Plan Your KTV Session"
     * gate screen (see HotelBookingFlowController#showKtvDatesEntry) for KTV Rooms.
     */
    void showHotelFlowFromKtvOverview(int variantId, Runnable onBackToOverview, boolean reserveOnly) {
        currentScreen = SCREEN_HOTEL_FLOW;
        ensureHotelBookingFlow().showKtvDatesEntry(variantId, onBackToOverview, reserveOnly);
        root.removeAllViews();
        root.addView(hotelBookingFlow.getRootView());
    }
}
