package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;
import java.util.function.Consumer;

/**
 * Drives the Book tab: a "What do you want to book?" gate (Hotel Room/Cottage/KTV Room, or several
 * at once via "Book Multiple Facilities" — see {@link FacilityPicker}), each leading into {@link HotelBookingFlowController}'s flow at that category's own focused
 * dates-entry gate screen — the exact same flow reached from a Room/Cottage/KTV Overview's
 * "Book Now" button (see {@link #showHotelFlow}), just without a specific
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
    private final Consumer<Boolean> setBottomNavHidden;
    private final FrameLayout root;

    private int currentScreen = -1;
    private HotelBookingFlowController hotelBookingFlow;

    BookingCatalogController(Activity activity, Runnable onBookNow, Runnable onBackToHome,
                              Consumer<Boolean> setBottomNavHidden) {
        this.activity = activity;
        this.onBookNow = onBookNow;
        this.onBackToHome = onBackToHome;
        this.setBottomNavHidden = setBottomNavHidden;
        root = new FrameLayout(activity);
        root.setBackgroundColor(ThemeManager.color(activity, R.attr.browsePageBackground));
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

        FacilityPicker.bind(activity, v, R.string.book_multiple_facilities, new FacilityPicker.Listener() {
            @Override
            public void onSingle(int facility) {
                showHotelFlow(facility, -1, BookingCatalogController.this::showPicker);
            }

            @Override
            public void onMultiple(List<Integer> facilities) {
                currentScreen = SCREEN_HOTEL_FLOW;
                ensureHotelBookingFlow().startMultiFlow(facilities, BookingCatalogController.this::showPicker, false);
                root.removeAllViews();
                root.addView(hotelBookingFlow.getRootView());
            }
        });

        root.removeAllViews();
        root.addView(v);
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
            }, setBottomNavHidden);
        }
        return hotelBookingFlow;
    }

    /**
     * Enters the Book flow for a just-picked facility, from either the tab's picker (variantId -1)
     * or LandingActivity's Room/Cottage/KTV Overview "Book Now" (a pre-chosen variantId), landing
     * on that category's own focused dates gate. ("Reserve Now" goes through
     * ReserveCatalogController's own equivalent so a reservation shares that controller's
     * instance/state.)
     */
    void showHotelFlow(int type, int variantId, Runnable onBackToOverview) {
        currentScreen = SCREEN_HOTEL_FLOW;
        HotelBookingFlowController flow = ensureHotelBookingFlow();
        if (type == PendingRoomSelection.TYPE_COTTAGE) {
            flow.showCottageDatesEntry(variantId, onBackToOverview, false);
        } else if (type == PendingRoomSelection.TYPE_KTV) {
            flow.showKtvDatesEntry(variantId, onBackToOverview, false);
        } else {
            flow.showRoomDatesEntry(variantId, onBackToOverview, false);
        }
        root.removeAllViews();
        root.addView(flow.getRootView());
    }

    /** Profile history -> Reservation Details for an already-booked (paid) record. */
    void showReservationDetails(com.aguafriogarden.resortinc.network.ReservationSummary summary, Runnable onBack) {
        currentScreen = SCREEN_HOTEL_FLOW;
        ensureHotelBookingFlow().openReservationFromHistory(summary, onBack);
        root.removeAllViews();
        root.addView(hotelBookingFlow.getRootView());
    }

    /** Called when the Book tab is activated — ensures the picker is shown. */
    void onTabShown() {
        showPicker();
    }

    /** Called when the Book tab is deactivated — clears in-progress form data. */
    void onTabHidden() {
        if (hotelBookingFlow != null) {
            hotelBookingFlow.resetFlow();
        }
    }
}
