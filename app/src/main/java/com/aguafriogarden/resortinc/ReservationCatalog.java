package com.aguafriogarden.resortinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Static mock catalog for the Reservation Module's Hotel Rooms tab only. Shaped like the
 * real {@code RoomTypeAvailability} DTO ({@link com.aguafriogarden.resortinc.network.RoomTypeAvailability})
 * so this can be swapped for a live endpoint later without reworking the screen.
 * <p>
 * Cottages & KTV options are NOT listed here — {@link ReservationFlowController} fetches
 * those live from {@code GET /api/booking/cottages-ktv} (Admin Web's Cottage Management /
 * KTV Management data) and builds {@link RoomOption} instances from the response instead,
 * reusing this class's {@code CATEGORY_COTTAGES_KTV}/{@code BOOKING_TYPE_*} constants and
 * {@link RoomOption} shape so both tabs flow through the same selection/rendering code.
 */
final class ReservationCatalog {

    static final int CATEGORY_HOTEL_ROOMS = 0;
    static final int CATEGORY_COTTAGES_KTV = 1;

    /** Only meaningful for {@link #CATEGORY_COTTAGES_KTV} options; matches the Booking Type
     *  dropdown's values so results can be filtered by it. Empty for Hotel Rooms options. */
    static final String BOOKING_TYPE_COTTAGE = "Cottage";
    static final String BOOKING_TYPE_KTV = "KTV";

    /** Client-only rate-type placeholders for the Cottage/KTV tabs (see
     *  ReservationFlowController) — the backend has no rate-type field yet, so these
     *  don't change pricing; they only drive the KTV tab's duration/end-time auto-calc. */
    static final String RATE_TYPE_DAY = "Day Rate";
    static final String RATE_TYPE_NIGHT = "Night Rate";
    static final String RATE_TYPE_REGULAR = "Regular KTV";
    static final String RATE_TYPE_CONSUMABLE = "Consumable KTV";

    static final class RoomOption {
        final int id;
        final int category;
        final String categoryName;
        final String variantName;
        final String description;
        final int capacity;
        final int pricePerNight;
        final String priceUnit;
        final int maxQuantity;
        final String bookingType;
        final String imagePath; // Admin Web photo path (Cottage/KTV only); null for Hotel Rooms

        RoomOption(int id, int category, String categoryName, String variantName, String description,
                int capacity, int pricePerNight, String priceUnit, int maxQuantity, String bookingType) {
            this(id, category, categoryName, variantName, description, capacity, pricePerNight,
                    priceUnit, maxQuantity, bookingType, null);
        }

        RoomOption(int id, int category, String categoryName, String variantName, String description,
                int capacity, int pricePerNight, String priceUnit, int maxQuantity, String bookingType,
                String imagePath) {
            this.id = id;
            this.category = category;
            this.categoryName = categoryName;
            this.variantName = variantName;
            this.description = description;
            this.capacity = capacity;
            this.pricePerNight = pricePerNight;
            this.priceUnit = priceUnit;
            this.maxQuantity = maxQuantity;
            this.bookingType = bookingType;
            this.imagePath = imagePath;
        }
    }

    private static final List<RoomOption> OPTIONS = Collections.unmodifiableList(new ArrayList<RoomOption>() {{
        // ---- Hotel Rooms (date-range, per-night) ---------------------------
        add(new RoomOption(1, CATEGORY_HOTEL_ROOMS, "Claricon", "Standard 3PAX",
                "Cozy air-conditioned room with a private bath, perfect for small groups.",
                4, 600, "night", 5, ""));
        add(new RoomOption(2, CATEGORY_HOTEL_ROOMS, "Claricon", "Deluxe 4PAX",
                "Spacious room with a garden view and extra sleeping space.",
                4, 850, "night", 4, ""));
        add(new RoomOption(3, CATEGORY_HOTEL_ROOMS, "KTV Hotel Rooms", "Dorm Type",
                "Shared-style room with bunk beds, ideal for barkada trips.",
                4, 400, "night", 6, ""));
        add(new RoomOption(4, CATEGORY_HOTEL_ROOMS, "Villa", "Matrimonial A (2PAX)",
                "Private villa room with a queen bed, ideal for couples.",
                2, 750, "night", 3, ""));
        add(new RoomOption(5, CATEGORY_HOTEL_ROOMS, "Villa", "Matrimonial B (2PAX)",
                "Private villa room with garden access.",
                2, 800, "night", 3, ""));
    }});

    static List<RoomOption> all() {
        return OPTIONS;
    }

    static List<RoomOption> byCategory(int category) {
        List<RoomOption> result = new ArrayList<>();
        for (RoomOption option : OPTIONS) {
            if (option.category == category) {
                result.add(option);
            }
        }
        return result;
    }

    static RoomOption find(int id) {
        for (RoomOption option : OPTIONS) {
            if (option.id == id) {
                return option;
            }
        }
        return null;
    }

    private ReservationCatalog() {
    }
}
