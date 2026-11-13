package com.aguafriogarden.resortinc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.aguafriogarden.resortinc.network.MyBookingsResponse;
import com.aguafriogarden.resortinc.network.MyStatusesResponse;
import com.aguafriogarden.resortinc.network.ReservationSummary;
import com.aguafriogarden.resortinc.network.StatusCatalogResponse;
import com.aguafriogarden.resortinc.network.StatusDetail;
import com.google.gson.Gson;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The rules behind "every status comes from the backend": how the API's JSON maps onto the models,
 * which records a history filter keeps, when Pay/Cancel exist, and what a my-statuses poll means
 * for the records already on screen. Statuses here are built from the same JSON the API returns,
 * never typed into the app.
 */
public class StatusFeedTest {

    private static final Gson GSON = new Gson();

    // A trimmed copy of the real GET /booking/statuses payload.
    private static final String CATALOG_JSON = "{\"reservation\":["
            + "{\"kind\":\"reservation\",\"key\":\"pending\",\"label\":\"Pending\",\"color\":\"#f5a623\",\"definition\":\"Waiting for payment.\"},"
            + "{\"kind\":\"reservation\",\"key\":\"cancelled\",\"label\":\"Cancelled\",\"color\":\"#dc2626\",\"definition\":\"Cancelled before payment.\"},"
            + "{\"kind\":\"reservation\",\"key\":\"expired\",\"label\":\"Expired\",\"color\":\"#9ca3af\",\"definition\":\"Deadline passed.\"}],"
            + "\"booking\":["
            + "{\"kind\":\"booking\",\"key\":\"pending\",\"label\":\"Pending\",\"color\":\"#f5a623\",\"definition\":\"Paid, awaiting confirmation.\"},"
            + "{\"kind\":\"booking\",\"key\":\"booked\",\"label\":\"Booked\",\"color\":\"#16a34a\",\"definition\":\"Confirmed.\"},"
            + "{\"kind\":\"booking\",\"key\":\"cancelled\",\"label\":\"Cancelled\",\"color\":\"#dc2626\",\"definition\":\"Cancelled.\"},"
            + "{\"kind\":\"booking\",\"key\":\"for_check_in\",\"label\":\"For Check-in\",\"color\":\"#0ea5e9\",\"definition\":\"Arrival day.\"},"
            + "{\"kind\":\"booking\",\"key\":\"checked_in\",\"label\":\"Checked-in\",\"color\":\"#2563eb\",\"definition\":\"Staying.\"},"
            + "{\"kind\":\"booking\",\"key\":\"for_check_out\",\"label\":\"For Check-out\",\"color\":\"#d97706\",\"definition\":\"Departure day.\"},"
            + "{\"kind\":\"booking\",\"key\":\"checked_out\",\"label\":\"Checked-out\",\"color\":\"#4f46e5\",\"definition\":\"Stay closed.\"}]}";

    private static StatusCatalogResponse catalog() {
        return GSON.fromJson(CATALOG_JSON, StatusCatalogResponse.class);
    }

    private static StatusDetail bookingStatus(String key) {
        for (StatusDetail status : catalog().booking) {
            if (status.hasKey(key)) {
                return status;
            }
        }
        throw new AssertionError("no booking status " + key);
    }

    private static StatusDetail requestStatus(String key) {
        for (StatusDetail status : catalog().reservation) {
            if (status.hasKey(key)) {
                return status;
            }
        }
        throw new AssertionError("no reservation status " + key);
    }

    private static ReservationSummary record(String kind, int id, String reference, StatusDetail status) {
        ReservationSummary summary = new ReservationSummary();
        summary.kind = kind;
        summary.id = id;
        summary.booking_reference = reference;
        summary.status = status;
        return summary;
    }

    private static MyStatusesResponse.Row feedRow(String kind, int id, StatusDetail status) {
        MyStatusesResponse.Row row = new MyStatusesResponse.Row();
        row.kind = kind;
        row.id = id;
        row.status = status;
        return row;
    }

    // ---- JSON mapping ------------------------------------------------------------------------

    @Test
    public void catalogKeepsTheBackendsLabelsColoursAndOrder() {
        StatusCatalogResponse parsed = catalog();
        assertEquals(3, parsed.reservation.size());
        assertEquals(7, parsed.booking.size());
        assertEquals("Checked-in", parsed.booking.get(4).label);
        assertEquals("For Check-out", parsed.booking.get(5).label);
        assertEquals("#16a34a", parsed.booking.get(1).color);
        assertEquals("booking:for_check_in", parsed.booking.get(3).token());
    }

    @Test
    public void myBookingsRowCarriesItsStatusObject() {
        String json = "{\"reservations\":[{\"kind\":\"reservation\",\"id\":7,\"booking_reference\":\"AFR-000007\","
                + "\"status\":{\"kind\":\"booking\",\"key\":\"booked\",\"label\":\"Booked\",\"color\":\"#16a34a\","
                + "\"definition\":\"Confirmed.\"},\"current_step\":1,\"cancelled\":false}],\"live_version\":\"abc\"}";
        MyBookingsResponse parsed = GSON.fromJson(json, MyBookingsResponse.class);
        ReservationSummary row = parsed.reservations.get(0);
        assertTrue(row.isBooking());
        assertEquals("Booked", row.status.label);
        assertEquals("abc", parsed.live_version);
    }

    // ---- Pay / Cancel ------------------------------------------------------------------------

    @Test
    public void payAndCancelExistOnlyForAPendingRequest() {
        assertTrue(record(ReservationSummary.KIND_REQUEST, 1, "REQ-1", requestStatus("pending")).canPayOrCancel());
        assertFalse(record(ReservationSummary.KIND_REQUEST, 1, "REQ-1", requestStatus("cancelled")).canPayOrCancel());
        assertFalse(record(ReservationSummary.KIND_REQUEST, 1, "REQ-1", requestStatus("expired")).canPayOrCancel());
        // A paid request is a booking: its Pending status must not offer Pay or Cancel.
        assertFalse(record(ReservationSummary.KIND_BOOKING, 2, "AFR-2", bookingStatus("pending")).canPayOrCancel());
        assertFalse(record(ReservationSummary.KIND_REQUEST, 1, "REQ-1", null).canPayOrCancel());
    }

    // ---- History tabs: Current / Upcoming / Past ---------------------------------------------

    private static final String TODAY = "2026-11-12";

    private static ReservationSummary stay(String kind, StatusDetail status, String checkIn, String checkOut) {
        ReservationSummary summary = record(kind, 1, "REF", status);
        summary.check_in = checkIn;
        summary.check_out = checkOut;
        return summary;
    }

    private static int tab(ReservationSummary summary) {
        return ReservationHistory.tabOf(summary, TODAY);
    }

    @Test
    public void cancelledExpiredAndCheckedOutAreAlwaysPast() {
        // Even with future dates: the status decides.
        assertEquals(ReservationHistory.TAB_PAST,
                tab(stay(ReservationSummary.KIND_BOOKING, bookingStatus("cancelled"), "2026-12-01", "2026-12-03")));
        assertEquals(ReservationHistory.TAB_PAST,
                tab(stay(ReservationSummary.KIND_REQUEST, requestStatus("cancelled"), "2026-12-01", "2026-12-03")));
        assertEquals(ReservationHistory.TAB_PAST,
                tab(stay(ReservationSummary.KIND_REQUEST, requestStatus("expired"), "2026-12-01", "2026-12-03")));
        assertEquals(ReservationHistory.TAB_PAST,
                tab(stay(ReservationSummary.KIND_BOOKING, bookingStatus("checked_out"), "2026-11-10", "2026-11-13")));
    }

    @Test
    public void checkInTomorrowOrLaterIsUpcoming() {
        assertEquals(ReservationHistory.TAB_UPCOMING,
                tab(stay(ReservationSummary.KIND_BOOKING, bookingStatus("booked"), "2026-11-13", "2026-11-15")));
        assertEquals(ReservationHistory.TAB_UPCOMING,
                tab(stay(ReservationSummary.KIND_BOOKING, bookingStatus("pending"), "2027-03-01", "2027-03-04")));
        assertEquals(ReservationHistory.TAB_UPCOMING,
                tab(stay(ReservationSummary.KIND_REQUEST, requestStatus("pending"), "2026-11-20", "2026-11-22")));
    }

    @Test
    public void aStayThatIsOnItsDatesOrCheckedInIsCurrent() {
        // Check-in is today.
        assertEquals(ReservationHistory.TAB_CURRENT,
                tab(stay(ReservationSummary.KIND_BOOKING, bookingStatus("for_check_in"), TODAY, "2026-11-14")));
        // Mid-stay, checked in.
        assertEquals(ReservationHistory.TAB_CURRENT,
                tab(stay(ReservationSummary.KIND_BOOKING, bookingStatus("checked_in"), "2026-11-10", "2026-11-14")));
        // Check-out is today and the guest has not left yet.
        assertEquals(ReservationHistory.TAB_CURRENT,
                tab(stay(ReservationSummary.KIND_BOOKING, bookingStatus("for_check_out"), "2026-11-10", TODAY)));
        // A pending request whose dates are already running.
        assertEquals(ReservationHistory.TAB_CURRENT,
                tab(stay(ReservationSummary.KIND_REQUEST, requestStatus("pending"), "2026-11-11", "2026-11-13")));
    }

    @Test
    public void aGuestStillInTheHouseStaysCurrentWhateverTheDatesSay() {
        // Check-out date was Nov 12, today is Nov 13, and the backend still says checked in:
        // an extension, or a check-out the system has not done yet. The status is the truth.
        assertEquals(ReservationHistory.TAB_CURRENT,
                tab(stay(ReservationSummary.KIND_BOOKING, bookingStatus("checked_in"), "2026-11-10", "2026-11-12")));
        assertEquals(ReservationHistory.TAB_CURRENT,
                tab(stay(ReservationSummary.KIND_BOOKING, bookingStatus("for_check_out"), "2026-11-08", "2026-11-12")));
        // Until the backend says otherwise: checked-out, and (for completeness) cancelled/expired, are Past.
        assertEquals(ReservationHistory.TAB_PAST,
                tab(stay(ReservationSummary.KIND_BOOKING, bookingStatus("checked_out"), "2026-11-10", "2026-11-12")));
        assertEquals(ReservationHistory.TAB_PAST,
                tab(stay(ReservationSummary.KIND_BOOKING, bookingStatus("cancelled"), "2026-11-10", "2026-11-12")));
        assertEquals(ReservationHistory.TAB_PAST,
                tab(stay(ReservationSummary.KIND_REQUEST, requestStatus("expired"), "2026-11-10", "2026-11-12")));
    }

    @Test
    public void anExtensionThatMovesTheCheckOutDateKeepsTheStayCurrent() {
        assertEquals(ReservationHistory.TAB_CURRENT,
                tab(stay(ReservationSummary.KIND_BOOKING, bookingStatus("checked_in"), "2026-11-10", "2026-11-15")));
    }

    @Test
    public void aRecordThatNeverCheckedInAndWhoseCheckOutHasGoneByIsPastAsBefore() {
        // The previous version's date rule, kept for everything that is not in the house.
        assertEquals(ReservationHistory.TAB_PAST,
                tab(stay(ReservationSummary.KIND_BOOKING, bookingStatus("booked"), "2026-11-01", "2026-11-03")));
        assertEquals(ReservationHistory.TAB_PAST,
                tab(stay(ReservationSummary.KIND_REQUEST, requestStatus("pending"), "2026-11-01", "2026-11-03")));
    }

    @Test
    public void recordsWithoutAStatusOrDatesStillLandSomewhere() {
        assertEquals(ReservationHistory.TAB_CURRENT, tab(stay(ReservationSummary.KIND_BOOKING, null, null, null)));
        assertEquals(ReservationHistory.TAB_UPCOMING, tab(stay(ReservationSummary.KIND_BOOKING, null, "2026-11-20", null)));
    }

    @Test
    public void sectionsSplitRequestsFromBookingsAndSelectByTab() {
        ReservationSummary pendingRequest = stay(ReservationSummary.KIND_REQUEST, requestStatus("pending"), "2099-01-01", "2099-01-03");
        ReservationSummary expiredRequest = stay(ReservationSummary.KIND_REQUEST, requestStatus("expired"), "2099-01-01", "2099-01-03");
        ReservationSummary bookedLater = stay(ReservationSummary.KIND_BOOKING, bookingStatus("booked"), "2099-02-01", "2099-02-03");
        ReservationSummary staying = stay(ReservationSummary.KIND_BOOKING, bookingStatus("checked_in"), "2000-01-01", "2099-01-01");
        ReservationSummary done = stay(ReservationSummary.KIND_BOOKING, bookingStatus("checked_out"), "2000-01-01", "2000-01-03");
        List<ReservationSummary> all = Arrays.asList(pendingRequest, expiredRequest, bookedLater, staying, done);

        List<ReservationSummary> upcomingRequests = ReservationHistory.select(
                all, ReservationHistory.SECTION_RESERVATIONS, ReservationHistory.TAB_UPCOMING);
        assertEquals(1, upcomingRequests.size());
        assertSame(pendingRequest, upcomingRequests.get(0));
        assertSame(expiredRequest, ReservationHistory.select(
                all, ReservationHistory.SECTION_RESERVATIONS, ReservationHistory.TAB_PAST).get(0));

        assertSame(bookedLater, ReservationHistory.select(
                all, ReservationHistory.SECTION_BOOKINGS, ReservationHistory.TAB_UPCOMING).get(0));
        assertSame(staying, ReservationHistory.select(
                all, ReservationHistory.SECTION_BOOKINGS, ReservationHistory.TAB_CURRENT).get(0));
        assertSame(done, ReservationHistory.select(
                all, ReservationHistory.SECTION_BOOKINGS, ReservationHistory.TAB_PAST).get(0));
        assertEquals(0, ReservationHistory.count(all, ReservationHistory.SECTION_RESERVATIONS, ReservationHistory.TAB_CURRENT));
    }

    // ---- Before the catalog has loaded (cold start, offline, first launch) --------------------

    @Test
    public void anUnloadedCatalogIsEmptyNotNull() {
        // This is the state the Profile screen binds in on a cold start; it crashed the app
        // when the catalog's lists were null.
        assertNotNull(StatusCatalog.reservation());
        assertNotNull(StatusCatalog.booking());
        assertTrue(StatusCatalog.forSection(true).isEmpty());
        assertTrue(StatusCatalog.forSection(false).isEmpty());
        assertTrue(StatusCatalog.bookingProgress().isEmpty());
        assertNull(StatusCatalog.find(StatusDetail.KIND_BOOKING, "booked"));
    }

    // ---- What a my-statuses poll does --------------------------------------------------------

    @Test
    public void confirmingABookingPatchesItsBadgeInPlaceAndRefetches() {
        ReservationSummary booking = record(ReservationSummary.KIND_BOOKING, 3, "AFR-3", bookingStatus("pending"));
        List<ReservationSummary> rows = new ArrayList<>(Arrays.asList(booking));

        FeedDiff diff = FeedDiff.apply(rows, Arrays.asList(
                feedRow(ReservationSummary.KIND_BOOKING, 3, bookingStatus("booked"))));

        assertTrue(diff.displayChanged);
        assertTrue(diff.needsRefetch);
        assertEquals("Booked", booking.status.label);
        assertSame(booking, rows.get(0));
    }

    @Test
    public void anUnchangedPollTouchesNothing() {
        List<ReservationSummary> rows = new ArrayList<>(Arrays.asList(
                record(ReservationSummary.KIND_BOOKING, 3, "AFR-3", bookingStatus("booked"))));

        FeedDiff diff = FeedDiff.apply(rows, Arrays.asList(
                feedRow(ReservationSummary.KIND_BOOKING, 3, bookingStatus("booked"))));

        assertFalse(diff.displayChanged);
        assertFalse(diff.needsRefetch);
    }

    @Test
    public void aPaidRequestLeavingTheRequestListAndAppearingAsABookingForcesARefetch() {
        List<ReservationSummary> rows = new ArrayList<>(Arrays.asList(
                record(ReservationSummary.KIND_REQUEST, 5, "REQ-5", requestStatus("pending"))));

        // After payment the poll no longer lists the request; a booking with its own id is there.
        FeedDiff diff = FeedDiff.apply(rows, Arrays.asList(
                feedRow(ReservationSummary.KIND_BOOKING, 11, bookingStatus("pending"))));

        assertTrue(diff.needsRefetch);
    }

    @Test
    public void aRequestThatExpiresMovesToExpired() {
        ReservationSummary request = record(ReservationSummary.KIND_REQUEST, 5, "REQ-5", requestStatus("pending"));
        List<ReservationSummary> rows = new ArrayList<>(Arrays.asList(request));

        FeedDiff diff = FeedDiff.apply(rows, Arrays.asList(
                feedRow(ReservationSummary.KIND_REQUEST, 5, requestStatus("expired"))));

        assertTrue(diff.displayChanged);
        assertEquals("Expired", request.status.label);
    }

    @Test
    public void aRewordedStatusUnderTheSameKeyIsPatchedWithoutARefetch() {
        ReservationSummary booking = record(ReservationSummary.KIND_BOOKING, 3, "AFR-3", bookingStatus("booked"));
        List<ReservationSummary> rows = new ArrayList<>(Arrays.asList(booking));
        StatusDetail reworded = GSON.fromJson(GSON.toJson(bookingStatus("booked")), StatusDetail.class);
        reworded.label = "Confirmed";

        FeedDiff diff = FeedDiff.apply(rows, Arrays.asList(feedRow(ReservationSummary.KIND_BOOKING, 3, reworded)));

        assertTrue(diff.displayChanged);
        assertFalse(diff.needsRefetch);
        assertEquals("Confirmed", booking.status.label);
    }

    @Test
    public void sameIdInDifferentKindsAreDifferentRecords() {
        List<ReservationSummary> rows = Arrays.asList(
                record(ReservationSummary.KIND_REQUEST, 9, "REQ-9", requestStatus("pending")),
                record(ReservationSummary.KIND_BOOKING, 9, "AFR-9", bookingStatus("booked")));

        assertEquals("REQ-9", FeedDiff.find(rows, ReservationSummary.KIND_REQUEST, 9).booking_reference);
        assertEquals("AFR-9", FeedDiff.find(rows, ReservationSummary.KIND_BOOKING, 9).booking_reference);
        assertNull(FeedDiff.find(rows, ReservationSummary.KIND_BOOKING, 10));
        assertNotNull(FeedDiff.find(rows, ReservationSummary.KIND_BOOKING, 9));
    }
}
