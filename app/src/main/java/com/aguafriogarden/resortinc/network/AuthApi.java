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
            @Part("validID_type") RequestBody validIdType,
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

    /** Forgot password: emails a 6-digit code and returns the token for the rest of the recovery. */
    @FormUrlEncoded
    @POST("guest/password/forgot")
    Call<ForgotPasswordResponse> forgotPassword(@Field("email") String email);

    @FormUrlEncoded
    @POST("guest/password/verify")
    Call<MessageResponse> verifyResetCode(@Field("reset_token") String resetToken, @Field("otp") String otp);

    @FormUrlEncoded
    @POST("guest/password/resend")
    Call<MessageResponse> resendResetCode(@Field("reset_token") String resetToken);

    @FormUrlEncoded
    @POST("guest/password/reset")
    Call<MessageResponse> resetPassword(
            @Field("reset_token") String resetToken,
            @Field("password") String password,
            @Field("password_confirmation") String passwordConfirmation);

    @POST("logout")
    Call<MessageResponse> logout(@Header("Authorization") String bearerToken);

    @GET("profile")
    Call<ProfileResponse> getProfile(@Header("Authorization") String bearerToken);

    /**
     * Saves Personal Information to the same user/guest rows the website's My Profile page edits.
     * Answers with the saved profile; a 422 carries the field errors (taken username, bad phone...).
     */
    @FormUrlEncoded
    @POST("profile")
    Call<ProfileResponse> updateProfile(
            @Header("Authorization") String bearerToken,
            @Field("Firstname") String firstName,
            @Field("Middlename") String middleName,
            @Field("Lastname") String lastName,
            @Field("username") String username,
            @Field("email") String email,
            @Field("gender") String gender,
            @Field("birthday") String birthday,
            @Field("contact_number") String contactNumber,
            @Field("address") String address);

    /** Replaces the profile picture the website shows for this guest. */
    @Multipart
    @POST("profile/photo")
    Call<ProfileResponse> uploadProfilePhoto(
            @Header("Authorization") String bearerToken,
            @Part MultipartBody.Part photo);

    /** The server checks the current password; other devices are signed out on success. */
    @FormUrlEncoded
    @POST("profile/password")
    Call<MessageResponse> changePassword(
            @Header("Authorization") String bearerToken,
            @Field("current_password") String currentPassword,
            @Field("new_password") String newPassword,
            @Field("new_password_confirmation") String newPasswordConfirmation);

    /** Replaces the ID photo and type on file, which staff open from the booking's "View ID Document" link. */
    @Multipart
    @POST("profile/valid-id")
    Call<ProfileResponse> uploadValidId(
            @Header("Authorization") String bearerToken,
            @Part("validID_type") RequestBody idType,
            @Part MultipartBody.Part validId);
}
