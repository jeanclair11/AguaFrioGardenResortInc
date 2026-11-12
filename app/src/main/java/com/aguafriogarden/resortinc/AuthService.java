package com.aguafriogarden.resortinc;

import android.content.Context;
import android.net.Uri;

import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.LoginResponse;
import com.aguafriogarden.resortinc.network.ProfileResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Single seam for authentication. Every login goes to the live Laravel backend, so mobile accounts
 * are exactly the accounts the website uses.
 */
final class AuthService {

    static final int RESULT_SUCCESS = 0;
    static final int RESULT_ACCOUNT_NOT_FOUND = 1;
    static final int RESULT_WRONG_PASSWORD = 2;
    static final int RESULT_NETWORK_ERROR = 3;

    interface LoginCallback {
        void onResult(int resultCode);
    }

    private AuthService() {
    }

    static void login(Context context, String usernameOrEmail, String password, LoginCallback callback) {
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
        Context app = context.getApplicationContext();
        ApiClient.authApi().getProfile("Bearer " + token).enqueue(new Callback<ProfileResponse>() {
            @Override
            public void onResponse(Call<ProfileResponse> call, Response<ProfileResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    ProfileResponse profile = response.body();
                    ProfileStore.saveServerProfile(app, profile);
                    syncAvatar(app, profile.profile_image);
                }
            }

            @Override
            public void onFailure(Call<ProfileResponse> call, Throwable t) {
                // Best-effort sync; login has already succeeded regardless.
            }
        });
    }

    /**
     * Makes the on-device picture match the one on the server. The server's picture is downloaded
     * into the app's own storage; with none on the server, any picture left on the device (by a
     * different account, say) is dropped so one guest never sees another's photo.
     */
    private static void syncAvatar(Context context, String serverPath) {
        String url = ApiClient.imageUrl(serverPath);
        if (url == null) {
            Uri old = ProfileStore.getAvatarUri(context);
            ProfileStore.saveAvatarUri(context, null);
            ProfileStore.deleteAvatarFile(context, old);
            ProfileStore.markAvatarDirty();
            return;
        }

        new Thread(() -> {
            byte[] image = download(url);
            if (image == null) {
                return;
            }
            Uri old = ProfileStore.getAvatarUri(context);
            if (ProfileStore.saveAvatarFile(context, image) != null) {
                ProfileStore.deleteAvatarFile(context, old);
                ProfileStore.markAvatarDirty();
            }
        }, "avatar-sync").start();
    }

    /** The bytes at the url, or null on any failure (the avatar then simply stays as it was). */
    private static byte[] download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
