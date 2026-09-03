package com.aguafriogarden.resortinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Static mock accommodation catalog for the Reservation Module's "Available
 * Hotel Rooms" screen. Shaped like the real {@code RoomTypeAvailability} DTO
 * ({@link com.aguafriogarden.resortinc.network.RoomTypeAvailability}) so this
 * can be swapped for a live endpoint later without reworking the screen.
 */
final class ReservationCatalog {

    static final class RoomOption {
        final int id;
        final String categoryName;
        final String variantName;
        final String description;
        final int capacity;
        final int pricePerNight;
        final int maxQuantity;

        RoomOption(int id, String categoryName, String variantName, String description,
                int capacity, int pricePerNight, int maxQuantity) {
            this.id = id;
            this.categoryName = categoryName;
            this.variantName = variantName;
            this.description = description;
            this.capacity = capacity;
            this.pricePerNight = pricePerNight;
            this.maxQuantity = maxQuantity;
        }
    }

    private static final List<RoomOption> OPTIONS = Collections.unmodifiableList(new ArrayList<RoomOption>() {{
        add(new RoomOption(1, "Claricon", "Standard 3PAX",
                "Cozy air-conditioned room with a private bath, perfect for small groups.", 4, 600, 5));
        add(new RoomOption(2, "Claricon", "Deluxe 4PAX",
                "Spacious room with a garden view and extra sleeping space.", 4, 850, 4));
        add(new RoomOption(3, "KTV Hotel Rooms", "Dorm Type",
                "Shared-style room with bunk beds, ideal for barkada trips.", 4, 400, 6));
        add(new RoomOption(4, "Villa", "Matrimonial A (2PAX)",
                "Private villa room with a queen bed, ideal for couples.", 2, 750, 3));
        add(new RoomOption(5, "Villa", "Matrimonial B (2PAX)",
                "Private villa room with garden access.", 2, 800, 3));
    }});

    static List<RoomOption> all() {
        return OPTIONS;
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
