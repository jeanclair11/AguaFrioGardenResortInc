package com.aguafriogarden.resortinc;

import com.aguafriogarden.resortinc.network.CottageKtvOption;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Cottage and KTV counterpart of {@link RoomSelectionRules}, kept free of Android so it can be
 * unit-tested. Same two separate questions:
 * <ul>
 *   <li><b>Availability</b> depends on the schedule (the cottage's date and use, the KTV room's date
 *       and time) and what is really free. An item with nothing free is not offered at all.</li>
 *   <li><b>Capacity</b> compares the party with the items picked (capacity x quantity, summed). It is
 *       advisory: it only decides whether an informational notice is shown and never blocks.</li>
 * </ul>
 * Picks are held per group_id, exactly as for rooms, so {@link RoomSelectionRules#clampPicks} and
 * {@link RoomSelectionRules#quantityOf} apply to them unchanged.
 */
final class FacilitySelectionRules {

    private FacilitySelectionRules() {
    }

    /**
     * What the guest can actually book for the searched schedule: every item with at least one free,
     * with the one they originally chose (if any) first. An item with nothing free is left out
     * entirely rather than shown as unavailable.
     */
    static List<CottageKtvOption> bookable(List<CottageKtvOption> offered, int originVariantId) {
        List<CottageKtvOption> origin = new ArrayList<>();
        List<CottageKtvOption> others = new ArrayList<>();
        for (CottageKtvOption item : offered) {
            if (item.available_quantity <= 0) {
                continue;
            }
            if (originVariantId >= 0 && item.variant_id == originVariantId) {
                origin.add(item);
            } else {
                others.add(item);
            }
        }
        origin.addAll(others);
        return origin;
    }

    /** group_id -> free count, for every item in the response (an unavailable one counts 0). */
    static Map<Integer, Integer> availableByGroup(List<CottageKtvOption> offered) {
        Map<Integer, Integer> available = new LinkedHashMap<>();
        for (CottageKtvOption item : offered) {
            available.put(item.group_id, item.available_quantity);
        }
        return available;
    }

    /** Items picked in total, counting only those on offer. */
    static int itemCount(List<CottageKtvOption> offered, Map<Integer, Integer> picks) {
        int total = 0;
        for (CottageKtvOption item : offered) {
            int qty = RoomSelectionRules.quantityOf(picks, item.group_id);
            if (qty > 0) {
                total += qty;
            }
        }
        return total;
    }

    /**
     * Total standard capacity of what is picked: each item's capacity x its quantity, summed. -1
     * when a picked item has no capacity configured, because the party can't be compared with an
     * unknown capacity (so no notice is shown for it).
     */
    static int totalCapacity(List<CottageKtvOption> offered, Map<Integer, Integer> picks) {
        int seats = 0;
        for (CottageKtvOption item : offered) {
            int qty = RoomSelectionRules.quantityOf(picks, item.group_id);
            if (qty <= 0) {
                continue;
            }
            if (item.capacity <= 0) {
                return -1;
            }
            seats += item.capacity * qty;
        }
        return seats;
    }

    /** True when the party is larger than what is picked can seat. Only ever informational. */
    static boolean exceedsCapacity(List<CottageKtvOption> offered, Map<Integer, Integer> picks, int guests) {
        if (itemCount(offered, picks) <= 0) {
            return false;
        }
        int seats = totalCapacity(offered, picks);
        return seats >= 0 && guests > seats;
    }
}
