package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the Book/Reserve tabs' shared facility gate (view_reserve_picker.xml): a "Book/Reserve
 * Multiple Facilities" toggle on top of the Hotel Room/Cottage/KTV Room cards. With the toggle off
 * each card opens that facility's own flow; with it on the cards turn into checkboxes and a
 * Continue button hands the chosen set to {@link Listener#onMultiple}.
 */
final class FacilityPicker {

    interface Listener {
        void onSingle(int facility);

        void onMultiple(List<Integer> facilities);
    }

    private static final int[] FACILITIES = {
            PendingRoomSelection.TYPE_ROOM, PendingRoomSelection.TYPE_COTTAGE, PendingRoomSelection.TYPE_KTV};
    private static final int[] FACILITY_NAMES = {
            R.string.service_type_hotel, R.string.booking_type_cottage, R.string.service_type_ktv};

    private FacilityPicker() {
    }

    static void bind(Activity activity, View pickerRoot, int multiToggleLabelRes, Listener listener) {
        LayoutInflater inflater = LayoutInflater.from(activity);
        FrameLayout multiSlot = pickerRoot.findViewById(R.id.reservePickerMultiSlot);
        LinearLayout container = pickerRoot.findViewById(R.id.reservePickerContainer);
        Button continueButton = pickerRoot.findViewById(R.id.reservePickerContinueButton);

        boolean[] multiMode = {false};
        boolean[] checked = new boolean[FACILITIES.length];
        CheckBox[] boxes = new CheckBox[FACILITIES.length];

        View toggleCard = inflater.inflate(R.layout.view_booking_category_card, multiSlot, false);
        ((TextView) toggleCard.findViewById(R.id.categoryName)).setText(multiToggleLabelRes);
        toggleCard.findViewById(R.id.categoryChevron).setVisibility(View.GONE);
        CheckBox toggleBox = toggleCard.findViewById(R.id.categoryCheckbox);
        toggleBox.setVisibility(View.VISIBLE);
        multiSlot.addView(toggleCard);

        for (int i = 0; i < FACILITIES.length; i++) {
            final int index = i;
            View card = inflater.inflate(R.layout.view_booking_category_card, container, false);
            ((TextView) card.findViewById(R.id.categoryName)).setText(FACILITY_NAMES[i]);
            boxes[i] = card.findViewById(R.id.categoryCheckbox);
            card.setOnClickListener(view -> {
                if (!multiMode[0]) {
                    listener.onSingle(FACILITIES[index]);
                    return;
                }
                checked[index] = !checked[index];
                boxes[index].setChecked(checked[index]);
            });
            container.addView(card);
        }

        toggleCard.setOnClickListener(view -> {
            multiMode[0] = !multiMode[0];
            toggleBox.setChecked(multiMode[0]);
            for (int i = 0; i < FACILITIES.length; i++) {
                checked[i] = false;
                boxes[i].setChecked(false);
                boxes[i].setVisibility(multiMode[0] ? View.VISIBLE : View.GONE);
                container.getChildAt(i).findViewById(R.id.categoryChevron)
                        .setVisibility(multiMode[0] ? View.GONE : View.VISIBLE);
            }
            continueButton.setVisibility(multiMode[0] ? View.VISIBLE : View.GONE);
        });

        continueButton.setOnClickListener(view -> {
            List<Integer> chosen = new ArrayList<>();
            for (int i = 0; i < FACILITIES.length; i++) {
                if (checked[i]) {
                    chosen.add(FACILITIES[i]);
                }
            }
            if (chosen.size() < 2) {
                Toast.makeText(activity, R.string.toast_select_two_facilities, Toast.LENGTH_LONG).show();
                return;
            }
            listener.onMultiple(chosen);
        });
    }
}
