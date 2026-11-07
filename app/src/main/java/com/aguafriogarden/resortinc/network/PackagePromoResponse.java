package com.aguafriogarden.resortinc.network;

import com.google.gson.annotations.SerializedName;

/** Response body for GET /api/booking/package-promos/{id}. */
public class PackagePromoResponse {
    @SerializedName("package")
    public PackagePromo packagePromo;
}
