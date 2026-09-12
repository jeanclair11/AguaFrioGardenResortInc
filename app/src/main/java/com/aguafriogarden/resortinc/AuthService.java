package com.aguafriogarden.resortinc;

import android.content.Context;

import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.LoginResponse;
import com.aguafriogarden.resortinc.network.ProfileResponse;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Single seam for authentication. A fixed test account is kept here on
 * purpose so the app can be logged into and exercised without a network
 * connection; every other login goes to the live Laravel backend so mobile
 * accounts are the same accounts the website uses.
 */
final class AuthService {

    static final int RESULT_SUCCESS = 0;
    static final int RESULT_ACCOUNT_NOT_FOUND = 1;
    static final int RESULT_WRONG_PASSWORD = 2;
    static final int RESULT_NETWORK_ERROR = 3;

    // The only hardcoded data in the app: a test account for development,
    // used purely to get past the login screen without a network call.
    private static final String TEST_EMAIL = "delgadojeanclair@gmail.com";
    private static final String TEST_PASSWORD = "12345";

    interface LoginCallback {
        void onResult(int resultCode);
    }

    private AuthService() {
    }

    static void login(Context context, String usernameOrEmail, String password, LoginCallback callback) {
        if (usernameOrEmail.equalsIgnoreCase(TEST_EMAIL)) {
            callback.onResult(TEST_PASSWORD.equals(password) ? RESULT_SUCCESS : RESULT_WRONG_PASSWORD);
            return;
        }

        ApiClient.authApi().login(usernameOrEmail, password).enqueue(new Callback<LoginResponse>() {
            @Override
            public void onResponse(Call<LoginResponse> call, Response<LoginResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    LoginResponse body = response.body();
                    ProfileStore.saveAuthToken(context, body.token);
                    fetchAndSaveProfile(context, body.token);
                    callback.onResult(RESULT_SUCCESS);
                } else if (response.code() == 404) {
                    callback.onResult(RESULT_ACCOUNT_NOT_FOUND);
                } else if (response.code() == 401) {
                    callback.onResult(RESULT_WRONG_PASSWORD);
                } else {
                    callback.onResult(RESULT_NETWORK_ERROR);
                }
            }

            @Override
            public void onFailure(Call<LoginResponse> call, Throwable t) {
                callback.onResult(RESULT_NETWORK_ERROR);
            }
        });
    }

    /**
     * Fetches the full guest profile in the background so the Profile tab has
     * real data; login itself doesn't wait on this (it only needs the token).
     */
    private static void fetchAndSaveProfile(Context context, String token) {
        ApiClient.authApi().getProfile("Bearer " + token).enqueue(new Callback<ProfileResponse>() {
            @Override
            public void onResponse(Call<ProfileResponse> call, Response<ProfileResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    ProfileResponse profile = response.body();
                    ProfileStore.saveServerProfile(context, profile.Firstname, profile.Middlename,
                            profile.Lastname, profile.birthday, profile.username, profile.email,
                            profile.address, profile.contact_number);
                }
            }

            @Override
            public void onFailure(Call<ProfileResponse> call, Throwable t) {
                // Best-effort sync; login has already succeeded regardless.
            }
        });
    }
}
