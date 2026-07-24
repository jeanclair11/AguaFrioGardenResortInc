package com.aguafriogarden.resortinc.network;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.Part;

/**
 * Mirrors the website's guest registration/login endpoints exactly (same
 * validation, same fields, same underlying Laravel controller), so an
 * account created here can log into the website and vice versa.
 */
public interface AuthApi {

    @Multipart
    @POST("guest/register")
    Call<RegisterResponse> register(
            @Part("username") RequestBody username,
            @Part("email") RequestBody email,
            @Part("password") RequestBody password,
            @Part("password_confirmation") RequestBody passwordConfirmation,
            @Part("Firstname") RequestBody firstName,
            @Part("Middlename") RequestBody middleName,
            @Part("Lastname") RequestBody lastName,
            @Part("gender") RequestBody gender,
            @Part("birthday") RequestBody birthday,
            @Part("contact_number") RequestBody contactNumber,
            @Part("address") RequestBody address,
            @Part MultipartBody.Part profileImage,
            @Part MultipartBody.Part validId);

    @FormUrlEncoded
    @POST("guest/register/verify")
    Call<VerifyResponse> verifyOtp(
            @Field("registration_token") String registrationToken,
            @Field("otp") String otp);

    @FormUrlEncoded
    @POST("guest/register/resend")
    Call<MessageResponse> resendOtp(@Field("registration_token") String registrationToken);

    @FormUrlEncoded
    @POST("login")
    Call<LoginResponse> login(@Field("login") String login, @Field("password") String password);

    @POST("logout")
    Call<MessageResponse> logout(@Header("Authorization") String bearerToken);

    @GET("profile")
    Call<ProfileResponse> getProfile(@Header("Authorization") String bearerToken);
}
