package com.aguafriogarden.resortinc;

import android.content.Context;

import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.MessageResponse;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * This phone's Firebase Cloud Messaging registration, so the backend can push a guest's
 * notifications to it while the app is closed (see {@link FcmService}).
 *
 * <p>Firebase is configured here in code from four string resources the build fills in from the
 * project-root {@code firebase.properties} (see firebase.properties.example) instead of through the
 * google-services Gradle plugin. When that file is absent every method is a no-op: the app simply
 * has no push channel and relies on its own polling ({@link ChatPollingService}), exactly as
 * before — nothing crashes and nothing needs a Firebase project to build or run.
 */
final class PushTokens {

    private PushTokens() {
    }

    /** True when the build carries a Firebase project's client configuration. */
    static boolean isConfigured(Context context) {
        return !context.getString(R.string.fcm_application_id).isEmpty()
                && !context.getString(R.string.fcm_api_key).isEmpty()
                && !context.getString(R.string.fcm_project_id).isEmpty()
                && !context.getString(R.string.fcm_sender_id).isEmpty();
    }

    /** Starts Firebase with the build's configuration (once). False when there is none, or Firebase
     *  refused it — push is then simply off. */
    static boolean ensureInitialized(Context context) {
        if (!isConfigured(context)) {
            return false;
        }
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context, new FirebaseOptions.Builder()
                        .setApplicationId(context.getString(R.string.fcm_application_id))
                        .setApiKey(context.getString(R.string.fcm_api_key))
                        .setProjectId(context.getString(R.string.fcm_project_id))
                        .setGcmSenderId(context.getString(R.string.fcm_sender_id))
                        .build());
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** After login (and on every launch while logged in): fetches this phone's token and tells the
     *  backend it belongs to the signed-in guest. Idempotent; a failed attempt is retried at the
     *  next launch or login. */
    static void register(Context context) {
        Context app = context.getApplicationContext();
        if (ProfileStore.getAuthToken(app).isEmpty() || !ensureInitialized(app)) {
            return;
        }
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null && !task.getResult().isEmpty()) {
                    send(app, task.getResult());
                }
            });
        } catch (RuntimeException ignored) {
            // Google Play services missing or outdated: no push on this phone, polling still works.
        }
    }

    /** Firebase issued this phone a new token (first run, or it rotated). */
    static void onNewToken(Context context, String token) {
        send(context.getApplicationContext(), token);
    }

    private static void send(Context app, String token) {
        String auth = ProfileStore.getAuthToken(app);
        if (auth.isEmpty() || token == null || token.isEmpty()) {
            return;
        }
        ProfileStore.setPushToken(app, token);
        ApiClient.notificationApi().registerDevice("Bearer " + auth, token).enqueue(new Callback<MessageResponse>() {
            @Override
            public void onResponse(Call<MessageResponse> call, Response<MessageResponse> response) {
                // Nothing to do on failure: register() runs again at the next launch.
            }

            @Override
            public void onFailure(Call<MessageResponse> call, Throwable t) {
            }
        });
    }

    /** Logout: asks the backend to stop pushing this guest's notifications to this phone. Call
     *  before the auth token is cleared. Best effort — if it fails, the backend still stops sending
     *  once Firebase reports the token dead or the next guest registers it. */
    static void unregister(Context context) {
        Context app = context.getApplicationContext();
        String auth = ProfileStore.getAuthToken(app);
        String token = ProfileStore.getPushToken(app);
        ProfileStore.setPushToken(app, "");
        if (auth.isEmpty() || token.isEmpty()) {
            return;
        }
        ApiClient.notificationApi().unregisterDevice("Bearer " + auth, token).enqueue(new Callback<MessageResponse>() {
            @Override
            public void onResponse(Call<MessageResponse> call, Response<MessageResponse> response) {
            }

            @Override
            public void onFailure(Call<MessageResponse> call, Throwable t) {
            }
        });
    }
}
