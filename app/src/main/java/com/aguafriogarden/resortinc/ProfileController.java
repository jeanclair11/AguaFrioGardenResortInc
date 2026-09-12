package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Drives the "Me" tab's Profile screen and its sub-screens (Personal
 * Information, Change Password, Valid ID, Upload New ID), each reached by
 * tapping a row on the Profile main screen and left via a header back arrow;
 * the main screen itself is left via its Back to Home button. DashboardActivity
 * keeps one instance alive across tab switches (only getRootView() is
 * detached/reattached), forwards onActivityResult() to it for the avatar and
 * ID image pickers, and consults handleBackPressed() so the hardware back
 * button steps out of a sub-screen (or off Profile entirely) instead of
 * exiting the app.
 */
final class ProfileController {

    private static final int SCREEN_MAIN = 0;
    private static final int SCREEN_PERSONAL_INFO = 1;
    private static final int SCREEN_PERSONAL_INFO_SUCCESS = 2;
    private static final int SCREEN_CHANGE_PASSWORD = 3;
    private static final int SCREEN_CHANGE_PASSWORD_SUCCESS = 4;
    private static final int SCREEN_VALID_ID = 5;
    private static final int SCREEN_UPLOAD_ID = 6;

    private static final int REQUEST_ID_FRONT = 3001;
    private static final int REQUEST_ID_BACK = 3002;
    private static final int REQUEST_AVATAR = 3003;

    private static final String PASSWORD_COMPLEXITY_REGEX =
            "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=!])(?=\\S+$).{8,}$";

    private static final int PHONE_NUMBER_LENGTH = 11;

    /** Strips out anything that isn't a digit (Personal Information's Phone Number field). */
    private static final InputFilter DIGITS_ONLY_FILTER = (source, start, end, dest, dstart, dend) -> {
        StringBuilder kept = new StringBuilder();
        boolean rejectedAny = false;
        for (int i = start; i < end; i++) {
            char c = source.charAt(i);
            if (Character.isDigit(c)) {
                kept.append(c);
            } else {
                rejectedAny = true;
            }
        }
        return rejectedAny ? kept.toString() : null;
    };

    private interface DateCallback {
        void onDatePicked(String date);
    }

    private final Activity activity;
    private final Runnable onBackToHome;
    private final Runnable onLogout;
    private final Runnable onAvatarChanged;
    private final FrameLayout root;

    private View currentScreenView;
    private int currentScreen = -1;

    // Upload New ID's in-progress, unsaved picks.
    private Bitmap idFrontBitmap;
    private Bitmap idBackBitmap;

    ProfileController(Activity activity, Runnable onBackToHome, Runnable onLogout,
            Runnable onAvatarChanged) {
        this.activity = activity;
        this.onBackToHome = onBackToHome;
        this.onLogout = onLogout;
        this.onAvatarChanged = onAvatarChanged;
        root = new FrameLayout(activity);
        showScreen(SCREEN_MAIN);
    }

    View getRootView() {
        return root;
    }

    /**
     * Steps out of a sub-screen, or off Profile entirely back to Home once
     * already on the main screen (there's no in-screen "Back to Home" button
     * or bottom nav to fall back on, so this always consumes the press).
     */
    boolean handleBackPressed() {
        switch (currentScreen) {
            case SCREEN_PERSONAL_INFO:
            case SCREEN_PERSONAL_INFO_SUCCESS:
            case SCREEN_CHANGE_PASSWORD:
            case SCREEN_CHANGE_PASSWORD_SUCCESS:
            case SCREEN_VALID_ID:
                showScreen(SCREEN_MAIN);
                return true;
            case SCREEN_UPLOAD_ID:
                showScreen(SCREEN_VALID_ID);
                return true;
            default:
                onBackToHome.run();
                return true;
        }
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_AVATAR) {
            ProfileStore.saveAvatarUri(activity, uri);
            showAvatarPreview();
            onAvatarChanged.run();
            return;
        }
        if (requestCode != REQUEST_ID_FRONT && requestCode != REQUEST_ID_BACK) {
            return;
        }
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(activity.getContentResolver(), uri);
            if (requestCode == REQUEST_ID_FRONT) {
                idFrontBitmap = bitmap;
                showIdPreview(true, bitmap);
            } else {
                idBackBitmap = bitmap;
                showIdPreview(false, bitmap);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void showAvatarPreview() {
        if (currentScreen != SCREEN_MAIN || currentScreenView == null) {
            return;
        }
        bindAvatar(currentScreenView.findViewById(R.id.profileAvatar));
    }

    private void showIdPreview(boolean front, Bitmap bitmap) {
        if (currentScreen != SCREEN_UPLOAD_ID || currentScreenView == null) {
            return;
        }
        int previewId = front ? R.id.uiFrontPreview : R.id.uiBackPreview;
        int hintId = front ? R.id.uiFrontHint : R.id.uiBackHint;
        ImageView preview = currentScreenView.findViewById(previewId);
        preview.setImageBitmap(bitmap);
        preview.setVisibility(View.VISIBLE);
        currentScreenView.findViewById(hintId).setVisibility(View.GONE);
        if (front) {
            currentScreenView.findViewById(R.id.uiFrontError).setVisibility(View.GONE);
        }
    }

    private void showScreen(int screen) {
        currentScreen = screen;
        View v = LayoutInflater.from(activity).inflate(layoutFor(screen), root, false);
        bindScreen(screen, v);
        root.removeAllViews();
        root.addView(v);
        currentScreenView = v;
    }

    private int layoutFor(int screen) {
        switch (screen) {
            case SCREEN_PERSONAL_INFO:
                return R.layout.view_personal_information;
            case SCREEN_PERSONAL_INFO_SUCCESS:
            case SCREEN_CHANGE_PASSWORD_SUCCESS:
                return R.layout.view_success_screen;
            case SCREEN_CHANGE_PASSWORD:
                return R.layout.view_change_password;
            case SCREEN_VALID_ID:
                return R.layout.view_valid_id;
            case SCREEN_UPLOAD_ID:
                return R.layout.view_upload_new_id;
            default:
                return R.layout.view_profile_main;
        }
    }

    private void bindScreen(int screen, View v) {
        switch (screen) {
            case SCREEN_PERSONAL_INFO:
                bindPersonalInfo(v);
                break;
            case SCREEN_PERSONAL_INFO_SUCCESS:
                bindSuccess(v, R.string.personal_info_success_title,
                        R.string.personal_info_success_message, () -> showScreen(SCREEN_MAIN));
                break;
            case SCREEN_CHANGE_PASSWORD:
                bindChangePassword(v);
                break;
            case SCREEN_CHANGE_PASSWORD_SUCCESS:
                bindSuccess(v, R.string.password_success_title,
                        R.string.password_success_message, () -> showScreen(SCREEN_MAIN));
                break;
            case SCREEN_VALID_ID:
                bindValidId(v);
                break;
            case SCREEN_UPLOAD_ID:
                bindUploadId(v);
                break;
            default:
                bindMain(v);
                break;
        }
    }

    // ---- Main profile screen ----------------------------------------

    private void bindMain(View v) {
        boolean light = ThemeManager.mode(activity) == ThemeManager.MODE_LIGHT;

        v.findViewById(R.id.profileBackButton).setOnClickListener(view -> onBackToHome.run());

        bindAvatar(v.findViewById(R.id.profileAvatar));
        View.OnClickListener changeAvatar = view -> pickImage(REQUEST_AVATAR);
        v.findViewById(R.id.profileAvatar).setOnClickListener(changeAvatar);
        v.findViewById(R.id.profileChangeAvatarButton).setOnClickListener(changeAvatar);

        ((TextView) v.findViewById(R.id.profileName)).setText(ProfileStore.getFullName(activity));

        TextView usernameView = v.findViewById(R.id.profileUsername);
        String username = ProfileStore.getUsername(activity);
        usernameView.setText(username);
        usernameView.setVisibility(username.isEmpty() ? View.GONE : View.VISIBLE);

        TextView emailView = v.findViewById(R.id.profileEmail);
        String email = ProfileStore.getEmail(activity);
        emailView.setText(email);
        emailView.setVisibility(email.isEmpty() ? View.GONE : View.VISIBLE);

        // Soft shadow in Light Mode; a subtle border reads better than a
        // shadow on Dark Mode's own dark surfaces.
        int cardBg = light ? R.drawable.bg_card : R.drawable.bg_card_bordered;
        float cardElevation = light ? 3f * activity.getResources().getDisplayMetrics().density : 0f;
        for (int cardId : new int[]{R.id.profileCard, R.id.accountCard, R.id.preferencesCard, R.id.logoutCard}) {
            View card = v.findViewById(cardId);
            card.setBackgroundResource(cardBg);
            card.setElevation(cardElevation);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                card.setOutlineAmbientShadowColor(
                        activity.getResources().getColor(R.color.card_shadow_ambient, activity.getTheme()));
                card.setOutlineSpotShadowColor(
                        activity.getResources().getColor(R.color.card_shadow_spot, activity.getTheme()));
            }
        }

        bindActionRow(v.findViewById(R.id.rowPersonalInfo), R.drawable.ic_person,
                R.string.row_personal_information_title, R.string.row_personal_information_subtitle,
                null, 0, () -> showScreen(SCREEN_PERSONAL_INFO));
        bindActionRow(v.findViewById(R.id.rowChangePassword), R.drawable.ic_lock,
                R.string.row_change_password_title, R.string.row_change_password_subtitle,
                null, 0, () -> showScreen(SCREEN_CHANGE_PASSWORD));

        String idBadgeText;
        int idBadgeBg;
        if (!ProfileStore.hasIdSubmission(activity)) {
            idBadgeText = activity.getString(R.string.status_not_submitted);
            idBadgeBg = R.drawable.bg_badge_neutral;
        } else if (ProfileStore.isIdVerified(activity)) {
            idBadgeText = activity.getString(R.string.status_verified);
            idBadgeBg = R.drawable.bg_badge_success;
        } else {
            idBadgeText = activity.getString(R.string.status_pending);
            idBadgeBg = R.drawable.bg_badge_pending;
        }
        bindActionRow(v.findViewById(R.id.rowValidId), R.drawable.ic_id_card,
                R.string.row_valid_id_title, R.string.row_valid_id_subtitle,
                idBadgeText, idBadgeBg, () -> showScreen(SCREEN_VALID_ID));

        View notificationsRow = v.findViewById(R.id.rowNotifications);
        bindSwitchRowLabels(notificationsRow, R.drawable.ic_bell,
                R.string.row_enable_notifications_title, R.string.row_enable_notifications_subtitle);
        Switch notificationsSwitch = notificationsRow.findViewById(R.id.rowSwitch);
        notificationsSwitch.setChecked(ProfileStore.isNotificationsEnabled(activity));
        notificationsSwitch.setOnCheckedChangeListener((btn, checked) ->
                ProfileStore.setNotificationsEnabled(activity, checked));

        View darkModeRow = v.findViewById(R.id.rowDarkMode);
        bindSwitchRowLabels(darkModeRow, R.drawable.ic_moon,
                R.string.row_dark_mode_title, R.string.row_dark_mode_subtitle);
        Switch darkModeSwitch = darkModeRow.findViewById(R.id.rowSwitch);
        darkModeSwitch.setChecked(ThemeManager.mode(activity) == ThemeManager.MODE_DARK);
        darkModeSwitch.setOnCheckedChangeListener((btn, checked) -> ThemeManager.setMode(
                activity, checked ? ThemeManager.MODE_DARK : ThemeManager.MODE_LIGHT));

        v.findViewById(R.id.logoutRow).setOnClickListener(view -> onLogout.run());
    }

    /** Shows the saved avatar bitmap, or the placeholder person icon when none is set. */
    private void bindAvatar(ImageView avatar) {
        Bitmap picture = ProfileStore.loadAvatar(activity);
        if (picture != null) {
            avatar.setPadding(0, 0, 0, 0);
            avatar.setImageTintList(null);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setImageBitmap(picture);
        } else {
            avatar.setPadding(16, 16, 16, 16);
            avatar.setImageResource(R.drawable.ic_person);
            avatar.setImageTintList(android.content.res.ColorStateList.valueOf(
                    ThemeManager.color(activity, R.attr.textOnScreen)));
            avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
        avatar.setClipToOutline(true);
    }

    private void bindActionRow(View row, int iconRes, int titleRes, int subtitleRes,
            String badgeText, int badgeBgRes, Runnable onClick) {
        ((ImageView) row.findViewById(R.id.rowIcon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.rowTitle)).setText(titleRes);
        ((TextView) row.findViewById(R.id.rowSubtitle)).setText(subtitleRes);
        TextView badge = row.findViewById(R.id.rowBadge);
        if (badgeText != null) {
            badge.setText(badgeText);
            badge.setBackgroundResource(badgeBgRes);
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }
        row.setOnClickListener(view -> onClick.run());
    }

    private void bindSwitchRowLabels(View row, int iconRes, int titleRes, int subtitleRes) {
        ((ImageView) row.findViewById(R.id.rowIcon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.rowTitle)).setText(titleRes);
        ((TextView) row.findViewById(R.id.rowSubtitle)).setText(subtitleRes);
    }

    private void bindSuccess(View v, int titleRes, int messageRes, Runnable onBack) {
        ((TextView) v.findViewById(R.id.successTitle)).setText(titleRes);
        ((TextView) v.findViewById(R.id.successMessage)).setText(messageRes);
        v.findViewById(R.id.successButton).setOnClickListener(view -> onBack.run());
    }

    // ---- Personal Information ----------------------------------------

    private void bindPersonalInfo(View v) {
        bindHeader(v, R.string.personal_info_title, () -> showScreen(SCREEN_MAIN));

        ((EditText) v.findViewById(R.id.piFirstNameField)).setText(ProfileStore.getFirstName(activity));
        ((EditText) v.findViewById(R.id.piMiddleNameField)).setText(ProfileStore.getMiddleName(activity));
        ((EditText) v.findViewById(R.id.piLastNameField)).setText(ProfileStore.getLastName(activity));
        ((EditText) v.findViewById(R.id.piProvinceField)).setText(ProfileStore.getProvince(activity));
        ((EditText) v.findViewById(R.id.piCityField)).setText(ProfileStore.getCity(activity));
        ((EditText) v.findViewById(R.id.piBarangayField)).setText(ProfileStore.getBarangay(activity));
        ((EditText) v.findViewById(R.id.piUsernameField)).setText(ProfileStore.getUsername(activity));
        ((EditText) v.findViewById(R.id.piEmailField)).setText(ProfileStore.getEmail(activity));

        setUpPromptedSpinner(v.findViewById(R.id.piGenderSpinner), R.string.gender_prompt,
                R.array.gender_options, ProfileStore.getGenderPos(activity));

        EditText birthDate = v.findViewById(R.id.piBirthDateField);
        birthDate.setText(ProfileStore.getBirthDate(activity));
        birthDate.setOnClickListener(view -> showPastDatePicker(birthDate.getText().toString(), date -> {
            birthDate.setText(date);
            v.findViewById(R.id.piBirthDateError).setVisibility(View.GONE);
        }));

        EditText phoneNumber = v.findViewById(R.id.piPhoneNumberField);
        phoneNumber.setFilters(new InputFilter[]{DIGITS_ONLY_FILTER, new InputFilter.LengthFilter(PHONE_NUMBER_LENGTH)});
        phoneNumber.setText(ProfileStore.getContactNumber(activity));

        v.findViewById(R.id.piCancelButton).setOnClickListener(view -> showScreen(SCREEN_MAIN));
        v.findViewById(R.id.piChangeButton).setOnClickListener(view -> {
            if (validatePersonalInfo(v)) {
                savePersonalInfo(v);
                showScreen(SCREEN_PERSONAL_INFO_SUCCESS);
            }
        });
    }

    private boolean validatePersonalInfo(View v) {
        boolean valid = true;

        String firstName = fieldText(v, R.id.piFirstNameField);
        String middleName = fieldText(v, R.id.piMiddleNameField);
        String lastName = fieldText(v, R.id.piLastNameField);
        String birthDate = fieldText(v, R.id.piBirthDateField);
        String phoneNumber = fieldText(v, R.id.piPhoneNumberField);
        String province = fieldText(v, R.id.piProvinceField);
        String city = fieldText(v, R.id.piCityField);
        String barangay = fieldText(v, R.id.piBarangayField);
        String username = fieldText(v, R.id.piUsernameField);
        String email = fieldText(v, R.id.piEmailField);
        int genderPos = ((Spinner) v.findViewById(R.id.piGenderSpinner)).getSelectedItemPosition();

        valid &= checkField(v, R.id.piFirstNameError,
                firstName.matches("[a-zA-Z ]+"), R.string.error_name_invalid);
        valid &= checkField(v, R.id.piMiddleNameError,
                middleName.isEmpty() || middleName.matches("[a-zA-Z. ]+"), R.string.error_name_invalid);
        valid &= checkField(v, R.id.piLastNameError,
                lastName.matches("[a-zA-Z ]+"), R.string.error_name_invalid);
        valid &= checkField(v, R.id.piGenderError, genderPos > 0, R.string.error_gender_required);
        valid &= checkField(v, R.id.piPhoneNumberError,
                phoneNumber.matches("09\\d{9}"), R.string.error_phone_invalid);

        if (birthDate.isEmpty()) {
            valid &= checkField(v, R.id.piBirthDateError, false, R.string.error_birth_date_required);
        } else {
            int year = Integer.parseInt(birthDate.split("-")[0]);
            valid &= checkField(v, R.id.piBirthDateError,
                    Calendar.getInstance().get(Calendar.YEAR) - year >= 18, R.string.error_age_invalid);
        }

        valid &= checkField(v, R.id.piProvinceError, !province.isEmpty(), R.string.error_province_required);
        valid &= checkField(v, R.id.piCityError, !city.isEmpty(), R.string.error_city_required);
        valid &= checkField(v, R.id.piBarangayError, !barangay.isEmpty(), R.string.error_barangay_required);
        valid &= checkField(v, R.id.piUsernameError,
                username.length() >= 4 && username.length() <= 20 && username.matches("[a-zA-Z0-9_]+"),
                R.string.error_username_invalid);
        valid &= checkField(v, R.id.piEmailError,
                Patterns.EMAIL_ADDRESS.matcher(email).matches(), R.string.error_email_invalid);

        return valid;
    }

    private void savePersonalInfo(View v) {
        int genderPos = ((Spinner) v.findViewById(R.id.piGenderSpinner)).getSelectedItemPosition();
        ProfileStore.savePersonalInfo(activity,
                fieldText(v, R.id.piFirstNameField),
                fieldText(v, R.id.piMiddleNameField),
                fieldText(v, R.id.piLastNameField),
                genderPos,
                fieldText(v, R.id.piBirthDateField),
                fieldText(v, R.id.piPhoneNumberField),
                fieldText(v, R.id.piProvinceField),
                fieldText(v, R.id.piCityField),
                fieldText(v, R.id.piBarangayField),
                fieldText(v, R.id.piUsernameField),
                fieldText(v, R.id.piEmailField));
    }

    // ---- Change Password ----------------------------------------

    private void bindChangePassword(View v) {
        bindHeader(v, R.string.change_password_title, () -> showScreen(SCREEN_MAIN));

        EditText current = v.findViewById(R.id.cpCurrentPasswordField);
        EditText newPassword = v.findViewById(R.id.cpNewPasswordField);
        EditText confirm = v.findViewById(R.id.cpConfirmPasswordField);
        TextView strengthText = v.findViewById(R.id.cpStrengthText);

        AuthUiUtils.attachPasswordToggle(current, v.findViewById(R.id.cpCurrentToggle));
        AuthUiUtils.attachPasswordToggle(newPassword, v.findViewById(R.id.cpNewToggle));
        AuthUiUtils.attachPasswordToggle(confirm, v.findViewById(R.id.cpConfirmToggle));

        AuthUiUtils.afterTextChanged(newPassword, () -> {
            String pw = newPassword.getText().toString();
            AuthUiUtils.updatePasswordStrength(pw, strengthText);
            updatePasswordRequirements(v, pw);
        });
        updatePasswordRequirements(v, "");

        v.findViewById(R.id.cpCancelButton).setOnClickListener(view -> showScreen(SCREEN_MAIN));
        v.findViewById(R.id.cpUpdateButton).setOnClickListener(view -> {
            boolean valid = true;
            String currentPw = current.getText().toString();
            String newPw = newPassword.getText().toString();
            String confirmPw = confirm.getText().toString();

            valid &= checkField(v, R.id.cpCurrentError,
                    !currentPw.isEmpty() && currentPw.equals(ProfileStore.getPassword(activity)),
                    R.string.error_password_incorrect);
            valid &= checkField(v, R.id.cpNewError,
                    newPw.matches(PASSWORD_COMPLEXITY_REGEX), R.string.error_password_complexity);
            valid &= checkField(v, R.id.cpConfirmError,
                    confirmPw.equals(newPw), R.string.error_passwords_dont_match);

            if (valid) {
                ProfileStore.setPassword(activity, newPw);
                showScreen(SCREEN_CHANGE_PASSWORD_SUCCESS);
            }
        });
    }

    private void updatePasswordRequirements(View v, String password) {
        setRequirementState(v, R.id.cpReqLengthIcon, R.id.cpReqLengthText, password.length() >= 8);
        setRequirementState(v, R.id.cpReqCaseIcon, R.id.cpReqCaseText,
                password.matches(".*[A-Z].*") && password.matches(".*[a-z].*"));
        setRequirementState(v, R.id.cpReqNumberIcon, R.id.cpReqNumberText,
                password.matches(".*[0-9].*"));
        setRequirementState(v, R.id.cpReqSpecialIcon, R.id.cpReqSpecialText,
                password.matches(".*[^A-Za-z0-9].*"));
    }

    private void setRequirementState(View v, int iconId, int textId, boolean satisfied) {
        ImageView icon = v.findViewById(iconId);
        TextView text = v.findViewById(textId);
        icon.setColorFilter(satisfied ? 0xFF2E7D32 : ThemeManager.color(activity, R.attr.iconMuted));
        text.setTextColor(satisfied
                ? ThemeManager.color(activity, R.attr.textPrimary)
                : ThemeManager.color(activity, R.attr.textMuted));
    }

    // ---- Valid ID ----------------------------------------

    private void bindValidId(View v) {
        bindHeader(v, R.string.valid_id_title, () -> showScreen(SCREEN_MAIN));

        boolean hasUpload = ProfileStore.hasIdSubmission(activity);
        boolean verified = hasUpload && ProfileStore.isIdVerified(activity);
        String uploadDate = ProfileStore.getIdUploadDate(activity);

        TextView statusText = v.findViewById(R.id.viStatusText);
        TextView statusDesc = v.findViewById(R.id.viStatusDesc);
        int statusTextRes;
        int statusDescRes;
        int statusColor;
        if (!hasUpload) {
            statusTextRes = R.string.status_not_submitted;
            statusDescRes = R.string.no_id_uploaded_message;
            statusColor = ThemeManager.color(activity, R.attr.iconMuted);
        } else if (verified) {
            statusTextRes = R.string.status_verified;
            statusDescRes = R.string.verification_status_verified_desc;
            statusColor = 0xFF2E7D32;
        } else {
            statusTextRes = R.string.status_pending;
            statusDescRes = R.string.verification_status_pending_desc;
            statusColor = 0xFFE0912E;
        }
        statusText.setText(statusTextRes);
        statusText.setTextColor(statusColor);
        ((ImageView) v.findViewById(R.id.viStatusIcon)).setColorFilter(statusColor);
        statusDesc.setText(statusDescRes);

        v.findViewById(R.id.viUploadedIdCard).setVisibility(hasUpload ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.viNoIdMessage).setVisibility(hasUpload ? View.GONE : View.VISIBLE);
        if (hasUpload) {
            String[] idTypes = activity.getResources().getStringArray(R.array.id_type_options);
            int idTypePos = ProfileStore.getIdTypePos(activity) - 1;
            String idTypeLabel = idTypePos >= 0 && idTypePos < idTypes.length
                    ? idTypes[idTypePos] : "";
            ((TextView) v.findViewById(R.id.viIdTypeText)).setText(idTypeLabel);
            ((TextView) v.findViewById(R.id.viUploadedOnText)).setText(
                    activity.getString(R.string.uploaded_on_format, uploadDate));
            ((TextView) v.findViewById(R.id.viIdNumberText)).setText(
                    activity.getString(R.string.id_number_format, ProfileStore.getIdNumber(activity)));
        }

        v.findViewById(R.id.viUploadNewIdButton).setOnClickListener(view -> showScreen(SCREEN_UPLOAD_ID));
        v.findViewById(R.id.viGuidelinesButton).setOnClickListener(view ->
                Toast.makeText(activity, R.string.coming_soon_toast, Toast.LENGTH_SHORT).show());
    }

    // ---- Upload New ID ----------------------------------------

    private void bindUploadId(View v) {
        bindHeader(v, R.string.upload_new_id_title, () -> showScreen(SCREEN_VALID_ID));

        // Always starts as a fresh, blank submission.
        idFrontBitmap = null;
        idBackBitmap = null;

        setUpPromptedSpinner(v.findViewById(R.id.uiIdTypeSpinner), R.string.id_type_prompt,
                R.array.id_type_options, 0);

        v.findViewById(R.id.uiFrontDropzone).setOnClickListener(view -> pickImage(REQUEST_ID_FRONT));
        v.findViewById(R.id.uiBackDropzone).setOnClickListener(view -> pickImage(REQUEST_ID_BACK));

        EditText expiry = v.findViewById(R.id.uiExpiryField);
        expiry.setOnClickListener(view -> showFutureDatePicker(date -> expiry.setText(date)));

        v.findViewById(R.id.uiCancelButton).setOnClickListener(view -> showScreen(SCREEN_VALID_ID));
        v.findViewById(R.id.uiSubmitButton).setOnClickListener(view -> {
            boolean valid = true;
            int idTypePos = ((Spinner) v.findViewById(R.id.uiIdTypeSpinner)).getSelectedItemPosition();
            String idNumber = fieldText(v, R.id.uiIdNumberField);

            valid &= checkField(v, R.id.uiIdTypeError, idTypePos > 0, R.string.error_id_type_required);
            valid &= checkField(v, R.id.uiFrontError, idFrontBitmap != null, R.string.error_id_required);
            valid &= checkField(v, R.id.uiIdNumberError, !idNumber.isEmpty(),
                    R.string.error_id_number_required);

            if (valid) {
                String today = new SimpleDateFormat("MMMM d, yyyy", Locale.US)
                        .format(Calendar.getInstance().getTime());
                ProfileStore.saveIdSubmission(activity, idTypePos, idNumber, today);
                Toast.makeText(activity, R.string.id_submitted_message, Toast.LENGTH_LONG).show();
                showScreen(SCREEN_VALID_ID);
            }
        });
    }

    private void pickImage(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        activity.startActivityForResult(intent, requestCode);
    }

    // ---- Shared helpers ----------------------------------------

    private void bindHeader(View v, int titleRes, Runnable onBack) {
        View header = v.findViewById(R.id.headerBar);
        ((TextView) header.findViewById(R.id.headerTitle)).setText(titleRes);
        header.findViewById(R.id.headerBackButton).setOnClickListener(view -> onBack.run());
    }

    private void setUpPromptedSpinner(Spinner spinner, int promptRes, int optionsRes, int selection) {
        List<String> options = new ArrayList<>();
        options.add(activity.getString(promptRes));
        options.addAll(Arrays.asList(activity.getResources().getStringArray(optionsRes)));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selection);
    }

    private String fieldText(View v, int fieldId) {
        return ((EditText) v.findViewById(fieldId)).getText().toString().trim();
    }

    private boolean checkField(View v, int errorId, boolean ok, int messageRes) {
        TextView errorView = v.findViewById(errorId);
        if (ok) {
            errorView.setVisibility(View.GONE);
        } else {
            errorView.setText(messageRes);
            errorView.setVisibility(View.VISIBLE);
        }
        return ok;
    }

    private void showPastDatePicker(String initialDate, DateCallback callback) {
        long current = -1;
        try {
            if (!initialDate.isEmpty()) {
                Calendar c = Calendar.getInstance();
                String[] parts = initialDate.split("-");
                c.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
                current = c.getTimeInMillis();
            }
        } catch (Exception ignored) {}

        GlassDatePicker.showBirthdatePicker(activity, current, millis -> {
            callback.onDatePicked(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date(millis)));
        });
    }

    private void showFutureDatePicker(DateCallback callback) {
        GlassDatePicker.showCalendarPicker(activity, -1, System.currentTimeMillis() - 1000L, millis -> {
            callback.onDatePicked(new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date(millis)));
        });
    }
}
