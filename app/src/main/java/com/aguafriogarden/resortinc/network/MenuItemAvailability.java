package com.aguafriogarden.resortinc.network;

/** One pre-orderable food/beverage menu item, from GET /api/booking/food. */
public class MenuItemAvailability {
    public int id;
    public String name;
    public String category_name;
    public String description;
    public double price;
    public String image_path;
}
