package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Drives the Reservation Module reached from the Reserve tab: reservation
 * dates, a single accommodation selection, review, guest information, a
 * payment-free summary, submission, and a status-driven My Reservations /
 * Details flow. This is a frontend prototype backed by {@link ReservationStore}
 * (in-memory mock data, no backend) — see that class's javadoc for what is
 * and isn't simulated. One instance is kept alive by {@link DashboardActivity}
 * for as long as the app is open, so in-progress answers survive switching
 * tabs and back.
 */
final class ReservationFlowController {

    private static final int SCREEN_DATES = 0;
    private static final int SCREEN_ROOMS = 1;
    private static final int SCREEN_REVIEW = 2;
    private static final int SCREEN_GUEST_INFO = 3;
    private static final int SCREEN_SUMMARY = 4;
    private static final int SCREEN_SUBMITTED = 5;
    private static final int SCREEN_MY_LIST = 6;
    private static final int SCREEN_DETAILS = 7;

    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

    private final Activity activity;
    private final Runnable onBackToHome;
    private final FrameLayout root;

    private int currentScreen = -1;

    // ---- Dates screen -----------------------------------------------------
    private long checkInMillis = -1;
    private long checkOutMillis = -1;
    private int adults = 1;
    private int children = 0;

    // ---- Rooms screen: only one option may have qty > 0 at a time ---------
    private final Map<Integer, Integer> roomQuantities = new LinkedHashMap<>();

    // ---- Review / Guest Info screens ---------------------------------------
    private String specialRequest = "";
    private boolean guestInfoPrefilled = false;
    private String guestName = "";
    private String guestEmail = "";
    private String guestMobile = "";
    private String guestAddress = "";
    private String purpose = "";

    // ---- Summary screen -----------------------------------------------------
    private boolean policyChecked = false;
    private boolean confirmationChecked = false;

    private ReservationStore.Reservation lastSubmitted;
    private int myListFilter = ReservationStore.STATUS_ALL;
    private String detailsReference = "";

    ReservationFlowController(Activity activity, Runnable onBackToHome) {
        this.activity = activity;
        this.onBackToHome = onBackToHome;
        root = new FrameLayout(activity);
        for (ReservationCatalog.RoomOption option : ReservationCatalog.all()) {
            roomQuantities.put(option.id, 0);
        }
        showScreen(SCREEN_DATES);
    }

    View getRootView() {
        return root;
    }

    /** Steps back one logical screen at a time; Dates lets the default back action run. */
    boolean handleBackPressed() {
        switch (currentScreen) {
            case SCREEN_DATES:
                return false;
            case SCREEN_ROOMS:
                showScreen(SCREEN_DATES);
                return true;
            case SCREEN_REVIEW:
                showScreen(SCREEN_ROOMS);
                return true;
            case SCREEN_GUEST_INFO:
                showScreen(SCREEN_REVIEW);
                return true;
            case SCREEN_SUMMARY:
                showScreen(SCREEN_GUEST_INFO);
                return true;
            case SCREEN_SUBMITTED:
                resetInProgressFields();
                showScreen(SCREEN_DATES);
                return true;
            case SCREEN_MY_LIST:
                showScreen(SCREEN_DATES);
                return true;
            case SCREEN_DETAILS:
                showScreen(SCREEN_MY_LIST);
                return true;
            default:
                return false;
        }
    }

    /** Deep-link entry point used by notification cards and the Submitted screen's CTA. */
    void openDetails(String referenceNo) {
        detailsReference = referenceNo;
        showScreen(SCREEN_DETAILS);
    }

    private void showScreen(int screen) {
        currentScreen = screen;
        View v = LayoutInflater.from(activity).inflate(layoutFor(screen), root, false);
        bindScreen(screen, v);
        root.removeAllViews();
        root.addView(v);
    }

    private int layoutFor(int screen) {
        switch (screen) {
            case SCREEN_ROOMS:
                return R.layout.view_reservation_rooms;
            case SCREEN_REVIEW:
                return R.layout.view_reservation_review;
            case SCREEN_GUEST_INFO:
                return R.layout.view_reservation_guest_info;
            case SCREEN_SUMMARY:
                return R.layout.view_reservation_summary;
            case SCREEN_SUBMITTED:
                return R.layout.view_reservation_submitted;
            case SCREEN_MY_LIST:
                return R.layout.view_reservation_my_list;
            case SCREEN_DETAILS:
                return R.layout.view_reservation_details;
            default:
                return R.layout.view_reservation_dates;
        }
    }

    private void bindScreen(int screen, View v) {
        switch (screen) {
            case SCREEN_ROOMS:
                bindRooms(v);
                break;
            case SCREEN_REVIEW:
                bindReview(v);
                break;
            case SCREEN_GUEST_INFO:
                bindGuestInfo(v);
                break;
            case SCREEN_SUMMARY:
                bindSummary(v);
                break;
            case SCREEN_SUBMITTED:
                bindSubmitted(v);
                break;
            case SCREEN_MY_LIST:
                bindMyList(v);
                break;
            case SCREEN_DETAILS:
                bindDetails(v);
                break;
            default:
                bindDates(v);
                break;
        }
    }

    // ---- Screen 1: Choose Your Reservation Dates ---------------------------

    private void bindDates(View v) {
        v.findViewById(R.id.reservationMyListLink).setOnClickListener(view -> showScreen(SCREEN_MY_LIST));

        EditText checkIn = v.findViewById(R.id.reservationCheckInField);
        EditText checkOut = v.findViewById(R.id.reservationCheckOutField);
        TextView checkInError = v.findViewById(R.id.reservationCheckInError);
        TextView checkOutError = v.findViewById(R.id.reservationCheckOutError);
        TextView adultsValue = v.findViewById(R.id.reservationAdultsValue);
        TextView childrenValue = v.findViewById(R.id.reservationChildrenValue);

        checkIn.setText(checkInMillis >= 0 ? formatDate(checkInMillis) : "");
        checkOut.setText(checkOutMillis >= 0 ? formatDate(checkOutMillis) : "");
        adultsValue.setText(String.valueOf(adults));
        childrenValue.setText(String.valueOf(children));

        checkIn.setOnClickListener(view -> GlassDatePicker.showRangePicker(activity, checkInMillis, checkOutMillis,
                System.currentTimeMillis() - 1000L, (start, end) -> {
                    checkInMillis = start;
                    checkOutMillis = end;
                    checkIn.setText(formatDate(start));
                    checkOut.setText(formatDate(end));
                    checkInError.setVisibility(View.GONE);
                    checkOutError.setVisibility(View.GONE);
                }));
        checkOut.setOnClickListener(view -> checkIn.performClick());

        v.findViewById(R.id.reservationAdultsMinus).setOnClickListener(view -> {
            if (adults > 1) {
                adults--;
                adultsValue.setText(String.valueOf(adults));
            }
        });
        v.findViewById(R.id.reservationAdultsPlus).setOnClickListener(view -> {
            adults++;
            adultsValue.setText(String.valueOf(adults));
        });
        v.findViewById(R.id.reservationChildrenMinus).setOnClickListener(view -> {
            if (children > 0) {
                children--;
                childrenValue.setText(String.valueOf(children));
            }
        });
        v.findViewById(R.id.reservationChildrenPlus).setOnClickListener(view -> {
            children++;
            childrenValue.setText(String.valueOf(children));
        });

        v.findViewById(R.id.reservationDatesNextButton).setOnClickListener(view -> {
            boolean valid = true;
            if (checkInMillis < 0) {
                showError(checkInError, R.string.error_check_in_required);
                valid = false;
            } else if (checkInMillis < System.currentTimeMillis() - ONE_DAY_MS) {
                showError(checkInError, R.string.error_check_in_past);
                valid = false;
            } else {
                checkInError.setVisibility(View.GONE);
            }
            if (checkOutMillis < 0) {
                showError(checkOutError, R.string.error_check_out_required);
                valid = false;
            } else if (checkInMillis >= 0 && checkOutMillis <= checkInMillis) {
                showError(checkOutError, R.string.error_check_out_before_checkin);
                valid = false;
            } else {
                checkOutError.setVisibility(View.GONE);
            }
            if (valid) {
                showScreen(SCREEN_ROOMS);
            }
        });
    }

    // ---- Screen 2: Available Hotel Rooms ------------------------------------

    private void bindRooms(View v) {
        bindHeader(v, R.id.reservationRoomsHeaderBar, R.string.reservation_rooms_title, () -> showScreen(SCREEN_DATES));

        LinearLayout container = v.findViewById(R.id.reservationRoomsContainer);
        renderRoomOptions(container);

        v.findViewById(R.id.reservationRoomsNextButton).setOnClickListener(view -> {
            if (totalRoomsSelected() == 0) {
                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle(R.string.dialog_no_room_title)
                        .setMessage(R.string.dialog_no_room_message)
                        .setPositiveButton(R.string.button_ok, null)
                        .create();
                ThemeManager.applyGlassEffect(dialog.getWindow());
                dialog.show();
                return;
            }
            showScreen(SCREEN_REVIEW);
        });
    }

    private void renderRoomOptions(LinearLayout container) {
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        String lastCategory = null;
        for (ReservationCatalog.RoomOption option : ReservationCatalog.all()) {
            if (!option.categoryName.equals(lastCategory)) {
                TextView header = (TextView) inflater.inflate(
                        R.layout.view_hotel_food_category_header, container, false);
                header.setText(option.categoryName);
                container.addView(header);
                lastCategory = option.categoryName;
            }

            View card = inflater.inflate(R.layout.view_hotel_stepper_item, container, false);
            ((ImageView) card.findViewById(R.id.stepperItemImage)).setImageResource(R.drawable.ic_bed);
            ((TextView) card.findViewById(R.id.stepperItemName)).setText(option.variantName);
            TextView description = card.findViewById(R.id.stepperItemDescription);
            description.setText(option.description);
            description.setVisibility(View.VISIBLE);
            TextView meta = card.findViewById(R.id.stepperItemMeta);
            meta.setText(activity.getString(R.string.format_capacity, option.capacity));
            meta.setVisibility(View.VISIBLE);
            ((TextView) card.findViewById(R.id.stepperItemPrice)).setText(
                    activity.getString(R.string.format_price_per_night, formatMoney(option.pricePerNight)));

            TextView qtyValue = card.findViewById(R.id.stepperItemQtyValue);
            ImageView minus = card.findViewById(R.id.stepperItemMinus);
            ImageView plus = card.findViewById(R.id.stepperItemPlus);
            int qty = roomQuantities.get(option.id);
            qtyValue.setText(String.valueOf(qty));
            setStepperEnabled(plus, qty < option.maxQuantity);

            minus.setOnClickListener(view -> {
                int current = roomQuantities.get(option.id);
                if (current > 0) {
                    roomQuantities.put(option.id, current - 1);
                    renderRoomOptions(container);
                }
            });
            plus.setOnClickListener(view -> {
                int current = roomQuantities.get(option.id);
                if (current < option.maxQuantity) {
                    // Only one accommodation may be selected at a time: every
                    // downstream screen (Review/Summary/Details) shows a single line.
                    for (Integer key : roomQuantities.keySet()) {
                        if (!key.equals(option.id)) {
                            roomQuantities.put(key, 0);
                        }
                    }
                    roomQuantities.put(option.id, current + 1);
                    renderRoomOptions(container);
                }
            });

            container.addView(card);
        }
    }

    // ---- Screen 3: Review Your Reservation ----------------------------------

    private void bindReview(View v) {
        bindHeader(v, R.id.reservationReviewHeaderBar, R.string.reservation_review_title, () -> showScreen(SCREEN_ROOMS));

        bindRow(v.findViewById(R.id.reviewResRowCheckIn), R.string.label_stay_check_in, formatDate(checkInMillis));
        bindRow(v.findViewById(R.id.reviewResRowCheckOut), R.string.label_stay_check_out, formatDate(checkOutMillis));
        bindRow(v.findViewById(R.id.reviewResRowGuests), R.string.label_stay_guests, formatGuests(adults, children));
        bindRow(v.findViewById(R.id.reviewResRowNights), R.string.label_stay_nights, formatNights(nights()));

        LinearLayout container = v.findViewById(R.id.reviewAccommodationContainer);
        container.removeAllViews();
        ReservationCatalog.RoomOption option = selectedOption();
        if (option != null) {
            int qty = selectedQuantity();
            View row = LayoutInflater.from(activity).inflate(R.layout.view_hotel_review_room_row, container, false);
            ((ImageView) row.findViewById(R.id.reviewRoomImage)).setImageResource(R.drawable.ic_bed);
            ((TextView) row.findViewById(R.id.reviewRoomName)).setText(option.categoryName + " · " + option.variantName);
            ((TextView) row.findViewById(R.id.reviewRoomMeta))
                    .setText(activity.getString(R.string.format_capacity, option.capacity));
            ((TextView) row.findViewById(R.id.reviewRoomPrice))
                    .setText(activity.getString(R.string.format_price_per_night, formatMoney(option.pricePerNight)));
            ((TextView) row.findViewById(R.id.reviewRoomQtyNights))
                    .setText(activity.getString(R.string.format_quantity, qty));
            int estimatedValue = option.pricePerNight * qty * nights();
            ((TextView) row.findViewById(R.id.reviewRoomSubtotal))
                    .setText(activity.getString(R.string.format_estimated_value, formatMoney(estimatedValue)));
            container.addView(row);
        }

        EditText specialRequestField = v.findViewById(R.id.reviewSpecialRequestField);
        specialRequestField.setText(specialRequest);
        AuthUiUtils.afterTextChanged(specialRequestField,
                () -> specialRequest = specialRequestField.getText().toString());

        v.findViewById(R.id.reservationReviewNextButton).setOnClickListener(view -> showScreen(SCREEN_GUEST_INFO));
    }

    // ---- Screen 4: Guest Information -----------------------------------------

    private void bindGuestInfo(View v) {
        bindHeader(v, R.id.reservationGuestInfoHeaderBar, R.string.reservation_guest_info_title,
                () -> showScreen(SCREEN_REVIEW));

        if (!guestInfoPrefilled) {
            guestName = ProfileStore.getFullName(activity);
            guestEmail = ProfileStore.getEmail(activity);
            guestInfoPrefilled = true;
        }

        EditText nameField = v.findViewById(R.id.guestInfoNameField);
        EditText emailField = v.findViewById(R.id.guestInfoEmailField);
        EditText mobileField = v.findViewById(R.id.guestInfoMobileField);
        EditText addressField = v.findViewById(R.id.guestInfoAddressField);
        EditText purposeField = v.findViewById(R.id.guestInfoPurposeField);
        EditText specialRequestField = v.findViewById(R.id.guestInfoSpecialRequestField);
        TextView nameError = v.findViewById(R.id.guestInfoNameError);
        TextView emailError = v.findViewById(R.id.guestInfoEmailError);
        TextView mobileError = v.findViewById(R.id.guestInfoMobileError);
        TextView addressError = v.findViewById(R.id.guestInfoAddressError);

        nameField.setText(guestName);
        emailField.setText(guestEmail);
        mobileField.setText(guestMobile);
        addressField.setText(guestAddress);
        purposeField.setText(purpose);
        specialRequestField.setText(specialRequest);

        AuthUiUtils.afterTextChanged(nameField, () -> guestName = nameField.getText().toString());
        AuthUiUtils.afterTextChanged(emailField, () -> guestEmail = emailField.getText().toString());
        AuthUiUtils.afterTextChanged(mobileField, () -> guestMobile = mobileField.getText().toString());
        AuthUiUtils.afterTextChanged(addressField, () -> guestAddress = addressField.getText().toString());
        AuthUiUtils.afterTextChanged(purposeField, () -> purpose = purposeField.getText().toString());
        AuthUiUtils.afterTextChanged(specialRequestField, () -> specialRequest = specialRequestField.getText().toString());

        v.findViewById(R.id.reservationGuestInfoNextButton).setOnClickListener(view -> {
            boolean valid = true;
            if (guestName.trim().isEmpty()) {
                showError(nameError, R.string.error_guest_full_name_required);
                valid = false;
            } else {
                nameError.setVisibility(View.GONE);
            }
            if (guestEmail.trim().isEmpty()) {
                showError(emailError, R.string.error_guest_email_required);
                valid = false;
            } else {
                emailError.setVisibility(View.GONE);
            }
            if (guestMobile.trim().isEmpty()) {
                showError(mobileError, R.string.error_guest_mobile_required);
                valid = false;
            } else {
                mobileError.setVisibility(View.GONE);
            }
            if (guestAddress.trim().isEmpty()) {
                showError(addressError, R.string.error_guest_address_required);
                valid = false;
            } else {
                addressError.setVisibility(View.GONE);
            }
            if (valid) {
                showScreen(SCREEN_SUMMARY);
            }
        });
    }

    // ---- Screen 5: Reservation Summary ---------------------------------------

    private void bindSummary(View v) {
        bindHeader(v, R.id.reservationSummaryHeaderBar, R.string.reservation_summary_title,
                () -> showScreen(SCREEN_GUEST_INFO));

        bindRow(v.findViewById(R.id.summaryRowFullName), R.string.label_full_name, guestName);
        bindRow(v.findViewById(R.id.summaryRowEmail), R.string.label_email_address, guestEmail);
        bindRow(v.findViewById(R.id.summaryRowMobile), R.string.label_mobile_number, guestMobile);
        bindRow(v.findViewById(R.id.summaryRowAddress), R.string.label_address, guestAddress);

        int nights = nights();
        bindRow(v.findViewById(R.id.summaryRowCheckIn), R.string.label_stay_check_in, formatDate(checkInMillis));
        bindRow(v.findViewById(R.id.summaryRowCheckOut), R.string.label_stay_check_out, formatDate(checkOutMillis));
        bindRow(v.findViewById(R.id.summaryRowNights), R.string.label_number_of_nights, String.valueOf(nights));
        bindRow(v.findViewById(R.id.summaryRowAdults), R.string.label_billing_adults, String.valueOf(adults));
        bindRow(v.findViewById(R.id.summaryRowChildren), R.string.label_billing_children, String.valueOf(children));
        bindRow(v.findViewById(R.id.summaryRowTotalGuests), R.string.label_total_guests, String.valueOf(adults + children));

        LinearLayout accommodationContainer = v.findViewById(R.id.summaryAccommodationContainer);
        accommodationContainer.removeAllViews();
        ReservationCatalog.RoomOption option = selectedOption();
        int estimatedValue = 0;
        if (option != null) {
            int qty = selectedQuantity();
            estimatedValue = option.pricePerNight * qty * nights;
            View row = LayoutInflater.from(activity).inflate(R.layout.view_hotel_billing_line_row, accommodationContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(option.categoryName + " · " + option.variantName);
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText(activity.getString(R.string.format_capacity, option.capacity) + " · Qty " + qty + " · "
                    + activity.getString(R.string.format_price_per_night, formatMoney(option.pricePerNight)));
            meta.setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(estimatedValue));
            accommodationContainer.addView(row);
        }
        ((TextView) v.findViewById(R.id.summaryEstimatedValue)).setText(formatCurrency(estimatedValue));

        CheckBox policyBox = v.findViewById(R.id.checkboxReservationPolicy);
        CheckBox confirmationBox = v.findViewById(R.id.checkboxConfirmationSubject);
        policyBox.setChecked(policyChecked);
        confirmationBox.setChecked(confirmationChecked);
        policyBox.setOnCheckedChangeListener((button, checked) -> policyChecked = checked);
        confirmationBox.setOnCheckedChangeListener((button, checked) -> confirmationChecked = checked);

        v.findViewById(R.id.reservationSubmitButton).setOnClickListener(view -> {
            if (!policyChecked || !confirmationChecked) {
                Toast.makeText(activity, R.string.toast_agree_reservation_required, Toast.LENGTH_LONG).show();
                return;
            }
            ReservationCatalog.RoomOption selected = selectedOption();
            if (selected == null) {
                showScreen(SCREEN_ROOMS);
                return;
            }
            lastSubmitted = ReservationStore.submit(activity, checkInMillis, checkOutMillis, adults, children,
                    selected, selectedQuantity(), specialRequest, guestName, guestEmail, guestMobile, guestAddress, purpose);
            showScreen(SCREEN_SUBMITTED);
        });
    }

    // ---- Screen 6: Reservation Request Submitted -----------------------------

    private void bindSubmitted(View v) {
        if (lastSubmitted == null) {
            showScreen(SCREEN_MY_LIST);
            return;
        }
        ReservationStore.Reservation r = lastSubmitted;

        bindRow(v.findViewById(R.id.submittedRowReference), R.string.label_reference_no, r.referenceNo);
        bindRow(v.findViewById(R.id.submittedRowAccommodation), R.string.label_accommodation,
                r.roomOption.categoryName + " · " + r.roomOption.variantName);
        bindRow(v.findViewById(R.id.submittedRowDate), R.string.label_date, formatDateRange(r.checkInMillis, r.checkOutMillis));
        bindRow(v.findViewById(R.id.submittedRowGuests), R.string.label_stay_guests, formatGuests(r.adults, r.children));

        v.findViewById(R.id.submittedStatusDot).setBackgroundTintList(
                ColorStateList.valueOf(ThemeManager.color(activity, R.attr.accentPrimary)));
        TextView statusLabel = v.findViewById(R.id.submittedStatusLabel);
        statusLabel.setText(R.string.res_status_pending);
        statusLabel.setTextColor(ThemeManager.color(activity, R.attr.accentPrimary));

        v.findViewById(R.id.submittedViewButton).setOnClickListener(view -> {
            String reference = r.referenceNo;
            resetInProgressFields();
            openDetails(reference);
        });
    }

    // ---- Screen 7: My Reservations --------------------------------------------

    private void bindMyList(View v) {
        bindHeader(v, R.id.reservationMyListHeaderBar, R.string.reservation_my_list_title, () -> showScreen(SCREEN_DATES));

        TextView tabAll = v.findViewById(R.id.tabAll);
        TextView tabPending = v.findViewById(R.id.tabPending);
        TextView tabConfirmed = v.findViewById(R.id.tabConfirmed);
        TextView tabCancelled = v.findViewById(R.id.tabCancelled);
        tabAll.setOnClickListener(view -> {
            myListFilter = ReservationStore.STATUS_ALL;
            bindMyList(v);
        });
        tabPending.setOnClickListener(view -> {
            myListFilter = ReservationStore.STATUS_PENDING;
            bindMyList(v);
        });
        tabConfirmed.setOnClickListener(view -> {
            myListFilter = ReservationStore.STATUS_CONFIRMED;
            bindMyList(v);
        });
        tabCancelled.setOnClickListener(view -> {
            myListFilter = ReservationStore.STATUS_CANCELLED;
            bindMyList(v);
        });
        updateTabStyle(tabAll, myListFilter == ReservationStore.STATUS_ALL);
        updateTabStyle(tabPending, myListFilter == ReservationStore.STATUS_PENDING);
        updateTabStyle(tabConfirmed, myListFilter == ReservationStore.STATUS_CONFIRMED);
        updateTabStyle(tabCancelled, myListFilter == ReservationStore.STATUS_CANCELLED);

        List<ReservationStore.Reservation> list = ReservationStore.byStatus(myListFilter);
        LinearLayout container = v.findViewById(R.id.reservationListContainer);
        container.removeAllViews();
        v.findViewById(R.id.reservationListEmpty).setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(activity);
        for (ReservationStore.Reservation r : list) {
            View card = inflater.inflate(R.layout.view_reservation_card, container, false);
            ((TextView) card.findViewById(R.id.cardReferenceNo)).setText(r.referenceNo);
            ((TextView) card.findViewById(R.id.cardReservationType)).setText(R.string.reservation_type_hotel);
            ((TextView) card.findViewById(R.id.cardAccommodation))
                    .setText(r.roomOption.categoryName + " · " + r.roomOption.variantName);
            ((TextView) card.findViewById(R.id.cardDateRange)).setText(formatDateRange(r.checkInMillis, r.checkOutMillis));
            ((TextView) card.findViewById(R.id.cardGuests)).setText(formatGuests(r.adults, r.children));

            StatusPresentation status = resolveStatus(r);
            card.findViewById(R.id.cardStatusDot).setBackgroundTintList(
                    ColorStateList.valueOf(ThemeManager.color(activity, status.colorAttr)));
            TextView statusLabel = card.findViewById(R.id.cardStatusLabel);
            statusLabel.setText(status.labelRes);
            statusLabel.setTextColor(ThemeManager.color(activity, status.colorAttr));

            card.findViewById(R.id.cardViewDetailsButton).setOnClickListener(view -> openDetails(r.referenceNo));
            container.addView(card);
        }
    }

    private void updateTabStyle(TextView tab, boolean active) {
        tab.setTextColor(ThemeManager.color(activity, active ? R.attr.accentPrimary : R.attr.textMuted));
        tab.setAlpha(active ? 1f : 0.55f);
    }

    // ---- Screen 8-12: Reservation Details (status-driven) ---------------------

    private void bindDetails(View v) {
        ReservationStore.Reservation r = ReservationStore.byReference(detailsReference);
        if (r == null) {
            showScreen(SCREEN_MY_LIST);
            return;
        }

        bindHeader(v, R.id.reservationDetailsHeaderBar, R.string.reservation_details_title, () -> showScreen(SCREEN_MY_LIST));

        StatusPresentation status = resolveStatus(r);
        v.findViewById(R.id.detailsStatusDot).setBackgroundTintList(
                ColorStateList.valueOf(ThemeManager.color(activity, status.colorAttr)));
        TextView statusLabel = v.findViewById(R.id.detailsStatusLabel);
        statusLabel.setText(status.labelRes);
        statusLabel.setTextColor(ThemeManager.color(activity, status.colorAttr));
        ((TextView) v.findViewById(R.id.detailsStatusDesc)).setText(status.descRes);

        bindRow(v.findViewById(R.id.detailsRowReference), R.string.label_reference_no, r.referenceNo);
        bindRow(v.findViewById(R.id.detailsRowAccommodation), R.string.label_accommodation,
                r.roomOption.categoryName + " · " + r.roomOption.variantName);
        bindRow(v.findViewById(R.id.detailsRowDate), R.string.label_date, formatDateRange(r.checkInMillis, r.checkOutMillis));
        bindRow(v.findViewById(R.id.detailsRowGuests), R.string.label_stay_guests, formatGuests(r.adults, r.children));

        View altContainer = v.findViewById(R.id.detailsAltScheduleContainer);
        View additionalContainer = v.findViewById(R.id.detailsAdditionalConfirmationContainer);
        View confirmedContainer = v.findViewById(R.id.detailsConfirmedContainer);
        View rejectedContainer = v.findViewById(R.id.detailsRejectedContainer);
        View cancelledReasonContainer = v.findViewById(R.id.detailsCancelledReasonContainer);
        altContainer.setVisibility(View.GONE);
        additionalContainer.setVisibility(View.GONE);
        confirmedContainer.setVisibility(View.GONE);
        rejectedContainer.setVisibility(View.GONE);
        cancelledReasonContainer.setVisibility(View.GONE);

        if (r.status == ReservationStore.STATUS_PENDING && ReservationStore.SUB_ALTERNATIVE_PROPOSED.equals(r.subStatus)) {
            altContainer.setVisibility(View.VISIBLE);
            bindRow(v.findViewById(R.id.detailsRowCurrentRequest), R.string.label_current_request,
                    formatDateRange(r.checkInMillis, r.checkOutMillis));
            bindRow(v.findViewById(R.id.detailsRowProposedSchedule), R.string.label_proposed_schedule,
                    formatDateRange(r.proposedCheckInMillis, r.proposedCheckOutMillis));
            v.findViewById(R.id.detailsAcceptButton).setOnClickListener(view -> {
                ReservationStore.acceptAlternativeSchedule(activity, r.referenceNo);
                showScreen(SCREEN_DETAILS);
            });
            v.findViewById(R.id.detailsDeclineButton).setOnClickListener(view -> {
                ReservationStore.declineAlternativeSchedule(activity, r.referenceNo);
                showScreen(SCREEN_DETAILS);
            });
        } else if (r.status == ReservationStore.STATUS_PENDING
                && ReservationStore.SUB_ADDITIONAL_CONFIRMATION.equals(r.subStatus)) {
            additionalContainer.setVisibility(View.VISIBLE);
        } else if (r.status == ReservationStore.STATUS_CONFIRMED) {
            confirmedContainer.setVisibility(View.VISIBLE);
        } else if (r.status == ReservationStore.STATUS_CANCELLED && ReservationStore.SUB_REJECTED.equals(r.subStatus)) {
            rejectedContainer.setVisibility(View.VISIBLE);
            ((TextView) v.findViewById(R.id.detailsRejectedReason)).setText(
                    r.cancelReason != null && !r.cancelReason.isEmpty()
                            ? r.cancelReason : activity.getString(R.string.reason_room_unavailable));
            v.findViewById(R.id.detailsTryAnotherDateButton).setOnClickListener(view -> {
                resetInProgressFields();
                showScreen(SCREEN_DATES);
            });
            v.findViewById(R.id.detailsViewOtherRoomsButton).setOnClickListener(view -> showScreen(SCREEN_ROOMS));
            v.findViewById(R.id.detailsBackToListButton).setOnClickListener(view -> showScreen(SCREEN_MY_LIST));
        } else if (r.status == ReservationStore.STATUS_CANCELLED
                && r.cancelReason != null && !r.cancelReason.isEmpty()) {
            cancelledReasonContainer.setVisibility(View.VISIBLE);
            ((TextView) v.findViewById(R.id.detailsCancelledReason)).setText(r.cancelReason);
        }

        bindTimeline(v, r);
    }

    private void bindTimeline(View v, ReservationStore.Reservation r) {
        LinearLayout container = v.findViewById(R.id.detailsTimelineContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        List<ReservationStore.TimelineEntry> entries = r.timeline;

        for (int i = 0; i < entries.size(); i++) {
            ReservationStore.TimelineEntry entry = entries.get(i);
            boolean isLast = i == entries.size() - 1;
            boolean filled = entry.state != ReservationStore.TIMELINE_UPCOMING;

            View row = inflater.inflate(R.layout.view_reservation_timeline_row, container, false);
            TextView label = row.findViewById(R.id.timelineLabel);
            label.setText(entry.label);
            label.setTextColor(ThemeManager.color(activity, filled ? R.attr.textPrimary : R.attr.textMuted));
            label.setTypeface(null, entry.state == ReservationStore.TIMELINE_CURRENT ? Typeface.BOLD : Typeface.NORMAL);

            TextView meta = row.findViewById(R.id.timelineMeta);
            String metaText = entry.millis > 0 ? formatDateTime(entry.millis) : "";
            if (entry.description != null && !entry.description.isEmpty()) {
                metaText = metaText.isEmpty() ? entry.description : metaText + " · " + entry.description;
            }
            meta.setText(metaText);
            meta.setVisibility(metaText.isEmpty() ? View.GONE : View.VISIBLE);

            View dot = row.findViewById(R.id.timelineDot);
            dot.setBackgroundResource(filled ? R.drawable.bg_progress_dot_filled : R.drawable.bg_progress_dot_hollow);
            if (filled && isLast) {
                if (r.status == ReservationStore.STATUS_CONFIRMED) {
                    dot.setBackgroundTintList(ColorStateList.valueOf(ThemeManager.color(activity, R.attr.successText)));
                } else if (r.status == ReservationStore.STATUS_CANCELLED) {
                    dot.setBackgroundTintList(ColorStateList.valueOf(ThemeManager.color(activity, R.attr.errorText)));
                }
            }

            View line = row.findViewById(R.id.timelineLine);
            if (isLast) {
                line.setVisibility(View.GONE);
            } else {
                line.setBackgroundColor(ThemeManager.color(activity, filled ? R.attr.accentPrimary : R.attr.dividerColor));
            }

            container.addView(row);
        }
    }

    private static final class StatusPresentation {
        final int colorAttr;
        final int labelRes;
        final int descRes;

        StatusPresentation(int colorAttr, int labelRes, int descRes) {
            this.colorAttr = colorAttr;
            this.labelRes = labelRes;
            this.descRes = descRes;
        }
    }

    private StatusPresentation resolveStatus(ReservationStore.Reservation r) {
        if (r.status == ReservationStore.STATUS_CONFIRMED) {
            return new StatusPresentation(R.attr.successText, R.string.res_status_confirmed, R.string.res_desc_confirmed);
        }
        if (r.status == ReservationStore.STATUS_CANCELLED) {
            if (ReservationStore.SUB_REJECTED.equals(r.subStatus)) {
                return new StatusPresentation(R.attr.errorText, R.string.res_status_rejected, R.string.res_desc_rejected);
            }
            return new StatusPresentation(R.attr.textMuted, R.string.res_status_cancelled, R.string.res_desc_cancelled);
        }
        if (ReservationStore.SUB_ADDITIONAL_CONFIRMATION.equals(r.subStatus)) {
            return new StatusPresentation(R.attr.warningText, R.string.res_status_additional_confirmation,
                    R.string.res_desc_additional_confirmation);
        }
        if (ReservationStore.SUB_ALTERNATIVE_PROPOSED.equals(r.subStatus)) {
            return new StatusPresentation(R.attr.infoText, R.string.res_status_action_required, R.string.res_desc_action_required);
        }
        return new StatusPresentation(R.attr.accentPrimary, R.string.res_status_pending, R.string.res_desc_pending);
    }

    // ---- Shared helpers ------------------------------------------------------

    private ReservationCatalog.RoomOption selectedOption() {
        for (Map.Entry<Integer, Integer> entry : roomQuantities.entrySet()) {
            if (entry.getValue() > 0) {
                return ReservationCatalog.find(entry.getKey());
            }
        }
        return null;
    }

    private int selectedQuantity() {
        ReservationCatalog.RoomOption option = selectedOption();
        return option == null ? 0 : roomQuantities.get(option.id);
    }

    private void resetInProgressFields() {
        checkInMillis = -1;
        checkOutMillis = -1;
        adults = 1;
        children = 0;
        for (Integer key : roomQuantities.keySet()) {
            roomQuantities.put(key, 0);
        }
        specialRequest = "";
        guestInfoPrefilled = false;
        guestName = "";
        guestEmail = "";
        guestMobile = "";
        guestAddress = "";
        purpose = "";
        policyChecked = false;
        confirmationChecked = false;
    }

    private void bindHeader(View v, int headerBarId, int titleRes, Runnable onBack) {
        View header = v.findViewById(headerBarId);
        ((TextView) header.findViewById(R.id.headerTitle)).setText(titleRes);
        header.findViewById(R.id.headerBackButton).setOnClickListener(view -> onBack.run());
    }

    private void bindRow(View row, int labelRes, String value) {
        ((TextView) row.findViewById(R.id.rowLabel)).setText(labelRes);
        ((TextView) row.findViewById(R.id.rowValue)).setText(value);
    }

    private void showError(TextView errorView, int messageRes) {
        errorView.setText(messageRes);
        errorView.setVisibility(View.VISIBLE);
    }

    private void setStepperEnabled(ImageView button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
    }

    private int totalRoomsSelected() {
        int total = 0;
        for (int qty : roomQuantities.values()) {
            total += qty;
        }
        return total;
    }

    private int nights() {
        if (checkInMillis < 0 || checkOutMillis < 0 || checkOutMillis <= checkInMillis) {
            return 0;
        }
        return (int) ((checkOutMillis - checkInMillis) / ONE_DAY_MS);
    }

    private String formatGuests(int adultsCount, int childrenCount) {
        return adultsCount + (adultsCount == 1 ? " Adult" : " Adults") + " · "
                + childrenCount + (childrenCount == 1 ? " Child" : " Children");
    }

    private String formatNights(int nights) {
        return nights + (nights == 1 ? " night" : " nights");
    }

    private String formatMoney(int amount) {
        return NumberFormat.getInstance(Locale.US).format(amount);
    }

    private String formatCurrency(int amount) {
        return activity.getString(R.string.format_currency, formatMoney(amount));
    }

    private String formatDate(long millis) {
        if (millis < 0) {
            return "";
        }
        return new SimpleDateFormat("MMM d, yyyy", Locale.US).format(new Date(millis));
    }

    private String formatDateTime(long millis) {
        return new SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.US).format(new Date(millis));
    }

    private String formatDateRange(long inMillis, long outMillis) {
        if (inMillis < 0 || outMillis < 0) {
            return "";
        }
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(inMillis);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(outMillis);
        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.MONTH) == b.get(Calendar.MONTH)) {
            String monthDay = new SimpleDateFormat("MMM d", Locale.US).format(new Date(inMillis));
            String dayOnly = new SimpleDateFormat("d", Locale.US).format(new Date(outMillis));
            String year = new SimpleDateFormat("yyyy", Locale.US).format(new Date(inMillis));
            return monthDay + "–" + dayOnly + ", " + year;
        }
        return formatDate(inMillis) + " – " + formatDate(outMillis);
    }
}
