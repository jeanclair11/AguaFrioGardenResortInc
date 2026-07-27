package com.aguafriogarden.resortinc.network;

import java.util.List;

/** Response body for GET /api/booking/food. */
public class FoodListResponse {
    public List<MenuItemAvailability> menu;
}
