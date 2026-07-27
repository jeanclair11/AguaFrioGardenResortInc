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
}
