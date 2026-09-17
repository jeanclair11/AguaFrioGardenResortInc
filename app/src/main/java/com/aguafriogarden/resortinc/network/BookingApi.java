package com.aguafriogarden.resortinc.network;

import java.util.Map;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FieldMap;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Multipart;
import retrofit2.http.Part;
import retrofit2.http.PartMap;
import retrofit2.http.Path;
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

    /** Every reservation/booking the logged-in guest has, real or still-pending — backs the My
     *  Reservations list, fetched fresh every time it's opened so it survives app restarts and
     *  re-logins (it's never cached locally). */
    @GET("booking/my-bookings")
    Call<MyBookingsResponse> getMyBookings(@Header("Authorization") String bearerToken);

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

    /**
     * Submits a Hotel Rooms "Reserve" request — creates a Pending reservation with no payment
     * collected yet, visible immediately in Reservation Management's Reservation List (both web
     * and mobile). Payment happens later via {@link #payReservation}. Hotel Rooms only for now.
     */
    @FormUrlEncoded
    @POST("booking/reservations")
    Call<ReservationRequestResponse> createReservation(
            @Header("Authorization") String bearerToken,
            @Field("check_in") String checkIn,
            @Field("check_out") String checkOut,
            @Field("adults") int adults,
            @Field("children") int children,
            @Field("special_request") String specialRequest,
            @FieldMap Map<String, String> roomQuantities);

    /**
     * Submits payment for a Pending reservation created by {@link #createReservation} — promotes
     * it into a real booking, visible in Booking Management. {@code quantities} holds the same
     * bracket-notation amenity/menu keys {@link #reserve} uses (e.g. "amenity_quantities[27]").
     */
    @Multipart
    @POST("booking/reservations/{id}/payment")
    Call<ReserveResponse> payReservation(
            @Header("Authorization") String bearerToken,
            @Path("id") int advanceReservationId,
            @Part("payment_option") RequestBody paymentOption,
            @Part("reference_number") RequestBody referenceNumber,
            @Part MultipartBody.Part proofImage,
            @PartMap Map<String, RequestBody> quantities);

    /**
     * Cancels a still-Pending reservation the guest submitted themselves (matches the backend's
     * own guard: 404/422 if it's not theirs, already promoted, or no longer pending).
     */
    @FormUrlEncoded
    @POST("booking/reservations/{id}/cancel")
    Call<CancelReservationResponse> cancelReservation(
            @Header("Authorization") String bearerToken,
            @Path("id") int advanceReservationId,
            @Field("reason") String reason);

    /**
     * Refreshes a reservation's current status — there's no push notification system, so the app
     * calls this whenever the guest opens Reservation Details, to pick up a receptionist-side
     * change (e.g. Reservation Management's own Cancel) the guest's device could otherwise never
     * find out about.
     */
    @GET("booking/reservations/{id}")
    Call<ReservationStatusResponse> getReservationStatus(
            @Header("Authorization") String bearerToken,
            @Path("id") int advanceReservationId);
}
