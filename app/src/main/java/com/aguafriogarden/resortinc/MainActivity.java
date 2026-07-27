package com.aguafriogarden.resortinc;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.ErrorResponse;
import com.aguafriogarden.resortinc.network.MessageResponse;
import com.aguafriogarden.resortinc.network.RegisterResponse;
import com.aguafriogarden.resortinc.network.VerifyResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Hosts all three authentication screens (Login, Sign Up, Recovery) behind a
 * single fixed header. The white floating card is a permanent part of this
 * layout: navigating swaps and animates only the contents inside it, while
 * the header, background, and card frame stay stationary (the card only
 * resizes smoothly to fit the new contents).
 */
public class MainActivity extends Activity {

    private static final int SCREEN_LOGIN = 0;
    private static final int SCREEN_SIGN_UP = 1;
    private static final int SCREEN_RECOVERY = 2;
    private static final int SCREEN_SIGN_UP_2 = 3;
    private static final int SCREEN_SIGN_UP_3 = 4;
    private static final int SCREEN_SIGN_UP_OTP = 5;
    private static final int SCREEN_RECOVERY_OTP = 6;
    private static final int SCREEN_RECOVERY_RESET = 7;
    private static final int SCREEN_RECOVERY_SUCCESS = 8;

    private static final String STATE_SCREEN = "screen";

    private static final long CARD_ANIM_DURATION_MS = 220L;
    private static final long CLOCK_TICK_MS = 1000L;
    // Screens swap with a quiet fade plus a barely-there vertical drift.
    private static final float CONTENT_SHIFT_DP = 8f;

    private static final int MIN_PASSWORD_LENGTH = 8;

    private static final int PICK_IMAGE_PROFILE = 1001;
    private static final int PICK_IMAGE_GOV_ID = 1002;
    private static final int CAPTURE_GOV_ID = 1003;

    private ScrollView scroll;
    private FrameLayout card;
    private Animator headerCrossfade;
    private View currentContent;
    private Handler clockHandler;
    private Runnable clockTick;
    private ValueAnimator cardHeightAnimator;
    private int currentScreen = -1;
    private boolean loginInProgress;

    /**
     * Everything entered across the three sign-up steps, kept while the user
     * moves back and forth so no progress is lost. Reset after account
     * creation or when returning to login.
     */
    private static class SignUpState {
        String firstName = "";
        String middleName = "";
        String lastName = "";
        int genderPos;
        String birthDate = "";
        String province = "";
        String city = "";
        String barangay = "";
        String username = "";
        String email = "";
        String password = "";
        String confirmPassword = "";
        int idTypePos;
        Uri govIdUri;
        Bitmap govIdBitmap;
        Uri profileUri;
        Bitmap profileBitmap;
        boolean termsAccepted;
        String registrationToken = "";
    }

    private static class RecoveryState {
        String email = "";
        String otp = "";
    }

    private SignUpState signUpState = new SignUpState();
    private RecoveryState recoveryState = new RecoveryState();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.apply(this);
        setContentView(R.layout.activity_main);

        // Keep the header fixed; anchor the card to overlap its bottom by 30dp
        // so the panel sits slightly lower on the screen.
        AuthUiUtils.anchorCardToHeader(
                findViewById(R.id.header), findViewById(R.id.headerSpacer), 30);

        scroll = findViewById(R.id.scroll);
        card = findViewById(R.id.card);

        ThemeManager.bindToggle(this, findViewById(R.id.themeToggle));

        // Stay on the same screen when the theme toggle recreates us.
        int screen = savedInstanceState != null
                ? savedInstanceState.getInt(STATE_SCREEN, SCREEN_LOGIN) : SCREEN_LOGIN;
        showScreen(screen, false);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SCREEN, currentScreen);
    }

    @Override
    protected void onStart() {
        super.onStart();
        headerCrossfade = ThemeManager.startHeaderCrossfade(this,
                findViewById(R.id.headerPhoto), findViewById(R.id.headerPhotoOverlay));

        clockHandler = new Handler(android.os.Looper.getMainLooper());
        clockTick = new Runnable() {
            @Override
            public void run() {
                updateHeaderClock();
                clockHandler.postDelayed(this, CLOCK_TICK_MS);
            }
        };
        clockTick.run();
    }

    private void updateHeaderClock() {
        Calendar now = Calendar.getInstance();
        TextView dateView = findViewById(R.id.headerDate);
        TextView timeView = findViewById(R.id.headerTime);
        if (dateView != null) {
            dateView.setText(new java.text.SimpleDateFormat(
                    "EEEE • MMMM d", Locale.getDefault()).format(now.getTime()));
        }
        if (timeView != null) {
            timeView.setText(new java.text.SimpleDateFormat(
                    "h:mm a", Locale.getDefault()).format(now.getTime()));
        }
    }

    @Override
    protected void onStop() {
        if (headerCrossfade != null) {
            headerCrossfade.cancel();
            headerCrossfade = null;
        }
        if (clockHandler != null && clockTick != null) {
            clockHandler.removeCallbacks(clockTick);
        }
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        switch (currentScreen) {
            case SCREEN_SIGN_UP_OTP:
                showScreen(SCREEN_SIGN_UP_3, true);
                break;
            case SCREEN_SIGN_UP_3:
                saveSignUpStep3(currentContent);
                showScreen(SCREEN_SIGN_UP_2, true);
                break;
            case SCREEN_SIGN_UP_2:
                saveSignUpStep2(currentContent);
                showScreen(SCREEN_SIGN_UP, true);
                break;
            case SCREEN_SIGN_UP:
                saveSignUpStep1(currentContent);
                showScreen(SCREEN_LOGIN, true);
                break;
            case SCREEN_RECOVERY:
                showScreen(SCREEN_LOGIN, true);
                break;
            case SCREEN_RECOVERY_OTP:
                showScreen(SCREEN_RECOVERY, true);
                break;
            case SCREEN_RECOVERY_RESET:
                showScreen(SCREEN_RECOVERY_OTP, true);
                break;
            default:
                super.onBackPressed();
                break;
        }
    }

    /**
     * Swaps the contents of the fixed white card, cross-fading them with a
     * slight horizontal slide. The card frame itself never moves; its height
     * animates to fit the incoming contents.
     */
    private void showScreen(int screen, boolean animate) {
        if (screen == currentScreen) {
            return;
        }
        currentScreen = screen;

        View newContent = LayoutInflater.from(this).inflate(layoutFor(screen), card, false);
        bindScreen(screen, newContent);

        View oldContent = currentContent;
        currentContent = newContent;

        if (!animate || oldContent == null) {
            if (oldContent != null) {
                card.removeView(oldContent);
            }
            card.addView(newContent);
            return;
        }

        if (cardHeightAnimator != null) {
            cardHeightAnimator.cancel();
        }
        int oldHeight = card.getHeight();
        card.addView(newContent);

        // Measure the incoming contents at the card's width to learn the
        // height the card should settle at.
        newContent.measure(
                View.MeasureSpec.makeMeasureSpec(card.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        animateCardHeight(oldHeight, newContent.getMeasuredHeight());

        // The old contents dissolve while the new ones fade in with a subtle
        // upward drift. Children are clipped to the card.
        float shiftPx = CONTENT_SHIFT_DP * getResources().getDisplayMetrics().density;

        oldContent.animate()
                .alpha(0f)
                .setDuration(CARD_ANIM_DURATION_MS)
                .withEndAction(() -> card.removeView(oldContent))
                .start();

        newContent.setAlpha(0f);
        newContent.setTranslationY(shiftPx);
        newContent.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(CARD_ANIM_DURATION_MS)
                .start();

        scroll.smoothScrollTo(0, 0);
    }

    /** Animates the card's height between content sizes, then releases it back to wrap_content. */
    private void animateCardHeight(int fromHeight, int toHeight) {
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        lp.height = fromHeight;
        card.setLayoutParams(lp);

        cardHeightAnimator = ValueAnimator.ofInt(fromHeight, toHeight);
        cardHeightAnimator.setDuration(CARD_ANIM_DURATION_MS);
        cardHeightAnimator.addUpdateListener(animation -> {
            lp.height = (int) animation.getAnimatedValue();
            card.setLayoutParams(lp);
        });
        cardHeightAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                card.setLayoutParams(lp);
            }
        });
        cardHeightAnimator.start();
    }

    private int layoutFor(int screen) {
        switch (screen) {
            case SCREEN_SIGN_UP:
                return R.layout.card_sign_up_1;
            case SCREEN_SIGN_UP_2:
                return R.layout.card_sign_up_2;
            case SCREEN_SIGN_UP_3:
                return R.layout.card_sign_up_3;
            case SCREEN_SIGN_UP_OTP:
                return R.layout.card_sign_up_otp;
            case SCREEN_RECOVERY:
                return R.layout.card_recovery;
            case SCREEN_RECOVERY_OTP:
                return R.layout.card_recovery_otp;
            case SCREEN_RECOVERY_RESET:
                return R.layout.card_recovery_reset;
            case SCREEN_RECOVERY_SUCCESS:
                return R.layout.card_recovery_success;
            default:
                return R.layout.card_login;
        }
    }

    private void bindScreen(int screen, View card) {
        switch (screen) {
            case SCREEN_SIGN_UP:
                bindSignUpStep1(card);
                break;
            case SCREEN_SIGN_UP_2:
                bindSignUpStep2(card);
                break;
            case SCREEN_SIGN_UP_3:
                bindSignUpStep3(card);
                break;
            case SCREEN_SIGN_UP_OTP:
                bindSignUpStepOtp(card);
                break;
            case SCREEN_RECOVERY:
                bindRecoveryCard(card);
                break;
            case SCREEN_RECOVERY_OTP:
                bindRecoveryOtpCard(card);
                break;
            case SCREEN_RECOVERY_RESET:
                bindRecoveryResetCard(card);
                break;
            case SCREEN_RECOVERY_SUCCESS:
                bindRecoverySuccessCard(card);
                break;
            default:
                bindLoginCard(card);
                break;
        }
    }

    private void bindLoginCard(View card) {
        EditText username = card.findViewById(R.id.usernameField);
        EditText password = card.findViewById(R.id.passwordField);
        TextView usernameError = card.findViewById(R.id.usernameError);
        TextView passwordError = card.findViewById(R.id.passwordError);
        Button loginButton = card.findViewById(R.id.loginButton);
        ImageView toggle = card.findViewById(R.id.passwordToggle);
        AuthUiUtils.attachPasswordToggle(password, toggle);

        card.findViewById(R.id.forgotPassword).setOnClickListener(v ->
                showScreen(SCREEN_RECOVERY, true));
        card.findViewById(R.id.goToSignUp).setOnClickListener(v ->
                showScreen(SCREEN_SIGN_UP, true));

        // Editing a field clears its stale error.
        AuthUiUtils.afterTextChanged(username, () -> {
            usernameError.setVisibility(View.GONE);
            updateLoginButton(card);
        });
        AuthUiUtils.afterTextChanged(password, () -> {
            passwordError.setVisibility(View.GONE);
            updateLoginButton(card);
        });

        // "Login" on the password field's keyboard behaves like the button.
        password.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_NULL) {
                attemptLogin(card);
                return true;
            }
            return false;
        });

        loginButton.setOnClickListener(v -> attemptLogin(card));
        updateLoginButton(card);
    }

    /** Enables the login button only while a request is not already in flight. */
    private void updateLoginButton(View card) {
        Button loginButton = card.findViewById(R.id.loginButton);
        loginButton.setEnabled(!loginInProgress);
    }

    /**
     * Validates both fields, surfacing inline errors, then runs the login
     * request with all inputs locked.
     */
    private void attemptLogin(View card) {
        if (loginInProgress) {
            return;
        }
        EditText username = card.findViewById(R.id.usernameField);
        EditText password = card.findViewById(R.id.passwordField);
        TextView usernameError = card.findViewById(R.id.usernameError);
        TextView passwordError = card.findViewById(R.id.passwordError);

        String user = username.getText().toString().trim();
        String pass = password.getText().toString();

        boolean valid = true;

        // Username or Email validation
        if (user.isEmpty()) {
            showError(usernameError, R.string.error_username_email_empty);
            valid = false;
        } else if (user.contains("@") && !Patterns.EMAIL_ADDRESS.matcher(user).matches()) {
            showError(usernameError, R.string.error_email_invalid);
            valid = false;
        } else {
            usernameError.setVisibility(View.GONE);
        }

        // Password validation. TEMP: minimum-length check disabled so the
        // short test password works; restore before hooking up the real backend.
        if (pass.isEmpty()) {
            showError(passwordError, R.string.error_password_empty);
            valid = false;
        } else {
            passwordError.setVisibility(View.GONE);
        }

        if (!valid) {
            return;
        }

        setLoginLoading(card, true);
        hideKeyboard();

        AuthService.login(this, user, pass, result -> {
            setLoginLoading(card, false);
            switch (result) {
                case AuthService.RESULT_SUCCESS:
                    Toast.makeText(this, "Login successful.", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(MainActivity.this, DashboardActivity.class);
                    startActivity(intent);
                    overridePendingTransition(R.anim.fade_enter, R.anim.fade_exit);
                    finish();
                    break;
                case AuthService.RESULT_ACCOUNT_NOT_FOUND:
                    password.setText("");
                    showError(usernameError, R.string.error_account_not_found);
                    break;
                case AuthService.RESULT_NETWORK_ERROR:
                    Toast.makeText(this, R.string.error_network, Toast.LENGTH_SHORT).show();
                    break;
                default:
                    password.setText("");
                    showError(passwordError, R.string.error_password_incorrect);
                    break;
            }
        });
    }

    private void setLoginLoading(View card, boolean loading) {
        loginInProgress = loading;

        card.findViewById(R.id.usernameField).setEnabled(!loading);
        card.findViewById(R.id.passwordField).setEnabled(!loading);
        card.findViewById(R.id.passwordToggle).setEnabled(!loading);
        card.findViewById(R.id.forgotPassword).setEnabled(!loading);
        card.findViewById(R.id.goToSignUp).setEnabled(!loading);

        Button loginButton = card.findViewById(R.id.loginButton);
        loginButton.setText(loading ? "" : getString(R.string.log_in));
        card.findViewById(R.id.loginProgress)
                .setVisibility(loading ? View.VISIBLE : View.GONE);

        updateLoginButton(card);
    }

    private void showError(TextView errorView, int messageRes) {
        errorView.setText(messageRes);
        errorView.setVisibility(View.VISIBLE);
    }

    private void showError(TextView errorView, String message) {
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (focus != null && imm != null) {
            imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    /** Fills a spinner with a "Select â€¦" prompt at position 0 plus the options. */
    private void setUpPromptedSpinner(Spinner spinner, int promptRes, int optionsRes, int selection) {
        List<String> options = new ArrayList<>();
        options.add(getString(promptRes));
        options.addAll(Arrays.asList(getResources().getStringArray(optionsRes)));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selection);
    }

    private void bindSignUpStep1(View card) {
        SignUpState s = signUpState;
        ((EditText) card.findViewById(R.id.firstNameField)).setText(s.firstName);
        ((EditText) card.findViewById(R.id.middleNameField)).setText(s.middleName);
        ((EditText) card.findViewById(R.id.lastNameField)).setText(s.lastName);
        ((EditText) card.findViewById(R.id.provinceField)).setText(s.province);
        ((EditText) card.findViewById(R.id.cityField)).setText(s.city);
        ((EditText) card.findViewById(R.id.barangayField)).setText(s.barangay);

        setUpPromptedSpinner(card.findViewById(R.id.genderSpinner),
                R.string.gender_prompt, R.array.gender_options, s.genderPos);

        EditText birthDate = card.findViewById(R.id.birthDateField);
        birthDate.setText(s.birthDate);
        birthDate.setOnClickListener(v -> showDatePicker(birthDate));

        card.findViewById(R.id.signUp1BackButton).setOnClickListener(v -> {
            saveSignUpStep1(card);
            showScreen(SCREEN_LOGIN, true);
        });
        card.findViewById(R.id.goToLogin).setOnClickListener(v -> {
            saveSignUpStep1(card);
            showScreen(SCREEN_LOGIN, true);
        });
        card.findViewById(R.id.signUp1ProceedButton).setOnClickListener(v -> {
            saveSignUpStep1(card);
            if (validateSignUpStep1(card)) {
                showScreen(SCREEN_SIGN_UP_2, true);
            }
        });
    }

    private void saveSignUpStep1(View card) {
        SignUpState s = signUpState;
        s.firstName = fieldText(card, R.id.firstNameField);
        s.middleName = fieldText(card, R.id.middleNameField);
        s.lastName = fieldText(card, R.id.lastNameField);
        s.genderPos = ((Spinner) card.findViewById(R.id.genderSpinner)).getSelectedItemPosition();
        s.birthDate = fieldText(card, R.id.birthDateField);
        s.province = fieldText(card, R.id.provinceField);
        s.city = fieldText(card, R.id.cityField);
        s.barangay = fieldText(card, R.id.barangayField);
    }

    private boolean validateSignUpStep1(View card) {
        SignUpState s = signUpState;
        boolean valid = true;

        valid &= checkField(card, R.id.firstNameError,
                s.firstName.matches("[a-zA-Z ]+"), R.string.error_name_invalid);
        valid &= checkField(card, R.id.middleNameError,
                s.middleName.isEmpty() || s.middleName.matches("[a-zA-Z ]+"),
                R.string.error_name_invalid);
        valid &= checkField(card, R.id.lastNameError,
                s.lastName.matches("[a-zA-Z ]+"), R.string.error_name_invalid);
        valid &= checkField(card, R.id.genderError,
                s.genderPos > 0, R.string.error_gender_required);

        if (s.birthDate.isEmpty()) {
            valid &= checkField(card, R.id.birthDateError, false, R.string.error_birth_date_required);
        } else {
            int year = Integer.parseInt(s.birthDate.split("-")[0]);
            valid &= checkField(card, R.id.birthDateError,
                    Calendar.getInstance().get(Calendar.YEAR) - year >= 18,
                    R.string.error_age_invalid);
        }

        valid &= checkField(card, R.id.provinceError,
                !s.province.isEmpty(), R.string.error_province_required);
        valid &= checkField(card, R.id.cityError,
                !s.city.isEmpty(), R.string.error_city_required);
        valid &= checkField(card, R.id.barangayError,
                !s.barangay.isEmpty(), R.string.error_barangay_required);

        return valid;
    }

    private void bindSignUpStep2(View card) {
        SignUpState s = signUpState;
        EditText password = card.findViewById(R.id.passwordField);
        TextView strengthText = card.findViewById(R.id.passwordStrengthText);

        ((EditText) card.findViewById(R.id.usernameField)).setText(s.username);
        ((EditText) card.findViewById(R.id.emailField)).setText(s.email);
        password.setText(s.password);
        ((EditText) card.findViewById(R.id.confirmPasswordField)).setText(s.confirmPassword);

        AuthUiUtils.attachPasswordToggle(password, card.findViewById(R.id.passwordToggle));
        AuthUiUtils.afterTextChanged(password, () ->
                AuthUiUtils.updatePasswordStrength(password.getText().toString(), strengthText));

        card.findViewById(R.id.signUp2BackButton).setOnClickListener(v -> {
            saveSignUpStep2(card);
            showScreen(SCREEN_SIGN_UP, true);
        });
        card.findViewById(R.id.signUp2ProceedButton).setOnClickListener(v -> {
            saveSignUpStep2(card);
            if (validateSignUpStep2(card)) {
                showScreen(SCREEN_SIGN_UP_3, true);
            }
        });
    }

    private void saveSignUpStep2(View card) {
        SignUpState s = signUpState;
        s.username = fieldText(card, R.id.usernameField);
        s.email = fieldText(card, R.id.emailField);
        s.password = ((EditText) card.findViewById(R.id.passwordField)).getText().toString();
        s.confirmPassword =
                ((EditText) card.findViewById(R.id.confirmPasswordField)).getText().toString();
    }

    private boolean validateSignUpStep2(View card) {
        SignUpState s = signUpState;
        boolean valid = true;

        valid &= checkField(card, R.id.usernameError,
                s.username.length() >= 4 && s.username.length() <= 20
                        && s.username.matches("[a-zA-Z0-9_]+"),
                R.string.error_username_invalid);
        valid &= checkField(card, R.id.emailError,
                Patterns.EMAIL_ADDRESS.matcher(s.email).matches(), R.string.error_email_invalid);
        valid &= checkField(card, R.id.passwordError,
                s.password.matches("^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=!])(?=\\S+$).{8,}$"),
                R.string.error_password_complexity);
        valid &= checkField(card, R.id.confirmPasswordError,
                s.confirmPassword.equals(s.password), R.string.error_passwords_dont_match);

        return valid;
    }

    private void bindSignUpStep3(View card) {
        SignUpState s = signUpState;
        CheckBox terms = card.findViewById(R.id.termsCheckbox);
        Button createButton = card.findViewById(R.id.createAccountButton);

        setUpPromptedSpinner(card.findViewById(R.id.idTypeSpinner),
                R.string.id_type_prompt, R.array.id_type_options, s.idTypePos);

        // Restore previews picked earlier (gallery or camera).
        if (s.govIdBitmap != null) {
            ImageView preview = card.findViewById(R.id.govIdPreview);
            preview.setImageBitmap(s.govIdBitmap);
            preview.setVisibility(View.VISIBLE);
        }
        if (s.profileBitmap != null) {
            ((ImageView) card.findViewById(R.id.profilePreview)).setImageBitmap(s.profileBitmap);
        }

        terms.setChecked(s.termsAccepted);
        createButton.setEnabled(s.termsAccepted);
        terms.setOnCheckedChangeListener((buttonView, isChecked) -> {
            s.termsAccepted = isChecked;
            createButton.setEnabled(isChecked);
            if (isChecked) {
                card.findViewById(R.id.termsError).setVisibility(View.GONE);
            }
        });

        card.findViewById(R.id.uploadGovIdButton).setOnClickListener(v -> pickImage(PICK_IMAGE_GOV_ID));
        card.findViewById(R.id.captureGovIdButton).setOnClickListener(v -> captureGovId());
        card.findViewById(R.id.uploadProfileButton).setOnClickListener(v -> pickImage(PICK_IMAGE_PROFILE));

        card.findViewById(R.id.signUp3BackButton).setOnClickListener(v -> {
            saveSignUpStep3(card);
            showScreen(SCREEN_SIGN_UP_2, true);
        });
        createButton.setOnClickListener(v -> attemptCreateAccount(card));
    }

    private void saveSignUpStep3(View card) {
        signUpState.idTypePos =
                ((Spinner) card.findViewById(R.id.idTypeSpinner)).getSelectedItemPosition();
    }

    private void attemptCreateAccount(View card) {
        SignUpState s = signUpState;
        saveSignUpStep3(card);

        boolean valid = true;
        valid &= checkField(card, R.id.idTypeError,
                s.idTypePos > 0, R.string.error_id_type_required);
        valid &= checkField(card, R.id.govIdError,
                s.govIdUri != null || s.govIdBitmap != null, R.string.error_id_required);
        valid &= checkField(card, R.id.termsError,
                s.termsAccepted, R.string.error_terms_required);

        if (!valid) {
            return;
        }

        // Remember the chosen picture locally so the dashboard avatar can show it
        // even before the server round-trip finishes.
        ProfileStore.saveAvatarUri(this, s.profileUri);

        setCreateAccountLoading(card, true);

        RequestBody profileImagePart = imagePart(s.profileUri, s.profileBitmap);
        RequestBody validIdPart = imagePart(s.govIdUri, s.govIdBitmap);

        ApiClient.authApi().register(
                textPart(s.username),
                textPart(s.email),
                textPart(s.password),
                textPart(s.confirmPassword),
                textPart(s.firstName),
                optionalTextPart(s.middleName),
                textPart(s.lastName),
                optionalTextPart(genderApiValue(s.genderPos)),
                optionalTextPart(s.birthDate),
                null,
                optionalTextPart(buildAddress(s)),
                toMultipart("profile_image", "profile.jpg", profileImagePart),
                toMultipart("validID", "valid_id.jpg", validIdPart)
        ).enqueue(new Callback<RegisterResponse>() {
            @Override
            public void onResponse(Call<RegisterResponse> call, Response<RegisterResponse> response) {
                setCreateAccountLoading(card, false);
                if (response.isSuccessful() && response.body() != null) {
                    s.registrationToken = response.body().registration_token;
                    Toast.makeText(MainActivity.this, response.body().message, Toast.LENGTH_LONG).show();
                    showScreen(SCREEN_SIGN_UP_OTP, true);
                } else {
                    ErrorResponse error = ApiClient.parseError(response.errorBody());
                    String message = error.firstMessage() != null
                            ? error.firstMessage() : getString(R.string.error_registration_failed);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<RegisterResponse> call, Throwable t) {
                setCreateAccountLoading(card, false);
                Toast.makeText(MainActivity.this, R.string.error_network, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setCreateAccountLoading(View card, boolean loading) {
        card.findViewById(R.id.signUp3BackButton).setEnabled(!loading);
        card.findViewById(R.id.termsCheckbox).setEnabled(!loading);

        Button createButton = card.findViewById(R.id.createAccountButton);
        createButton.setText(loading ? "" : getString(R.string.create_account));
        createButton.setEnabled(!loading && signUpState.termsAccepted);
        card.findViewById(R.id.createAccountProgress)
                .setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    /** "Barangay, City, Province" — the website stores this as one free-text address field. */
    private static String buildAddress(SignUpState s) {
        StringBuilder address = new StringBuilder();
        for (String part : new String[]{s.barangay, s.city, s.province}) {
            if (part != null && !part.isEmpty()) {
                if (address.length() > 0) {
                    address.append(", ");
                }
                address.append(part);
            }
        }
        return address.toString();
    }

    /** Maps the sign-up spinner position to the website's gender enum (Male/Female/Other). */
    private static String genderApiValue(int genderPos) {
        switch (genderPos) {
            case 1:
                return "Male";
            case 2:
                return "Female";
            case 3:
                return "Other";
            default:
                return null;
        }
    }

    private static RequestBody textPart(String value) {
        return RequestBody.create(value != null ? value : "", MediaType.parse("text/plain"));
    }

    /** Null (rather than an empty string) so Retrofit omits the part entirely, matching the backend's "nullable" fields. */
    private static RequestBody optionalTextPart(String value) {
        return (value == null || value.isEmpty()) ? null : textPart(value);
    }

    /** Reads the picked gallery image or captured camera preview into an upload-ready request body. */
    private RequestBody imagePart(Uri uri, Bitmap bitmap) {
        try {
            if (uri != null) {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        return null;
                    }
                    byte[] bytes = readAllBytes(in);
                    String mime = getContentResolver().getType(uri);
                    return RequestBody.create(bytes, MediaType.parse(mime != null ? mime : "image/jpeg"));
                }
            } else if (bitmap != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
                return RequestBody.create(out.toByteArray(), MediaType.parse("image/jpeg"));
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private static MultipartBody.Part toMultipart(String fieldName, String filename, RequestBody body) {
        return body != null ? MultipartBody.Part.createFormData(fieldName, filename, body) : null;
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private void bindSignUpStepOtp(View card) {
        EditText otpField = card.findViewById(R.id.otpField);
        TextView otpError = card.findViewById(R.id.otpError);

        AuthUiUtils.afterTextChanged(otpField, () -> otpError.setVisibility(View.GONE));

        card.findViewById(R.id.verifyOtpButton).setOnClickListener(v -> attemptVerifyOtp(card));
        card.findViewById(R.id.resendOtpButton).setOnClickListener(v -> attemptResendOtp(card));
    }

    private void attemptVerifyOtp(View card) {
        EditText otpField = card.findViewById(R.id.otpField);
        TextView otpError = card.findViewById(R.id.otpError);
        String otp = otpField.getText().toString().trim();

        if (otp.length() != 6) {
            showError(otpError, R.string.error_otp_required);
            return;
        }

        setVerifyOtpLoading(card, true);
        ApiClient.authApi().verifyOtp(signUpState.registrationToken, otp)
                .enqueue(new Callback<VerifyResponse>() {
                    @Override
                    public void onResponse(Call<VerifyResponse> call, Response<VerifyResponse> response) {
                        setVerifyOtpLoading(card, false);
                        if (response.isSuccessful()) {
                            Toast.makeText(MainActivity.this, R.string.account_created_message,
                                    Toast.LENGTH_LONG).show();
                            signUpState = new SignUpState();
                            showScreen(SCREEN_LOGIN, true);
                        } else {
                            ErrorResponse error = ApiClient.parseError(response.errorBody());
                            showError(otpError, error.firstMessage() != null
                                    ? error.firstMessage() : getString(R.string.error_registration_failed));
                        }
                    }

                    @Override
                    public void onFailure(Call<VerifyResponse> call, Throwable t) {
                        setVerifyOtpLoading(card, false);
                        Toast.makeText(MainActivity.this, R.string.error_network, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void attemptResendOtp(View card) {
        ApiClient.authApi().resendOtp(signUpState.registrationToken)
                .enqueue(new Callback<MessageResponse>() {
                    @Override
                    public void onResponse(Call<MessageResponse> call, Response<MessageResponse> response) {
                        if (response.isSuccessful()) {
                            Toast.makeText(MainActivity.this, R.string.resend_code_sent, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, R.string.error_registration_failed, Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<MessageResponse> call, Throwable t) {
                        Toast.makeText(MainActivity.this, R.string.error_network, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void setVerifyOtpLoading(View card, boolean loading) {
        card.findViewById(R.id.otpField).setEnabled(!loading);
        card.findViewById(R.id.resendOtpButton).setEnabled(!loading);

        Button verifyButton = card.findViewById(R.id.verifyOtpButton);
        verifyButton.setText(loading ? "" : getString(R.string.button_verify));
        verifyButton.setEnabled(!loading);
        card.findViewById(R.id.verifyOtpProgress).setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private String fieldText(View card, int fieldId) {
        return ((EditText) card.findViewById(fieldId)).getText().toString().trim();
    }

    /** Shows or hides one inline error; returns whether the check passed. */
    private boolean checkField(View card, int errorId, boolean ok, int messageRes) {
        TextView errorView = card.findViewById(errorId);
        if (ok) {
            errorView.setVisibility(View.GONE);
        } else {
            showError(errorView, messageRes);
        }
        return ok;
    }

    private void showDatePicker(EditText birthDateField) {
        long current = -1;
        try {
            if (!birthDateField.getText().toString().isEmpty()) {
                Calendar c = Calendar.getInstance();
                String[] parts = birthDateField.getText().toString().split("-");
                c.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
                current = c.getTimeInMillis();
            }
        } catch (Exception ignored) {}

        GlassDatePicker.showBirthdatePicker(this, current, millis -> {
            String date = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date(millis));
            birthDateField.setText(date);
            currentContent.findViewById(R.id.birthDateError).setVisibility(View.GONE);
        });
    }

    private void pickImage(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, requestCode);
    }

    /** Opens the camera for the valid-ID photo; the capture returns a preview bitmap. */
    private void captureGovId() {
        try {
            startActivityForResult(new Intent(MediaStore.ACTION_IMAGE_CAPTURE), CAPTURE_GOV_ID);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.error_no_camera, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }

        if (requestCode == CAPTURE_GOV_ID) {
            Bitmap shot = data.getExtras() != null
                    ? (Bitmap) data.getExtras().get("data") : null;
            if (shot != null) {
                signUpState.govIdBitmap = shot;
                signUpState.govIdUri = null;
                showGovIdPreview(shot);
            }
            return;
        }

        if (data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
            if (requestCode == PICK_IMAGE_PROFILE) {
                signUpState.profileUri = uri;
                signUpState.profileBitmap = bitmap;
                ((ImageView) currentContent.findViewById(R.id.profilePreview)).setImageBitmap(bitmap);
            } else if (requestCode == PICK_IMAGE_GOV_ID) {
                signUpState.govIdUri = uri;
                signUpState.govIdBitmap = bitmap;
                showGovIdPreview(bitmap);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void showGovIdPreview(Bitmap bitmap) {
        ImageView preview = currentContent.findViewById(R.id.govIdPreview);
        preview.setImageBitmap(bitmap);
        preview.setVisibility(View.VISIBLE);
        currentContent.findViewById(R.id.govIdError).setVisibility(View.GONE);
    }

    private void bindRecoveryCard(View card) {
        EditText emailField = card.findViewById(R.id.emailField);
        emailField.setText(recoveryState.email);

        card.findViewById(R.id.backToLogin).setOnClickListener(v ->
                showScreen(SCREEN_LOGIN, true));

        card.findViewById(R.id.sendOtpButton).setOnClickListener(v -> {
            String email = emailField.getText().toString().trim();
            if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(this, R.string.error_email_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            recoveryState.email = email;
            // Mock API call success
            Toast.makeText(this, "OTP sent to " + email, Toast.LENGTH_SHORT).show();
            showScreen(SCREEN_RECOVERY_OTP, true);
        });
    }

    private void bindRecoveryOtpCard(View card) {
        EditText[] boxes = {
                card.findViewById(R.id.otp1), card.findViewById(R.id.otp2),
                card.findViewById(R.id.otp3), card.findViewById(R.id.otp4),
                card.findViewById(R.id.otp5), card.findViewById(R.id.otp6)
        };
        TextView otpError = card.findViewById(R.id.otpError);

        AuthUiUtils.setupOtpBoxes(boxes, () -> otpError.setVisibility(View.GONE));

        card.findViewById(R.id.backToLogin).setOnClickListener(v ->
                showScreen(SCREEN_LOGIN, true));

        card.findViewById(R.id.verifyOtpButton).setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            for (EditText box : boxes) sb.append(box.getText().toString().trim());
            String otp = sb.toString();

            if (otp.length() != 6) {
                otpError.setText(R.string.error_otp_required);
                otpError.setVisibility(View.VISIBLE);
                return;
            }
            recoveryState.otp = otp;
            // Mock verification success
            showScreen(SCREEN_RECOVERY_RESET, true);
        });

        card.findViewById(R.id.resendOtpButton).setOnClickListener(v ->
                Toast.makeText(this, R.string.resend_code_sent, Toast.LENGTH_SHORT).show());
    }

    private void bindRecoveryResetCard(View card) {
        EditText newPass = card.findViewById(R.id.newPasswordField);
        EditText confirmPass = card.findViewById(R.id.confirmPasswordField);
        TextView error = card.findViewById(R.id.resetError);

        AuthUiUtils.attachPasswordToggle(newPass, card.findViewById(R.id.newPasswordToggle));
        AuthUiUtils.attachPasswordToggle(confirmPass, card.findViewById(R.id.confirmPasswordToggle));

        card.findViewById(R.id.backToLogin).setOnClickListener(v ->
                showScreen(SCREEN_LOGIN, true));

        card.findViewById(R.id.resetPasswordButton).setOnClickListener(v -> {
            String p1 = newPass.getText().toString();
            String p2 = confirmPass.getText().toString();

            if (p1.length() < 8) {
                error.setText(R.string.error_password_too_short);
                error.setVisibility(View.VISIBLE);
                return;
            }
            if (!p1.equals(p2)) {
                error.setText(R.string.error_passwords_dont_match);
                error.setVisibility(View.VISIBLE);
                return;
            }

            // Mock password reset success
            showScreen(SCREEN_RECOVERY_SUCCESS, true);
        });

        AuthUiUtils.afterTextChanged(newPass, () -> error.setVisibility(View.GONE));
        AuthUiUtils.afterTextChanged(confirmPass, () -> error.setVisibility(View.GONE));
    }

    private void bindRecoverySuccessCard(View card) {
        new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (currentScreen == SCREEN_RECOVERY_SUCCESS) {
                showScreen(SCREEN_LOGIN, true);
            }
        }, 2000);
    }

}