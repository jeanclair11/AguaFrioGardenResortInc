package com.aguafriogarden.resortinc.network;

import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * Mirrors the website's guest notifications (Api\NotificationController on the backend): the same
 * guest_notifications rows, so read/archive state follows the guest across devices.
 */
public interface NotificationApi {

    @GET("notifications")
    Call<NotificationListResponse> list(@Header("Authorization") String bearerToken);

    /** Uploads an alert the app raised itself; idempotent on {@code clientUuid}. */
    @FormUrlEncoded
    @POST("notifications")
    Call<NotificationResponse> create(
            @Header("Authorization") String bearerToken,
            @Field("client_uuid") String clientUuid,
            @Field("title") String title,
            @Field("message") String message,
            @Field("type") String type,
            @Field("created_at_ms") long createdAtMs);

    /** Either flag may be null to leave it unchanged; sent as 1/0 since Laravel's boolean rule rejects "true". */
    @FormUrlEncoded
    @PATCH("notifications/{id}")
    Call<NotificationResponse> update(
            @Header("Authorization") String bearerToken,
            @Path("id") long id,
            @Field("is_read") Integer isRead,
            @Field("is_archived") Integer isArchived);

    @POST("notifications/read-all")
    Call<MessageResponse> readAll(@Header("Authorization") String bearerToken);
}
