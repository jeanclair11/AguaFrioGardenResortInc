package com.aguafriogarden.resortinc.network;

import java.util.List;

/**
 * One Cottage/KTV Room/Function Hall Type, from GET /api/booking/cottages-ktv,
 * /booking/cottage-types, /booking/ktv-types, or /booking/hall-types — sourced
 * directly from the Admin Web's own Cottage/KTV/Facility Management data
 * (CottageVariant/KtvVariant/FacilityVariant). Unlike Hotel Rooms, these three
 * have no Category above Type — see {@link RoomTypeAvailability} for the
 * Category+Type shape Hotel Rooms alone still uses. Shaped like that class for
 * consistency otherwise, with {@code price}/{@code price_unit} in place of
 * price_per_night since Cottages/KTV/Halls aren't priced per night.
 */
public class CottageKtvOption {
    /** KTV only, from GET /api/booking/ktv-units: the physical unit's id (0 when this option is
     *  a Type from the older ktv-types/cottages-ktv lists). */
    public int unit_id;
    public int group_id;
    public int variant_id;
    public String variant_name;
    public String description;
    public String image_path;
    public int capacity;
    public double price;
    public String price_unit;
    public int available_quantity;
    /** Cottages only (Day Use/Night Use/Overnight, or a single Single Rate row) — null for
     *  KTV/Halls, which fall back to the flat price/price_unit line instead. See
     *  LandingActivity#bindCottageKtvCard. */
    public List<PriceBreakdownEntry> price_breakdown;
}
