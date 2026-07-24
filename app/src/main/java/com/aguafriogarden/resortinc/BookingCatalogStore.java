package com.aguafriogarden.resortinc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Placeholder catalog of bookable resort services (hotel rooms, KTV rooms,
 * function halls, pool &amp; cottage units) shown on the Book tab.
 *
 * Every field here mirrors what the Laravel REST API is expected to return
 * (id, display name, starting price, capacity, live availability,
 * description, amenities, filter tag). getCategories() and getItems() are
 * the only two entry points {@link BookingCatalogController} calls into
 * this class, so once the backend is available they're the only methods
 * that need to change into HTTP calls (e.g. GET /api/service-categories and
 * GET /api/service-categories/{id}/items) — nothing else should need to
 * change shape. filterTag is placeholder grouping data (e.g. building/wing)
 * for the browse page's Filter button and isn't meant to match real
 * property data yet.
 */
final class BookingCatalogStore {

    static final int CATEGORY_HOTEL = 1;
    static final int CATEGORY_KTV = 2;
    static final int CATEGORY_HALLS = 3;
    static final int CATEGORY_POOL = 4;

    static final int AVAILABILITY_AVAILABLE = 0;
    static final int AVAILABILITY_LIMITED = 1;
    static final int AVAILABILITY_FULL = 2;

    /** One of the four top-level booking categories shown on the catalog screen. */
    static final class Category {
        final int id;
        final int nameRes;
        final int iconRes;
        final int tileBackgroundRes;

        Category(int id, int nameRes, int iconRes, int tileBackgroundRes) {
            this.id = id;
            this.nameRes = nameRes;
            this.iconRes = iconRes;
            this.tileBackgroundRes = tileBackgroundRes;
        }
    }

    /** One bookable option within a category (e.g. "Deluxe Room" under Hotel Rooms). */
    static final class ServiceItem {
        final int categoryId;
        final String name;
        final String startingPriceLabel; // e.g. "From PHP 2,500 / night"; null when the backend has no rate yet
        final String capacityLabel; // e.g. "2-4 pax"; null when not applicable
        final int availability;
        final String description;
        final String[] amenities;
        final String filterTag; // placeholder browse-filter grouping; null if uncategorized

        ServiceItem(int categoryId, String name, String startingPriceLabel, String capacityLabel,
                int availability, String description, String[] amenities, String filterTag) {
            this.categoryId = categoryId;
            this.name = name;
            this.startingPriceLabel = startingPriceLabel;
            this.capacityLabel = capacityLabel;
            this.availability = availability;
            this.description = description;
            this.amenities = amenities;
            this.filterTag = filterTag;
        }
    }

    private static final Category[] CATEGORIES = {
            new Category(CATEGORY_HOTEL, R.string.category_hotel_rooms,
                    R.drawable.ic_bed, R.drawable.bg_catalog_tile_hotel),
            new Category(CATEGORY_KTV, R.string.category_ktv_rooms,
                    R.drawable.ic_mic, R.drawable.bg_catalog_tile_ktv),
            new Category(CATEGORY_HALLS, R.string.category_function_halls,
                    R.drawable.ic_hall, R.drawable.bg_catalog_tile_halls),
            new Category(CATEGORY_POOL, R.string.category_pool_cottage,
                    R.drawable.ic_pool, R.drawable.bg_catalog_tile_pool),
    };

    private static final ServiceItem[] ITEMS = {
            // Hotel Rooms
            new ServiceItem(CATEGORY_HOTEL, "Standard Room", "From PHP 2,200 / night", "2 pax",
                    AVAILABILITY_AVAILABLE,
                    "A cozy, comfortable room with all the essentials for a relaxing overnight stay.",
                    new String[]{"Free Wi-Fi", "Air-conditioning", "Private bathroom", "Daily housekeeping"},
                    "Villa Rooms"),
            new ServiceItem(CATEGORY_HOTEL, "Deluxe Room", "From PHP 3,200 / night", "2-3 pax",
                    AVAILABILITY_AVAILABLE,
                    "A spacious upgrade with premium furnishings and a garden view.",
                    new String[]{"Free Wi-Fi", "Air-conditioning", "Garden view", "Complimentary breakfast"},
                    "Villa Rooms"),
            new ServiceItem(CATEGORY_HOTEL, "Family Room", "From PHP 4,500 / night", "4-5 pax",
                    AVAILABILITY_LIMITED,
                    "Extra sleeping space designed for families traveling together.",
                    new String[]{"Free Wi-Fi", "Air-conditioning", "Extra bedding", "Complimentary breakfast"},
                    "Claricon Rooms"),
            new ServiceItem(CATEGORY_HOTEL, "Executive Suite", "From PHP 6,800 / night", "2-4 pax",
                    AVAILABILITY_AVAILABLE,
                    "Our most luxurious accommodation with a separate living area.",
                    new String[]{"Free Wi-Fi", "Air-conditioning", "Living area", "Premium toiletries",
                            "Complimentary breakfast"},
                    "KTV Hotel Rooms"),

            // KTV Consumable Rooms
            new ServiceItem(CATEGORY_KTV, "Small KTV Room", "From PHP 800 / hour", "2-6 pax",
                    AVAILABILITY_AVAILABLE,
                    "An intimate room for a quick song session with close friends.",
                    new String[]{"Karaoke system", "Premium sound", "Consumable package"},
                    "Standard KTV"),
            new ServiceItem(CATEGORY_KTV, "Medium KTV Room", "From PHP 1,500 / hour", "6-12 pax",
                    AVAILABILITY_LIMITED,
                    "A roomier setup ideal for birthdays and small get-togethers.",
                    new String[]{"Karaoke system", "Premium sound", "Consumable package", "LED lighting"},
                    "Standard KTV"),
            new ServiceItem(CATEGORY_KTV, "Large VIP KTV Room", "From PHP 2,800 / hour", "12-20 pax",
                    AVAILABILITY_AVAILABLE,
                    "Our largest room with VIP amenities for big celebrations.",
                    new String[]{"Karaoke system", "Premium sound", "Consumable package", "LED lighting",
                            "VIP lounge seating"},
                    "VIP KTV"),

            // Function Halls
            new ServiceItem(CATEGORY_HALLS, "Garden Hall", "From PHP 15,000 / event", "Up to 150 pax",
                    AVAILABILITY_AVAILABLE,
                    "An open-air garden setting perfect for intimate gatherings.",
                    new String[]{"Custom setup", "With AV system", "Outdoor seating"},
                    "Outdoor Venues"),
            new ServiceItem(CATEGORY_HALLS, "Grand Ballroom", "From PHP 45,000 / event", "Up to 500 pax",
                    AVAILABILITY_LIMITED,
                    "A grand indoor venue for weddings and large celebrations.",
                    new String[]{"Custom setup", "With AV system", "Stage & lighting", "Bridal room"},
                    "Indoor Venues"),
            new ServiceItem(CATEGORY_HALLS, "Conference Hall", "From PHP 12,000 / event", "Up to 100 pax",
                    AVAILABILITY_AVAILABLE,
                    "A professional space equipped for meetings and seminars.",
                    new String[]{"With AV system", "Projector & screen", "High-speed Wi-Fi"},
                    "Indoor Venues"),
            new ServiceItem(CATEGORY_HALLS, "Event Pavilion", "From PHP 20,000 / event", "Up to 250 pax",
                    AVAILABILITY_FULL,
                    "A semi-open pavilion ideal for themed parties and reunions.",
                    new String[]{"Custom setup", "With AV system", "Covered seating"},
                    "Outdoor Venues"),

            // Pool & Cottage
            new ServiceItem(CATEGORY_POOL, "Open Cottage", "From PHP 1,200 / day use", "Up to 6 pax",
                    AVAILABILITY_AVAILABLE,
                    "A simple open-air cottage steps away from the pool.",
                    new String[]{"Pool access", "Picnic table", "Day use"},
                    "Standard Cottages"),
            new ServiceItem(CATEGORY_POOL, "Family Cottage", "From PHP 2,000 / day use", "Up to 10 pax",
                    AVAILABILITY_AVAILABLE,
                    "A shaded cottage with extra room for the whole family.",
                    new String[]{"Pool access", "Picnic table", "Electric fan", "Day use"},
                    "Standard Cottages"),
            new ServiceItem(CATEGORY_POOL, "VIP Cottage", "From PHP 3,500 / day use", "Up to 12 pax",
                    AVAILABILITY_LIMITED,
                    "An enclosed cottage with added privacy and comfort.",
                    new String[]{"Pool access", "Air-conditioning", "Private restroom", "Day use"},
                    "Premium Cottages"),
            new ServiceItem(CATEGORY_POOL, "Poolside Cottage", "From PHP 2,600 / day use", "Up to 8 pax",
                    AVAILABILITY_AVAILABLE,
                    "Sit right at the water's edge with a full pool view.",
                    new String[]{"Pool access", "Poolside view", "Picnic table", "Day use"},
                    "Standard Cottages"),
            new ServiceItem(CATEGORY_POOL, "Private Cottage", "From PHP 4,200 / day use", "Up to 15 pax",
                    AVAILABILITY_FULL,
                    "Our most secluded cottage for a fully private resort day.",
                    new String[]{"Pool access", "Air-conditioning", "Private restroom", "Dedicated staff",
                            "Day use"},
                    "Premium Cottages"),
    };

    private BookingCatalogStore() {
    }

    /** Placeholder for GET /api/service-categories. */
    static Category[] getCategories() {
        return CATEGORIES;
    }

    /** Placeholder for GET /api/service-categories/{categoryId}/items. */
    static ServiceItem[] getItems(int categoryId) {
        List<ServiceItem> matches = new ArrayList<>();
        for (ServiceItem item : ITEMS) {
            if (item.categoryId == categoryId) {
                matches.add(item);
            }
        }
        return matches.toArray(new ServiceItem[0]);
    }

    /** Distinct filter tags among a category's items, in first-seen order. */
    static String[] getFilterTags(int categoryId) {
        Set<String> tags = new LinkedHashSet<>();
        for (ServiceItem item : ITEMS) {
            if (item.categoryId == categoryId && item.filterTag != null) {
                tags.add(item.filterTag);
            }
        }
        return tags.toArray(new String[0]);
    }

    static Category findCategory(int categoryId) {
        for (Category category : CATEGORIES) {
            if (category.id == categoryId) {
                return category;
            }
        }
        return null;
    }

    static int availabilityLabelRes(int availability) {
        switch (availability) {
            case AVAILABILITY_LIMITED:
                return R.string.booking_status_limited;
            case AVAILABILITY_FULL:
                return R.string.booking_status_full;
            default:
                return R.string.booking_status_available;
        }
    }

    static int availabilityBadgeRes(int availability) {
        switch (availability) {
            case AVAILABILITY_LIMITED:
                return R.drawable.bg_badge_pending;
            case AVAILABILITY_FULL:
                return R.drawable.bg_badge_neutral;
            default:
                return R.drawable.bg_badge_success;
        }
    }
}
