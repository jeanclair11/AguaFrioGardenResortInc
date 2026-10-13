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
import retrofit2.http.Query;

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

    /** Individual KTV units. With event_date/start_time/rate_label (same formats as the "ktv[...]"
     *  reservation fields) each unit's {@code available} reflects that slot; without them it is
     *  the plain browse list. */
    @GET("booking/ktv-units")
    Call<KtvUnitListResponse> getKtvUnits(
            @Query("event_date") String eventDate,
            @Query("start_time") String startTime,
            @Query("rate_label") String rateLabel);

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

    /**
     * Real, date + Cottage Use-scoped availability — the Cottage equivalent of
     * checkAvailability() above, checked against actual Bookings/pending holds for that exact
     * date and period (see the backend's HandlesCottageAvailability::cottageAvailableAt()),
     * unlike getCottagesKtv()'s static in-stock count. Always called fresh, never cached client-
     * side, same as checkAvailability().
     */
    @FormUrlEncoded
    @POST("booking/cottage-availability")
    Call<CottageAvailabilityResponse> checkCottageAvailability(
            @Header("Authorization") String bearerToken,
            @Field("date") String date,
            @Field("use") String use);

    @GET("booking/amenities")
    Call<AmenityListResponse> getAmenities(@Header("Authorization") String bearerToken);

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
     * from the website (e.g. "room_quantities[1]", "amenity_quantities[27]"), one entry per selected item with qty &gt; 0.
     */
    @Multipart
    @POST("booking/reserve")
    Call<ReserveResponse> reserve(
            @Header("Authorization") String bearerToken,
            @Part("multiple_facilities") RequestBody multipleFacilities,
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
            @Field("multiple_facilities") String multipleFacilities,
            @Field("check_in") String checkIn,
            @Field("check_out") String checkOut,
            @Field("adults") int adults,
            @Field("children") int children,
            @Field("special_request") String specialRequest,
            @FieldMap Map<String, String> roomQuantities);

    /**
     * Multi-facility "Reserve" (multiple_facilities=1): one request carrying a block per facility
     * (bracket-notation keys such as "hotel[check_in]", "hotel[room_quantities][5]",
     * "cottage[date]", "ktv[start_time]"), answered with a single reservation and reference.
     */
    @FormUrlEncoded
    @POST("booking/reservations")
    Call<ReservationRequestResponse> createMultiReservation(
            @Header("Authorization") String bearerToken,
            @FieldMap Map<String, String> fields);

    /**
     * Multi-facility "Book" (multiple_facilities=1): same facility blocks as
     * {@link #createMultiReservation}, plus the usual payment fields, as multipart.
     */
    @Multipart
    @POST("booking/reserve")
    Call<ReserveResponse> reserveMulti(
            @Header("Authorization") String bearerToken,
            @Part("multiple_facilities") RequestBody multipleFacilities,
            @Part("payment_option") RequestBody paymentOption,
            @Part("reference_number") RequestBody referenceNumber,
            @Part MultipartBody.Part proofImage,
            @PartMap Map<String, RequestBody> fields);

    /**
     * Cottage's own "Reserve" request — same lifecycle as {@link #createReservation} (creates a
     * Pending AdvanceReservation, visible immediately in Reservation Management's Cottage list),
     * just with Cottage's own date+Cottage Use fields instead of a check-in/check-out range.
     * {@code use} is "day"/"night"/"overnight" (see BookingApi#checkCottageAvailability).
     * {@code cottageQuantities} holds the same bracket-notation keys as roomQuantities above
     * (e.g. "cottage_quantities[10]"), keyed by Cottage Type (variant_id). Payment/status/cancel
     * afterward reuse {@link #payReservation}/{@link #getReservationStatus}/
     * {@link #cancelReservation} unchanged — those already work on any reservation by ID.
     */
    @FormUrlEncoded
    @POST("booking/cottage-reservations")
    Call<ReservationRequestResponse> createCottageReservation(
            @Header("Authorization") String bearerToken,
            @Field("multiple_facilities") String multipleFacilities,
            @Field("date") String date,
            @Field("use") String use,
            @Field("adults") int adults,
            @Field("children") int children,
            @Field("special_request") String specialRequest,
            @FieldMap Map<String, String> cottageQuantities);

    /**
     * Submits payment for a Pending reservation created by {@link #createReservation} — promotes
     * it into a real booking, visible in Booking Management. {@code quantities} holds the same
     * bracket-notation amenity keys {@link #reserve} uses (e.g. "amenity_quantities[27]").
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
