package com.aguafriogarden.resortinc.network;

import java.util.List;

/**
 * A Package Promo the admin built in Promo Management, from GET /api/booking/package-promos. Only
 * packages that are active, inside their validity dates and fully bookable are ever returned, so
 * one that is deactivated, expired or edited changes on the next refresh. {@code price} is the
 * fixed package price; {@code regular_price}/{@code savings} are null when the package is not
 * cheaper than buying its items separately.
 */
public class PackagePromo {
    public int id;
    public String name;
    public String description;
    public double price;
    public Double regular_price;
    public Double savings;
    public String image_path;
    /** yyyy-MM-dd. */
    public String valid_from;
    /** yyyy-MM-dd, null when the package has no end date. */
    public String valid_until;
    /** True when the package includes a room, which makes it a one-night stay (see the backend's
     *  PackagePromoService); otherwise it is a single visit day. */
    public boolean includes_room;
    /** Most guests the package seats; null when not limited. */
    public Integer max_guests;
    public List<PackageInclusion> inclusions;
}
