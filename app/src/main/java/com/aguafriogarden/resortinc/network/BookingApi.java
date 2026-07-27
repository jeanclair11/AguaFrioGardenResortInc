package com.aguafriogarden.resortinc.network;

import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;

/**
 * Mirrors the website's guest booking wizard endpoints (Api\BookingController
 * on the backend), so mobile bookings check availability and pricing against
 * the exact same booking records the website uses.
 */
public interface BookingApi {

    @FormUrlEncoded
    @POST("booking/availability")
    Call<AvailabilityResponse> checkAvailability(
            @Header("Authorization") String bearerToken,
            @Field("check_in") String checkIn,
            @Field("check_out") String checkOut,
            @Field("adults") int adults,
            @Field("children") int children);

    @GET("booking/amenities")
    Call<AmenityListResponse> getAmenities(@Header("Authorization") String bearerToken);

    @GET("booking/food")
    Call<FoodListResponse> getFood(@Header("Authorization") String bearerToken);

    @GET("booking/my-bookings")
    Call<MyBookingsResponse> getMyBookings(@Header("Authorization") String bearerToken);
}
