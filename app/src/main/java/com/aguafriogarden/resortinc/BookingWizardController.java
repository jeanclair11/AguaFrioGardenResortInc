package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Drives the Reservation module's step-by-step booking wizard: booking
 * details, then a room-selection placeholder, then guest info and a special
 * request. DashboardActivity keeps one instance alive across tab switches
 * (only getRootView() is detached/reattached), so navigating to another
 * module and back preserves whatever the guest already entered.
 */
final class BookingWizardController {

    private static final int STEP_DETAILS = 1;
    private static final int STEP_ROOMS = 2;
    private static final int STEP_GUEST_INFO = 3;

    private static final long STEP_ANIM_DURATION_MS = 200L;
    // Steps swap with a quiet fade plus a barely-there vertical drift.
    private static final float STEP_SHIFT_DP = 8f;
    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

    private interface DateCallback {
        void onDatePicked(String date);
    }

    private final Activity activity;
    private final View root;
    private final TextView stepIndicator;
    private final FrameLayout stepFrame;

    private View currentStepView;

    // Step 1 answers
    private String checkInDate = "";
    private String checkOutDate = "";
    private int adults = 1;
    private int children = 0;

    // Step 3 answers
    private boolean bookingForSelf = true;
    private final List<View> childAgeRows = new ArrayList<>();

    BookingWizardController(Activity activity) {
        this.activity = activity;
        root = LayoutInflater.from(activity).inflate(R.layout.view_booking_wizard_root, null, false);
        stepIndicator = root.findViewById(R.id.wizardStepIndicator);
        stepFrame = root.findViewById(R.id.wizardStepFrame);
        showStep(STEP_DETAILS, 0);
    }

    View getRootView() {
        return root;
    }

    /** Swaps the step content; direction 0 skips the animation. */
    private void showStep(int step, int direction) {
        stepIndicator.setText(activity.getString(
                R.string.wizard_step_indicator, step, activity.getString(titleFor(step))));

        View newView = LayoutInflater.from(activity).inflate(layoutFor(step), stepFrame, false);
        bindStep(step, newView);

        View oldView = currentStepView;
        currentStepView = newView;

        if (direction == 0 || oldView == null) {
            stepFrame.removeAllViews();
            stepFrame.addView(newView);
            return;
        }

        float shiftPx = STEP_SHIFT_DP * activity.getResources().getDisplayMetrics().density;
        stepFrame.addView(newView);

        oldView.animate()
                .alpha(0f)
                .setDuration(STEP_ANIM_DURATION_MS)
                .withEndAction(() -> stepFrame.removeView(oldView))
                .start();

        newView.setAlpha(0f);
        newView.setTranslationY(shiftPx);
        newView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(STEP_ANIM_DURATION_MS)
                .start();
    }

    private int layoutFor(int step) {
        switch (step) {
            case STEP_ROOMS:
                return R.layout.view_booking_step2;
            case STEP_GUEST_INFO:
                return R.layout.view_booking_step3;
            default:
                return R.layout.view_booking_step1;
        }
    }

    private int titleFor(int step) {
        switch (step) {
            case STEP_ROOMS:
                return R.string.wizard_step2_title;
            case STEP_GUEST_INFO:
                return R.string.wizard_step3_title;
            default:
                return R.string.wizard_step1_title;
        }
    }

    private void bindStep(int step, View v) {
        switch (step) {
            case STEP_ROOMS:
                bindStep2(v);
                break;
            case STEP_GUEST_INFO:
                bindStep3(v);
                break;
            default:
                bindStep1(v);
                break;
        }
    }

    private void bindStep1(View v) {
        EditText checkIn = v.findViewById(R.id.checkInField);
        EditText checkOut = v.findViewById(R.id.checkOutField);
        TextView checkInError = v.findViewById(R.id.checkInError);
        TextView checkOutError = v.findViewById(R.id.checkOutError);
        TextView adultsValue = v.findViewById(R.id.adultsValue);
        TextView childrenValue = v.findViewById(R.id.childrenValue);

        checkIn.setText(checkInDate);
        checkOut.setText(checkOutDate);
        adultsValue.setText(String.valueOf(adults));
        childrenValue.setText(String.valueOf(children));

        checkIn.setOnClickListener(view -> showFutureDatePicker(System.currentTimeMillis() - 1000L, date -> {
            checkInDate = date;
            checkIn.setText(date);
            checkInError.setVisibility(View.GONE);
        }));
        checkOut.setOnClickListener(view -> {
            long min = checkInDate.isEmpty()
                    ? System.currentTimeMillis() - 1000L : parseDateMillis(checkInDate) + ONE_DAY_MS;
            showFutureDatePicker(min, date -> {
                checkOutDate = date;
                checkOut.setText(date);
                checkOutError.setVisibility(View.GONE);
            });
        });

        v.findViewById(R.id.adultsMinus).setOnClickListener(view -> {
            if (adults > 1) {
                adults--;
                adultsValue.setText(String.valueOf(adults));
            }
        });
        v.findViewById(R.id.adultsPlus).setOnClickListener(view -> {
            adults++;
            adultsValue.setText(String.valueOf(adults));
        });
        v.findViewById(R.id.childrenMinus).setOnClickListener(view -> {
            if (children > 0) {
                children--;
                childrenValue.setText(String.valueOf(children));
            }
        });
        v.findViewById(R.id.childrenPlus).setOnClickListener(view -> {
            children++;
            childrenValue.setText(String.valueOf(children));
        });

        v.findViewById(R.id.step1ProceedButton).setOnClickListener(view -> {
            boolean valid = true;
            if (checkInDate.isEmpty()) {
                showError(checkInError, R.string.error_check_in_required);
                valid = false;
            } else {
                checkInError.setVisibility(View.GONE);
            }
            if (checkOutDate.isEmpty()) {
                showError(checkOutError, R.string.error_check_out_required);
                valid = false;
            } else if (!checkInDate.isEmpty() && checkOutDate.compareTo(checkInDate) <= 0) {
                showError(checkOutError, R.string.error_check_out_before_checkin);
                valid = false;
            } else {
                checkOutError.setVisibility(View.GONE);
            }
            if (valid) {
                showStep(STEP_ROOMS, 1);
            }
        });
    }

    private void bindStep2(View v) {
        v.findViewById(R.id.step2BackButton).setOnClickListener(view -> showStep(STEP_DETAILS, -1));
        v.findViewById(R.id.step2ProceedButton).setOnClickListener(view -> showStep(STEP_GUEST_INFO, 1));
    }

    private void bindStep3(View v) {
        RadioGroup group = v.findViewById(R.id.bookingForGroup);
        View cardForMe = v.findViewById(R.id.cardForMe);
        View cardForSomeoneElse = v.findViewById(R.id.cardForSomeoneElse);
        View guestInfoSection = v.findViewById(R.id.guestInfoSection);
        View childrenInfoSection = v.findViewById(R.id.childrenInfoSection);
        LinearLayout childrenAgesContainer = v.findViewById(R.id.childrenAgesContainer);

        group.check(bookingForSelf ? R.id.radioForMe : R.id.radioForSomeoneElse);
        updateBookingForCards(cardForMe, cardForSomeoneElse, bookingForSelf);
        guestInfoSection.setVisibility(bookingForSelf ? View.GONE : View.VISIBLE);

        cardForMe.setOnClickListener(view -> group.check(R.id.radioForMe));
        cardForSomeoneElse.setOnClickListener(view -> group.check(R.id.radioForSomeoneElse));
        group.setOnCheckedChangeListener((g, checkedId) -> {
            bookingForSelf = checkedId == R.id.radioForMe;
            updateBookingForCards(cardForMe, cardForSomeoneElse, bookingForSelf);
            guestInfoSection.setVisibility(bookingForSelf ? View.GONE : View.VISIBLE);
        });

        // "Select ..." occupies position 0 so a real choice can be required.
        Spinner genderSpinner = v.findViewById(R.id.guestGenderSpinner);
        List<String> genderOptions = new ArrayList<>();
        genderOptions.add(activity.getString(R.string.gender_prompt));
        genderOptions.addAll(Arrays.asList(activity.getResources().getStringArray(R.array.gender_options)));
        ArrayAdapter<String> genderAdapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_item, genderOptions);
        genderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        genderSpinner.setAdapter(genderAdapter);

        Spinner relationshipSpinner = v.findViewById(R.id.guestRelationshipSpinner);
        List<String> relationshipOptions = new ArrayList<>();
        relationshipOptions.add(activity.getString(R.string.relationship_prompt));
        relationshipOptions.addAll(
                Arrays.asList(activity.getResources().getStringArray(R.array.relationship_options)));
        ArrayAdapter<String> relationshipAdapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_item, relationshipOptions);
        relationshipAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        relationshipSpinner.setAdapter(relationshipAdapter);

        EditText guestBirthDate = v.findViewById(R.id.guestBirthDateField);
        TextView guestBirthDateError = v.findViewById(R.id.guestBirthDateError);
        guestBirthDate.setOnClickListener(view -> showPastDatePicker(date -> {
            guestBirthDate.setText(date);
            guestBirthDateError.setVisibility(View.GONE);
        }));

        // Children ages: rebuild rows to match step 1's children count every
        // time this step is (re)bound, since that count can change on step 1.
        childrenInfoSection.setVisibility(children > 0 ? View.VISIBLE : View.GONE);
        childrenAgesContainer.removeAllViews();
        childAgeRows.clear();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (int i = 0; i < children; i++) {
            View row = inflater.inflate(R.layout.view_child_age_field, childrenAgesContainer, false);
            ((TextView) row.findViewById(R.id.childAgeLabel))
                    .setText(activity.getString(R.string.label_child_age_format, i + 1));
            childrenAgesContainer.addView(row);
            childAgeRows.add(row);
        }

        v.findViewById(R.id.step3BackButton).setOnClickListener(view -> showStep(STEP_ROOMS, -1));
        v.findViewById(R.id.step3ProceedButton).setOnClickListener(view -> {
            if (validateStep3(v)) {
                Toast.makeText(activity, R.string.booking_submitted_message, Toast.LENGTH_LONG).show();
                resetWizard();
            }
        });
    }

    private void updateBookingForCards(View cardForMe, View cardForSomeoneElse, boolean forSelf) {
        cardForMe.setBackgroundResource(
                forSelf ? R.drawable.bg_selectable_card_selected : R.drawable.bg_selectable_card);
        cardForSomeoneElse.setBackgroundResource(
                forSelf ? R.drawable.bg_selectable_card : R.drawable.bg_selectable_card_selected);
    }

    private boolean validateStep3(View v) {
        boolean valid = true;

        if (!bookingForSelf) {
            EditText firstName = v.findViewById(R.id.guestFirstNameField);
            EditText lastName = v.findViewById(R.id.guestLastNameField);
            Spinner genderSpinner = v.findViewById(R.id.guestGenderSpinner);
            Spinner relationshipSpinner = v.findViewById(R.id.guestRelationshipSpinner);
            EditText birthDate = v.findViewById(R.id.guestBirthDateField);

            if (firstName.getText().toString().trim().isEmpty()) {
                showError(v.findViewById(R.id.guestFirstNameError), R.string.error_guest_first_name_required);
                valid = false;
            } else {
                v.findViewById(R.id.guestFirstNameError).setVisibility(View.GONE);
            }
            if (lastName.getText().toString().trim().isEmpty()) {
                showError(v.findViewById(R.id.guestLastNameError), R.string.error_guest_last_name_required);
                valid = false;
            } else {
                v.findViewById(R.id.guestLastNameError).setVisibility(View.GONE);
            }
            if (genderSpinner.getSelectedItemPosition() == 0) {
                showError(v.findViewById(R.id.guestGenderError), R.string.error_guest_gender_required);
                valid = false;
            } else {
                v.findViewById(R.id.guestGenderError).setVisibility(View.GONE);
            }
            if (birthDate.getText().toString().trim().isEmpty()) {
                showError(v.findViewById(R.id.guestBirthDateError), R.string.error_guest_birth_date_required);
                valid = false;
            } else {
                v.findViewById(R.id.guestBirthDateError).setVisibility(View.GONE);
            }
            if (relationshipSpinner.getSelectedItemPosition() == 0) {
                showError(v.findViewById(R.id.guestRelationshipError), R.string.error_guest_relationship_required);
                valid = false;
            } else {
                v.findViewById(R.id.guestRelationshipError).setVisibility(View.GONE);
            }
        }

        for (View row : childAgeRows) {
            EditText ageField = row.findViewById(R.id.childAgeField);
            TextView ageError = row.findViewById(R.id.childAgeError);
            if (ageField.getText().toString().trim().isEmpty()) {
                showError(ageError, R.string.error_child_age_required);
                valid = false;
            } else {
                ageError.setVisibility(View.GONE);
            }
        }

        return valid;
    }

    private void resetWizard() {
        checkInDate = "";
        checkOutDate = "";
        adults = 1;
        children = 0;
        bookingForSelf = true;
        childAgeRows.clear();
        showStep(STEP_DETAILS, 0);
    }

    private void showError(TextView errorView, int messageRes) {
        errorView.setText(messageRes);
        errorView.setVisibility(View.VISIBLE);
    }

    private void showFutureDatePicker(long minMillis, DateCallback callback) {
        Calendar cal = Calendar.getInstance();
        if (minMillis > cal.getTimeInMillis()) {
            cal.setTimeInMillis(minMillis);
        }
        DatePickerDialog dialog = new DatePickerDialog(activity, (view, year, month, day) ->
                callback.onDatePicked(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)),
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        dialog.getDatePicker().setMinDate(minMillis);
        dialog.show();
    }

    private void showPastDatePicker(DateCallback callback) {
        Calendar cal = Calendar.getInstance();
        DatePickerDialog dialog = new DatePickerDialog(activity, (view, year, month, day) ->
                callback.onDatePicked(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)),
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.show();
    }

    private long parseDateMillis(String yyyyMmDd) {
        String[] parts = yyyyMmDd.split("-");
        Calendar cal = Calendar.getInstance();
        cal.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]), 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }
}
