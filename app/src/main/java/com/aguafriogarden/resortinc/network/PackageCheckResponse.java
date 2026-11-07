package com.aguafriogarden.resortinc.network;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Response body for POST /api/booking/package-promos/{id}/check: whether the package can be booked
 * for the guest's date and party. When {@code available} is false, {@code reasons} are plain
 * sentences to show the guest (package no longer active, not valid on that date, an included room
 * fully booked, ...).
 */
public class PackageCheckResponse {
    public boolean available;
    public List<String> reasons;
    @SerializedName("package")
    public PackagePromo packagePromo;
    public String check_in;
    public String check_out;
    public int nights;
}
