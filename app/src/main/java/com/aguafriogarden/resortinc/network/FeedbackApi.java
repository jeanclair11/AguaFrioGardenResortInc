package com.aguafriogarden.resortinc.network;

import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;

/**
 * Mirrors the website's guest Feedback form and Admin Feedback Module
 * (Api\FeedbackController on the backend), reading and writing the same
 * feedback rows so a submission and an admin's response are shared data.
 */
public interface FeedbackApi {

    @GET("feedback")
    Call<FeedbackListResponse> getFeedback(@Header("Authorization") String bearerToken);

    @FormUrlEncoded
    @POST("feedback")
    Call<FeedbackSubmitResponse> submit(
            @Header("Authorization") String bearerToken,
            @Field("rating") int rating,
            @Field("message") String message);
}
