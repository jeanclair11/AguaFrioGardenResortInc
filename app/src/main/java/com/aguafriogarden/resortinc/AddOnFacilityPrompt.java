package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Do you also want to reserve/book a Cottage or KTV Room?" step shown when a guest finishes
 * the Choose Amenity step of a single-facility Book/Reserve flow. Yes opens a second dialog
 * to tick the extra facilities; No (or dismissing) carries on with the single-facility flow. The
 * chosen extras are handed to {@link Listener#onDecided} — empty means "No".
 */
final class AddOnFacilityPrompt {

    interface Listener {
        void onDecided(List<Integer> extras);
    }

    private AddOnFacilityPrompt() {
    }

    static int nameRes(int facility) {
        return facility == PendingRoomSelection.TYPE_COTTAGE ? R.string.booking_type_cottage
                : facility == PendingRoomSelection.TYPE_KTV ? R.string.service_type_ktv
                : R.string.service_type_hotel;
    }

    /**
     * Asks about extras once a guest finishes the Choose Amenity step of a single-facility flow.
     * Only facilities ordered after {@code facility} (Room, Cottage, KTV) are offered, since the
     * multi-facility flow keeps that order and reuses the first facility's date; a KTV flow has
     * nothing to add, so the listener fires straight away.
     */
    static void showFor(Activity activity, boolean reserve, int facility, Listener listener) {
        List<Integer> options = new ArrayList<>();
        for (int other : new int[]{PendingRoomSelection.TYPE_ROOM, PendingRoomSelection.TYPE_COTTAGE,
                PendingRoomSelection.TYPE_KTV}) {
            if (other > facility) {
                options.add(other);
            }
        }
        show(activity, reserve, options, listener);
    }

    /** {@code options} are the facilities that can still be added; with none, the listener fires
     *  straight away with an empty list. {@code reserve} only changes the wording. */
    static void show(Activity activity, boolean reserve, List<Integer> options, Listener listener) {
        if (options.isEmpty()) {
            listener.onDecided(new ArrayList<>());
            return;
        }
        List<String> names = new ArrayList<>();
        for (int facility : options) {
            names.add(activity.getString(nameRes(facility)));
        }
        String joined = String.join(" / ", names);
        new AlertDialog.Builder(activity)
                .setTitle(reserve ? R.string.add_on_title_reserve : R.string.add_on_title_book)
                .setMessage(activity.getString(R.string.add_on_message, joined))
                .setCancelable(false)
                .setPositiveButton(R.string.button_yes, (d, w) -> showChooser(activity, options, names, listener))
                .setNegativeButton(R.string.button_no, (d, w) -> listener.onDecided(new ArrayList<>()))
                .show();
    }

    private static void showChooser(Activity activity, List<Integer> options, List<String> names, Listener listener) {
        boolean[] checked = new boolean[options.size()];
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.add_on_choose_title)
                .setMultiChoiceItems(names.toArray(new String[0]), checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setCancelable(false)
                .setPositiveButton(R.string.button_continue_multi, null)
                .setNegativeButton(R.string.button_no, (d, w) -> listener.onDecided(new ArrayList<>()))
                .create();
        // Positive click handled manually so an empty selection keeps the dialog open.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            List<Integer> extras = new ArrayList<>();
            for (int i = 0; i < checked.length; i++) {
                if (checked[i]) {
                    extras.add(options.get(i));
                }
            }
            if (extras.isEmpty()) {
                android.widget.Toast.makeText(activity, R.string.toast_select_add_on, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            listener.onDecided(extras);
        }));
        dialog.show();
    }
}
