package com.aguafriogarden.resortinc;

/**
 * Holds a room/cottage/KTV selection made before login. After successful login, the overview
 * screen is shown instead of the home page. Once the guest taps its CTA button, this is cleared
 * and either the hotel booking flow or the Reserve tab begins, depending on {@code purpose}.
 * {@code type} drives the overview screen's header text ("You Selected This Room/Cottage/KTV
 * Room") and which browse category its back button returns to — kept as a plain int rather than
 * LandingActivity's private Section enum since this class has no visibility into it.
 */
final class PendingRoomSelection {
    static final int TYPE_ROOM = 0;
    static final int TYPE_COTTAGE = 1;
    static final int TYPE_KTV = 2;

    /** Which button on the browse card was tapped — same overview screen either way, but its
     *  CTA button's label ("Book Now" vs "Reserve Now") and destination differ (see
     *  LandingActivity#showRoomOverview). */
    static final int PURPOSE_BOOK = 0;
    static final int PURPOSE_RESERVE = 1;

    private static PendingRoomSelection current;

    final String imagePath;
    final String roomTypeName;
    final String categoryName;
    final int capacity;
    final String description;
    final String pricePerNight;
    final int variantId;
    final int type;
    final int purpose;

    private PendingRoomSelection(String imagePath, String roomTypeName, String categoryName,
                                 int capacity, String description, String pricePerNight, int variantId, int type,
                                 int purpose) {
        this.imagePath = imagePath;
        this.roomTypeName = roomTypeName;
        this.categoryName = categoryName;
        this.capacity = capacity;
        this.description = description;
        this.pricePerNight = pricePerNight;
        this.variantId = variantId;
        this.type = type;
        this.purpose = purpose;
    }

    static void set(String imagePath, String roomTypeName, String categoryName,
                    int capacity, String description, String pricePerNight, int variantId, int type,
                    int purpose) {
        current = new PendingRoomSelection(imagePath, roomTypeName, categoryName,
                capacity, description, pricePerNight, variantId, type, purpose);
    }

    static PendingRoomSelection take() {
        PendingRoomSelection result = current;
        current = null;
        return result;
    }

    static boolean has() {
        return current != null;
    }
}
