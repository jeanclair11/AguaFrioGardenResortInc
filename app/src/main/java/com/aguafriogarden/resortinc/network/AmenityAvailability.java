package com.aguafriogarden.resortinc.network;

/** One add-on amenity available to attach to a booking, from GET /api/booking/amenities. */
public class AmenityAvailability {
    public int id;
    public String name;
    public String category_name;
    public String image_path;
    public double price;
    public boolean unlimited;
    public Integer available_quantity;
}
