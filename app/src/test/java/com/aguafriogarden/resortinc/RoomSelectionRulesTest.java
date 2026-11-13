package com.aguafriogarden.resortinc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aguafriogarden.resortinc.network.RoomTypeAvailability;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Hotel Rooms step's rules: availability is by dates and inventory only (a type with nothing
 * free is not offered, quantities never exceed what is free), and capacity is advisory (it only
 * says whether a notice shows). The scenarios mirror the booking-flow spec.
 */
public class RoomSelectionRulesTest {

    private static RoomTypeAvailability room(int groupId, int variantId, int capacity, double price, int available) {
        RoomTypeAvailability room = new RoomTypeAvailability();
        room.group_id = groupId;
        room.variant_id = variantId;
        room.capacity = capacity;
        room.price_per_night = price;
        room.available_quantity = available;
        return room;
    }

    private static Map<Integer, Integer> picks(int... groupAndQty) {
        Map<Integer, Integer> picks = new LinkedHashMap<>();
        for (int i = 0; i < groupAndQty.length; i += 2) {
            picks.put(groupAndQty[i], groupAndQty[i + 1]);
        }
        return picks;
    }

    // Scenario 3: A has 3 of 10 free, B has 0 of 5 free, C has 4 of 8 free.
    private static List<RoomTypeAvailability> inventory() {
        return Arrays.asList(
                room(1, 10, 2, 1000, 3),
                room(2, 20, 3, 1500, 0),
                room(3, 30, 5, 2500, 4));
    }

    @Test
    public void typeWithNoRoomFreeIsHiddenNotShownAsUnavailable() {
        List<RoomTypeAvailability> shown = RoomSelectionRules.bookable(inventory(), -1);

        assertEquals(2, shown.size());
        assertEquals(1, shown.get(0).group_id);
        assertEquals(3, shown.get(1).group_id);
        assertEquals(3, shown.get(0).available_quantity);
        assertEquals(4, shown.get(1).available_quantity);
    }

    @Test
    public void originallyChosenRoomComesFirstWhenAvailable() {
        List<RoomTypeAvailability> shown = RoomSelectionRules.bookable(inventory(), 30);

        assertEquals(3, shown.get(0).group_id);
        assertEquals(1, shown.get(1).group_id);
    }

    @Test
    public void unavailableOriginIsHiddenAndTheRestStillOffered() {
        List<RoomTypeAvailability> shown = RoomSelectionRules.bookable(inventory(), 20);

        assertEquals(2, shown.size());
        assertEquals(1, shown.get(0).group_id);
        assertEquals(3, shown.get(1).group_id);
    }

    @Test
    public void nothingFreeMeansNothingOffered() {
        assertTrue(RoomSelectionRules.bookable(Arrays.asList(room(1, 10, 2, 1000, 0)), 10).isEmpty());
    }

    @Test
    public void changedDatesDropAndLowerPicksToWhatIsNowFree() {
        // Guest picked 3 of A, 1 of B and 4 of C; new dates leave 2 of A, none of B and C gone.
        Map<Integer, Integer> picks = picks(1, 3, 2, 1, 3, 4);
        Map<Integer, Integer> nowFree = new LinkedHashMap<>();
        nowFree.put(1, 2);

        assertTrue(RoomSelectionRules.clampPicks(picks, nowFree));
        assertEquals(2, (int) picks.get(1));
        assertEquals(0, (int) picks.get(2));
        assertEquals(0, (int) picks.get(3));
    }

    @Test
    public void picksWithinAvailabilityAreLeftAlone() {
        Map<Integer, Integer> picks = picks(1, 2, 3, 1);

        assertFalse(RoomSelectionRules.clampPicks(picks,
                RoomSelectionRules.availableByGroup(inventory())));
        assertEquals(2, (int) picks.get(1));
        assertEquals(1, (int) picks.get(3));
        // A type seen for the first time starts at zero.
        assertEquals(0, (int) picks.get(2));
    }

    // Scenario 1: 2 rooms of Type B (capacity 3) seat 6 guests, so no notice.
    @Test
    public void capacityIsRoomCapacityTimesQuantity() {
        List<RoomTypeAvailability> rooms = Arrays.asList(
                room(1, 10, 2, 1000, 5), room(2, 20, 3, 1500, 5), room(3, 30, 5, 2500, 5));

        assertEquals(6, RoomSelectionRules.totalCapacity(rooms, picks(2, 2)));
        assertFalse(RoomSelectionRules.exceedsCapacity(rooms, picks(2, 2), 6, 0));
        assertFalse(RoomSelectionRules.exceedsCapacity(rooms, picks(2, 2), 4, 2));
    }

    // Scenario 2: 2 rooms of Type B (capacity 3) = 6, but 8 guests: notice, never a block.
    @Test
    public void guestsAboveCombinedCapacityShowTheNotice() {
        List<RoomTypeAvailability> rooms = Arrays.asList(room(2, 20, 3, 1500, 5));

        assertTrue(RoomSelectionRules.exceedsCapacity(rooms, picks(2, 2), 8, 0));
        assertTrue(RoomSelectionRules.exceedsCapacity(rooms, picks(2, 2), 6, 2));
        assertTrue(RoomSelectionRules.exceedsCapacity(rooms, picks(2, 2), 7, 0));
        assertFalse(RoomSelectionRules.exceedsCapacity(rooms, picks(2, 2), 6, 0));
    }

    @Test
    public void capacityAddsUpAcrossDifferentRoomTypes() {
        List<RoomTypeAvailability> rooms = Arrays.asList(room(1, 10, 2, 1000, 5), room(3, 30, 5, 2500, 5));

        // 2 x 2 + 1 x 5 = 9
        assertEquals(9, RoomSelectionRules.totalCapacity(rooms, picks(1, 2, 3, 1)));
        assertFalse(RoomSelectionRules.exceedsCapacity(rooms, picks(1, 2, 3, 1), 9, 0));
        assertTrue(RoomSelectionRules.exceedsCapacity(rooms, picks(1, 2, 3, 1), 9, 1));
    }

    @Test
    public void noNoticeWithoutRoomsOrWithAnUnknownCapacity() {
        List<RoomTypeAvailability> rooms = Arrays.asList(room(1, 10, 0, 1000, 5), room(2, 20, 3, 1500, 5));

        // Nothing picked yet.
        assertFalse(RoomSelectionRules.exceedsCapacity(rooms, picks(1, 0, 2, 0), 50, 0));
        // A picked type with no capacity configured can't be compared with the party.
        assertEquals(-1, RoomSelectionRules.totalCapacity(rooms, picks(1, 1, 2, 1)));
        assertFalse(RoomSelectionRules.exceedsCapacity(rooms, picks(1, 1, 2, 1), 50, 0));
    }

    @Test
    public void roomSubtotalIsPriceTimesQuantityTimesNights() {
        assertEquals(6000, RoomSelectionRules.roomSubtotal(room(1, 10, 2, 1000, 5), 2, 3));
        assertEquals(1000, RoomSelectionRules.roomSubtotal(room(1, 10, 2, 1000, 5), 1, 1));
    }

    @Test
    public void totalBookingAmountIsTheSumOfEveryRoomSubtotal() {
        List<RoomTypeAvailability> rooms = new ArrayList<>(inventory());
        // 2 x A (1,000) for 2 nights = 4,000; 1 x C (2,500) for 2 nights = 5,000; B isn't picked.
        assertEquals(9000, RoomSelectionRules.roomsTotal(rooms, picks(1, 2, 2, 0, 3, 1), 2));
    }
}
