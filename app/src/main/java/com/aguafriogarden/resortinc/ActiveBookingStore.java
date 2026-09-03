package com.aguafriogarden.resortinc;

/**
 * Shared display constants and lookup helpers for the Home tab's Booking
 * Progress section and Booking Details screen. Reservation data itself comes
 * from the shared backend (see {@link com.aguafriogarden.resortinc.network.ReservationSummary}
 * via {@link DashboardHomeController}); this class only maps a reservation's
 * service type to its icon/label/step-lifecycle wording.
 */
final class ActiveBookingStore {

    static final int SERVICE_HOTEL = 0;
    static final int SERVICE_KTV = 1;
    static final int SERVICE_COTTAGE = 2;
    static final int SERVICE_HALL = 3;

    private ActiveBookingStore() {
    }

    /** Maps the backend's service_type string (e.g. "hotel", "cottage", "hall") to its constant. */
    static int serviceTypeFromString(String type) {
        if ("cottage".equals(type)) {
            return SERVICE_COTTAGE;
        }
        if ("hall".equals(type)) {
            return SERVICE_HALL;
        }
        if ("ktv".equals(type)) {
            return SERVICE_KTV;
        }
        return SERVICE_HOTEL;
    }

    static int serviceIconRes(int serviceType) {
        switch (serviceType) {
            case SERVICE_KTV:
                return R.drawable.ic_mic;
            case SERVICE_COTTAGE:
                return R.drawable.ic_pool;
            case SERVICE_HALL:
                return R.drawable.ic_hall;
            default:
                return R.drawable.ic_bed;
        }
    }

    static int serviceTypeLabelRes(int serviceType) {
        switch (serviceType) {
            case SERVICE_KTV:
                return R.string.service_type_ktv;
            case SERVICE_COTTAGE:
                return R.string.service_type_cottage;
            case SERVICE_HALL:
                return R.string.service_type_hall;
            default:
                return R.string.service_type_hotel;
        }
    }

    /** The 4 lifecycle step labels, indexed by a reservation's current_step (0-3). Same across all service types. */
    static final int[] STEP_LABELS_RES = {
            R.string.step_pending_booking, R.string.step_confirmed, R.string.step_ongoing, R.string.step_checked_out,
    };

    /** The short description shown above the progress indicator for each current_step (0-3). */
    static final int[] STEP_DESCRIPTIONS_RES = {
            R.string.desc_pending_booking, R.string.desc_confirmed, R.string.desc_ongoing, R.string.desc_checked_out,
    };
}
