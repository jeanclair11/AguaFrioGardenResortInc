package com.aguafriogarden.resortinc.network;

/**
 * One active, bookable room variant (e.g. "Standard 2 Pax") for the Home
 * screen's Hotel Rooms discovery list, from GET /api/booking/rooms.
 * {@code category_name} is its parent room type category (e.g. "Villa"),
 * shown only as secondary context — see {@link RoomTypeAvailability} for the
 * same category/variant split used by the availability endpoint. Unlike
 * {@link RoomTypeAvailability} this carries no date-specific quantity —
 * {@code available} reflects live occupancy right now, not availability for
 * a chosen stay.
 *
 * <p>{@code category}/{@code item_name} are kept alongside {@code category_name}
 * as fallbacks: this endpoint's exact JSON field for the category couldn't be
 * confirmed against the live backend (no authenticated access to it was
 * available), and this codebase isn't consistent about the name for this
 * concept elsewhere either — compare {@code category_name} here and on
 * {@link RoomTypeAvailability} with {@code item_name} on
 * {@link ReservationSummary}. See {@link com.aguafriogarden.resortinc.DashboardHomeController}'s
 * category-label binding, which tries each in turn. Once the real field name
 * is confirmed, delete whichever of these three aren't it.
 */
public class RoomSummary {
    public int id;
    public String name;
    public String category_name;
    public String category;
    public String item_name;
    public String image_path;
    public int capacity;
    public double price_per_night;
    public String description;
    public boolean available;
}
