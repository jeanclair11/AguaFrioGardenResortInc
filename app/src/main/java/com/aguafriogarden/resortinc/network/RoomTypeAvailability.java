package com.aguafriogarden.resortinc.network;

/**
 * One room variant's (e.g. "STANDARD 2PAX") live availability within its room
 * type category (e.g. "Villa") for a searched stay, from POST /api/booking/availability.
 */
public class RoomTypeAvailability {
    public int group_id;
    public int category_id;
    public String category_name;
    public int variant_id;
    public String variant_name;
    public String description;
    public String image_path;
    public int capacity;
    public double price_per_night;
    public int available_quantity;
    /** Home-card carousel, from GET /api/booking/room-types: storage paths, the Type's main image
     *  first, then the pictures of the first unit added under it. Null from an older server (the
     *  card then shows just {@link #image_path}). */
    public java.util.List<String> images;
}
