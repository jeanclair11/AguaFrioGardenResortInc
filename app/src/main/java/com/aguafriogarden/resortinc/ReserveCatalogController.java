package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import java.util.List;
import java.util.function.Consumer;

/**
 * Drives the Reserve tab: a "What do you want to reserve?" gate (Hotel Room/Cottage/KTV Room, or
 * several at once via "Reserve Multiple Facilities" — see {@link FacilityPicker}), each choice handing off to {@link HotelBookingFlowController}'s reserve-now flow at that
 * category's own focused dates-entry screen (Choose Your Stay Dates/Choose Your Visit Date/Plan
 * Your KTV Session) — the exact same flow reached from a Room/Cottage/KTV Overview's "Reserve
 * Now" button (see BookingCatalogController#showHotelFlow), just without a
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
    private final Consumer<Boolean> setBottomNavHidden;
    private final FrameLayout root;

    private int currentScreen = -1;
    private HotelBookingFlowController hotelBookingFlow;

    ReserveCatalogController(Activity activity, Runnable onBackToHome, Consumer<Boolean> setBottomNavHidden) {
        this.activity = activity;
        this.onBackToHome = onBackToHome;
        this.setBottomNavHidden = setBottomNavHidden;
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

        FacilityPicker.bind(activity, v, R.string.reserve_multiple_facilities, new FacilityPicker.Listener() {
            @Override
            public void onSingle(int facility) {
                showHotelFlow(facility, -1, ReserveCatalogController.this::showPicker);
            }

            @Override
            public void onMultiple(List<Integer> facilities) {
                currentScreen = SCREEN_FLOW;
                ensureHotelBookingFlow().startMultiFlow(facilities, ReserveCatalogController.this::showPicker, true);
                root.removeAllViews();
                root.addView(hotelBookingFlow.getRootView());
            }
        });

        root.removeAllViews();
        root.addView(v);
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
            }, setBottomNavHidden);
        }
        return hotelBookingFlow;
    }

    /**
     * Enters the Reserve flow for a just-picked facility, from either the tab's picker (variantId
     * -1) or LandingActivity's Room/Cottage/KTV Overview "Reserve Now" (a pre-chosen variantId) —
     * the latter shares THIS controller's instance/state, so a reservation started from the
     * Landing Page and one started from the Reserve tab's own picker are the same reservation,
     * visible from either entry point's Reservation Details screen.
     */
    void showHotelFlow(int type, int variantId, Runnable onBackToOverview) {
        currentScreen = SCREEN_FLOW;
        HotelBookingFlowController flow = ensureHotelBookingFlow();
        if (type == PendingRoomSelection.TYPE_COTTAGE) {
            flow.showCottageDatesEntry(variantId, onBackToOverview, true);
        } else if (type == PendingRoomSelection.TYPE_KTV) {
            flow.showKtvDatesEntry(variantId, onBackToOverview, true);
        } else {
            flow.showRoomDatesEntry(variantId, onBackToOverview, true);
        }
        root.removeAllViews();
        root.addView(flow.getRootView());
    }

    /** Called when the Reserve tab is deactivated — clears in-progress form data. */
    void onTabHidden() {
        if (hotelBookingFlow != null) {
            hotelBookingFlow.resetFlow();
        }
    }
}
