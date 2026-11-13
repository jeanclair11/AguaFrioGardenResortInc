package com.aguafriogarden.resortinc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aguafriogarden.resortinc.network.CottageKtvOption;
import com.aguafriogarden.resortinc.network.KtvUnitListResponse;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KTV rental duration, rate type, end time and price are never entered by the guest: they are what
 * the Admin configured for each room's Type, and the rooms picked together share one session.
 */
public class KtvRentalRulesTest {

    private static CottageKtvOption room(int groupId, int hours, String label, double price) {
        CottageKtvOption item = new CottageKtvOption();
        item.group_id = groupId;
        item.unit_id = groupId;
        item.rental_hours = hours;
        item.rate_label = label;
        item.price = price;
        item.available_quantity = 1;
        return item;
    }

    private static Map<Integer, Integer> picks(int... groupIds) {
        Map<Integer, Integer> picks = new LinkedHashMap<>();
        for (int id : groupIds) {
            picks.put(id, 1);
        }
        return picks;
    }

    @Test
    public void theLengthIsTheConfiguredOneOfThePickedRoom() {
        List<CottageKtvOption> offered = Arrays.asList(room(1, 3, "3 Hours", 1500), room(2, 5, "5 Hours", 2500));

        assertEquals(3, KtvRentalRules.longestHours(offered, picks(1)));
        assertEquals(5, KtvRentalRules.longestHours(offered, picks(2)));
    }

    @Test
    public void nothingPickedMeansNoLengthYet() {
        List<CottageKtvOption> offered = Arrays.asList(room(1, 3, "3 Hours", 1500));

        assertEquals(0, KtvRentalRules.longestHours(offered, picks()));
        assertTrue(KtvRentalRules.pickedHours(offered, picks()).isEmpty());
    }

    @Test
    public void roomsOfDifferentLengthsEndAtTheLongestLikeTheReceptionist() {
        List<CottageKtvOption> offered = Arrays.asList(room(1, 3, "3 Hours", 1500), room(2, 5, "5 Hours", 2500));

        assertEquals(5, KtvRentalRules.longestHours(offered, picks(1, 2)));
        assertEquals(Arrays.asList(3, 5), KtvRentalRules.pickedHours(offered, picks(1, 2)));
        assertEquals("3 Hours, 5 Hours", KtvRentalRules.rateLabel(offered, picks(1, 2)));
    }

    @Test
    public void roomsWithTheSameLengthShareOneRateType() {
        List<CottageKtvOption> offered = Arrays.asList(room(1, 3, "3 Hours", 1500), room(2, 3, "3 Hours", 2200));

        assertEquals(Arrays.asList(3), KtvRentalRules.pickedHours(offered, picks(1, 2)));
        assertEquals("3 Hours", KtvRentalRules.rateLabel(offered, picks(1, 2)));
    }

    @Test
    public void thePriceIsTheSumOfTheConfiguredRates() {
        List<CottageKtvOption> offered = Arrays.asList(room(1, 3, "3 Hours", 1500), room(2, 3, "3 Hours", 2200),
                room(3, 3, "3 Hours", 900));

        assertEquals(3700, KtvRentalRules.rentalSubtotal(offered, picks(1, 2)));
    }

    @Test
    public void anUnconfiguredRoomIsNotAllConfigured() {
        List<CottageKtvOption> offered = Arrays.asList(room(1, 3, "3 Hours", 1500), room(2, 0, null, 0));

        assertTrue(KtvRentalRules.allConfigured(offered, picks(1)));
        assertFalse(KtvRentalRules.allConfigured(offered, picks(1, 2)));
    }

    @Test
    public void theEndTimeIsTheStartPlusTheConfiguredLength() {
        // 1:00 PM + 3 hours = 4:00 PM
        assertEquals(16 * 60, KtvRentalRules.endMinuteOfDay(13, 0, 3));
        assertEquals(16 * 60 + 30, KtvRentalRules.endMinuteOfDay(13, 30, 3));
    }

    @Test
    public void theEndTimeWrapsPastMidnightLikeTheServer() {
        assertEquals(60, KtvRentalRules.endMinuteOfDay(22, 0, 3));
    }

    @Test
    public void noEndTimeUntilThereIsAStartAndALength() {
        assertEquals(-1, KtvRentalRules.endMinuteOfDay(-1, -1, 3));
        assertEquals(-1, KtvRentalRules.endMinuteOfDay(13, 0, 0));
    }

    private static KtvUnitListResponse.Unit unit(int id, boolean available) {
        KtvUnitListResponse.Unit unit = new KtvUnitListResponse.Unit();
        unit.unit_id = id;
        unit.unit_name = "KTV Room " + id;
        unit.available = available;
        return unit;
    }

    private static KtvUnitListResponse.Package pack(String label, int hours, double amount) {
        KtvUnitListResponse.Package p = new KtvUnitListResponse.Package();
        p.label = label;
        p.hours = hours;
        p.amount = amount;
        return p;
    }

    @Test
    public void theServersRentalFieldDrivesTheOption() {
        KtvUnitListResponse.Unit unit = unit(7, true);
        unit.rental = pack("3 Hours", 3, 1500);
        KtvUnitListResponse response = new KtvUnitListResponse();
        response.ktv_units = Arrays.asList(unit);

        CottageKtvOption option = response.toOptions().get(0);

        assertEquals(3, option.rental_hours);
        assertEquals("3 Hours", option.rate_label);
        assertEquals(1500.0, option.price, 0.001);
        assertEquals(1, option.available_quantity);
    }

    @Test
    public void anOlderServerFallsBackToItsFirstPackage() {
        KtvUnitListResponse.Unit unit = unit(7, true);
        unit.packages = Arrays.asList(pack("3 Hours", 3, 1500));
        KtvUnitListResponse response = new KtvUnitListResponse();
        response.ktv_units = Arrays.asList(unit);

        assertEquals(3, response.toOptions().get(0).rental_hours);
    }

    @Test
    public void aUnitWithNoRentalConfiguredIsNeverOffered() {
        KtvUnitListResponse response = new KtvUnitListResponse();
        response.ktv_units = Arrays.asList(unit(7, true));

        CottageKtvOption option = response.toOptions().get(0);

        assertEquals(0, option.rental_hours);
        assertEquals(0, option.available_quantity);
    }

    @Test
    public void aTakenUnitStaysUnavailable() {
        KtvUnitListResponse.Unit unit = unit(7, false);
        unit.rental = pack("3 Hours", 3, 1500);
        KtvUnitListResponse response = new KtvUnitListResponse();
        response.ktv_units = Arrays.asList(unit);

        assertEquals(0, response.toOptions().get(0).available_quantity);
    }
}
