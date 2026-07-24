package com.aguafriogarden.resortinc;

import android.content.Context;

/**
 * Single seam for authentication. Everything else in the app (profile
 * fields, bookings, notifications, etc.) is placeholder/dynamic data, but a
 * fixed test account is kept here on purpose so the app can be logged into
 * and exercised during development without a backend.
 *
 * TODO(backend): once the Laravel API is available, replace the body of
 * login() with a POST /login call (and delete TEST_EMAIL/TEST_PASSWORD).
 * The signature and result codes are already shaped for that: callers only
 * see RESULT_SUCCESS / RESULT_ACCOUNT_NOT_FOUND / RESULT_WRONG_PASSWORD, so
 * swapping the implementation won't require touching MainActivity.
 */
final class AuthService {

    static final int RESULT_SUCCESS = 0;
    static final int RESULT_ACCOUNT_NOT_FOUND = 1;
    static final int RESULT_WRONG_PASSWORD = 2;

    // The only hardcoded data in the app: a test account for development,
    // used purely to get past the login screen. It seeds no profile data —
    // Profile still shows placeholders until a real account signs up or the
    // backend returns actual guest details.
    private static final String TEST_EMAIL = "delgadojeanclair@gmail.com";
    private static final String TEST_PASSWORD = "12345";

    private AuthService() {
    }

    static int login(Context context, String usernameOrEmail, String password) {
        if (usernameOrEmail.equalsIgnoreCase(TEST_EMAIL)) {
            return TEST_PASSWORD.equals(password) ? RESULT_SUCCESS : RESULT_WRONG_PASSWORD;
        }

        // Accounts created through the in-app sign-up wizard, until sign-up
        // itself is wired to a real POST /register call.
        String storedUsername = ProfileStore.getUsername(context);
        String storedEmail = ProfileStore.getEmail(context);
        boolean matchesStoredAccount = (!storedUsername.isEmpty() && usernameOrEmail.equalsIgnoreCase(storedUsername))
                || (!storedEmail.isEmpty() && usernameOrEmail.equalsIgnoreCase(storedEmail));
        if (matchesStoredAccount) {
            return ProfileStore.getPassword(context).equals(password)
                    ? RESULT_SUCCESS : RESULT_WRONG_PASSWORD;
        }

        return RESULT_ACCOUNT_NOT_FOUND;
    }
}
