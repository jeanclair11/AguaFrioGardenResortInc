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
    public Integer available_quantity;
}
