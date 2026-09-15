package com.aguafriogarden.resortinc.network;

import java.util.Map;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Multipart;
import retrofit2.http.Part;
import retrofit2.http.PartMap;
import retrofit2.http.POST;

/**
 * Mirrors the website's guest booking wizard endpoints (Api\BookingController
 * on the backend), so mobile bookings check availability and pricing against
 * the exact same booking records the website uses.
 */
public interface BookingApi {

    @GET("booking/rooms")
    Call<RoomListResponse> getRooms(@Header("Authorization") String bearerToken);

    /** Public — no login required. Powers the pre-login landing page's browse screens. */
    @GET("booking/room-types")
    Call<RoomTypeListResponse> getRoomTypes();

    @GET("booking/cottage-types")
    Call<CottageTypeListResponse> getCottageTypes();

    @GET("booking/ktv-types")
    Call<KtvTypeListResponse> getKtvTypes();

    @GET("booking/hall-types")
    Call<HallTypeListResponse> getHallTypes();

    @GET("booking/cottages-ktv")
    Call<CottageKtvListResponse> getCottagesKtv(@Header("Authorization") String bearerToken);

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

    /**
     * Creates a real reservation in one call — the mobile client has no server-side
     * session to spread this across multiple requests the way the website's wizard
     * does, so it sends everything accumulated across its own screens at once.
     * {@code quantities} holds bracket-notation form keys the backend already expects
     * from the website (e.g. "room_quantities[1]", "amenity_quantities[27]",
     * "menu_quantities[2]"), one entry per selected item with qty &gt; 0.
     */
    @Multipart
    @POST("booking/reserve")
    Call<ReserveResponse> reserve(
            @Header("Authorization") String bearerToken,
            @Part("check_in") RequestBody checkIn,
            @Part("check_out") RequestBody checkOut,
            @Part("adults") RequestBody adults,
            @Part("children") RequestBody children,
            @Part("special_request") RequestBody specialRequest,
            @Part("payment_option") RequestBody paymentOption,
            @Part("reference_number") RequestBody referenceNumber,
            @Part MultipartBody.Part proofImage,
            @PartMap Map<String, RequestBody> quantities);
}
