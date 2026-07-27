package com.aguafriogarden.resortinc;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Small helpers shared across the authentication screens.
 */
final class AuthUiUtils {

    private AuthUiUtils() {
    }

    /**
     * Sizes the scroll spacer so the card starts anchored just below the fixed
     * header, overlapping its bottom edge by {@code overlapDp}. The whole card
     * then scrolls as one unit over the stationary header.
     */
    static void anchorCardToHeader(final View header, final View spacer, final int overlapDp) {
        header.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        int headerHeight = header.getHeight();
                        if (headerHeight <= 0) {
                            return;
                        }
                        float density = header.getResources().getDisplayMetrics().density;
                        int overlapPx = Math.round(overlapDp * density);
                        int target = Math.max(0, headerHeight - overlapPx);

                        ViewGroup.LayoutParams lp = spacer.getLayoutParams();
                        if (lp.height != target) {
                            lp.height = target;
                            spacer.setLayoutParams(lp);
                        }
                        header.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                });
    }

    /** Runs {@code onChange} after every edit to {@code field}. */
    static void afterTextChanged(EditText field, final Runnable onChange) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onChange.run();
            }
        });
    }
    /**SIGN UP INTERFACE SHOW/HIDE PASSWORD USING EYE SYMBOL*/
    /**
     * Wires an eye {@link ImageView} to toggle the visibility of a password
     * {@link EditText}, keeping the caret at the end of the text.
     */
    static void attachPasswordToggle(final EditText passwordField, final ImageView toggle) {
        toggle.setOnClickListener(v -> {
            int inputType = passwordField.getInputType();
            boolean isPassword = (inputType & InputType.TYPE_MASK_VARIATION)
                    == InputType.TYPE_TEXT_VARIATION_PASSWORD;

            Typeface typeface = passwordField.getTypeface();

            if (isPassword) {
                passwordField.setInputType(
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                toggle.setImageResource(R.drawable.ic_eye_off);
            } else {
                passwordField.setInputType(
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                toggle.setImageResource(R.drawable.ic_eye);
            }

            passwordField.setTypeface(typeface);
            passwordField.setSelection(passwordField.getText().length());
        });
    }

    /**SIGN UP INTERFACE PASSWORD VALIDATIONS**/
    /**
     * Evaluates password strength and updates a label with text and color.
     */
    static void updatePasswordStrength(String password, TextView strengthLabel) {
        if (password.isEmpty()) {
            strengthLabel.setVisibility(View.GONE);
            return;
        }
        strengthLabel.setVisibility(View.VISIBLE);

        int score = 0;
        if (password.length() >= 8) score++;
        if (password.matches(".*[A-Z].*")) score++;
        if (password.matches(".*[a-z].*")) score++;
        if (password.matches(".*[0-9].*")) score++;
        if (password.matches(".*[^A-Za-z0-9].*")) score++;



        if (score <= 2) {
            strengthLabel.setText(R.string.strength_weak);
            strengthLabel.setTextColor(Color.parseColor("#C62828")); // Red
        } else if (score == 3) {
            strengthLabel.setText(R.string.strength_fair);
            strengthLabel.setTextColor(Color.parseColor("#E0912E")); // Orange
        } else if (score == 4) {
            strengthLabel.setText(R.string.strength_good);
            strengthLabel.setTextColor(Color.parseColor("#35619F")); // Blue
        } else {
            strengthLabel.setText(R.string.strength_strong);
            strengthLabel.setTextColor(Color.parseColor("#2E7D32")); // Green
        }
    }

    /**
     * Wires multiple OTP boxes to auto-focus forward and backward.
     */
    static void setupOtpBoxes(final EditText[] boxes, final Runnable onChange) {
        for (int i = 0; i < boxes.length; i++) {
            final int index = i;
            boxes[i].addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (s.length() == 1 && index < boxes.length - 1) {
                        boxes[index + 1].requestFocus();
                    }
                    onChange.run();
                }
            });

            boxes[i].setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == android.view.KeyEvent.KEYCODE_DEL
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                        && boxes[index].getText().length() == 0
                        && index > 0) {
                    boxes[index - 1].requestFocus();
                    boxes[index - 1].setText("");
                    return true;
                }
                return false;
            });
        }
    }
}
