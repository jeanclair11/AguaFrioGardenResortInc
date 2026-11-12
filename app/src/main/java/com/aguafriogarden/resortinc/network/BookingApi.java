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

    /** Same list, with the party size: the server reports a unit whose maximum capacity is below
     *  {@code guests} as not available (the booking wizard's lookups). */
    @GET("booking/ktv-units")
    Call<KtvUnitListResponse> getKtvUnitsForParty(
            @Query("event_date") String eventDate,
            @Query("start_time") String startTime,
            @Query("rate_label") String rateLabel,
            @Query("guests") Integer guests);

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
     * side, same as checkAvailability(). The party is optional and the app leaves it out: which
     * cottages are offered depends on the date and use only, never on how many guests are coming.
     */
    @FormUrlEncoded
    @POST("booking/cottage-availability")
    Call<CottageAvailabilityResponse> checkCottageAvailability(
            @Header("Authorization") String bearerToken,
            @Field("date") String date,
            @Field("use") String use,
            @Field("adults") Integer adults,
            @Field("children") Integer children);

    @GET("booking/amenities")
    Call<AmenityListResponse> getAmenities(
            @Header("Authorization") String bearerToken,
            @Query("facility_type") String facilityType);

    /** Every reservation/booking the logged-in guest has, real or still-pending — backs the My
     *  Reservations list, fetched fresh every time it's opened so it survives app restarts and
     *  re-logins (it's never cached locally). */
    /**
     * Promo Coupons (Promo Management "Promo Discount" promos) the booking qualifies for. Takes the
     * same form fields the matching submit would send (see HotelBookingFlowController), so the
     * server judges dates, nights, guests, facilities and usage limit on exactly what will be
     * booked; only eligible coupons come back.
     */
    @FormUrlEncoded
    @POST("booking/promo-coupons")
    Call<PromoCouponListResponse> getPromoCoupons(
            @Header("Authorization") String bearerToken,
            @FieldMap Map<String, String> fields);

    @GET("booking/my-bookings")
    Call<MyBookingsResponse> getMyBookings(@Header("Authorization") String bearerToken);

    /** Public — the catalog of every reservation and booking status (key, label, colour,
     *  definition). The app builds every filter, tab and badge from it. */
    @GET("booking/statuses")
    Call<StatusCatalogResponse> getStatusCatalog();

    /** The light feed polled while a reservations/bookings screen is visible: only each record's
     *  kind, id, reference and status. */
    @GET("booking/my-statuses")
    Call<MyStatusesResponse> getMyStatuses(@Header("Authorization") String bearerToken);

    /** Public — Package Promos on sale right now (the admin's Promo Management packages), for the
     *  banners above the Hotel Rooms/Cottages/KTV Rooms lists. */
    @GET("booking/package-promos")
    Call<PackagePromoListResponse> getPackagePromos();

    /** Public — one package's details; 404 once it is inactive, expired or removed. */
    @GET("booking/package-promos/{id}")
    Call<PackagePromoResponse> getPackagePromo(@Path("id") int packageId);

    /** Before payment: is the package still active, valid on {@code date}, and are all of its
     *  rooms/cottages/amenities/food available for the party? */
    @FormUrlEncoded
    @POST("booking/package-promos/{id}/check")
    Call<PackageCheckResponse> checkPackagePromo(
            @Header("Authorization") String bearerToken,
            @Path("id") int packageId,
            @Field("date") String date,
            @Field("adults") int adults,
            @Field("children") int children);

    /** Books the package and records the payment in one call (multipart, like {@link #reserve});
     *  {@code fields} carries date, adults, children, special_request and any extra
     *  amenity_quantities[id] the guest added on top of the package. */
    @Multipart
    @POST("booking/package-promos/{id}/reserve")
    Call<ReserveResponse> reservePackagePromo(
            @Header("Authorization") String bearerToken,
            @Path("id") int packageId,
            @Part("payment_option") RequestBody paymentOption,
            @Part("reference_number") RequestBody referenceNumber,
            @Part MultipartBody.Part proofImage,
            @PartMap Map<String, RequestBody> fields);

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
