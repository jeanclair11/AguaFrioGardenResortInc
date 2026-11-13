package com.aguafriogarden.resortinc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aguafriogarden.resortinc.network.CottageKtvOption;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Cottage and KTV selection rules: availability is by schedule only (an item with nothing free is
 * not offered, quantities never exceed what is free), and capacity is advisory (it only says whether
 * a notice shows). The KTV cases use units, which are one physical room each.
 */
public class FacilitySelectionRulesTest {

    private static CottageKtvOption item(int groupId, int variantId, int capacity, int available) {
        CottageKtvOption item = new CottageKtvOption();
        item.group_id = groupId;
        item.variant_id = variantId;
        item.capacity = capacity;
        item.available_quantity = available;
        return item;
    }

    private static Map<Integer, Integer> picks(int... groupAndQty) {
        Map<Integer, Integer> picks = new LinkedHashMap<>();
        for (int i = 0; i < groupAndQty.length; i += 2) {
            picks.put(groupAndQty[i], groupAndQty[i + 1]);
        }
        return picks;
    }

    @Test
    public void typesWithNothingFreeAreHiddenNotShownAsUnavailable() {
        // Cottage A: 3 free, B: none free, C: 4 free.
        List<CottageKtvOption> offered = Arrays.asList(item(1, 10, 6, 3), item(2, 20, 4, 0), item(3, 30, 10, 4));

        List<CottageKtvOption> shown = FacilitySelectionRules.bookable(offered, -1);

        assertEquals(2, shown.size());
        assertEquals(1, shown.get(0).group_id);
        assertEquals(3, shown.get(1).group_id);
    }

    @Test
    public void theItemTheGuestCameInOnComesFirstWhenFree() {
        List<CottageKtvOption> offered = Arrays.asList(item(1, 10, 6, 3), item(2, 20, 4, 2), item(3, 30, 10, 4));

        assertEquals(3, FacilitySelectionRules.bookable(offered, 30).get(0).group_id);
    }

    @Test
    public void anUnavailableOriginIsHiddenAndTheRestStillOffered() {
        List<CottageKtvOption> offered = Arrays.asList(item(1, 10, 6, 3), item(2, 20, 4, 0));

        List<CottageKtvOption> shown = FacilitySelectionRules.bookable(offered, 20);

        assertEquals(1, shown.size());
        assertEquals(1, shown.get(0).group_id);
    }

    @Test
    public void aBusyKtvUnitIsNotOfferedButFreeOnesAre() {
        // KTV units are one physical room each: available_quantity is 1 when free, 0 when taken.
        List<CottageKtvOption> units = Arrays.asList(item(11, 11, 10, 1), item(12, 12, 10, 0), item(13, 13, 20, 1));

        List<CottageKtvOption> shown = FacilitySelectionRules.bookable(units, -1);

        assertEquals(2, shown.size());
        assertEquals(11, shown.get(0).group_id);
        assertEquals(13, shown.get(1).group_id);
    }

    @Test
    public void changedScheduleDropsPicksThatAreNoLongerFreeAndLowersTheRest() {
        // Picked 3 of cottage 1, the busy unit 12 and 2 of cottage 3; the new schedule frees only 2 of 1.
        Map<Integer, Integer> picks = picks(1, 3, 12, 1, 3, 2);
        Map<Integer, Integer> nowFree = new LinkedHashMap<>();
        nowFree.put(1, 2);

        assertTrue(RoomSelectionRules.clampPicks(picks, nowFree));
        assertEquals(2, (int) picks.get(1));
        assertEquals(0, (int) picks.get(12));
        assertEquals(0, (int) picks.get(3));
    }

    @Test
    public void capacityIsItemCapacityTimesQuantityAndAddsUpAcrossTypes() {
        List<CottageKtvOption> offered = Arrays.asList(item(1, 10, 6, 5), item(2, 20, 4, 5));

        // 2 x 6 + 1 x 4 = 16
        assertEquals(16, FacilitySelectionRules.totalCapacity(offered, picks(1, 2, 2, 1)));
        assertFalse(FacilitySelectionRules.exceedsCapacity(offered, picks(1, 2, 2, 1), 16));
        assertTrue(FacilitySelectionRules.exceedsCapacity(offered, picks(1, 2, 2, 1), 17));
    }

    @Test
    public void aPartyAboveASingleKtvRoomsCapacityOnlyShowsTheNotice() {
        List<CottageKtvOption> units = Arrays.asList(item(11, 11, 10, 1), item(13, 13, 20, 1));

        // One 10-person room, 14 guests: over capacity, so the notice shows.
        assertTrue(FacilitySelectionRules.exceedsCapacity(units, picks(11, 1), 14));
        // Add the 20-person room: 30 seats, so no notice.
        assertFalse(FacilitySelectionRules.exceedsCapacity(units, picks(11, 1, 13, 1), 14));
    }

    @Test
    public void noNoticeWithNothingPickedOrAnUnknownCapacity() {
        List<CottageKtvOption> offered = Arrays.asList(item(1, 10, 0, 5), item(2, 20, 4, 5));

        assertFalse(FacilitySelectionRules.exceedsCapacity(offered, picks(1, 0, 2, 0), 50));
        assertEquals(-1, FacilitySelectionRules.totalCapacity(offered, picks(1, 1, 2, 1)));
        assertFalse(FacilitySelectionRules.exceedsCapacity(offered, picks(1, 1, 2, 1), 50));
    }
}
