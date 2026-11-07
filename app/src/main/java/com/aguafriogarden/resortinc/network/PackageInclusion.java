package com.aguafriogarden.resortinc.network;

/**
 * One item a Package Promo includes, as the admin set it in Promo Management (via
 * GET /api/booking/package-promos). {@code kind} is "room", "cottage", "amenity" or "food".
 */
public class PackageInclusion {
    public String kind;
    public String name;
    public int quantity;
}
