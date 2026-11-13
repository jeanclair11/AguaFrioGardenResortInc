package com.aguafriogarden.resortinc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aguafriogarden.resortinc.network.ReservationSummary;

import org.junit.Test;

/** Which category an alert is in, and whether it can open a booking or reservation. */
public class NotificationTargetTest {

    private static NotificationStore.Notification alert(String kind, long id) {
        NotificationStore.Notification n = new NotificationStore.Notification("t", "m", null, null, 0L,
                NotificationStore.Type.BOOKING);
        n.targetKind = kind;
        n.targetId = id;
        return n;
    }

    @Test
    public void categoryWordsMapToTypes() {
        assertEquals(NotificationStore.Type.RESERVATION, NotificationStore.typeFromKey("reservation"));
        assertEquals(NotificationStore.Type.BOOKING, NotificationStore.typeFromKey("booking"));
        assertEquals(NotificationStore.Type.PAYMENT, NotificationStore.typeFromKey("payment"));
        assertEquals(NotificationStore.Type.CHAT, NotificationStore.typeFromKey("chat"));
        assertEquals(NotificationStore.Type.FEEDBACK, NotificationStore.typeFromKey("feedback"));
    }

    @Test
    public void unknownOrMissingCategoryIsGeneral() {
        assertEquals(NotificationStore.Type.GENERAL, NotificationStore.typeFromKey(null));
        assertEquals(NotificationStore.Type.GENERAL, NotificationStore.typeFromKey(""));
        assertEquals(NotificationStore.Type.GENERAL, NotificationStore.typeFromKey("something-new"));
    }

    @Test
    public void anAlertWithAKindAndAnIdHasATarget() {
        assertTrue(alert(ReservationSummary.KIND_BOOKING, 12).hasTarget());
        assertTrue(alert(ReservationSummary.KIND_REQUEST, 3).hasTarget());
    }

    @Test
    public void anAlertMissingEitherHasNoTarget() {
        assertFalse(alert(null, 12).hasTarget());
        assertFalse(alert("", 12).hasTarget());
        assertFalse(alert(ReservationSummary.KIND_BOOKING, 0).hasTarget());
    }

    @Test
    public void onlyTheBookingKindIsABooking() {
        assertTrue(alert(ReservationSummary.KIND_BOOKING, 1).isTargetBooking());
        assertFalse(alert(ReservationSummary.KIND_REQUEST, 1).isTargetBooking());
    }
}
