package com.aguafriogarden.resortinc;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;

/**
 * Local placeholder for the guest profile that will eventually live behind
 * the Laravel REST API. Every getter defaults to "unset" (empty string, 0 for
 * an unselected spinner, false for a status flag) rather than any fabricated
 * name/email/ID — nothing here should be mistaken for real seed data. Once
 * sign-up/login/profile calls are wired to the backend, these same accessors
 * can be backed by the API response instead of SharedPreferences.
 */
final class ProfileStore {

    private static final String PREFS = "profile";

    private static final String KEY_AVATAR_URI = "avatar_uri";
    private static final String KEY_FIRST_NAME = "first_name";
    private static final String KEY_MIDDLE_NAME = "middle_name";
    private static final String KEY_LAST_NAME = "last_name";
    private static final String KEY_GENDER_POS = "gender_pos";
    private static final String KEY_BIRTH_DATE = "birth_date";
    private static final String KEY_PROVINCE = "province";
    private static final String KEY_CITY = "city";
    private static final String KEY_BARANGAY = "barangay";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_NOTIFICATIONS_ENABLED = "notifications_enabled";
    private static final String KEY_ID_VERIFIED = "id_verified";
    private static final String KEY_ID_TYPE_POS = "id_type_pos";
    private static final String KEY_ID_NUMBER = "id_number";
    private static final String KEY_ID_UPLOAD_DATE = "id_upload_date";
    private static final String KEY_AUTH_TOKEN = "auth_token";

    private ProfileStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void saveAvatarUri(Context context, Uri uri) {
        prefs(context).edit()
                .putString(KEY_AVATAR_URI, uri != null ? uri.toString() : null)
                .apply();
    }

    /** The saved profile picture, or null when none is set or it can't be read. */
    static Bitmap loadAvatar(Context context) {
        String uri = prefs(context).getString(KEY_AVATAR_URI, null);
        if (uri == null) {
            return null;
        }
        try {
            return MediaStore.Images.Media.getBitmap(
                    context.getContentResolver(), Uri.parse(uri));
        } catch (Exception e) {
            return null;
        }
    }

    static String getFirstName(Context context) {
        return prefs(context).getString(KEY_FIRST_NAME, "");
    }

    static String getMiddleName(Context context) {
        return prefs(context).getString(KEY_MIDDLE_NAME, "");
    }

    static String getLastName(Context context) {
        return prefs(context).getString(KEY_LAST_NAME, "");
    }

    /** First name for casual display (e.g. the dashboard greeting); falls back to a generic label. */
    static String getDisplayFirstName(Context context) {
        String first = getFirstName(context);
        return first.isEmpty() ? context.getString(R.string.guest_fallback_name) : first;
    }

    /** "First Middle Last", skipping any part that's empty; falls back to a generic label. */
    static String getFullName(Context context) {
        StringBuilder name = new StringBuilder();
        for (String part : new String[]{getFirstName(context), getMiddleName(context), getLastName(context)}) {
            if (!part.isEmpty()) {
                if (name.length() > 0) {
                    name.append(' ');
                }
                name.append(part);
            }
        }
        return name.length() > 0 ? name.toString() : context.getString(R.string.guest_fallback_name);
    }

    /**
     * Gender/ID-type positions are in "prompted spinner" terms (index 0 is
     * the "Select..." prompt), the same convention MainActivity's sign-up
     * wizard uses; 0 means nothing has been chosen yet.
     */
    static int getGenderPos(Context context) {
        return prefs(context).getInt(KEY_GENDER_POS, 0);
    }

    static String getBirthDate(Context context) {
        return prefs(context).getString(KEY_BIRTH_DATE, "");
    }

    static String getProvince(Context context) {
        return prefs(context).getString(KEY_PROVINCE, "");
    }

    static String getCity(Context context) {
        return prefs(context).getString(KEY_CITY, "");
    }

    static String getBarangay(Context context) {
        return prefs(context).getString(KEY_BARANGAY, "");
    }

    static String getUsername(Context context) {
        return prefs(context).getString(KEY_USERNAME, "");
    }

    static String getEmail(Context context) {
        return prefs(context).getString(KEY_EMAIL, "");
    }

    static String getPassword(Context context) {
        return prefs(context).getString(KEY_PASSWORD, "");
    }

    static void setPassword(Context context, String password) {
        prefs(context).edit().putString(KEY_PASSWORD, password).apply();
    }

    static boolean isNotificationsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFICATIONS_ENABLED, true);
    }

    static void setNotificationsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply();
    }

    /** Whether a valid ID has ever been submitted (upload date is the source of truth). */
    static boolean hasIdSubmission(Context context) {
        return !getIdUploadDate(context).isEmpty();
    }

    static boolean isIdVerified(Context context) {
        return prefs(context).getBoolean(KEY_ID_VERIFIED, false);
    }

    static int getIdTypePos(Context context) {
        return prefs(context).getInt(KEY_ID_TYPE_POS, 0);
    }

    static String getIdNumber(Context context) {
        return prefs(context).getString(KEY_ID_NUMBER, "");
    }

    static String getIdUploadDate(Context context) {
        return prefs(context).getString(KEY_ID_UPLOAD_DATE, "");
    }

    /** Called after Personal Information is edited and confirmed. */
    static void savePersonalInfo(Context context, String firstName, String middleName,
            String lastName, int genderPos, String birthDate, String province, String city,
            String barangay, String username, String email) {
        prefs(context).edit()
                .putString(KEY_FIRST_NAME, firstName)
                .putString(KEY_MIDDLE_NAME, middleName)
                .putString(KEY_LAST_NAME, lastName)
                .putInt(KEY_GENDER_POS, genderPos)
                .putString(KEY_BIRTH_DATE, birthDate)
                .putString(KEY_PROVINCE, province)
                .putString(KEY_CITY, city)
                .putString(KEY_BARANGAY, barangay)
                .putString(KEY_USERNAME, username)
                .putString(KEY_EMAIL, email)
                .apply();
    }

    /** Called once a new account finishes the sign-up wizard. */
    static void saveSignUpProfile(Context context, String firstName, String middleName,
            String lastName, int genderPos, String birthDate, String province, String city,
            String barangay, String username, String email, String password, int idTypePos) {
        savePersonalInfo(context, firstName, middleName, lastName, genderPos, birthDate,
                province, city, barangay, username, email);
        prefs(context).edit()
                .putString(KEY_PASSWORD, password)
                .putInt(KEY_ID_TYPE_POS, idTypePos)
                .putString(KEY_ID_NUMBER, "")
                .putString(KEY_ID_UPLOAD_DATE, "")
                .putBoolean(KEY_ID_VERIFIED, false)
                .apply();
    }

    /** The Sanctum bearer token for the account that's currently logged in on the shared backend. */
    static String getAuthToken(Context context) {
        return prefs(context).getString(KEY_AUTH_TOKEN, "");
    }

    static void saveAuthToken(Context context, String token) {
        prefs(context).edit().putString(KEY_AUTH_TOKEN, token).apply();
    }

    static void clearAuthToken(Context context) {
        prefs(context).edit().remove(KEY_AUTH_TOKEN).apply();
    }

    /**
     * Called after a successful login against the live backend, to sync real
     * profile fields for display. Only fields that map cleanly onto this
     * store's existing shape are synced (gender/address are stored here as a
     * spinner position and province/city/barangay respectively, which the
     * server's plain gender/address strings can't be losslessly split back
     * into, so those stay whatever the device already had).
     */
    static void saveServerProfile(Context context, String firstName, String middleName,
            String lastName, String birthDate, String username, String email) {
        prefs(context).edit()
                .putString(KEY_FIRST_NAME, firstName)
                .putString(KEY_MIDDLE_NAME, middleName)
                .putString(KEY_LAST_NAME, lastName)
                .putString(KEY_BIRTH_DATE, birthDate)
                .putString(KEY_USERNAME, username)
                .putString(KEY_EMAIL, email)
                .apply();
    }

    /** Called after a new ID photo is submitted; awaits admin review. */
    static void saveIdSubmission(Context context, int idTypePos, String idNumber,
            String uploadDate) {
        prefs(context).edit()
                .putInt(KEY_ID_TYPE_POS, idTypePos)
                .putString(KEY_ID_NUMBER, idNumber)
                .putString(KEY_ID_UPLOAD_DATE, uploadDate)
                .putBoolean(KEY_ID_VERIFIED, false)
                .apply();
    }
}
