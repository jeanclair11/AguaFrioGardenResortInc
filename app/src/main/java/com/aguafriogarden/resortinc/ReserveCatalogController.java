package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Drives the Reserve tab: a "What do you want to reserve?" gate (Hotel Room/Cottage/KTV Room),
 * each choice handing off to {@link HotelBookingFlowController}'s reserve-now flow at that
 * category's own focused dates-entry screen (Choose Your Stay Dates/Choose Your Visit Date/Plan
 * Your KTV Session) — the exact same flow reached from a Room/Cottage/KTV Overview's "Reserve
 * Now" button (see BookingCatalogController#showHotelFlowFromRoomOverview etc.), just without a
 * specific pre-chosen variant (variantId -1, so Your Selected .../s shows every available option
 * with no pre-selected origin card).
 *
 * <p>Deliberately owns its own HotelBookingFlowController instance rather than reusing
 * BookingCatalogController's (the Book tab's) — that instance's mutable state (dates, quantities,
 * guest info, reservation status, payment fields) is scoped to one flow at a time, and a guest
 * should be able to leave a Book flow in progress on the Book tab, switch to Reserve, start (or
 * resume) a Reserve flow, and switch back without either clobbering the other's state.
 */
final class ReserveCatalogController {

    private static final int SCREEN_PICKER = 0;
    private static final int SCREEN_FLOW = 1;

    private final Activity activity;
    private final Runnable onBackToHome;
    private final FrameLayout root;

    private int currentScreen = -1;
    private HotelBookingFlowController hotelBookingFlow;

    ReserveCatalogController(Activity activity, Runnable onBackToHome) {
        this.activity = activity;
        this.onBackToHome = onBackToHome;
        root = new FrameLayout(activity);
        showPicker();
    }

    View getRootView() {
        return root;
    }

    /** Delegates to the active flow's own back-stack; the picker itself is the tab's root screen. */
    boolean handleBackPressed() {
        if (currentScreen == SCREEN_FLOW) {
            return hotelBookingFlow.handleBackPressed();
        }
        return false;
    }

    /** Forwards the payment-screenshot picker result to the reserve flow, if active. */
    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (hotelBookingFlow != null) {
            hotelBookingFlow.onActivityResult(requestCode, resultCode, data);
        }
    }

    /** Package-private so LandingActivity's bottom-nav Reserve tab can force back to this screen
     *  on every tap — see its own buildBottomNav() click-listener wiring. */
    void showPicker() {
        currentScreen = SCREEN_PICKER;
        View v = LayoutInflater.from(activity).inflate(R.layout.view_reserve_picker, root, false);

        LayoutInflater inflater = LayoutInflater.from(activity);
        LinearLayout container = v.findViewById(R.id.reservePickerContainer);
        container.removeAllViews();
        container.addView(buildOption(inflater, container, R.string.service_type_hotel,
                () -> showFlow(PendingRoomSelection.TYPE_ROOM)));
        container.addView(buildOption(inflater, container, R.string.booking_type_cottage,
                () -> showFlow(PendingRoomSelection.TYPE_COTTAGE)));
        container.addView(buildOption(inflater, container, R.string.service_type_ktv,
                () -> showFlow(PendingRoomSelection.TYPE_KTV)));

        root.removeAllViews();
        root.addView(v);
    }

    private View buildOption(LayoutInflater inflater, LinearLayout container, int nameRes, Runnable onChosen) {
        View card = inflater.inflate(R.layout.view_booking_category_card, container, false);
        ((TextView) card.findViewById(R.id.categoryName)).setText(nameRes);
        card.setOnClickListener(view -> onChosen.run());
        return card;
    }

    private void showFlow(int type) {
        currentScreen = SCREEN_FLOW;
        if (type == PendingRoomSelection.TYPE_COTTAGE) {
            ensureHotelBookingFlow().showCottageDatesEntry(-1, this::showPicker, true);
        } else if (type == PendingRoomSelection.TYPE_KTV) {
            ensureHotelBookingFlow().showKtvDatesEntry(-1, this::showPicker, true);
        } else {
            ensureHotelBookingFlow().showRoomDatesEntry(-1, this::showPicker, true);
        }
        root.removeAllViews();
        root.addView(hotelBookingFlow.getRootView());
    }

    /**
     * Lazily creates the shared HotelBookingFlowController instance — same reasoning as
     * BookingCatalogController's own ensureHotelBookingFlow(): resetFlow() (run by every "Return
     * Home"/Cancel button before this callback fires) leaves hotelBookingFlow's own root empty
     * rather than showing anything, so onBackToHome is wrapped to re-arm the picker first.
     */
    private HotelBookingFlowController ensureHotelBookingFlow() {
        if (hotelBookingFlow == null) {
            hotelBookingFlow = new HotelBookingFlowController(activity, this::showPicker, () -> {
                showPicker();
                onBackToHome.run();
            });
        }
        return hotelBookingFlow;
    }

    /**
     * LandingActivity's Room Overview "Reserve Now" for a specific pre-chosen Hotel Room —
     * mirrors BookingCatalogController#showHotelFlowFromRoomOverview, but shares THIS controller's
     * instance/state instead, so a reservation started from the Landing Page and one started from
     * the Reserve tab's own picker are the same reservation, visible from either entry point's
     * Reservation Details screen (always reserve mode — this controller only ever drives Reserve).
     */
    void showHotelFlowFromRoomOverview(int variantId, Runnable onBackToOverview) {
        currentScreen = SCREEN_FLOW;
        ensureHotelBookingFlow().showRoomDatesEntry(variantId, onBackToOverview, true);
        root.removeAllViews();
        root.addView(hotelBookingFlow.getRootView());
    }

    /** Same as {@link #showHotelFlowFromRoomOverview}, but for Cottages. */
    void showHotelFlowFromCottageOverview(int variantId, Runnable onBackToOverview) {
        currentScreen = SCREEN_FLOW;
        ensureHotelBookingFlow().showCottageDatesEntry(variantId, onBackToOverview, true);
        root.removeAllViews();
        root.addView(hotelBookingFlow.getRootView());
    }

    /** Same as {@link #showHotelFlowFromRoomOverview}, but for KTV Rooms. */
    void showHotelFlowFromKtvOverview(int variantId, Runnable onBackToOverview) {
        currentScreen = SCREEN_FLOW;
        ensureHotelBookingFlow().showKtvDatesEntry(variantId, onBackToOverview, true);
        root.removeAllViews();
        root.addView(hotelBookingFlow.getRootView());
    }
}
