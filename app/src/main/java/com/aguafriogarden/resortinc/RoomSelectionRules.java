package com.aguafriogarden.resortinc;

import com.aguafriogarden.resortinc.network.RoomTypeAvailability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules behind the Hotel Rooms step, kept free of Android so they can be unit-tested.
 *
 * <p>Two questions are answered separately on purpose:
 * <ul>
 *   <li><b>Availability</b> depends on the stay dates and the real room inventory only. A room type
 *       with no room free for the dates is not offered at all, and a quantity can never go above
 *       what is free. The number of guests plays no part.</li>
 *   <li><b>Capacity</b> compares the party with the rooms picked (capacity x quantity, summed). It
 *       is advisory: it only decides whether an informational notice is shown and never blocks
 *       anything.</li>
 * </ul>
 */
final class RoomSelectionRules {

    /**
     * The availability endpoint insists on a party of at least one adult, but availability does not
     * depend on guests, so the app always asks as if one adult were travelling: the number carries
     * no meaning and never hides a room type.
     */
    static final int AVAILABILITY_PROBE_ADULTS = 1;
    static final int AVAILABILITY_PROBE_CHILDREN = 0;

    /** The server's own ceiling per field (adults and children are each validated max:20). */
    static final int MAX_ADULTS = 20;
    static final int MAX_CHILDREN = 20;

    private RoomSelectionRules() {
    }

    static int quantityOf(Map<Integer, Integer> picks, int groupId) {
        Integer qty = picks == null ? null : picks.get(groupId);
        return qty == null ? 0 : qty;
    }

    /**
     * The room types the guest can actually book for the searched dates: every type with at least
     * one room free, with the room they originally chose (if any) first. A type with nothing free is
     * left out entirely rather than shown as unavailable.
     */
    static List<RoomTypeAvailability> bookable(List<RoomTypeAvailability> offered, int originVariantId) {
        List<RoomTypeAvailability> origin = new ArrayList<>();
        List<RoomTypeAvailability> others = new ArrayList<>();
        for (RoomTypeAvailability room : offered) {
            if (room.available_quantity <= 0) {
                continue;
            }
            if (originVariantId >= 0 && room.variant_id == originVariantId) {
                origin.add(room);
            } else {
                others.add(room);
            }
        }
        origin.addAll(others);
        return origin;
    }

    /** group_id -> rooms free, for every type in the response. */
    static Map<Integer, Integer> availableByGroup(List<RoomTypeAvailability> offered) {
        Map<Integer, Integer> available = new LinkedHashMap<>();
        for (RoomTypeAvailability room : offered) {
            available.put(room.group_id, room.available_quantity);
        }
        return available;
    }

    /**
     * Brings the guest's picks in line with fresh availability: a pick above what is free is
     * lowered to it (to zero for a type that is gone), and a type seen for the first time starts at
     * zero. Returns whether any pick had to be lowered, so the guest can be told.
     */
    static boolean clampPicks(Map<Integer, Integer> picks, Map<Integer, Integer> availableById) {
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

    /** Rooms picked in total, counting only room types on offer. */
    static int roomCount(List<RoomTypeAvailability> offered, Map<Integer, Integer> picks) {
        int total = 0;
        for (RoomTypeAvailability room : offered) {
            int qty = quantityOf(picks, room.group_id);
            if (qty > 0) {
                total += qty;
            }
        }
        return total;
    }

    /**
     * Total standard capacity of the rooms picked: each type's capacity x its quantity, summed. -1
     * when a picked type has no capacity configured, because the party can't be compared with an
     * unknown capacity (so no notice is shown for it).
     */
    static int totalCapacity(List<RoomTypeAvailability> offered, Map<Integer, Integer> picks) {
        int seats = 0;
        for (RoomTypeAvailability room : offered) {
            int qty = quantityOf(picks, room.group_id);
            if (qty <= 0) {
                continue;
            }
            if (room.capacity <= 0) {
                return -1;
            }
            seats += room.capacity * qty;
        }
        return seats;
    }

    /** True when the party is larger than the rooms picked can seat. Only ever informational. */
    static boolean exceedsCapacity(List<RoomTypeAvailability> offered, Map<Integer, Integer> picks,
                                   int adults, int children) {
        if (roomCount(offered, picks) <= 0) {
            return false;
        }
        int seats = totalCapacity(offered, picks);
        return seats >= 0 && adults + children > seats;
    }

    static int pricePerNight(RoomTypeAvailability room) {
        return (int) Math.round(room.price_per_night);
    }

    /** Room subtotal = price x quantity x nights. */
    static int roomSubtotal(RoomTypeAvailability room, int quantity, int nights) {
        return pricePerNight(room) * quantity * nights;
    }

    /** Total booking amount = the sum of every picked room's subtotal. */
    static int roomsTotal(List<RoomTypeAvailability> offered, Map<Integer, Integer> picks, int nights) {
        int total = 0;
        for (RoomTypeAvailability room : offered) {
            int qty = quantityOf(picks, room.group_id);
            if (qty > 0) {
                total += roomSubtotal(room, qty, nights);
            }
        }
        return total;
    }
}
