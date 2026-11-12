package com.aguafriogarden.resortinc.network;

/** One add-on amenity available to attach to a booking, from GET /api/booking/amenities. */
public class AmenityAvailability {
    public int id;
    public String name;
    public String category_name;
    /** Admin Amenity Management facility type (room/cottage/ktv_room/hall); null = offered for all. */
    public String facility_types;
    public String image_path;
    public double price;
    public boolean unlimited;
    /** Units left for the requested stay (all dates when none were sent). */
    public Integer available_quantity;
    /** The most the quantity picker may allow, decided by the backend. */
    public Integer max_quantity;
    /** True when nothing is left: the amenity cannot be requested. */
    public boolean out_of_stock;
    /** Ready-made stock text from the backend: "5 available", "Unlimited" or "Out of Stock". */
    public String stock_label;

    /**
     * The highest quantity the guest may pick, taken from the backend and never invented here:
     * 0 when out of stock, otherwise max_quantity. Only if an older backend omits max_quantity
     * does it fall back to what the older fields said (no ceiling for an unlimited amenity).
     */
    public int pickerMax() {
        if (out_of_stock) {
            return 0;
        }
        if (max_quantity != null) {
            return Math.max(0, max_quantity);
        }
        if (unlimited || available_quantity == null) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, available_quantity);
    }
}
