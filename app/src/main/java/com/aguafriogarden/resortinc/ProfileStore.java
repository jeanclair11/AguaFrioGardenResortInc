package com.aguafriogarden.resortinc;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;

import com.aguafriogarden.resortinc.network.ProfileResponse;

/**
 * This device's copy of the signed-in guest's profile. The server is the source of truth: login
 * fills it from GET /profile (see saveServerProfile), and every edit is sent to the backend first
 * (ProfileController) and only then written here. Every getter defaults to "unset" (empty string,
 * 0 for an unselected spinner, false for a status flag) rather than any fabricated name/email/ID.
 * No password is kept on the device.
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
    private static final String KEY_ADDRESS = "address";
    private static final String KEY_CONTACT_NUMBER = "contact_number";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_NOTIFICATIONS_ENABLED = "notifications_enabled";
    private static final String KEY_HAS_VALID_ID = "has_valid_id";
    private static final String KEY_VALID_ID_TYPE = "valid_id_type";
    private static final String KEY_AUTH_TOKEN = "auth_token";
    private static final String KEY_ACTIVE_RESERVATION_ID = "active_reservation_id";
    private static final String KEY_ACTIVE_RESERVATION_STATUS = "active_reservation_status";
    private static final String KEY_CHAT_CURSOR = "chat_cursor";
    private static final String KEY_ALERT_CURSOR = "alert_cursor";
    private static final String KEY_NOTIFICATIONS_SYNC_PENDING = "notifications_sync_pending";

    private ProfileStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Set when the picture changes from outside the UI (the login sync), so the header re-reads it. */
    private static volatile boolean avatarDirty = false;

    static void saveAvatarUri(Context context, Uri uri) {
        prefs(context).edit()
                .putString(KEY_AVATAR_URI, uri != null ? uri.toString() : null)
                .apply();
    }

    /** The saved profile picture's uri, or null when none is set. */
    static Uri getAvatarUri(Context context) {
        String uri = prefs(context).getString(KEY_AVATAR_URI, null);
        return uri != null ? Uri.parse(uri) : null;
    }

    /**
     * Keeps a copy of the profile picture inside the app and points the avatar at it, so it
     * survives the gallery revoking access to the picked image. A fresh file name each time lets
     * the previous copy be restored if the upload that follows fails.
     */
    static Uri saveAvatarFile(Context context, byte[] jpeg) {
        java.io.File file = new java.io.File(context.getFilesDir(), "avatar_" + System.currentTimeMillis() + ".jpg");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(jpeg);
        } catch (java.io.IOException e) {
            return null;
        }
        Uri uri = Uri.fromFile(file);
        saveAvatarUri(context, uri);
        return uri;
    }

    /** Deletes an avatar copy this class wrote (and nothing else, e.g. never a gallery picture). */
    static void deleteAvatarFile(Context context, Uri uri) {
        if (uri == null || !"file".equals(uri.getScheme()) || uri.getPath() == null) {
            return;
        }
        java.io.File file = new java.io.File(uri.getPath());
        if (file.getName().startsWith("avatar_") && context.getFilesDir().equals(file.getParentFile())) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    static void markAvatarDirty() {
        avatarDirty = true;
    }

    /** True once after the picture was replaced behind the UI's back; resets itself. */
    static boolean consumeAvatarDirty() {
        boolean dirty = avatarDirty;
        avatarDirty = false;
        return dirty;
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

    /**
     * The single free-text address string the server stores (built from Barangay/City/Province
     * at sign-up — see MainActivity#buildAddress). Kept separate from getProvince/getCity/
     * getBarangay above since those are this device's own split-out sign-up fields and can't be
     * losslessly reconstructed from this string once it round-trips through the server.
     */
    static String getAddress(Context context) {
        return prefs(context).getString(KEY_ADDRESS, "");
    }

    /** The mobile number collected at sign-up (see MainActivity's Phone Number field). */
    static String getContactNumber(Context context) {
        return prefs(context).getString(KEY_CONTACT_NUMBER, "");
    }

    static String getUsername(Context context) {
        return prefs(context).getString(KEY_USERNAME, "");
    }

    static String getEmail(Context context) {
        return prefs(context).getString(KEY_EMAIL, "");
    }

    static boolean isNotificationsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFICATIONS_ENABLED, true);
    }

    static void setNotificationsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply();
    }

    /** True while the guest's last change to the Enable Notifications switch hasn't reached the
     *  server yet; until it does, the server's value must not overwrite the local one. */
    static boolean isNotificationsSyncPending(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFICATIONS_SYNC_PENDING, false);
    }

    static void setNotificationsSyncPending(Context context, boolean pending) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS_SYNC_PENDING, pending).apply();
    }

    /** Whether an ID photo is on file with the resort (sign-up or the Valid ID screen). */
    static boolean hasValidId(Context context) {
        return prefs(context).getBoolean(KEY_HAS_VALID_ID, false);
    }

    /** The type of the ID on file (e.g. "Passport"), or "" when the server doesn't know it. */
    static String getValidIdType(Context context) {
        return prefs(context).getString(KEY_VALID_ID_TYPE, "");
    }

    /** Called once the server has accepted a new ID photo. */
    static void saveValidId(Context context, String idType) {
        prefs(context).edit()
                .putBoolean(KEY_HAS_VALID_ID, true)
                .putString(KEY_VALID_ID_TYPE, idType != null ? idType : "")
                .apply();
    }

    /** "Barangay, City, Province" — the single free-text address the server stores. */
    static String buildAddress(String province, String city, String barangay) {
        StringBuilder address = new StringBuilder();
        for (String part : new String[]{barangay, city, province}) {
            if (part != null && !part.isEmpty()) {
                if (address.length() > 0) {
                    address.append(", ");
                }
                address.append(part);
            }
        }
        return address.toString();
    }

    /** Called after Personal Information has been saved on the server. */
    static void savePersonalInfo(Context context, String firstName, String middleName,
            String lastName, int genderPos, String birthDate, String phoneNumber, String province,
            String city, String barangay, String username, String email) {
        prefs(context).edit()
                .putString(KEY_ADDRESS, buildAddress(province, city, barangay))
                .putString(KEY_FIRST_NAME, firstName)
                .putString(KEY_MIDDLE_NAME, middleName)
                .putString(KEY_LAST_NAME, lastName)
                .putInt(KEY_GENDER_POS, genderPos)
                .putString(KEY_BIRTH_DATE, birthDate)
                .putString(KEY_CONTACT_NUMBER, phoneNumber)
                .putString(KEY_PROVINCE, province)
                .putString(KEY_CITY, city)
                .putString(KEY_BARANGAY, barangay)
                .putString(KEY_USERNAME, username)
                .putString(KEY_EMAIL, email)
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
     * The Hotel Rooms reservation ChatPollingService should keep checking on in the background,
     * and the status it last saw for it — persisted here (not just held in
     * HotelBookingFlowController's own in-memory field) so the polling service, a separate
     * component with no reference to any Activity's UI objects, can still find it. Cleared on
     * logout so a different guest signing in on the same device doesn't inherit it.
     */
    static int getActiveReservationId(Context context) {
        return prefs(context).getInt(KEY_ACTIVE_RESERVATION_ID, -1);
    }

    static void setActiveReservationId(Context context, int advanceReservationId) {
        prefs(context).edit().putInt(KEY_ACTIVE_RESERVATION_ID, advanceReservationId).apply();
    }

    static String getActiveReservationStatus(Context context) {
        return prefs(context).getString(KEY_ACTIVE_RESERVATION_STATUS, "");
    }

    static void setActiveReservationStatus(Context context, String status) {
        prefs(context).edit().putString(KEY_ACTIVE_RESERVATION_STATUS, status).apply();
    }

    /** Id of the newest chat message ChatPollingService has already handled, or -1 on a fresh
     *  login (so the existing thread is taken as history rather than alerted as new). Kept across
     *  app restarts so a staff reply that arrived while the app was closed still alerts once. */
    static long getChatCursor(Context context) {
        return prefs(context).getLong(KEY_CHAT_CURSOR, -1L);
    }

    static void setChatCursor(Context context, long messageId) {
        prefs(context).edit().putLong(KEY_CHAT_CURSOR, messageId).apply();
    }

    static void clearChatCursor(Context context) {
        prefs(context).edit().remove(KEY_CHAT_CURSOR).apply();
    }

    /** Highest server alert id already shown or taken as history, or -1 on a fresh login (so the
     *  existing feed isn't announced as new). Kept across restarts so an alert staff raised while
     *  the app was closed still produces one system notification. */
    static long getAlertCursor(Context context) {
        return prefs(context).getLong(KEY_ALERT_CURSOR, -1L);
    }

    static void setAlertCursor(Context context, long alertId) {
        prefs(context).edit().putLong(KEY_ALERT_CURSOR, alertId).apply();
    }

    static void clearAlertCursor(Context context) {
        prefs(context).edit().remove(KEY_ALERT_CURSOR).apply();
    }

    static void clearActiveReservation(Context context) {
        prefs(context).edit().remove(KEY_ACTIVE_RESERVATION_ID).remove(KEY_ACTIVE_RESERVATION_STATUS).apply();
    }

    /**
     * Called with GET /profile right after login. The server is the source of truth, so every
     * field it knows replaces this device's copy:
     * - gender maps back to the spinner position (1 Male, 2 Female, 3 Prefer not to say, 0 unset);
     * - an address shaped "Barangay, City, Province" (what the app and sign-up write) is split back
     *   into the three dropdown values; any other free text leaves them empty for the guest to pick;
     * - the ID fields only say whether a photo is on file and what type it is.
     */
    static void saveServerProfile(Context context, ProfileResponse p) {
        String address = orEmpty(p.address).trim();
        String[] parts = address.split("\\s*,\\s*");
        boolean splits = parts.length == 3 && !parts[0].isEmpty() && !parts[1].isEmpty() && !parts[2].isEmpty();

        prefs(context).edit()
                .putString(KEY_FIRST_NAME, orEmpty(p.Firstname))
                .putString(KEY_MIDDLE_NAME, orEmpty(p.Middlename))
                .putString(KEY_LAST_NAME, orEmpty(p.Lastname))
                .putInt(KEY_GENDER_POS, genderPosition(p.gender))
                .putString(KEY_BIRTH_DATE, dateOnly(p.birthday))
                .putString(KEY_USERNAME, orEmpty(p.username))
                .putString(KEY_EMAIL, orEmpty(p.email))
                .putString(KEY_ADDRESS, address)
                .putString(KEY_BARANGAY, splits ? parts[0] : "")
                .putString(KEY_CITY, splits ? parts[1] : "")
                .putString(KEY_PROVINCE, splits ? parts[2] : "")
                .putString(KEY_CONTACT_NUMBER, localPhone(p.contact_number))
                .putBoolean(KEY_HAS_VALID_ID, p.has_valid_id)
                .putString(KEY_VALID_ID_TYPE, orEmpty(p.valid_id_type))
                .apply();
    }

    /** The server's gender text as the "prompted spinner" position the profile form uses. */
    private static int genderPosition(String gender) {
        if (gender == null) {
            return 0;
        }
        switch (gender) {
            case "Male":
                return 1;
            case "Female":
                return 2;
            default:
                return 3; // "Prefer not to say" (the website's only other value)
        }
    }

    /** The website also accepts +639XXXXXXXXX; the form's phone field only holds the 09XXXXXXXXX form. */
    private static String localPhone(String number) {
        String phone = orEmpty(number).trim();
        return phone.startsWith("+63") ? "0" + phone.substring(3) : phone;
    }

    /** yyyy-MM-dd from a server date that may carry a time part. */
    private static String dateOnly(String date) {
        if (date == null) {
            return "";
        }
        return date.length() >= 10 && date.substring(0, 10).matches("\\d{4}-\\d{2}-\\d{2}")
                ? date.substring(0, 10) : date;
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }
}
