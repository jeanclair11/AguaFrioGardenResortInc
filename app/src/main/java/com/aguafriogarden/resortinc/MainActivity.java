package com.aguafriogarden.resortinc;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import java.io.IOException;
import java.util.Calendar;
import java.util.Locale;

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

    private static final long CARD_ANIM_DURATION_MS = 220L;
    private static final float CONTENT_SLIDE_DP = 40f;

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final long FAKE_LOGIN_DELAY_MS = 1500L;

    private static final int PICK_IMAGE_PROFILE = 1001;
    private static final int PICK_IMAGE_GOV_ID = 1002;

    private static final int LOGIN_SUCCESS = 0;
    private static final int LOGIN_ACCOUNT_NOT_FOUND = 1;
    private static final int LOGIN_WRONG_PASSWORD = 2;

    // Stand-in credentials until a real backend is wired up; see fakeAuthenticate().
    private static final String DEMO_USERNAME = "ace";
    private static final String DEMO_EMAIL = "acedelgado@gmail.com";
    private static final String DEMO_PASSWORD = "&Ace091104";

    private ScrollView scroll;
    private FrameLayout card;
    private View currentContent;
    private ValueAnimator cardHeightAnimator;
    private int currentScreen = -1;
    private boolean loginInProgress;

    // Temporary storage for preview images
    private Uri profileImageUri;
    private Uri govIdImageUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Keep the header fixed; anchor the card to overlap its bottom by 50dp.
        AuthUiUtils.anchorCardToHeader(
                findViewById(R.id.header), findViewById(R.id.headerSpacer), 50);

        scroll = findViewById(R.id.scroll);
        card = findViewById(R.id.card);

        showScreen(SCREEN_LOGIN, false);
    }

    @Override
    public void onBackPressed() {
        if (currentScreen != SCREEN_LOGIN) {
            showScreen(SCREEN_LOGIN, true);
        } else {
            super.onBackPressed();
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

        // Only the contents move: leaving the login screen slides forward
        // (left); returning slides back. Children are clipped to the card.
        float slidePx = CONTENT_SLIDE_DP * getResources().getDisplayMetrics().density;
        float direction = screen == SCREEN_LOGIN ? -1f : 1f;

        oldContent.animate()
                .alpha(0f)
                .translationX(-direction * slidePx)
                .setDuration(CARD_ANIM_DURATION_MS)
                .withEndAction(() -> card.removeView(oldContent))
                .start();

        newContent.setAlpha(0f);
        newContent.setTranslationX(direction * slidePx);
        newContent.animate()
                .alpha(1f)
                .translationX(0f)
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
                return R.layout.card_sign_up;
            case SCREEN_RECOVERY:
                return R.layout.card_recovery;
            default:
                return R.layout.card_login;
        }
    }

    private void bindScreen(int screen, View card) {
        switch (screen) {
            case SCREEN_SIGN_UP:
                bindSignUpCard(card);
                break;
            case SCREEN_RECOVERY:
                bindRecoveryCard(card);
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

        // Password validation
        if (pass.isEmpty()) {
            showError(passwordError, R.string.error_password_empty);
            valid = false;
        } else if (pass.length() < MIN_PASSWORD_LENGTH) {
            showError(passwordError, R.string.error_password_too_short);
            valid = false;
        } else {
            passwordError.setVisibility(View.GONE);
        }

        if (!valid) {
            return;
        }

        setLoginLoading(card, true);
        hideKeyboard();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            int result = fakeAuthenticate(user, pass);
            setLoginLoading(card, false);
            switch (result) {
                case LOGIN_SUCCESS:
                    Toast.makeText(this, "Login successful.", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(MainActivity.this, DashboardActivity.class);
                    startActivity(intent);
                    finish();
                    break;
                case LOGIN_ACCOUNT_NOT_FOUND:
                    password.setText("");
                    showError(usernameError, R.string.error_account_not_found);
                    break;
                default:
                    password.setText("");
                    showError(passwordError, R.string.error_password_incorrect);
                    break;
            }
        }, FAKE_LOGIN_DELAY_MS);
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

    private int fakeAuthenticate(String usernameOrEmail, String password) {
        if (!usernameOrEmail.equalsIgnoreCase(DEMO_USERNAME)
                && !usernameOrEmail.equalsIgnoreCase(DEMO_EMAIL)) {
            return LOGIN_ACCOUNT_NOT_FOUND;
        }
        return DEMO_PASSWORD.equals(password) ? LOGIN_SUCCESS : LOGIN_WRONG_PASSWORD;
    }

    private void showError(TextView errorView, int messageRes) {
        errorView.setText(messageRes);
        errorView.setVisibility(View.VISIBLE);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (focus != null && imm != null) {
            imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    private void bindSignUpCard(View card) {
        EditText firstName = card.findViewById(R.id.firstNameField);
        EditText middleName = card.findViewById(R.id.middleNameField);
        EditText lastName = card.findViewById(R.id.lastNameField);
        EditText username = card.findViewById(R.id.usernameField);
        EditText email = card.findViewById(R.id.emailField);
        EditText phone = card.findViewById(R.id.phoneField);
        Spinner gender = card.findViewById(R.id.genderSpinner);
        EditText birthDate = card.findViewById(R.id.birthDateField);
        EditText password = card.findViewById(R.id.passwordField);
        EditText confirmPassword = card.findViewById(R.id.confirmPasswordField);
        CheckBox terms = card.findViewById(R.id.termsCheckbox);
        Button createButton = card.findViewById(R.id.createAccountButton);
        TextView strengthText = card.findViewById(R.id.passwordStrengthText);

        // Setup Gender Spinner
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.gender_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        gender.setAdapter(adapter);

        // Password Toggle
        AuthUiUtils.attachPasswordToggle(password, card.findViewById(R.id.passwordToggle));

        // Birth Date Picker
        birthDate.setOnClickListener(v -> showDatePicker(birthDate));

        // Password Strength Indicator
        AuthUiUtils.afterTextChanged(password, () -> 
                AuthUiUtils.updatePasswordStrength(password.getText().toString(), strengthText));

        // Terms and Conditions behavior
        terms.setOnCheckedChangeListener((buttonView, isChecked) -> {
            createButton.setEnabled(isChecked);
            if (isChecked) {
                card.findViewById(R.id.termsError).setVisibility(View.GONE);
            }
        });

        // Image Uploads (Mocks)
        card.findViewById(R.id.uploadProfileButton).setOnClickListener(v -> pickImage(PICK_IMAGE_PROFILE));
        card.findViewById(R.id.uploadGovIdButton).setOnClickListener(v -> pickImage(PICK_IMAGE_GOV_ID));

        card.findViewById(R.id.goToLogin).setOnClickListener(v -> showScreen(SCREEN_LOGIN, true));

        createButton.setOnClickListener(v -> attemptSignUp(card));
    }

    private void showDatePicker(EditText birthDateField) {
        Calendar cal = Calendar.getInstance();
        DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, day) -> {
            String date = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day);
            birthDateField.setText(date);
            currentContent.findViewById(R.id.birthDateError).setVisibility(View.GONE);
        }, cal.get(Calendar.YEAR) - 18, cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        dialog.show();
    }

    private void pickImage(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
                if (requestCode == PICK_IMAGE_PROFILE) {
                    profileImageUri = uri;
                    ((ImageView) currentContent.findViewById(R.id.profilePreview)).setImageBitmap(bitmap);
                } else if (requestCode == PICK_IMAGE_GOV_ID) {
                    govIdImageUri = uri;
                    ImageView preview = currentContent.findViewById(R.id.govIdPreview);
                    preview.setImageBitmap(bitmap);
                    preview.setVisibility(View.VISIBLE);
                    currentContent.findViewById(R.id.govIdError).setVisibility(View.GONE);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void attemptSignUp(View card) {
        if (!((CheckBox) card.findViewById(R.id.termsCheckbox)).isChecked()) {
            showError(card.findViewById(R.id.termsError), R.string.error_terms_required);
            return;
        }

        EditText fName = card.findViewById(R.id.firstNameField);
        EditText mName = card.findViewById(R.id.middleNameField);
        EditText lName = card.findViewById(R.id.lastNameField);
        EditText userField = card.findViewById(R.id.usernameField);
        EditText emailField = card.findViewById(R.id.emailField);
        EditText phoneField = card.findViewById(R.id.phoneField);
        EditText birthField = card.findViewById(R.id.birthDateField);
        EditText passField = card.findViewById(R.id.passwordField);
        EditText confirmField = card.findViewById(R.id.confirmPasswordField);

        boolean valid = true;

        // Name validation
        if (!fName.getText().toString().matches("[a-zA-Z ]+")) {
            showError(card.findViewById(R.id.firstNameError), R.string.error_name_invalid);
            valid = false;
        }
        // Middle name is optional but if filled should match pattern
        String middle = mName.getText().toString();
        if (!middle.isEmpty() && !middle.matches("[a-zA-Z ]+")) {
            showError(card.findViewById(R.id.middleNameError), R.string.error_name_invalid);
            valid = false;
        }
        if (!lName.getText().toString().matches("[a-zA-Z ]+")) {
            showError(card.findViewById(R.id.lastNameError), R.string.error_name_invalid);
            valid = false;
        }

        // Username validation
        String uname = userField.getText().toString();
        if (uname.length() < 4 || uname.length() > 20 || !uname.matches("[a-zA-Z0-9_]+")) {
            showError(card.findViewById(R.id.usernameError), R.string.error_username_invalid);
            valid = false;
        }

        // Email validation
        if (!Patterns.EMAIL_ADDRESS.matcher(emailField.getText()).matches()) {
            showError(card.findViewById(R.id.emailError), R.string.error_email_invalid);
            valid = false;
        }

        // Phone validation
        String phone = phoneField.getText().toString();
        if (phone.length() != 11 || !phone.startsWith("09")) {
            showError(card.findViewById(R.id.phoneError), R.string.error_phone_invalid);
            valid = false;
        }

        // Birth date (Age 18+)
        String bday = birthField.getText().toString();
        if (bday.isEmpty()) {
            showError(card.findViewById(R.id.birthDateError), R.string.error_birth_date_required);
            valid = false;
        } else {
            // Basic age check
            String[] parts = bday.split("-");
            int year = Integer.parseInt(parts[0]);
            if (Calendar.getInstance().get(Calendar.YEAR) - year < 18) {
                showError(card.findViewById(R.id.birthDateError), R.string.error_age_invalid);
                valid = false;
            }
        }

        // Password complexity
        String pass = passField.getText().toString();
        String pattern = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[@#$%^&+=!])(?=\\S+$).{8,}$";
        if (!pass.matches(pattern)) {
            showError(card.findViewById(R.id.passwordError), R.string.error_password_complexity);
            valid = false;
        }

        // Confirm Password
        if (!confirmField.getText().toString().equals(pass)) {
            showError(card.findViewById(R.id.confirmPasswordError), R.string.error_passwords_dont_match);
            valid = false;
        }

        // Gov ID
        if (govIdImageUri == null) {
            showError(card.findViewById(R.id.govIdError), R.string.error_id_required);
            valid = false;
        }

        if (valid) {
            Toast.makeText(this, "Account created successfully!", Toast.LENGTH_LONG).show();
            showScreen(SCREEN_LOGIN, true);
        }
    }

    private void bindRecoveryCard(View card) {
        card.findViewById(R.id.backToLogin).setOnClickListener(v ->
                showScreen(SCREEN_LOGIN, true));
        card.findViewById(R.id.sendOtpButton).setOnClickListener(this::onSendOtp);
    }

    private void onSendOtp(View v) {
        // OTP delivery
    }
}
