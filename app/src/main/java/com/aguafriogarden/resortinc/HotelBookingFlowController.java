package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.aguafriogarden.resortinc.network.AmenityAvailability;
import com.aguafriogarden.resortinc.network.AmenityListResponse;
import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.AvailabilityResponse;
import com.aguafriogarden.resortinc.network.ErrorResponse;
import com.aguafriogarden.resortinc.network.FoodListResponse;
import com.aguafriogarden.resortinc.network.MenuItemAvailability;
import com.aguafriogarden.resortinc.network.ReserveResponse;
import com.aguafriogarden.resortinc.network.RoomTypeAvailability;
import com.bumptech.glide.Glide;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Drives the Hotel Rooms booking flow reached from the Book tab: stay dates,
 * room selection, review, optional amenities/food, billing summary, mock
 * GCash payment and a success screen. Rooms, amenities and food all come
 * from the shared backend (see {@link #fetchAvailability}, {@link #fetchAmenities}
 * and {@link #fetchFood}) with real photos loaded via Glide. One instance is
 * kept alive by {@link BookingCatalogController} for as long as the Book tab
 * exists, so answers survive navigating to another module and back;
 * {@link #resetFlow()} clears them once a booking is completed.
 */
final class HotelBookingFlowController {

    private static final int SCREEN_DATES = 0;
    private static final int SCREEN_ROOMS = 1;
    private static final int SCREEN_REVIEW = 2;
    private static final int SCREEN_AMENITY = 3;
    private static final int SCREEN_FOOD = 4;
    private static final int SCREEN_BILLING = 5;
    private static final int SCREEN_PAYMENT = 6;
    private static final int SCREEN_SUCCESS = 7;

    private static final int PAYMENT_NONE = 0;
    private static final int PAYMENT_FULL = 1;
    private static final int PAYMENT_PARTIAL = 2;

    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;
    private static final double PARTIAL_PAYMENT_RATE = 0.30;
    private static final int REQUEST_PAYMENT_SCREENSHOT = 4101;


    private final Activity activity;
    private final Runnable onExitToCatalog;
    private final Runnable onBackToHome;
    private final FrameLayout root;

    private int currentScreen = -1;
    private View currentScreenView;

    // ---- Persisted answers (survive navigating away and back) -----------
    private long checkInMillis = -1;
    private long checkOutMillis = -1;
    private int adults = 1;
    private int children = 0;
    private List<RoomTypeAvailability> availableRoomTypes = new ArrayList<>();
    private final Map<Integer, Integer> roomQuantities = new LinkedHashMap<>();
    private boolean isLoadingAvailability = false;
    private String specialRequest = "";
    private List<AmenityAvailability> availableAmenities = new ArrayList<>();
    private final Map<Integer, Integer> amenityQuantities = new LinkedHashMap<>();
    private boolean isLoadingAmenities = false;
    private List<MenuItemAvailability> availableFood = new ArrayList<>();
    private final Map<Integer, Integer> foodQuantities = new LinkedHashMap<>();
    private boolean isLoadingFood = false;
    private int paymentOption = PAYMENT_NONE;
    private boolean termsChecked;
    private boolean cancellationChecked;
    private String amountPaidText = "";
    private String referenceNumberText = "";
    private Uri screenshotUri;
    private String bookingReference = "";

    HotelBookingFlowController(Activity activity, Runnable onExitToCatalog, Runnable onBackToHome) {
        this.activity = activity;
        this.onExitToCatalog = onExitToCatalog;
        this.onBackToHome = onBackToHome;
        root = new FrameLayout(activity);
        showScreen(SCREEN_DATES);
    }

    View getRootView() {
        return root;
    }

    /** Steps back one screen at a time; Success and Dates both exit the flow. */
    boolean handleBackPressed() {
        switch (currentScreen) {
            case SCREEN_DATES:
                onExitToCatalog.run();
                return true;
            case SCREEN_ROOMS:
                showScreen(SCREEN_DATES);
                return true;
            case SCREEN_REVIEW:
                showScreen(SCREEN_ROOMS);
                return true;
            case SCREEN_AMENITY:
                showScreen(SCREEN_REVIEW);
                return true;
            case SCREEN_FOOD:
                showScreen(SCREEN_AMENITY);
                return true;
            case SCREEN_BILLING:
                showScreen(SCREEN_FOOD);
                return true;
            case SCREEN_PAYMENT:
                showScreen(SCREEN_BILLING);
                return true;
            case SCREEN_SUCCESS:
                resetFlow();
                onBackToHome.run();
                return true;
            default:
                return true;
        }
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PAYMENT_SCREENSHOT || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        screenshotUri = data.getData();
        if (currentScreen == SCREEN_PAYMENT && currentScreenView != null) {
            showScreenshotPreview(currentScreenView);
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
            case SCREEN_ROOMS:
                return R.layout.view_hotel_rooms;
            case SCREEN_REVIEW:
                return R.layout.view_hotel_review;
            case SCREEN_AMENITY:
                return R.layout.view_hotel_amenity;
            case SCREEN_FOOD:
                return R.layout.view_hotel_food;
            case SCREEN_BILLING:
                return R.layout.view_hotel_billing;
            case SCREEN_PAYMENT:
                return R.layout.view_hotel_payment;
            case SCREEN_SUCCESS:
                return R.layout.view_hotel_success;
            default:
                return R.layout.view_hotel_dates;
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
            case SCREEN_AMENITY:
                bindAmenity(v);
                break;
            case SCREEN_FOOD:
                bindFood(v);
                break;
            case SCREEN_BILLING:
                bindBilling(v);
                break;
            case SCREEN_PAYMENT:
                bindPayment(v);
                break;
            case SCREEN_SUCCESS:
                bindSuccess(v);
                break;
            default:
                bindDates(v);
                break;
        }
    }

    // ---- Screen 1: Choose Your Stay Dates --------------------------------

    private void bindDates(View v) {
        bindHeader(v, R.id.hotelDatesHeaderBar, R.string.hotel_dates_title, onExitToCatalog);

        EditText checkIn = v.findViewById(R.id.hotelCheckInField);
        EditText checkOut = v.findViewById(R.id.hotelCheckOutField);
        TextView checkInError = v.findViewById(R.id.hotelCheckInError);
        TextView checkOutError = v.findViewById(R.id.hotelCheckOutError);
        TextView adultsValue = v.findViewById(R.id.hotelAdultsValue);
        TextView childrenValue = v.findViewById(R.id.hotelChildrenValue);

        checkIn.setText(checkInMillis >= 0 ? formatDate(checkInMillis) : "");
        checkOut.setText(checkOutMillis >= 0 ? formatDate(checkOutMillis) : "");
        adultsValue.setText(String.valueOf(adults));
        childrenValue.setText(String.valueOf(children));

        checkIn.setOnClickListener(view -> GlassDatePicker.showRangePicker(activity, checkInMillis, checkOutMillis, System.currentTimeMillis() - 1000L, (start, end) -> {
            checkInMillis = start;
            checkOutMillis = end;
            checkIn.setText(formatDate(start));
            checkOut.setText(formatDate(end));
            checkInError.setVisibility(View.GONE);
            checkOutError.setVisibility(View.GONE);
        }));
        checkOut.setOnClickListener(view -> checkIn.performClick());

        v.findViewById(R.id.hotelAdultsMinus).setOnClickListener(view -> {
            if (adults > 1) {
                adults--;
                adultsValue.setText(String.valueOf(adults));
            }
        });
        v.findViewById(R.id.hotelAdultsPlus).setOnClickListener(view -> {
            adults++;
            adultsValue.setText(String.valueOf(adults));
        });
        v.findViewById(R.id.hotelChildrenMinus).setOnClickListener(view -> {
            if (children > 0) {
                children--;
                childrenValue.setText(String.valueOf(children));
            }
        });
        v.findViewById(R.id.hotelChildrenPlus).setOnClickListener(view -> {
            children++;
            childrenValue.setText(String.valueOf(children));
        });

        v.findViewById(R.id.hotelDatesNextButton).setOnClickListener(view -> {
            boolean valid = true;
            if (checkInMillis < 0) {
                showError(checkInError, R.string.error_check_in_required);
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
                fetchAvailability(v);
            }
        });
    }

    /** Checks real room availability on the shared backend, then advances to Hotel Rooms. */
    private void fetchAvailability(View datesView) {
        if (isLoadingAvailability) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }

        Button nextButton = datesView.findViewById(R.id.hotelDatesNextButton);
        isLoadingAvailability = true;
        nextButton.setEnabled(false);
        nextButton.setText(R.string.button_checking_availability);

        String checkInStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(checkInMillis));
        String checkOutStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(checkOutMillis));

        ApiClient.bookingApi().checkAvailability("Bearer " + token, checkInStr, checkOutStr, adults, children)
                .enqueue(new Callback<AvailabilityResponse>() {
                    @Override
                    public void onResponse(Call<AvailabilityResponse> call, Response<AvailabilityResponse> response) {
                        isLoadingAvailability = false;
                        nextButton.setEnabled(true);
                        nextButton.setText(R.string.button_next);

                        if (response.isSuccessful() && response.body() != null) {
                            availableRoomTypes = response.body().room_types != null
                                    ? response.body().room_types : new ArrayList<>();
                            roomQuantities.clear();
                            for (RoomTypeAvailability type : availableRoomTypes) {
                                roomQuantities.put(type.group_id, 0);
                            }
                            showScreen(SCREEN_ROOMS);
                        } else if (response.code() == 401) {
                            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(activity, R.string.toast_availability_check_failed, Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<AvailabilityResponse> call, Throwable t) {
                        isLoadingAvailability = false;
                        nextButton.setEnabled(true);
                        nextButton.setText(R.string.button_next);
                        Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
                    }
                });
    }

    // ---- Screen 2: Hotel Rooms --------------------------------------------

    private void bindRooms(View v) {
        bindHeader(v, R.id.hotelRoomsHeaderBar, R.string.hotel_rooms_title, () -> showScreen(SCREEN_DATES));

        LinearLayout container = v.findViewById(R.id.hotelRoomsContainer);
        container.removeAllViews();
        View emptyState = v.findViewById(R.id.hotelRoomsEmptyState);
        emptyState.setVisibility(availableRoomTypes.isEmpty() ? View.VISIBLE : View.GONE);
        container.setVisibility(availableRoomTypes.isEmpty() ? View.GONE : View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(activity);
        String lastCategory = null;
        for (RoomTypeAvailability room : availableRoomTypes) {
            if (!room.category_name.equals(lastCategory)) {
                TextView header = (TextView) inflater.inflate(
                        R.layout.view_hotel_food_category_header, container, false);
                header.setText(room.category_name);
                container.addView(header);
                lastCategory = room.category_name;
            }

            View card = inflater.inflate(R.layout.view_hotel_stepper_item, container, false);
            loadImage(card.findViewById(R.id.stepperItemImage), room.image_path, R.drawable.ic_bed);
            ((TextView) card.findViewById(R.id.stepperItemName)).setText(room.variant_name);
            TextView description = card.findViewById(R.id.stepperItemDescription);
            if (room.description != null && !room.description.trim().isEmpty()) {
                description.setText(room.description);
                description.setVisibility(View.VISIBLE);
            } else {
                description.setVisibility(View.GONE);
            }
            TextView meta = card.findViewById(R.id.stepperItemMeta);
            meta.setText(activity.getString(R.string.format_capacity, room.capacity));
            meta.setVisibility(View.VISIBLE);
            int pricePerNight = (int) Math.round(room.price_per_night);
            ((TextView) card.findViewById(R.id.stepperItemPrice))
                    .setText(activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)));

            TextView qtyValue = card.findViewById(R.id.stepperItemQtyValue);
            ImageView minus = card.findViewById(R.id.stepperItemMinus);
            ImageView plus = card.findViewById(R.id.stepperItemPlus);
            qtyValue.setText(String.valueOf(roomQuantities.get(room.group_id)));
            setStepperEnabled(plus, roomQuantities.get(room.group_id) < room.available_quantity);

            minus.setOnClickListener(view -> {
                int qty = roomQuantities.get(room.group_id);
                if (qty > 0) {
                    roomQuantities.put(room.group_id, qty - 1);
                    qtyValue.setText(String.valueOf(qty - 1));
                    setStepperEnabled(plus, qty - 1 < room.available_quantity);
                }
            });
            plus.setOnClickListener(view -> {
                int qty = roomQuantities.get(room.group_id);
                if (qty < room.available_quantity) {
                    roomQuantities.put(room.group_id, qty + 1);
                    qtyValue.setText(String.valueOf(qty + 1));
                    setStepperEnabled(plus, qty + 1 < room.available_quantity);
                }
            });

            container.addView(card);
        }

        v.findViewById(R.id.hotelRoomsNextButton).setOnClickListener(view -> {
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

    // ---- Screen 3: Review Your Selection -----------------------------------

    private void bindReview(View v) {
        bindHeader(v, R.id.hotelReviewHeaderBar, R.string.hotel_review_title, () -> showScreen(SCREEN_ROOMS));

        bindRow(v.findViewById(R.id.reviewRowCheckIn), R.string.label_stay_check_in, formatDate(checkInMillis));
        bindRow(v.findViewById(R.id.reviewRowCheckOut), R.string.label_stay_check_out, formatDate(checkOutMillis));
        bindRow(v.findViewById(R.id.reviewRowGuests), R.string.label_stay_guests, formatGuests());
        bindRow(v.findViewById(R.id.reviewRowNights), R.string.label_stay_nights, formatNights(nights()));

        LinearLayout container = v.findViewById(R.id.reviewRoomsContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        int nights = nights();
        for (RoomTypeAvailability room : availableRoomTypes) {
            int qty = roomQuantities.get(room.group_id);
            if (qty <= 0) {
                continue;
            }
            int pricePerNight = (int) Math.round(room.price_per_night);
            View row = inflater.inflate(R.layout.view_hotel_review_room_row, container, false);
            loadImage(row.findViewById(R.id.reviewRoomImage), room.image_path, R.drawable.ic_bed);
            ((TextView) row.findViewById(R.id.reviewRoomName)).setText(room.category_name + " · " + room.variant_name);
            ((TextView) row.findViewById(R.id.reviewRoomMeta))
                    .setText(activity.getString(R.string.format_capacity, room.capacity));
            ((TextView) row.findViewById(R.id.reviewRoomPrice))
                    .setText(activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)));
            ((TextView) row.findViewById(R.id.reviewRoomQtyNights)).setText(
                    activity.getString(R.string.format_quantity, qty) + " · " + formatNights(nights));
            int subtotal = pricePerNight * qty * nights;
            ((TextView) row.findViewById(R.id.reviewRoomSubtotal))
                    .setText(activity.getString(R.string.format_subtotal, formatMoney(subtotal)));
            container.addView(row);
        }

        EditText specialRequestField = v.findViewById(R.id.reviewSpecialRequestField);
        specialRequestField.setText(specialRequest);
        AuthUiUtils.afterTextChanged(specialRequestField,
                () -> specialRequest = specialRequestField.getText().toString());

        v.findViewById(R.id.hotelReviewNextButton).setOnClickListener(view -> fetchAmenities(v));
    }

    /** Loads real add-on amenities from the shared backend, then advances to Choose Amenity. */
    private void fetchAmenities(View reviewView) {
        if (isLoadingAmenities) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }

        Button nextButton = reviewView.findViewById(R.id.hotelReviewNextButton);
        isLoadingAmenities = true;
        nextButton.setEnabled(false);
        nextButton.setText(R.string.button_loading);

        ApiClient.bookingApi().getAmenities("Bearer " + token).enqueue(new Callback<AmenityListResponse>() {
            @Override
            public void onResponse(Call<AmenityListResponse> call, Response<AmenityListResponse> response) {
                isLoadingAmenities = false;
                nextButton.setEnabled(true);
                nextButton.setText(R.string.button_next);

                if (response.isSuccessful() && response.body() != null) {
                    availableAmenities = response.body().amenities != null
                            ? response.body().amenities : new ArrayList<>();
                    for (AmenityAvailability amenity : availableAmenities) {
                        amenityQuantities.putIfAbsent(amenity.id, 0);
                    }
                    showScreen(SCREEN_AMENITY);
                } else if (response.code() == 401) {
                    Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(activity, R.string.toast_amenities_load_failed, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<AmenityListResponse> call, Throwable t) {
                isLoadingAmenities = false;
                nextButton.setEnabled(true);
                nextButton.setText(R.string.button_next);
                Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---- Screen 4: Choose Amenity ------------------------------------------

    private void bindAmenity(View v) {
        bindHeader(v, R.id.hotelAmenityHeaderBar, R.string.hotel_amenity_title, () -> showScreen(SCREEN_REVIEW));

        LinearLayout container = v.findViewById(R.id.hotelAmenityContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (AmenityAvailability amenity : availableAmenities) {
            View card = inflater.inflate(R.layout.view_hotel_stepper_item, container, false);
            loadImage(card.findViewById(R.id.stepperItemImage), amenity.image_path, R.drawable.ic_star);
            ((TextView) card.findViewById(R.id.stepperItemName)).setText(amenity.name);
            card.findViewById(R.id.stepperItemDescription).setVisibility(View.GONE);
            int unitPrice = (int) Math.round(amenity.price);
            ((TextView) card.findViewById(R.id.stepperItemPrice))
                    .setText(activity.getString(R.string.format_currency, formatMoney(unitPrice)));

            TextView qtyValue = card.findViewById(R.id.stepperItemQtyValue);
            ImageView minus = card.findViewById(R.id.stepperItemMinus);
            ImageView plus = card.findViewById(R.id.stepperItemPlus);
            qtyValue.setText(String.valueOf(amenityQuantities.get(amenity.id)));
            setStepperEnabled(plus, amenityCanIncrement(amenity, amenityQuantities.get(amenity.id)));

            minus.setOnClickListener(view -> {
                int qty = amenityQuantities.get(amenity.id);
                if (qty > 0) {
                    amenityQuantities.put(amenity.id, qty - 1);
                    qtyValue.setText(String.valueOf(qty - 1));
                    setStepperEnabled(plus, amenityCanIncrement(amenity, qty - 1));
                }
            });
            plus.setOnClickListener(view -> {
                int qty = amenityQuantities.get(amenity.id);
                if (amenityCanIncrement(amenity, qty)) {
                    amenityQuantities.put(amenity.id, qty + 1);
                    qtyValue.setText(String.valueOf(qty + 1));
                    setStepperEnabled(plus, amenityCanIncrement(amenity, qty + 1));
                }
            });

            container.addView(card);
        }

        v.findViewById(R.id.hotelAmenitySkipButton).setOnClickListener(view -> fetchFood(v));
        v.findViewById(R.id.hotelAmenityNextButton).setOnClickListener(view -> {
            if (totalFromMap(amenityQuantities) == 0) {
                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle(R.string.dialog_no_amenity_title)
                        .setMessage(R.string.dialog_no_amenity_message)
                        .setPositiveButton(R.string.button_ok, null)
                        .create();
                ThemeManager.applyGlassEffect(dialog.getWindow());
                dialog.show();
                return;
            }
            fetchFood(v);
        });
    }

    private boolean amenityCanIncrement(AmenityAvailability amenity, int currentQty) {
        return amenity.unlimited || amenity.available_quantity == null || currentQty < amenity.available_quantity;
    }

    /** Loads the real food/beverage menu from the shared backend, then advances to Order Food. */
    private void fetchFood(View amenityView) {
        if (isLoadingFood) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }

        Button nextButton = amenityView.findViewById(R.id.hotelAmenityNextButton);
        Button skipButton = amenityView.findViewById(R.id.hotelAmenitySkipButton);
        isLoadingFood = true;
        nextButton.setEnabled(false);
        skipButton.setEnabled(false);
        nextButton.setText(R.string.button_loading);

        ApiClient.bookingApi().getFood("Bearer " + token).enqueue(new Callback<FoodListResponse>() {
            @Override
            public void onResponse(Call<FoodListResponse> call, Response<FoodListResponse> response) {
                isLoadingFood = false;
                nextButton.setEnabled(true);
                skipButton.setEnabled(true);
                nextButton.setText(R.string.button_next);

                if (response.isSuccessful() && response.body() != null) {
                    availableFood = response.body().menu != null ? response.body().menu : new ArrayList<>();
                    for (MenuItemAvailability food : availableFood) {
                        foodQuantities.putIfAbsent(food.id, 0);
                    }
                    showScreen(SCREEN_FOOD);
                } else if (response.code() == 401) {
                    Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(activity, R.string.toast_food_load_failed, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<FoodListResponse> call, Throwable t) {
                isLoadingFood = false;
                nextButton.setEnabled(true);
                skipButton.setEnabled(true);
                nextButton.setText(R.string.button_next);
                Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---- Screen 5: Order Food ----------------------------------------------

    private void bindFood(View v) {
        bindHeader(v, R.id.hotelFoodHeaderBar, R.string.hotel_food_title, () -> showScreen(SCREEN_AMENITY));

        LinearLayout container = v.findViewById(R.id.hotelFoodContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        String lastCategory = null;
        for (MenuItemAvailability food : availableFood) {
            if (!food.category_name.equals(lastCategory)) {
                TextView header = (TextView) inflater.inflate(
                        R.layout.view_hotel_food_category_header, container, false);
                header.setText(food.category_name);
                container.addView(header);
                lastCategory = food.category_name;
            }

            View card = inflater.inflate(R.layout.view_hotel_stepper_item, container, false);
            loadImage(card.findViewById(R.id.stepperItemImage), food.image_path, R.drawable.ic_food);
            ((TextView) card.findViewById(R.id.stepperItemName)).setText(food.name);
            TextView description = card.findViewById(R.id.stepperItemDescription);
            if (food.description != null && !food.description.trim().isEmpty()) {
                description.setText(food.description);
                description.setVisibility(View.VISIBLE);
            } else {
                description.setVisibility(View.GONE);
            }
            int unitPrice = (int) Math.round(food.price);
            ((TextView) card.findViewById(R.id.stepperItemPrice))
                    .setText(activity.getString(R.string.format_currency, formatMoney(unitPrice)));

            TextView qtyValue = card.findViewById(R.id.stepperItemQtyValue);
            qtyValue.setText(String.valueOf(foodQuantities.get(food.id)));

            card.findViewById(R.id.stepperItemMinus).setOnClickListener(view -> {
                int qty = foodQuantities.get(food.id);
                if (qty > 0) {
                    foodQuantities.put(food.id, qty - 1);
                    qtyValue.setText(String.valueOf(qty - 1));
                }
            });
            card.findViewById(R.id.stepperItemPlus).setOnClickListener(view -> {
                int qty = foodQuantities.get(food.id);
                foodQuantities.put(food.id, qty + 1);
                qtyValue.setText(String.valueOf(qty + 1));
            });

            container.addView(card);
        }

        v.findViewById(R.id.hotelFoodSkipButton).setOnClickListener(view -> showScreen(SCREEN_BILLING));
        v.findViewById(R.id.hotelFoodNextButton).setOnClickListener(view -> {
            if (totalFromMap(foodQuantities) == 0) {
                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle(R.string.dialog_no_food_title)
                        .setMessage(R.string.dialog_no_food_message)
                        .setPositiveButton(R.string.button_ok, null)
                        .create();
                ThemeManager.applyGlassEffect(dialog.getWindow());
                dialog.show();
                return;
            }
            showScreen(SCREEN_BILLING);
        });
    }

    // ---- Screen 6: Billing Summary ------------------------------------------

    private void bindBilling(View v) {
        bindHeader(v, R.id.hotelBillingHeaderBar, R.string.hotel_billing_title, () -> showScreen(SCREEN_FOOD));

        bindRow(v.findViewById(R.id.billingRowFullName), R.string.label_full_name, "Adrianne N. Romero");
        bindRow(v.findViewById(R.id.billingRowEmail), R.string.label_email_address, "adrianneromero2005@gmail.com");
        bindRow(v.findViewById(R.id.billingRowMobile), R.string.label_mobile_number, "0912345678");
        bindRow(v.findViewById(R.id.billingRowAddress), R.string.label_address, "Koronadal City, South Cotabato");

        int nights = nights();
        bindRow(v.findViewById(R.id.billingRowCheckIn), R.string.label_stay_check_in, formatDate(checkInMillis));
        bindRow(v.findViewById(R.id.billingRowCheckOut), R.string.label_stay_check_out, formatDate(checkOutMillis));
        bindRow(v.findViewById(R.id.billingRowNights), R.string.label_number_of_nights, String.valueOf(nights));
        bindRow(v.findViewById(R.id.billingRowAdults), R.string.label_billing_adults, String.valueOf(adults));
        bindRow(v.findViewById(R.id.billingRowChildren), R.string.label_billing_children, String.valueOf(children));
        bindRow(v.findViewById(R.id.billingRowTotalGuests), R.string.label_total_guests,
                String.valueOf(adults + children));

        LinearLayout accommodationContainer = v.findViewById(R.id.billingAccommodationContainer);
        accommodationContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        int roomTotal = 0;
        for (RoomTypeAvailability room : availableRoomTypes) {
            int qty = roomQuantities.get(room.group_id);
            if (qty <= 0) {
                continue;
            }
            int pricePerNight = (int) Math.round(room.price_per_night);
            int subtotal = pricePerNight * qty * nights;
            roomTotal += subtotal;
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, accommodationContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(room.category_name + " · " + room.variant_name);
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText(activity.getString(R.string.format_capacity, room.capacity) + " · Qty " + qty + " · "
                    + activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight))
                    + " × " + nights);
            meta.setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(subtotal));
            accommodationContainer.addView(row);
        }

        View amenitiesCard = v.findViewById(R.id.billingAmenitiesCard);
        LinearLayout amenitiesContainer = v.findViewById(R.id.billingAmenitiesContainer);
        amenitiesContainer.removeAllViews();
        int amenitiesTotal = 0;
        for (AmenityAvailability amenity : availableAmenities) {
            int qty = amenityQuantities.get(amenity.id);
            if (qty <= 0) {
                continue;
            }
            int unitPrice = (int) Math.round(amenity.price);
            int subtotal = unitPrice * qty;
            amenitiesTotal += subtotal;
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, amenitiesContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(amenity.name);
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText("Qty " + qty + " · " + formatCurrency(unitPrice));
            meta.setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(subtotal));
            amenitiesContainer.addView(row);
        }
        amenitiesCard.setVisibility(amenitiesTotal > 0 ? View.VISIBLE : View.GONE);

        View foodCard = v.findViewById(R.id.billingFoodCard);
        LinearLayout foodContainer = v.findViewById(R.id.billingFoodContainer);
        foodContainer.removeAllViews();
        int foodTotal = 0;
        for (MenuItemAvailability food : availableFood) {
            int qty = foodQuantities.get(food.id);
            if (qty <= 0) {
                continue;
            }
            int unitPrice = (int) Math.round(food.price);
            int subtotal = unitPrice * qty;
            foodTotal += subtotal;
            View row = inflater.inflate(R.layout.view_hotel_billing_line_row, foodContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(food.name);
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText("Qty " + qty + " · " + formatCurrency(unitPrice));
            meta.setVisibility(View.VISIBLE);
            ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(subtotal));
            foodContainer.addView(row);
        }
        foodCard.setVisibility(foodTotal > 0 ? View.VISIBLE : View.GONE);

        bindLine(v.findViewById(R.id.billingRowRoomCharges), R.string.label_room_charges, roomTotal);
        bindLine(v.findViewById(R.id.billingRowAmenitiesTotal), R.string.label_amenities_total, amenitiesTotal);
        bindLine(v.findViewById(R.id.billingRowFoodTotal), R.string.label_food_total, foodTotal);

        int grandTotal = roomTotal + amenitiesTotal + foodTotal;
        ((TextView) v.findViewById(R.id.billingGrandTotal)).setText(formatCurrency(grandTotal));

        View fullCard = v.findViewById(R.id.paymentOptionFullCard);
        View partialCard = v.findViewById(R.id.paymentOptionPartialCard);
        ((TextView) v.findViewById(R.id.paymentOptionFullPrice)).setText(formatCurrency(grandTotal));
        int partialAmount = computePartialAmount(grandTotal);
        ((TextView) v.findViewById(R.id.paymentOptionPartialPrice)).setText(formatCurrency(partialAmount));
        ((TextView) v.findViewById(R.id.paymentOptionPartialRemaining)).setText(
                activity.getString(R.string.label_remaining_balance) + ": "
                        + formatCurrency(grandTotal - partialAmount));
        updatePaymentOptionCards(fullCard, partialCard);
        fullCard.setOnClickListener(view -> {
            paymentOption = PAYMENT_FULL;
            updatePaymentOptionCards(fullCard, partialCard);
        });
        partialCard.setOnClickListener(view -> {
            paymentOption = PAYMENT_PARTIAL;
            updatePaymentOptionCards(fullCard, partialCard);
        });

        CheckBox termsBox = v.findViewById(R.id.checkboxTerms);
        CheckBox cancellationBox = v.findViewById(R.id.checkboxCancellation);
        termsBox.setChecked(termsChecked);
        cancellationBox.setChecked(cancellationChecked);
        termsBox.setOnCheckedChangeListener((button, checked) -> termsChecked = checked);
        cancellationBox.setOnCheckedChangeListener((button, checked) -> cancellationChecked = checked);

        v.findViewById(R.id.hotelBillingProceedButton).setOnClickListener(view -> {
            if (paymentOption == PAYMENT_NONE) {
                Toast.makeText(activity, R.string.toast_select_payment_option, Toast.LENGTH_LONG).show();
                return;
            }
            if (!termsChecked || !cancellationChecked) {
                Toast.makeText(activity, R.string.toast_agree_terms_required, Toast.LENGTH_LONG).show();
                return;
            }
            showScreen(SCREEN_PAYMENT);
        });
    }

    private void updatePaymentOptionCards(View fullCard, View partialCard) {
        fullCard.setBackgroundResource(paymentOption == PAYMENT_FULL
                ? R.drawable.bg_selectable_card_selected : R.drawable.bg_selectable_card);
        partialCard.setBackgroundResource(paymentOption == PAYMENT_PARTIAL
                ? R.drawable.bg_selectable_card_selected : R.drawable.bg_selectable_card);
    }

    // ---- Screen 7: Payment ---------------------------------------------------

    private void bindPayment(View v) {
        bindHeader(v, R.id.hotelPaymentHeaderBar, R.string.hotel_payment_title, () -> showScreen(SCREEN_BILLING));

        int grandTotal = computeGrandTotal();
        int amountDue = paymentOption == PAYMENT_PARTIAL ? computePartialAmount(grandTotal) : grandTotal;
        ((TextView) v.findViewById(R.id.paymentAmountDue)).setText(formatCurrency(amountDue));

        EditText amountPaidField = v.findViewById(R.id.paymentAmountPaidField);
        EditText referenceField = v.findViewById(R.id.paymentReferenceField);
        amountPaidField.setText(amountPaidText);
        referenceField.setText(referenceNumberText);
        AuthUiUtils.afterTextChanged(amountPaidField, () -> amountPaidText = amountPaidField.getText().toString());
        AuthUiUtils.afterTextChanged(referenceField, () -> referenceNumberText = referenceField.getText().toString());

        v.findViewById(R.id.paymentScreenshotDropzone).setOnClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            activity.startActivityForResult(intent, REQUEST_PAYMENT_SCREENSHOT);
        });
        if (screenshotUri != null) {
            showScreenshotPreview(v);
        }

        v.findViewById(R.id.hotelPaymentCompleteButton).setOnClickListener(view -> {
            if (amountPaidText.trim().isEmpty()) {
                Toast.makeText(activity, R.string.toast_amount_paid_required, Toast.LENGTH_LONG).show();
                return;
            }
            if (referenceNumberText.trim().isEmpty()) {
                Toast.makeText(activity, R.string.toast_reference_number_required, Toast.LENGTH_LONG).show();
                return;
            }
            if (screenshotUri == null) {
                Toast.makeText(activity, R.string.toast_screenshot_required, Toast.LENGTH_LONG).show();
                return;
            }
            submitReservation(v);
        });
    }

    /**
     * Creates the real reservation on the shared backend (same tables/models the
     * website's own booking wizard writes to), so it shows up in the receptionist's
     * Booking List immediately — no separate sync step, just one database.
     */
    private void submitReservation(View paymentView) {
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }

        RequestBody proofBody;
        try {
            proofBody = readImagePart(screenshotUri);
        } catch (IOException e) {
            Toast.makeText(activity, R.string.toast_screenshot_required, Toast.LENGTH_LONG).show();
            return;
        }
        if (proofBody == null) {
            Toast.makeText(activity, R.string.toast_screenshot_required, Toast.LENGTH_LONG).show();
            return;
        }

        Button completeButton = paymentView.findViewById(R.id.hotelPaymentCompleteButton);
        completeButton.setEnabled(false);

        MultipartBody.Part proofPart = MultipartBody.Part.createFormData("proof_image", "payment_proof.jpg", proofBody);
        String checkInStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(checkInMillis));
        String checkOutStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(checkOutMillis));
        String paymentOptionValue = paymentOption == PAYMENT_PARTIAL ? "partial" : "full";

        Map<String, RequestBody> quantities = new LinkedHashMap<>();
        putQuantityParts(quantities, "room_quantities", roomQuantities);
        putQuantityParts(quantities, "amenity_quantities", amenityQuantities);
        putQuantityParts(quantities, "menu_quantities", foodQuantities);

        ApiClient.bookingApi().reserve(
                "Bearer " + token,
                textPart(checkInStr),
                textPart(checkOutStr),
                textPart(String.valueOf(adults)),
                textPart(String.valueOf(children)),
                textPart(specialRequest),
                textPart(paymentOptionValue),
                textPart(referenceNumberText),
                proofPart,
                quantities
        ).enqueue(new Callback<ReserveResponse>() {
            @Override
            public void onResponse(Call<ReserveResponse> call, Response<ReserveResponse> response) {
                completeButton.setEnabled(true);
                if (response.isSuccessful() && response.body() != null) {
                    bookingReference = response.body().booking_reference;
                    showScreen(SCREEN_SUCCESS);
                } else if (response.code() == 401) {
                    Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                } else {
                    ErrorResponse error = ApiClient.parseError(response.errorBody());
                    String message = error.firstMessage() != null
                            ? error.firstMessage() : activity.getString(R.string.toast_booking_failed);
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<ReserveResponse> call, Throwable t) {
                completeButton.setEnabled(true);
                Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void putQuantityParts(Map<String, RequestBody> target, String fieldName, Map<Integer, Integer> quantities) {
        for (Map.Entry<Integer, Integer> entry : quantities.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                target.put(fieldName + "[" + entry.getKey() + "]", textPart(String.valueOf(entry.getValue())));
            }
        }
    }

    private static RequestBody textPart(String value) {
        return RequestBody.create(value != null ? value : "", MediaType.parse("text/plain"));
    }

    /** Reads the picked gallery screenshot into an upload-ready request body. */
    private RequestBody readImagePart(Uri uri) throws IOException {
        if (uri == null) {
            return null;
        }
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            String mime = activity.getContentResolver().getType(uri);
            return RequestBody.create(out.toByteArray(), MediaType.parse(mime != null ? mime : "image/jpeg"));
        }
    }

    private void showScreenshotPreview(View screenView) {
        ImageView preview = screenView.findViewById(R.id.paymentScreenshotPreview);
        View hint = screenView.findViewById(R.id.paymentScreenshotHint);
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(activity.getContentResolver(), screenshotUri);
            preview.setImageBitmap(bitmap);
            preview.setVisibility(View.VISIBLE);
            hint.setVisibility(View.GONE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ---- Screen 8: Booking Success --------------------------------------------

    private void bindSuccess(View v) {
        ((TextView) v.findViewById(R.id.successBookingReference)).setText(bookingReference);
        v.findViewById(R.id.successViewBookingButton).setOnClickListener(view ->
                Toast.makeText(activity, R.string.toast_view_booking_placeholder, Toast.LENGTH_LONG).show());
        v.findViewById(R.id.successBackToHomeButton).setOnClickListener(view -> {
            resetFlow();
            onBackToHome.run();
        });
    }

    private void resetFlow() {
        checkInMillis = -1;
        checkOutMillis = -1;
        adults = 1;
        children = 0;
        availableRoomTypes = new ArrayList<>();
        roomQuantities.clear();
        specialRequest = "";
        availableAmenities = new ArrayList<>();
        amenityQuantities.clear();
        availableFood = new ArrayList<>();
        foodQuantities.clear();
        paymentOption = PAYMENT_NONE;
        termsChecked = false;
        cancellationChecked = false;
        amountPaidText = "";
        referenceNumberText = "";
        screenshotUri = null;
        bookingReference = "";
        showScreen(SCREEN_DATES);
    }

    // ---- Shared helpers --------------------------------------------------

    private void bindHeader(View v, int headerBarId, int titleRes, Runnable onBack) {
        View header = v.findViewById(headerBarId);
        ((TextView) header.findViewById(R.id.headerTitle)).setText(titleRes);
        header.findViewById(R.id.headerBackButton).setOnClickListener(view -> onBack.run());
    }

    private void bindRow(View row, int labelRes, String value) {
        ((TextView) row.findViewById(R.id.rowLabel)).setText(labelRes);
        ((TextView) row.findViewById(R.id.rowValue)).setText(value);
    }

    private void bindLine(View row, int nameRes, int amount) {
        ((TextView) row.findViewById(R.id.lineName)).setText(nameRes);
        ((TextView) row.findViewById(R.id.linePrice)).setText(formatCurrency(amount));
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
        return totalFromMap(roomQuantities);
    }

    private int totalFromMap(Map<?, Integer> map) {
        int total = 0;
        for (int qty : map.values()) {
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

    private String formatGuests() {
        String guests = adults + (adults == 1 ? " Adult" : " Adults");
        if (children > 0) {
            guests += ", " + children + (children == 1 ? " Child" : " Children");
        }
        return guests;
    }

    private String formatNights(int nights) {
        return nights + (nights == 1 ? " night" : " nights");
    }

    private int computeGrandTotal() {
        int nights = nights();
        int total = 0;
        for (RoomTypeAvailability room : availableRoomTypes) {
            total += (int) Math.round(room.price_per_night) * roomQuantities.get(room.group_id) * nights;
        }
        for (AmenityAvailability amenity : availableAmenities) {
            total += (int) Math.round(amenity.price) * amenityQuantities.get(amenity.id);
        }
        for (MenuItemAvailability food : availableFood) {
            total += (int) Math.round(food.price) * foodQuantities.get(food.id);
        }
        return total;
    }

    /** Loads a room/amenity/food thumbnail from the backend, falling back to a placeholder icon. */
    private void loadImage(ImageView imageView, String imagePath, int placeholderRes) {
        String url = ApiClient.imageUrl(imagePath);
        if (url == null) {
            imageView.setImageResource(placeholderRes);
            return;
        }
        Glide.with(activity)
                .load(url)
                .placeholder(placeholderRes)
                .error(placeholderRes)
                .centerCrop()
                .into(imageView);
    }

    private int computePartialAmount(int grandTotal) {
        return (int) Math.round(grandTotal * PARTIAL_PAYMENT_RATE);
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

}
