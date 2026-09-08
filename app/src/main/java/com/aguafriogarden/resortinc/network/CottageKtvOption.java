package com.aguafriogarden.resortinc.network;

/**
 * One Cottage or KTV Type, with its Category, from GET /api/booking/cottages-ktv —
 * sourced directly from the Admin Web's Cottage Management / KTV Management data
 * (CottageType/CottageVariant, KtvType/KtvVariant). Shaped like {@link RoomTypeAvailability}
 * for consistency, with {@code price}/{@code price_unit} in place of price_per_night since
 * Cottages/KTV aren't priced per night.
 */
public class CottageKtvOption {
    public int group_id;
    public int category_id;
    public String category_name;
    public int variant_id;
    public String variant_name;
    public String description;
    public String image_path;
    public int capacity;
    public double price;
    public String price_unit;
    public int available_quantity;
}
