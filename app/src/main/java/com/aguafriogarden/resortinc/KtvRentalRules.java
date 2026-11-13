package com.aguafriogarden.resortinc;

import com.aguafriogarden.resortinc.network.CottageKtvOption;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the guest is renting when they pick KTV rooms, kept free of Android so it can be unit-tested.
 * The rental length, rate type and price are never entered by the guest: they are the ones the
 * Admin configured for each room's Type in KTV Room Management (see
 * {@link CottageKtvOption#rental_hours}, {@link CottageKtvOption#rate_label}), and the end time is
 * the start time plus that length. Rooms of different Types may be configured for different
 * lengths: each keeps its own, and the session's end time is the longest one, the same rule the
 * Receptionist's forms and the server use (KtvRates::longestHours).
 */
final class KtvRentalRules {

    private KtvRentalRules() {
    }

    /** The configured lengths (hours) of the rooms picked, ascending and distinct. */
    static List<Integer> pickedHours(List<CottageKtvOption> offered, Map<Integer, Integer> picks) {
        Set<Integer> hours = new TreeSet<>();
        for (CottageKtvOption item : offered) {
            if (RoomSelectionRules.quantityOf(picks, item.group_id) > 0 && item.rental_hours > 0) {
                hours.add(item.rental_hours);
            }
        }
        return new ArrayList<>(hours);
    }

    /** The longest configured length among the rooms picked, 0 when none is picked. */
    static int longestHours(List<CottageKtvOption> offered, Map<Integer, Integer> picks) {
        List<Integer> hours = pickedHours(offered, picks);
        return hours.isEmpty() ? 0 : hours.get(hours.size() - 1);
    }

    /** The picked rooms' rate types, e.g. "3 Hours" (distinct, comma-joined when they differ). */
    static String rateLabel(List<CottageKtvOption> offered, Map<Integer, Integer> picks) {
        Set<String> labels = new LinkedHashSet<>();
        for (CottageKtvOption item : offered) {
            if (RoomSelectionRules.quantityOf(picks, item.group_id) > 0
                    && item.rate_label != null && !item.rate_label.trim().isEmpty()) {
                labels.add(item.rate_label.trim());
            }
        }
        return String.join(", ", labels);
    }

    /** Sum of the picked rooms' configured rental prices, before extra-guest charges. */
    static int rentalSubtotal(List<CottageKtvOption> offered, Map<Integer, Integer> picks) {
        int total = 0;
        for (CottageKtvOption item : offered) {
            int qty = RoomSelectionRules.quantityOf(picks, item.group_id);
            if (qty > 0) {
                total += (int) Math.round(item.price) * qty;
            }
        }
        return total;
    }

    /** Whether every picked room has a rental configured. */
    static boolean allConfigured(List<CottageKtvOption> offered, Map<Integer, Integer> picks) {
        for (CottageKtvOption item : offered) {
            if (RoomSelectionRules.quantityOf(picks, item.group_id) > 0 && item.rental_hours <= 0) {
                return false;
            }
        }
        return true;
    }

    /** Minutes since midnight of the end time: the start plus {@code hours}, wrapping past midnight
     *  the way the server does. -1 when there is no start time or no length yet. */
    static int endMinuteOfDay(int startHour, int startMinute, int hours) {
        if (startHour < 0 || startMinute < 0 || hours <= 0) {
            return -1;
        }
        return (startHour * 60 + startMinute + hours * 60) % (24 * 60);
    }
}
