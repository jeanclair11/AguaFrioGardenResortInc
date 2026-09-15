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

    private void showPicker() {
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
        if (hotelBookingFlow == null) {
            // onBookNowOtherCategory only ever fired from the Book tab's own generic tabbed
            // browse screen, which no longer exists and which this flow never reached anyway
            // (it always enters at a focused dates-entry gate screen instead) — showPicker is a
            // safe, never-really-invoked fallback rather than passing null.
            //
            // The onBackToHome wrapper below is load-bearing, not decorative: resetFlow() (run
            // by every "Return Home"/Cancel button before this callback fires) leaves
            // hotelBookingFlow's own root empty rather than showing anything (see resetFlow()'s
            // own comment) — the same View instance still sitting inside this controller's root
            // from the last showFlow() call. Without re-arming the picker here, the next tap on
            // the Reserve tab would show that empty root instead of this gate, since nothing else
            // ever tells this controller to go back to it.
            hotelBookingFlow = new HotelBookingFlowController(activity, this::showPicker, () -> {
                showPicker();
                onBackToHome.run();
            });
        }
        if (type == PendingRoomSelection.TYPE_COTTAGE) {
            hotelBookingFlow.showCottageDatesEntry(-1, this::showPicker, true);
        } else if (type == PendingRoomSelection.TYPE_KTV) {
            hotelBookingFlow.showKtvDatesEntry(-1, this::showPicker, true);
        } else {
            hotelBookingFlow.showRoomDatesEntry(-1, this::showPicker, true);
        }
        root.removeAllViews();
        root.addView(hotelBookingFlow.getRootView());
    }
}
