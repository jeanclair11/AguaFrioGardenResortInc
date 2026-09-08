package com.aguafriogarden.resortinc;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aguafriogarden.resortinc.network.AmenityAvailability;
import com.aguafriogarden.resortinc.network.AmenityListResponse;
import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.AvailabilityResponse;
import com.aguafriogarden.resortinc.network.CottageKtvListResponse;
import com.aguafriogarden.resortinc.network.CottageKtvOption;
import com.aguafriogarden.resortinc.network.ErrorResponse;
import com.aguafriogarden.resortinc.network.FoodListResponse;
import com.aguafriogarden.resortinc.network.MenuItemAvailability;
import com.aguafriogarden.resortinc.network.ReserveResponse;
import com.aguafriogarden.resortinc.network.RoomListResponse;
import com.aguafriogarden.resortinc.network.RoomSummary;
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
    private static final int SCREEN_REVIEW = 2;
    private static final int SCREEN_AMENITY = 3;
    private static final int SCREEN_FOOD = 4;
    private static final int SCREEN_BILLING = 5;
    private static final int SCREEN_PAYMENT = 6;
    private static final int SCREEN_SUCCESS = 7;
    private static final int SCREEN_MY_BOOKINGS = 8;
    private static final int SCREEN_BOOKING_DETAILS = 9;

    private static final int PAYMENT_NONE = 0;
    private static final int PAYMENT_FULL = 1;
    private static final int PAYMENT_PARTIAL = 2;

    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;
    private static final double PARTIAL_PAYMENT_RATE = 0.30;
    private static final int REQUEST_PAYMENT_SCREENSHOT = 4101;

    // Tabs: Hotel Rooms / Cottage / KTV each get their own field group and card style below
    // (see #updateCategoryTabs / #renderAvailabilityResults).
    private static final int TAB_HOTEL_ROOMS = 0;
    private static final int TAB_COTTAGE = 1;
    private static final int TAB_KTV = 2;

    // Client-side placeholder durations for the KTV rate types, matching the Reserve tab's
    // ReservationFlowController — no backend field for this exists yet.
    private static final int KTV_REGULAR_HOURS = 5;
    private static final int KTV_CONSUMABLE_HOURS = 3;

    private static final int PANEL_ANIMATION_MS = 220;
    private static final float PANEL_TAP_SLOP_PX = 16f;

    private final Activity activity;
    private final Runnable onBookNowOtherCategory;
    private final Runnable onBackToHome;
    private final FrameLayout root;

    private int currentScreen = -1;
    private View currentScreenView;

    // ---- Search panel drag/collapse state (UI-only, reset each time the Dates screen binds) --
    private int panelCollapseDistance = 0;
    private float panelFraction = 0f; // 0 = fully expanded, 1 = fully collapsed
    private ValueAnimator panelAnimator;

    // ---- Persisted answers (survive navigating away and back) -----------
    private long checkInMillis = -1;
    private long checkOutMillis = -1;
    private int adults = 0;
    private int children = 0;
    private int selectedTab = TAB_HOTEL_ROOMS;

    // ---- Rate type / date / time / guests (Cottage tab) ------------------
    private String cottageRateType = "";
    private long cottageDateMillis = -1;
    private int cottageTimeHour = -1;
    private int cottageTimeMinute = -1;
    private int cottageAdults = 1;
    private int cottageChildren = 0;

    // ---- Rate type / date / time / guests (KTV tab) -----------------------
    private String ktvRateType = "";
    private long ktvDateMillis = -1;
    private int ktvStartHour = -1;
    private int ktvStartMinute = -1;
    private int ktvGuestCount = 1;

    private boolean hasSearchedHotelRooms = false;
    private boolean availabilityErrored = false;
    private List<RoomSummary> defaultRooms = new ArrayList<>();
    private boolean isLoadingDefaultRooms = false;
    private boolean defaultRoomsLoaded = false;
    private List<CottageKtvOption> cottageItems = new ArrayList<>();
    private List<CottageKtvOption> ktvItems = new ArrayList<>();
    private boolean isLoadingCottageKtv = false;
    private boolean cottageKtvLoaded = false;
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

    // ---- View My Bookings (mock, see BookingStore) -------------------------
    private int myBookingsTab = BookingStore.TAB_CURRENT;
    private String myBookingDetailsReference = "";

    HotelBookingFlowController(Activity activity, Runnable onBookNowOtherCategory, Runnable onBackToHome) {
        this.activity = activity;
        this.onBookNowOtherCategory = onBookNowOtherCategory;
        this.onBackToHome = onBackToHome;
        root = new FrameLayout(activity);
        showScreen(SCREEN_DATES);
    }

    View getRootView() {
        return root;
    }

    /** Steps back one screen at a time; Dates is the Book tab's root screen
     *  (nothing to step back to), so it's left unhandled like every other
     *  tab root; Success exits the flow back to Home. */
    boolean handleBackPressed() {
        switch (currentScreen) {
            case SCREEN_DATES:
                return false;
            case SCREEN_REVIEW:
                showScreen(SCREEN_DATES);
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
            case SCREEN_MY_BOOKINGS:
                showScreen(SCREEN_DATES);
                return true;
            case SCREEN_BOOKING_DETAILS:
                showScreen(SCREEN_MY_BOOKINGS);
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
            case SCREEN_MY_BOOKINGS:
                return R.layout.view_my_bookings_list;
            case SCREEN_BOOKING_DETAILS:
                return R.layout.view_my_booking_details;
            default:
                return R.layout.view_hotel_dates;
        }
    }

    private void bindScreen(int screen, View v) {
        switch (screen) {
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
            case SCREEN_MY_BOOKINGS:
                bindMyBookingsList(v);
                break;
            case SCREEN_BOOKING_DETAILS:
                bindBookingDetails(v);
                break;
            default:
                bindDates(v);
                break;
        }
    }

    // ---- Screen 1: Book Your Experience (search + browse) -----------------

    private void bindDates(View v) {
        View checkIn = v.findViewById(R.id.hotelCheckInField);
        View checkOut = v.findViewById(R.id.hotelCheckOutField);
        TextView checkInDateText = v.findViewById(R.id.hotelCheckInDateText);
        TextView checkInDayText = v.findViewById(R.id.hotelCheckInDayText);
        TextView checkOutDateText = v.findViewById(R.id.hotelCheckOutDateText);
        TextView checkOutDayText = v.findViewById(R.id.hotelCheckOutDayText);
        TextView checkInError = v.findViewById(R.id.hotelCheckInError);
        TextView checkOutError = v.findViewById(R.id.hotelCheckOutError);
        EditText adultsValue = v.findViewById(R.id.hotelAdultsValue);
        EditText childrenValue = v.findViewById(R.id.hotelChildrenValue);
        View tabRooms = v.findViewById(R.id.hotelTabRooms);
        View tabCottage = v.findViewById(R.id.hotelTabCottage);
        View tabKtv = v.findViewById(R.id.hotelTabKtv);

        updateDateField(checkInDateText, checkInDayText, checkInMillis);
        updateDateField(checkOutDateText, checkOutDayText, checkOutMillis);
        adultsValue.setText(String.valueOf(adults));
        childrenValue.setText(String.valueOf(children));

        View.OnClickListener openDatePicker = view -> GlassDatePicker.showRangePicker(activity, checkInMillis, checkOutMillis, System.currentTimeMillis() - 1000L, (start, end) -> {
            checkInMillis = start;
            checkOutMillis = end;
            updateDateField(checkInDateText, checkInDayText, checkInMillis);
            updateDateField(checkOutDateText, checkOutDayText, checkOutMillis);
            checkInError.setVisibility(View.GONE);
            checkOutError.setVisibility(View.GONE);
        });
        checkIn.setOnClickListener(openDatePicker);
        checkOut.setOnClickListener(openDatePicker);

        v.findViewById(R.id.hotelAdultsMinus).setOnClickListener(view -> {
            if (adults > 0) {
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

        AuthUiUtils.afterTextChanged(adultsValue, () -> {
            adults = parseGuestCount(adultsValue.getText().toString());
            renderAvailabilityResults(v);
        });
        adultsValue.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                adultsValue.setText(String.valueOf(adults));
            }
        });
        AuthUiUtils.afterTextChanged(childrenValue, () -> {
            children = parseGuestCount(childrenValue.getText().toString());
            renderAvailabilityResults(v);
        });
        childrenValue.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) {
                childrenValue.setText(String.valueOf(children));
            }
        });

        v.findViewById(R.id.hotelMyBookingsLink).setOnClickListener(view -> showScreen(SCREEN_MY_BOOKINGS));

        bindCottageFields(v);
        bindKtvFields(v);

        updateCategoryTabs(v, tabRooms, tabCottage, tabKtv);

        tabRooms.setOnClickListener(view -> {
            if (selectedTab != TAB_HOTEL_ROOMS) {
                selectedTab = TAB_HOTEL_ROOMS;
                updateCategoryTabs(v, tabRooms, tabCottage, tabKtv);
                renderAvailabilityResults(v);
            }
        });
        tabCottage.setOnClickListener(view -> {
            if (selectedTab != TAB_COTTAGE) {
                selectedTab = TAB_COTTAGE;
                updateCategoryTabs(v, tabRooms, tabCottage, tabKtv);
                renderAvailabilityResults(v);
            }
        });
        tabKtv.setOnClickListener(view -> {
            if (selectedTab != TAB_KTV) {
                selectedTab = TAB_KTV;
                updateCategoryTabs(v, tabRooms, tabCottage, tabKtv);
                renderAvailabilityResults(v);
            }
        });

        v.findViewById(R.id.hotelDatesNextButton).setOnClickListener(view -> {
            if (selectedTab == TAB_HOTEL_ROOMS) {
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
                if (adults + children < 1) {
                    Toast.makeText(activity, R.string.toast_guests_required, Toast.LENGTH_LONG).show();
                    valid = false;
                }
                if (valid) {
                    runAvailabilitySearch(v);
                }
            } else if (selectedTab == TAB_COTTAGE) {
                if (validateCottageFields(v)) {
                    animatePanelTo(v, 1f);
                }
            } else {
                if (validateKtvFields(v)) {
                    animatePanelTo(v, 1f);
                }
            }
        });

        v.findViewById(R.id.hotelResultsChangeDatesButton).setOnClickListener(view -> expandPanel(v));
        v.findViewById(R.id.hotelResultsTryAgainButton).setOnClickListener(view -> runAvailabilitySearch(v));

        v.findViewById(R.id.hotelKtvProceedButton).setOnClickListener(view -> {
            if (!validateKtvFields(v)) {
                expandPanel(v);
                return;
            }
            if (selectedKtvItem() == null) {
                Toast.makeText(activity, R.string.toast_select_ktv_room_required, Toast.LENGTH_LONG).show();
                expandPanel(v);
                return;
            }
            onBookNowOtherCategory.run();
        });

        setUpDragPanel(v);

        renderAvailabilityResults(v);
        fetchDefaultRooms(v);
        fetchCottagesKtv(v);
    }

    /** Binds the Cottage tab's own rate type / date / time / adults-children-total guest
     *  fields, independent from both the Hotel Rooms and KTV field groups. */
    private void bindCottageFields(View v) {
        View cottageDateField = v.findViewById(R.id.hotelCottageDateField);
        TextView cottageDateText = v.findViewById(R.id.hotelCottageDateText);
        TextView cottageDateError = v.findViewById(R.id.hotelCottageDateError);
        View cottageTimeField = v.findViewById(R.id.hotelCottageTimeField);
        TextView cottageTimeText = v.findViewById(R.id.hotelCottageTimeText);
        TextView cottageTimeError = v.findViewById(R.id.hotelCottageTimeError);
        RadioGroup rateGroup = v.findViewById(R.id.hotelCottageRateTypeGroup);
        TextView rateError = v.findViewById(R.id.hotelCottageRateTypeError);
        TextView adultsValue = v.findViewById(R.id.hotelCottageAdultsValue);
        TextView childrenValue = v.findViewById(R.id.hotelCottageChildrenValue);
        TextView totalGuestValue = v.findViewById(R.id.hotelCottageTotalGuestValue);

        updateCottageDateField(cottageDateText);
        updateCottageTimeField(cottageTimeText);
        adultsValue.setText(String.valueOf(cottageAdults));
        childrenValue.setText(String.valueOf(cottageChildren));
        totalGuestValue.setText(String.valueOf(cottageAdults + cottageChildren));
        if (ReservationCatalog.RATE_TYPE_DAY.equals(cottageRateType)) {
            rateGroup.check(R.id.hotelCottageRateDay);
        } else if (ReservationCatalog.RATE_TYPE_NIGHT.equals(cottageRateType)) {
            rateGroup.check(R.id.hotelCottageRateNight);
        } else {
            rateGroup.clearCheck();
        }

        rateGroup.setOnCheckedChangeListener((group, checkedId) -> {
            cottageRateType = checkedId == R.id.hotelCottageRateDay
                    ? ReservationCatalog.RATE_TYPE_DAY : ReservationCatalog.RATE_TYPE_NIGHT;
            rateError.setVisibility(View.GONE);
        });

        cottageDateField.setOnClickListener(view -> GlassDatePicker.showCalendarPicker(
                activity, cottageDateMillis, System.currentTimeMillis() - 1000L, millis -> {
                    cottageDateMillis = millis;
                    updateCottageDateField(cottageDateText);
                    cottageDateError.setVisibility(View.GONE);
                }));

        cottageTimeField.setOnClickListener(view -> {
            Calendar now = Calendar.getInstance();
            int initialHour = cottageTimeHour >= 0 ? cottageTimeHour : now.get(Calendar.HOUR_OF_DAY);
            int initialMinute = cottageTimeMinute >= 0 ? cottageTimeMinute : now.get(Calendar.MINUTE);
            TimePickerDialog dialog = new TimePickerDialog(activity, (picker, hour, minute) -> {
                cottageTimeHour = hour;
                cottageTimeMinute = minute;
                updateCottageTimeField(cottageTimeText);
                cottageTimeError.setVisibility(View.GONE);
            }, initialHour, initialMinute, false);
            ThemeManager.applyGlassEffect(dialog.getWindow());
            dialog.show();
        });

        v.findViewById(R.id.hotelCottageAdultsMinus).setOnClickListener(view -> {
            if (cottageAdults > 1) {
                cottageAdults--;
                adultsValue.setText(String.valueOf(cottageAdults));
                totalGuestValue.setText(String.valueOf(cottageAdults + cottageChildren));
            }
        });
        v.findViewById(R.id.hotelCottageAdultsPlus).setOnClickListener(view -> {
            cottageAdults++;
            adultsValue.setText(String.valueOf(cottageAdults));
            totalGuestValue.setText(String.valueOf(cottageAdults + cottageChildren));
        });
        v.findViewById(R.id.hotelCottageChildrenMinus).setOnClickListener(view -> {
            if (cottageChildren > 0) {
                cottageChildren--;
                childrenValue.setText(String.valueOf(cottageChildren));
                totalGuestValue.setText(String.valueOf(cottageAdults + cottageChildren));
            }
        });
        v.findViewById(R.id.hotelCottageChildrenPlus).setOnClickListener(view -> {
            cottageChildren++;
            childrenValue.setText(String.valueOf(cottageChildren));
            totalGuestValue.setText(String.valueOf(cottageAdults + cottageChildren));
        });
    }

    /** Binds the KTV tab's own rate type / date / start-end time / duration / guest count
     *  fields. End Time and Duration are read-only, recomputed whenever the rate type or
     *  start time changes (see {@link #updateKtvEndTimeAndDuration}). */
    private void bindKtvFields(View v) {
        View ktvDateField = v.findViewById(R.id.hotelKtvDateField);
        TextView ktvDateText = v.findViewById(R.id.hotelKtvDateText);
        TextView ktvDateError = v.findViewById(R.id.hotelKtvDateError);
        View ktvStartTimeField = v.findViewById(R.id.hotelKtvStartTimeField);
        TextView ktvStartTimeText = v.findViewById(R.id.hotelKtvStartTimeText);
        TextView ktvStartTimeError = v.findViewById(R.id.hotelKtvStartTimeError);
        RadioGroup rateGroup = v.findViewById(R.id.hotelKtvRateTypeGroup);
        TextView rateError = v.findViewById(R.id.hotelKtvRateTypeError);
        TextView guestCountValue = v.findViewById(R.id.hotelKtvGuestCountValue);

        updateKtvDateField(ktvDateText);
        updateKtvStartTimeField(ktvStartTimeText);
        updateKtvEndTimeAndDuration(v);
        guestCountValue.setText(String.valueOf(ktvGuestCount));
        if (ReservationCatalog.RATE_TYPE_REGULAR.equals(ktvRateType)) {
            rateGroup.check(R.id.hotelKtvRateRegular);
        } else if (ReservationCatalog.RATE_TYPE_CONSUMABLE.equals(ktvRateType)) {
            rateGroup.check(R.id.hotelKtvRateConsumable);
        } else {
            rateGroup.clearCheck();
        }

        rateGroup.setOnCheckedChangeListener((group, checkedId) -> {
            ktvRateType = checkedId == R.id.hotelKtvRateRegular
                    ? ReservationCatalog.RATE_TYPE_REGULAR : ReservationCatalog.RATE_TYPE_CONSUMABLE;
            rateError.setVisibility(View.GONE);
            updateKtvEndTimeAndDuration(v);
        });

        ktvDateField.setOnClickListener(view -> GlassDatePicker.showCalendarPicker(
                activity, ktvDateMillis, System.currentTimeMillis() - 1000L, millis -> {
                    ktvDateMillis = millis;
                    updateKtvDateField(ktvDateText);
                    ktvDateError.setVisibility(View.GONE);
                }));

        ktvStartTimeField.setOnClickListener(view -> {
            Calendar now = Calendar.getInstance();
            int initialHour = ktvStartHour >= 0 ? ktvStartHour : now.get(Calendar.HOUR_OF_DAY);
            int initialMinute = ktvStartMinute >= 0 ? ktvStartMinute : now.get(Calendar.MINUTE);
            TimePickerDialog dialog = new TimePickerDialog(activity, (picker, hour, minute) -> {
                ktvStartHour = hour;
                ktvStartMinute = minute;
                updateKtvStartTimeField(ktvStartTimeText);
                updateKtvEndTimeAndDuration(v);
                ktvStartTimeError.setVisibility(View.GONE);
            }, initialHour, initialMinute, false);
            ThemeManager.applyGlassEffect(dialog.getWindow());
            dialog.show();
        });

        v.findViewById(R.id.hotelKtvGuestCountMinus).setOnClickListener(view -> {
            if (ktvGuestCount > 1) {
                ktvGuestCount--;
                guestCountValue.setText(String.valueOf(ktvGuestCount));
            }
        });
        v.findViewById(R.id.hotelKtvGuestCountPlus).setOnClickListener(view -> {
            ktvGuestCount++;
            guestCountValue.setText(String.valueOf(ktvGuestCount));
        });
    }

    // ---- Search panel collapse/drag mechanics ------------------------------

    /**
     * Wires the collapsible search panel: measures its height once laid out, auto-collapses it
     * the first time the guest scrolls the results, and lets them drag (or tap) the handle to
     * bring it back. UI-only — doesn't touch any booking data or validation.
     */
    private void setUpDragPanel(View v) {
        View panelContent = v.findViewById(R.id.hotelSearchPanelContent);
        View dragHandle = v.findViewById(R.id.hotelDragHandle);
        ScrollView resultsScrollView = v.findViewById(R.id.hotelResultsScrollView);

        panelFraction = 0f;
        panelContent.post(() -> {
            panelCollapseDistance = panelContent.getHeight();
            setPanelFraction(v, panelFraction);
        });

        resultsScrollView.setOnScrollChangeListener((View.OnScrollChangeListener) (view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (scrollY > 24 && panelFraction < 1f && panelCollapseDistance > 0) {
                animatePanelTo(v, 1f);
            }
        });

        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            float startRawY;
            float startFraction;
            float totalMovement;

            @Override
            public boolean onTouch(View handleView, MotionEvent event) {
                if (panelCollapseDistance <= 0) {
                    return true;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        cancelPanelAnimator();
                        startRawY = event.getRawY();
                        startFraction = panelFraction;
                        totalMovement = 0f;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float deltaY = event.getRawY() - startRawY;
                        totalMovement = Math.max(totalMovement, Math.abs(deltaY));
                        float newFraction = startFraction - deltaY / panelCollapseDistance;
                        setPanelFraction(v, Math.max(0f, Math.min(1f, newFraction)));
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (totalMovement < PANEL_TAP_SLOP_PX) {
                            handleView.performClick();
                            animatePanelTo(v, startFraction < 0.5f ? 1f : 0f);
                        } else {
                            animatePanelTo(v, panelFraction < 0.5f ? 0f : 1f);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        animatePanelTo(v, panelFraction < 0.5f ? 0f : 1f);
                        return true;
                    default:
                        return false;
                }
            }
        });
        dragHandle.setOnClickListener(view -> { });
    }

    /** Applies the panel's translation and the results list's top padding for the given
     *  0 (expanded) .. 1 (collapsed) fraction, immediately (no animation). */
    private void setPanelFraction(View v, float fraction) {
        panelFraction = fraction;
        View dragSheet = v.findViewById(R.id.hotelDragSheet);
        View dragHandle = v.findViewById(R.id.hotelDragHandle);
        ScrollView resultsScrollView = v.findViewById(R.id.hotelResultsScrollView);

        dragSheet.setTranslationY(-fraction * panelCollapseDistance);
        resultsScrollView.setPadding(
                resultsScrollView.getPaddingLeft(),
                dragHandle.getHeight() + Math.round((1f - fraction) * panelCollapseDistance),
                resultsScrollView.getPaddingRight(),
                resultsScrollView.getPaddingBottom());
    }

    /** Animates the panel to the given fraction, cancelling any drag/animation already in flight. */
    private void animatePanelTo(View v, float target) {
        cancelPanelAnimator();
        if (panelCollapseDistance <= 0) {
            setPanelFraction(v, target);
            return;
        }
        panelAnimator = ValueAnimator.ofFloat(panelFraction, target);
        panelAnimator.setDuration(PANEL_ANIMATION_MS);
        panelAnimator.setInterpolator(new DecelerateInterpolator());
        panelAnimator.addUpdateListener(animation -> setPanelFraction(v, (float) animation.getAnimatedValue()));
        panelAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                panelAnimator = null;
            }
        });
        panelAnimator.start();
    }

    private void cancelPanelAnimator() {
        if (panelAnimator != null) {
            panelAnimator.cancel();
            panelAnimator = null;
        }
    }

    /** Expands the search panel (e.g. so the guest can see/edit dates again after it auto-collapsed). */
    private void expandPanel(View v) {
        animatePanelTo(v, 0f);
    }

    /** Runs the search for whichever category tab is active. */
    private void runAvailabilitySearch(View v) {
        if (selectedTab == TAB_HOTEL_ROOMS) {
            fetchAvailability(v);
        } else {
            // Cottage/KTV have no dated-availability search yet; already showing the live catalog.
            renderAvailabilityResults(v);
        }
    }

    /**
     * Loads the general (date-less) Hotel Rooms catalog so accommodations are visible the
     * moment the guest opens the Book tab, before they've picked dates. Runs once per flow
     * instance; a real, dated {@link #fetchAvailability} search takes over once it succeeds.
     */
    private void fetchDefaultRooms(View v) {
        if (isLoadingDefaultRooms || defaultRoomsLoaded || hasSearchedHotelRooms) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            return;
        }
        isLoadingDefaultRooms = true;
        renderAvailabilityResults(v);

        ApiClient.bookingApi().getRooms("Bearer " + token).enqueue(new Callback<RoomListResponse>() {
            @Override
            public void onResponse(Call<RoomListResponse> call, Response<RoomListResponse> response) {
                isLoadingDefaultRooms = false;
                defaultRoomsLoaded = true;
                defaultRooms = response.isSuccessful() && response.body() != null && response.body().rooms != null
                        ? response.body().rooms : new ArrayList<>();
                renderAvailabilityResults(v);
            }

            @Override
            public void onFailure(Call<RoomListResponse> call, Throwable t) {
                isLoadingDefaultRooms = false;
                defaultRoomsLoaded = true;
                defaultRooms = new ArrayList<>();
                renderAvailabilityResults(v);
            }
        });
    }

    /** Loads the Cottages & KTV Type/Category catalog from the Admin Web's Cottage Management /
     *  KTV Management data (see {@link CottageKtvOption}) — no mobile-side hardcoded Type or
     *  Category values. Runs once per flow instance, same as {@link #fetchDefaultRooms}. */
    private void fetchCottagesKtv(View v) {
        if (isLoadingCottageKtv || cottageKtvLoaded) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            return;
        }
        isLoadingCottageKtv = true;
        renderAvailabilityResults(v);

        ApiClient.bookingApi().getCottagesKtv("Bearer " + token).enqueue(new Callback<CottageKtvListResponse>() {
            @Override
            public void onResponse(Call<CottageKtvListResponse> call, Response<CottageKtvListResponse> response) {
                isLoadingCottageKtv = false;
                cottageKtvLoaded = true;
                cottageItems = new ArrayList<>();
                ktvItems = new ArrayList<>();
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body().cottages != null) {
                        cottageItems.addAll(response.body().cottages);
                    }
                    if (response.body().ktv_rooms != null) {
                        ktvItems.addAll(response.body().ktv_rooms);
                    }
                }
                for (CottageKtvOption item : cottageItems) {
                    if (!roomQuantities.containsKey(item.group_id)) {
                        roomQuantities.put(item.group_id, 0);
                    }
                }
                for (CottageKtvOption item : ktvItems) {
                    if (!roomQuantities.containsKey(item.group_id)) {
                        roomQuantities.put(item.group_id, 0);
                    }
                }
                renderAvailabilityResults(v);
            }

            @Override
            public void onFailure(Call<CottageKtvListResponse> call, Throwable t) {
                isLoadingCottageKtv = false;
                cottageKtvLoaded = true;
                cottageItems = new ArrayList<>();
                ktvItems = new ArrayList<>();
                renderAvailabilityResults(v);
            }
        });
    }

    /** Checks real room availability on the shared backend, then refreshes the results below. */
    private void fetchAvailability(View datesView) {
        if (isLoadingAvailability) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }

        View searchButton = datesView.findViewById(R.id.hotelDatesNextButton);
        TextView searchButtonText = datesView.findViewById(R.id.hotelDatesNextButtonText);
        isLoadingAvailability = true;
        searchButton.setEnabled(false);
        searchButtonText.setText(R.string.button_checking_availability);
        renderAvailabilityResults(datesView);

        String checkInStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(checkInMillis));
        String checkOutStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(checkOutMillis));

        ApiClient.bookingApi().checkAvailability("Bearer " + token, checkInStr, checkOutStr, adults, children)
                .enqueue(new Callback<AvailabilityResponse>() {
                    @Override
                    public void onResponse(Call<AvailabilityResponse> call, Response<AvailabilityResponse> response) {
                        isLoadingAvailability = false;
                        searchButton.setEnabled(true);
                        searchButtonText.setText(R.string.button_search);

                        if (response.isSuccessful() && response.body() != null) {
                            availableRoomTypes = response.body().room_types != null
                                    ? response.body().room_types : new ArrayList<>();
                            roomQuantities.clear();
                            for (RoomTypeAvailability type : availableRoomTypes) {
                                roomQuantities.put(type.group_id, 0);
                            }
                            hasSearchedHotelRooms = true;
                            availabilityErrored = false;
                        } else if (response.code() == 401) {
                            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                        } else {
                            availabilityErrored = true;
                            Toast.makeText(activity, R.string.toast_availability_check_failed, Toast.LENGTH_LONG).show();
                        }
                        renderAvailabilityResults(datesView);
                    }

                    @Override
                    public void onFailure(Call<AvailabilityResponse> call, Throwable t) {
                        isLoadingAvailability = false;
                        searchButton.setEnabled(true);
                        searchButtonText.setText(R.string.button_search);
                        availabilityErrored = true;
                        Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
                        renderAvailabilityResults(datesView);
                    }
                });
    }

    /** Shows the loading/error/empty/results state for the active category tab below the search controls. */
    private void renderAvailabilityResults(View v) {
        View loading = v.findViewById(R.id.hotelResultsLoading);
        View emptyState = v.findViewById(R.id.hotelResultsEmptyState);
        View errorState = v.findViewById(R.id.hotelResultsErrorState);
        View sectionHeader = v.findViewById(R.id.hotelResultsSectionHeader);
        TextView sectionLabel = v.findViewById(R.id.hotelResultsSectionLabel);
        ImageView sectionIcon = v.findViewById(R.id.hotelResultsSectionIcon);
        LinearLayout container = v.findViewById(R.id.hotelResultsContainer);
        View cottageTotalRow = v.findViewById(R.id.hotelCottageTotalUnitsRow);
        TextView cottageTotalValue = v.findViewById(R.id.hotelCottageTotalUnitsValue);
        View ktvFooter = v.findViewById(R.id.hotelKtvFooter);
        TextView ktvTotalValue = v.findViewById(R.id.hotelKtvTotalSelectedValue);
        Button ktvProceedButton = v.findViewById(R.id.hotelKtvProceedButton);
        container.removeAllViews();

        boolean hotelRooms = selectedTab == TAB_HOTEL_ROOMS;
        boolean cottage = selectedTab == TAB_COTTAGE;
        boolean ktv = selectedTab == TAB_KTV;

        cottageTotalRow.setVisibility(cottage ? View.VISIBLE : View.GONE);
        ktvFooter.setVisibility(ktv ? View.VISIBLE : View.GONE);
        updateCottageTotalUnits(cottageTotalValue);
        updateKtvFooter(ktvTotalValue, ktvProceedButton);

        TextView resultsSubtitle = v.findViewById(R.id.hotelResultsSubtitle);
        boolean showingLiveSearch = !hotelRooms || hasSearchedHotelRooms;
        resultsSubtitle.setText(showingLiveSearch
                ? R.string.label_available_accommodations_subtitle
                : R.string.label_available_accommodations_subtitle_browse);

        if (isLoadingAvailability || (hotelRooms && !hasSearchedHotelRooms && isLoadingDefaultRooms)
                || (!hotelRooms && isLoadingCottageKtv)) {
            loading.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
            errorState.setVisibility(View.GONE);
            sectionHeader.setVisibility(View.GONE);
            container.setVisibility(View.GONE);
            return;
        }
        loading.setVisibility(View.GONE);

        if (hotelRooms && hasSearchedHotelRooms && availabilityErrored) {
            errorState.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
            sectionHeader.setVisibility(View.GONE);
            container.setVisibility(View.GONE);
            return;
        }
        errorState.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(activity);
        if (hotelRooms && hasSearchedHotelRooms) {
            // A real, dated search has been run: show live availability for those exact dates.
            int totalGuests = adults + children;
            List<RoomTypeAvailability> rooms = new ArrayList<>();
            for (RoomTypeAvailability room : availableRoomTypes) {
                if (meetsCapacity(room.capacity, totalGuests)) {
                    rooms.add(room);
                }
            }
            if (rooms.isEmpty()) {
                emptyState.setVisibility(View.VISIBLE);
                sectionHeader.setVisibility(View.GONE);
                container.setVisibility(View.GONE);
                return;
            }
            emptyState.setVisibility(View.GONE);
            sectionIcon.setImageResource(R.drawable.ic_bed);
            sectionLabel.setText(R.string.label_section_rooms);
            sectionHeader.setVisibility(View.VISIBLE);
            container.setVisibility(View.VISIBLE);
            for (RoomTypeAvailability room : rooms) {
                container.addView(buildRoomCard(inflater, container, room));
            }
        } else if (hotelRooms) {
            // No dated search yet: show the general room catalog so the guest has something to
            // browse the moment they open the Book tab.
            int totalGuests = adults + children;
            List<RoomSummary> rooms = new ArrayList<>();
            for (RoomSummary room : defaultRooms) {
                if (meetsCapacity(room.capacity, totalGuests)) {
                    rooms.add(room);
                }
            }
            if (rooms.isEmpty()) {
                emptyState.setVisibility(View.VISIBLE);
                sectionHeader.setVisibility(View.GONE);
                container.setVisibility(View.GONE);
                return;
            }
            emptyState.setVisibility(View.GONE);
            sectionIcon.setImageResource(R.drawable.ic_bed);
            sectionLabel.setText(R.string.label_section_rooms);
            sectionHeader.setVisibility(View.VISIBLE);
            container.setVisibility(View.VISIBLE);
            for (RoomSummary room : rooms) {
                container.addView(buildDefaultRoomCard(inflater, container, room, v));
            }
        } else {
            // Cottage/KTV: Type/Category data comes from the Admin Web's Cottage Management /
            // KTV Management modules via fetchCottagesKtv(), not any mobile-side hardcoded list.
            int totalGuests = cottage ? cottageAdults + cottageChildren : ktvGuestCount;
            List<CottageKtvOption> source = cottage ? cottageItems : ktvItems;
            List<CottageKtvOption> items = new ArrayList<>();
            for (CottageKtvOption item : source) {
                if (meetsCapacity(item.capacity, totalGuests)) {
                    items.add(item);
                }
            }
            if (items.isEmpty()) {
                emptyState.setVisibility(View.VISIBLE);
                sectionHeader.setVisibility(View.GONE);
                container.setVisibility(View.GONE);
                return;
            }
            emptyState.setVisibility(View.GONE);
            sectionIcon.setImageResource(cottage ? R.drawable.ic_pool : R.drawable.ic_mic);
            sectionLabel.setText(cottage ? R.string.label_select_desired_cottage_type : R.string.label_select_ktv_room);
            sectionHeader.setVisibility(View.VISIBLE);
            container.setVisibility(View.VISIBLE);
            for (CottageKtvOption item : items) {
                container.addView(buildCottageKtvCard(inflater, container, item, cottage, v));
            }
        }
    }

    private void updateCottageTotalUnits(TextView cottageTotalValue) {
        int total = 0;
        for (CottageKtvOption item : cottageItems) {
            Integer qty = roomQuantities.get(item.group_id);
            if (qty != null) {
                total += qty;
            }
        }
        cottageTotalValue.setText(String.valueOf(total));
    }

    private void updateKtvFooter(TextView ktvTotalValue, Button ktvProceedButton) {
        boolean selected = selectedKtvItem() != null;
        ktvTotalValue.setText(selected ? "1" : "0");
        ktvProceedButton.setEnabled(selected);
        ktvProceedButton.setAlpha(selected ? 1f : 0.4f);
    }

    /** The KTV room currently selected via its card's Select toggle, or null if none. */
    private CottageKtvOption selectedKtvItem() {
        for (CottageKtvOption item : ktvItems) {
            Integer qty = roomQuantities.get(item.group_id);
            if (qty != null && qty > 0) {
                return item;
            }
        }
        return null;
    }

    private boolean isCottageItemId(int id) {
        for (CottageKtvOption item : cottageItems) {
            if (item.group_id == id) {
                return true;
            }
        }
        return false;
    }

    private View buildRoomCard(LayoutInflater inflater, ViewGroup parent, RoomTypeAvailability room) {
        View card = inflater.inflate(R.layout.view_accommodation_card, parent, false);
        loadImage(card.findViewById(R.id.accommodationImage), room.image_path, R.drawable.ic_bed);

        boolean available = room.available_quantity > 0;
        TextView badge = card.findViewById(R.id.accommodationAvailabilityBadge);
        badge.setText(available ? R.string.booking_status_available : R.string.booking_status_no_rooms_available);
        badge.setBackgroundResource(available ? R.drawable.bg_badge_available_solid : R.drawable.bg_badge_full_solid);

        ((TextView) card.findViewById(R.id.accommodationName))
                .setText(formatShowcaseName(room.variant_name, room.category_name));
        ((TextView) card.findViewById(R.id.accommodationCapacity))
                .setText(activity.getString(R.string.format_capacity, room.capacity));

        TextView bedInfo = card.findViewById(R.id.accommodationBedInfo);
        if (room.description != null && !room.description.trim().isEmpty()) {
            bedInfo.setText(room.description);
            bedInfo.setVisibility(View.VISIBLE);
        } else {
            bedInfo.setVisibility(View.GONE);
        }

        int pricePerNight = (int) Math.round(room.price_per_night);
        ((TextView) card.findViewById(R.id.accommodationPrice))
                .setText(activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)));

        Button bookNow = card.findViewById(R.id.accommodationBookNowButton);
        bookNow.setEnabled(available);
        bookNow.setOnClickListener(view -> bookHotelRoomNow(room));

        // Fully-booked rooms stay visible (not hidden) but dimmed, matching the general-catalog
        // cards' treatment, so guests can see what exists without being able to book it.
        card.setAlpha(available ? 1f : 0.45f);
        return card;
    }

    /** Renders one card from the general (date-less) room catalog shown before the guest has
     *  run a real, dated search. BOOK NOW here can't jump to Review yet — there's no validated
     *  availability record for this room on any specific dates — so it prompts the guest to
     *  search first instead. */
    private View buildDefaultRoomCard(LayoutInflater inflater, ViewGroup parent, RoomSummary room, View screenView) {
        View card = inflater.inflate(R.layout.view_accommodation_card, parent, false);
        loadImage(card.findViewById(R.id.accommodationImage), room.image_path, R.drawable.ic_bed);

        TextView badge = card.findViewById(R.id.accommodationAvailabilityBadge);
        badge.setText(room.available ? R.string.booking_status_available : R.string.booking_status_no_rooms_available);
        badge.setBackgroundResource(room.available ? R.drawable.bg_badge_available_solid : R.drawable.bg_badge_full_solid);

        ((TextView) card.findViewById(R.id.accommodationName))
                .setText(formatShowcaseName(room.name, resolveRoomCategoryLabel(room)));
        ((TextView) card.findViewById(R.id.accommodationCapacity))
                .setText(activity.getString(R.string.format_capacity, room.capacity));

        TextView bedInfo = card.findViewById(R.id.accommodationBedInfo);
        if (room.description != null && !room.description.trim().isEmpty()) {
            bedInfo.setText(room.description);
            bedInfo.setVisibility(View.VISIBLE);
        } else {
            bedInfo.setVisibility(View.GONE);
        }

        int pricePerNight = (int) Math.round(room.price_per_night);
        ((TextView) card.findViewById(R.id.accommodationPrice))
                .setText(activity.getString(R.string.format_price_per_night, formatMoney(pricePerNight)));

        Button bookNow = card.findViewById(R.id.accommodationBookNowButton);
        bookNow.setEnabled(room.available);
        bookNow.setOnClickListener(view -> promptSearchBeforeBooking(screenView));

        // Fully-booked rooms are still shown (not hidden) but visibly dimmed so guests can tell
        // them apart from ones they can actually book right now.
        card.setAlpha(room.available ? 1f : 0.45f);
        return card;
    }

    /**
     * The room's category/building label (e.g. "Villa", "Claricon"), trying every field name
     * this backend uses for that concept since the real one on this endpoint isn't confirmed —
     * mirrors {@link DashboardHomeController#resolveRoomCategoryLabel}.
     */
    private String resolveRoomCategoryLabel(RoomSummary room) {
        String[] candidates = {room.category_name, room.category, room.item_name};
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) {
                return candidate;
            }
        }
        return activity.getString(R.string.hotel_rooms_title);
    }

    /** A room passes the guest-count filter if its listed capacity covers the party, OR if its
     *  capacity is missing/zero — treating that as unknown data rather than "sleeps nobody" so a
     *  backend data gap can't silently hide an entire room type from the catalog. */
    private boolean meetsCapacity(int roomCapacity, int totalGuests) {
        return roomCapacity <= 0 || roomCapacity >= totalGuests;
    }

    /** Showcase card title format: "TYPE (CATEGORY)", e.g. "Standard (Claricon)" — rendered in
     *  caps by the TextView itself. Falls back to just the type if no category is known. */
    private String formatShowcaseName(String type, String category) {
        if (category == null || category.trim().isEmpty()) {
            return type;
        }
        return type + " (" + category + ")";
    }

    /** BOOK NOW on a general-catalog card (no dated search run yet): nudges the guest toward
     *  entering dates and tapping Search, since only that produces a real, bookable record. */
    private void promptSearchBeforeBooking(View v) {
        boolean missingDates = false;
        if (checkInMillis < 0) {
            showError(v.findViewById(R.id.hotelCheckInError), R.string.error_check_in_required);
            missingDates = true;
        }
        if (checkOutMillis < 0) {
            showError(v.findViewById(R.id.hotelCheckOutError), R.string.error_check_out_required);
            missingDates = true;
        }
        expandPanel(v);
        Toast.makeText(activity, missingDates
                ? R.string.toast_select_dates_to_book
                : R.string.toast_tap_search_to_book, Toast.LENGTH_LONG).show();
    }

    /** One Cottage or KTV card: Type/Category ("Standard (Claricon)"-style, via
     *  {@link #formatShowcaseName}), capacity, price and photo all come from
     *  {@link CottageKtvOption} (Admin Web data via {@link #fetchCottagesKtv}), not any
     *  mobile-side hardcoded catalog. Cottage keeps a quantity stepper + Reserve button; KTV
     *  gets a single Select/Selected toggle (see the KTV footer's Proceed button). Reserve/
     *  Proceed keep the existing behavior — this category has no dated-availability booking
     *  flow yet, so both just route to the Reserve tab like Book Now always has. */
    private View buildCottageKtvCard(LayoutInflater inflater, ViewGroup parent, CottageKtvOption item,
            boolean cottage, View v) {
        View card = inflater.inflate(R.layout.view_accommodation_card, parent, false);
        loadImage(card.findViewById(R.id.accommodationImage), item.image_path, cottage ? R.drawable.ic_pool : R.drawable.ic_mic);

        boolean available = item.available_quantity > 0;
        TextView badge = card.findViewById(R.id.accommodationAvailabilityBadge);
        badge.setText(available ? R.string.booking_status_available : R.string.booking_status_no_rooms_available);
        badge.setBackgroundResource(available ? R.drawable.bg_badge_available_solid : R.drawable.bg_badge_full_solid);

        ((TextView) card.findViewById(R.id.accommodationName))
                .setText(formatShowcaseName(item.variant_name, item.category_name));
        ((TextView) card.findViewById(R.id.accommodationCapacity))
                .setText(activity.getString(R.string.format_capacity, item.capacity));

        TextView bedInfo = card.findViewById(R.id.accommodationBedInfo);
        if (item.description != null && !item.description.trim().isEmpty()) {
            bedInfo.setText(item.description);
            bedInfo.setVisibility(View.VISIBLE);
        } else {
            bedInfo.setVisibility(View.GONE);
        }

        TextView availableUnits = card.findViewById(R.id.accommodationAvailableUnits);
        availableUnits.setText(activity.getString(R.string.format_available_units, item.available_quantity));
        availableUnits.setVisibility(View.VISIBLE);

        ((TextView) card.findViewById(R.id.accommodationPrice)).setText(activity.getString(
                R.string.format_price_per_unit, formatMoney((int) Math.round(item.price)), item.price_unit));
        card.findViewById(R.id.accommodationBookNowButton).setVisibility(View.GONE);

        View quantityRow = card.findViewById(R.id.accommodationQuantityRow);
        Button reserveButton = card.findViewById(R.id.accommodationReserveButton);
        Button selectButton = card.findViewById(R.id.accommodationSelectButton);
        int qty = roomQuantities.containsKey(item.group_id) ? roomQuantities.get(item.group_id) : 0;

        if (!cottage) {
            // KTV rooms are a single mutually-exclusive pick, not a quantity.
            quantityRow.setVisibility(View.GONE);
            reserveButton.setVisibility(View.GONE);
            selectButton.setVisibility(View.VISIBLE);
            boolean selected = qty > 0;
            bindKtvSelectButton(selectButton, selected);
            selectButton.setOnClickListener(view -> {
                for (Integer key : roomQuantities.keySet()) {
                    roomQuantities.put(key, key.equals(item.group_id) && !selected ? 1 : 0);
                }
                renderAvailabilityResults(v);
            });
            card.setAlpha(available ? 1f : 0.45f);
            return card;
        }

        quantityRow.setVisibility(View.VISIBLE);
        selectButton.setVisibility(View.GONE);
        reserveButton.setVisibility(View.VISIBLE);

        TextView qtyValue = card.findViewById(R.id.accommodationQuantityValue);
        ImageView minus = card.findViewById(R.id.accommodationQuantityMinus);
        ImageView plus = card.findViewById(R.id.accommodationQuantityPlus);
        qtyValue.setText(String.valueOf(qty));
        setStepperEnabled(plus, qty < item.available_quantity);

        minus.setOnClickListener(view -> {
            int current = roomQuantities.get(item.group_id);
            if (current > 0) {
                roomQuantities.put(item.group_id, current - 1);
                renderAvailabilityResults(v);
            }
        });
        plus.setOnClickListener(view -> {
            int current = roomQuantities.get(item.group_id);
            if (current < item.available_quantity) {
                // Several cottage types may carry a quantity at once while browsing (see the
                // class-level selection model note) — but a reservation is still one category
                // at a time, so clear anything picked on the Hotel Rooms/KTV tabs.
                for (Integer key : roomQuantities.keySet()) {
                    if (!key.equals(item.group_id) && !isCottageItemId(key)) {
                        roomQuantities.put(key, 0);
                    }
                }
                roomQuantities.put(item.group_id, current + 1);
                renderAvailabilityResults(v);
            }
        });

        reserveButton.setEnabled(qty > 0);
        reserveButton.setAlpha(qty > 0 ? 1f : 0.4f);
        reserveButton.setOnClickListener(view -> {
            if (!validateCottageFields(v)) {
                expandPanel(v);
                return;
            }
            for (Integer key : roomQuantities.keySet()) {
                if (!key.equals(item.group_id)) {
                    roomQuantities.put(key, 0);
                }
            }
            onBookNowOtherCategory.run();
        });

        card.setAlpha(available ? 1f : 0.45f);
        return card;
    }

    /** Styles the KTV card's toggle button for its current selected/unselected state. */
    private void bindKtvSelectButton(Button button, boolean selected) {
        button.setText(selected ? R.string.button_selected : R.string.button_select);
        button.setBackgroundResource(selected ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        button.setTextColor(selected ? activity.getColor(R.color.button_primary_text)
                : ThemeManager.color(activity, R.attr.textPrimary));
    }

    /** BOOK NOW on a Hotel Rooms card: select that room at quantity 1 (clearing any other
     *  selection) and jump straight into the existing Review screen. */
    private void bookHotelRoomNow(RoomTypeAvailability room) {
        for (Integer groupId : roomQuantities.keySet()) {
            roomQuantities.put(groupId, 0);
        }
        roomQuantities.put(room.group_id, 1);
        showScreen(SCREEN_REVIEW);
    }

    /** Parses a manually-typed guest count, treating anything empty/invalid as 0 rather than
     *  crashing or leaving the previous value silently in place. */
    private int parseGuestCount(String text) {
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void updateDateField(TextView dateText, TextView dayText, long millis) {
        if (millis < 0) {
            dateText.setText(R.string.hint_select_date);
            dateText.setAlpha(0.5f);
            dayText.setVisibility(View.GONE);
        } else {
            dateText.setText(formatDate(millis));
            dateText.setAlpha(1f);
            dayText.setText(formatWeekday(millis));
            dayText.setVisibility(View.VISIBLE);
        }
    }

    private void updateCategoryTabs(View v, View tabRooms, View tabCottage, View tabKtv) {
        int accentColor = ThemeManager.color(activity, R.attr.accentPrimary);
        int mutedColor = ThemeManager.color(activity, R.attr.textOnScreenMuted);

        styleTab(tabRooms, R.id.hotelTabRoomsText, R.id.hotelTabRoomsIcon,
                selectedTab == TAB_HOTEL_ROOMS, accentColor, mutedColor);
        styleTab(tabCottage, R.id.hotelTabCottageText, R.id.hotelTabCottageIcon,
                selectedTab == TAB_COTTAGE, accentColor, mutedColor);
        styleTab(tabKtv, R.id.hotelTabKtvText, R.id.hotelTabKtvIcon,
                selectedTab == TAB_KTV, accentColor, mutedColor);

        v.findViewById(R.id.hotelRoomsDatesRow).setVisibility(selectedTab == TAB_HOTEL_ROOMS ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.hotelHotelGuestsSection).setVisibility(selectedTab == TAB_HOTEL_ROOMS ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.hotelCottageFieldsGroup).setVisibility(selectedTab == TAB_COTTAGE ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.hotelKtvFieldsGroup).setVisibility(selectedTab == TAB_KTV ? View.VISIBLE : View.GONE);

        // Switching tabs changes the search panel's content height, so re-measure it for
        // the drag/collapse mechanics (see setUpDragPanel) instead of leaving a stale distance.
        View panelContent = v.findViewById(R.id.hotelSearchPanelContent);
        panelContent.post(() -> {
            panelCollapseDistance = panelContent.getHeight();
            setPanelFraction(v, panelFraction);
        });
    }

    private void styleTab(View tab, int textId, int iconId, boolean selected, int accentColor, int mutedColor) {
        tab.setBackgroundResource(selected ? R.drawable.bg_tab_pill_selected : R.drawable.bg_tab_pill_unselected);
        ((TextView) tab.findViewById(textId)).setTextColor(selected ? accentColor : mutedColor);
        ((ImageView) tab.findViewById(iconId)).setColorFilter(selected ? accentColor : mutedColor);
    }

    private void updateCottageDateField(TextView cottageDateText) {
        if (cottageDateMillis < 0) {
            cottageDateText.setText(R.string.hint_select_date);
            cottageDateText.setAlpha(0.5f);
        } else {
            cottageDateText.setText(formatDate(cottageDateMillis));
            cottageDateText.setAlpha(1f);
        }
    }

    private void updateCottageTimeField(TextView cottageTimeText) {
        if (cottageTimeHour < 0) {
            cottageTimeText.setText(R.string.hint_select_time);
            cottageTimeText.setAlpha(0.5f);
        } else {
            cottageTimeText.setText(formatHourMinute(cottageTimeHour, cottageTimeMinute));
            cottageTimeText.setAlpha(1f);
        }
    }

    private void updateKtvDateField(TextView ktvDateText) {
        if (ktvDateMillis < 0) {
            ktvDateText.setText(R.string.hint_select_date);
            ktvDateText.setAlpha(0.5f);
        } else {
            ktvDateText.setText(formatDate(ktvDateMillis));
            ktvDateText.setAlpha(1f);
        }
    }

    private void updateKtvStartTimeField(TextView ktvStartTimeText) {
        if (ktvStartHour < 0) {
            ktvStartTimeText.setText(R.string.hint_select_time);
            ktvStartTimeText.setAlpha(0.5f);
        } else {
            ktvStartTimeText.setText(formatHourMinute(ktvStartHour, ktvStartMinute));
            ktvStartTimeText.setAlpha(1f);
        }
    }

    /** Recomputes the read-only End Time/Duration fields from Start Time + the selected rate
     *  type's fixed duration; called whenever either changes. */
    private void updateKtvEndTimeAndDuration(View v) {
        TextView endTimeText = v.findViewById(R.id.hotelKtvEndTimeText);
        TextView durationValue = v.findViewById(R.id.hotelKtvDurationValue);
        int hours = ReservationCatalog.RATE_TYPE_REGULAR.equals(ktvRateType) ? KTV_REGULAR_HOURS
                : ReservationCatalog.RATE_TYPE_CONSUMABLE.equals(ktvRateType) ? KTV_CONSUMABLE_HOURS : 0;

        durationValue.setText(hours <= 0 ? activity.getString(R.string.placeholder_em_dash)
                : activity.getString(R.string.format_duration_hours, hours));

        if (hours <= 0 || ktvStartHour < 0) {
            endTimeText.setText(R.string.placeholder_em_dash);
            endTimeText.setAlpha(0.5f);
            return;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, ktvStartHour);
        calendar.set(Calendar.MINUTE, ktvStartMinute);
        calendar.add(Calendar.HOUR_OF_DAY, hours);
        endTimeText.setText(formatHourMinute(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)));
        endTimeText.setAlpha(1f);
    }

    private String formatHourMinute(int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        return new SimpleDateFormat("h:mm a", Locale.US).format(calendar.getTime());
    }

    /** Validates the Cottage tab's rate type/date/time fields, showing inline field errors. */
    private boolean validateCottageFields(View v) {
        TextView rateError = v.findViewById(R.id.hotelCottageRateTypeError);
        TextView dateError = v.findViewById(R.id.hotelCottageDateError);
        TextView timeError = v.findViewById(R.id.hotelCottageTimeError);
        boolean valid = true;
        if (cottageRateType.isEmpty()) {
            showError(rateError, R.string.error_rate_type_required);
            valid = false;
        } else {
            rateError.setVisibility(View.GONE);
        }
        if (cottageDateMillis < 0) {
            showError(dateError, R.string.error_date_required);
            valid = false;
        } else if (cottageDateMillis < System.currentTimeMillis() - ONE_DAY_MS) {
            showError(dateError, R.string.error_date_past);
            valid = false;
        } else {
            dateError.setVisibility(View.GONE);
        }
        if (cottageTimeHour < 0) {
            showError(timeError, R.string.error_time_required);
            valid = false;
        } else {
            timeError.setVisibility(View.GONE);
        }
        return valid;
    }

    /** Validates the KTV tab's rate type/date/start-time fields, showing inline field errors. */
    private boolean validateKtvFields(View v) {
        TextView rateError = v.findViewById(R.id.hotelKtvRateTypeError);
        TextView dateError = v.findViewById(R.id.hotelKtvDateError);
        TextView timeError = v.findViewById(R.id.hotelKtvStartTimeError);
        boolean valid = true;
        if (ktvRateType.isEmpty()) {
            showError(rateError, R.string.error_rate_type_required);
            valid = false;
        } else {
            rateError.setVisibility(View.GONE);
        }
        if (ktvDateMillis < 0) {
            showError(dateError, R.string.error_date_required);
            valid = false;
        } else if (ktvDateMillis < System.currentTimeMillis() - ONE_DAY_MS) {
            showError(dateError, R.string.error_date_past);
            valid = false;
        } else {
            dateError.setVisibility(View.GONE);
        }
        if (ktvStartHour < 0) {
            showError(timeError, R.string.error_time_required);
            valid = false;
        } else {
            timeError.setVisibility(View.GONE);
        }
        return valid;
    }

    private String formatWeekday(long millis) {
        return new SimpleDateFormat("EEEE", Locale.US).format(new Date(millis));
    }

    // ---- Screen 2: Review Your Selection -----------------------------------

    private void bindReview(View v) {
        bindHeader(v, R.id.hotelReviewHeaderBar, R.string.hotel_review_title, () -> showScreen(SCREEN_DATES));

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

    // ---- View My Bookings (booking-flavored twin of ReservationFlowController's
    // My Reservations / Reservation Details — see BookingStore) ---------------------

    private void bindMyBookingsList(View v) {
        bindHeader(v, R.id.myBookingsHeaderBar, R.string.booking_my_list_title, () -> showScreen(SCREEN_DATES));

        TextView tabCurrent = v.findViewById(R.id.tabCurrentBookings);
        TextView tabPast = v.findViewById(R.id.tabPastBookings);
        tabCurrent.setOnClickListener(view -> {
            myBookingsTab = BookingStore.TAB_CURRENT;
            bindMyBookingsList(v);
        });
        tabPast.setOnClickListener(view -> {
            myBookingsTab = BookingStore.TAB_PAST;
            bindMyBookingsList(v);
        });
        updateBookingTabStyle(tabCurrent, myBookingsTab == BookingStore.TAB_CURRENT);
        updateBookingTabStyle(tabPast, myBookingsTab == BookingStore.TAB_PAST);

        List<BookingStore.Booking> list = BookingStore.byTab(myBookingsTab);
        LinearLayout container = v.findViewById(R.id.myBookingsListContainer);
        container.removeAllViews();
        v.findViewById(R.id.myBookingsListEmpty).setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(activity);
        for (BookingStore.Booking b : list) {
            View card = inflater.inflate(R.layout.view_my_booking_card, container, false);
            ((TextView) card.findViewById(R.id.bookingCardReferenceNo)).setText(b.bookingReference);
            ((TextView) card.findViewById(R.id.bookingCardType)).setText(
                    isCottageKtvBooking(b) ? R.string.booking_type_cottage_ktv : R.string.booking_type_hotel);
            ((TextView) card.findViewById(R.id.bookingCardAccommodation))
                    .setText(b.roomOption.categoryName + " · " + b.roomOption.variantName);
            ((TextView) card.findViewById(R.id.bookingCardDateRange)).setText(formatBookingDateRange(b.checkInMillis, b.checkOutMillis));
            ((TextView) card.findViewById(R.id.bookingCardGuests)).setText(formatBookingGuests(b.adults, b.children));

            StatusPresentation status = resolveBookingStatus(b);
            card.findViewById(R.id.bookingCardStatusDot).setBackgroundTintList(
                    ColorStateList.valueOf(ThemeManager.color(activity, status.colorAttr)));
            TextView statusLabel = card.findViewById(R.id.bookingCardStatusLabel);
            statusLabel.setText(status.labelRes);
            statusLabel.setTextColor(ThemeManager.color(activity, status.colorAttr));

            card.findViewById(R.id.bookingCardViewDetailsButton).setOnClickListener(view -> {
                myBookingDetailsReference = b.bookingReference;
                showScreen(SCREEN_BOOKING_DETAILS);
            });
            container.addView(card);
        }
    }

    private void updateBookingTabStyle(TextView tab, boolean active) {
        tab.setTextColor(ThemeManager.color(activity, active ? R.attr.accentPrimary : R.attr.textMuted));
        tab.setAlpha(active ? 1f : 0.55f);
    }

    private void bindBookingDetails(View v) {
        BookingStore.Booking b = BookingStore.byReference(myBookingDetailsReference);
        if (b == null) {
            showScreen(SCREEN_MY_BOOKINGS);
            return;
        }

        bindHeader(v, R.id.bookingDetailsHeaderBar, R.string.booking_details_title, () -> showScreen(SCREEN_MY_BOOKINGS));

        StatusPresentation status = resolveBookingStatus(b);
        v.findViewById(R.id.bookingDetailsStatusDot).setBackgroundTintList(
                ColorStateList.valueOf(ThemeManager.color(activity, status.colorAttr)));
        TextView statusLabel = v.findViewById(R.id.bookingDetailsStatusLabel);
        statusLabel.setText(status.labelRes);
        statusLabel.setTextColor(ThemeManager.color(activity, status.colorAttr));
        ((TextView) v.findViewById(R.id.bookingDetailsStatusDesc)).setText(status.descRes);

        bindRow(v.findViewById(R.id.bookingDetailsRowReference), R.string.label_reference_no, b.bookingReference);
        bindRow(v.findViewById(R.id.bookingDetailsRowAccommodation), R.string.label_accommodation,
                b.roomOption.categoryName + " · " + b.roomOption.variantName);
        bindRow(v.findViewById(R.id.bookingDetailsRowDate), R.string.label_date, formatBookingDateRange(b.checkInMillis, b.checkOutMillis));
        bindRow(v.findViewById(R.id.bookingDetailsRowGuests), R.string.label_stay_guests, formatBookingGuests(b.adults, b.children));

        View altContainer = v.findViewById(R.id.bookingDetailsAltScheduleContainer);
        View additionalContainer = v.findViewById(R.id.bookingDetailsAdditionalConfirmationContainer);
        View confirmedContainer = v.findViewById(R.id.bookingDetailsConfirmedContainer);
        View rejectedContainer = v.findViewById(R.id.bookingDetailsRejectedContainer);
        View cancelledReasonContainer = v.findViewById(R.id.bookingDetailsCancelledReasonContainer);
        altContainer.setVisibility(View.GONE);
        additionalContainer.setVisibility(View.GONE);
        confirmedContainer.setVisibility(View.GONE);
        rejectedContainer.setVisibility(View.GONE);
        cancelledReasonContainer.setVisibility(View.GONE);

        if (b.status == BookingStore.STATUS_PENDING && BookingStore.SUB_ALTERNATIVE_PROPOSED.equals(b.subStatus)) {
            altContainer.setVisibility(View.VISIBLE);
            bindRow(v.findViewById(R.id.bookingDetailsRowCurrentRequest), R.string.label_current_request,
                    formatBookingDateRange(b.checkInMillis, b.checkOutMillis));
            bindRow(v.findViewById(R.id.bookingDetailsRowProposedSchedule), R.string.label_proposed_schedule,
                    formatBookingDateRange(b.proposedCheckInMillis, b.proposedCheckOutMillis));
            v.findViewById(R.id.bookingDetailsAcceptButton).setOnClickListener(view -> {
                BookingStore.acceptAlternativeSchedule(b.bookingReference);
                showScreen(SCREEN_BOOKING_DETAILS);
            });
            v.findViewById(R.id.bookingDetailsDeclineButton).setOnClickListener(view -> {
                BookingStore.declineAlternativeSchedule(b.bookingReference);
                showScreen(SCREEN_BOOKING_DETAILS);
            });
        } else if (b.status == BookingStore.STATUS_PENDING
                && BookingStore.SUB_ADDITIONAL_CONFIRMATION.equals(b.subStatus)) {
            additionalContainer.setVisibility(View.VISIBLE);
        } else if (b.status == BookingStore.STATUS_CONFIRMED) {
            confirmedContainer.setVisibility(View.VISIBLE);
        } else if (b.status == BookingStore.STATUS_CANCELLED && BookingStore.SUB_REJECTED.equals(b.subStatus)) {
            rejectedContainer.setVisibility(View.VISIBLE);
            ((TextView) v.findViewById(R.id.bookingDetailsRejectedReason)).setText(
                    b.cancelReason != null && !b.cancelReason.isEmpty()
                            ? b.cancelReason : activity.getString(R.string.reason_room_unavailable));
            v.findViewById(R.id.bookingDetailsTryAnotherDateButton).setOnClickListener(view -> showScreen(SCREEN_DATES));
            v.findViewById(R.id.bookingDetailsViewOtherRoomsButton).setOnClickListener(view -> showScreen(SCREEN_DATES));
            v.findViewById(R.id.bookingDetailsBackToListButton).setOnClickListener(view -> showScreen(SCREEN_MY_BOOKINGS));
        } else if (b.status == BookingStore.STATUS_CANCELLED
                && b.cancelReason != null && !b.cancelReason.isEmpty()) {
            cancelledReasonContainer.setVisibility(View.VISIBLE);
            ((TextView) v.findViewById(R.id.bookingDetailsCancelledReason)).setText(b.cancelReason);
        }

        bindBookingTimeline(v, b);
    }

    private void bindBookingTimeline(View v, BookingStore.Booking b) {
        LinearLayout container = v.findViewById(R.id.bookingDetailsTimelineContainer);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        List<BookingStore.TimelineEntry> entries = b.timeline;

        for (int i = 0; i < entries.size(); i++) {
            BookingStore.TimelineEntry entry = entries.get(i);
            boolean isLast = i == entries.size() - 1;
            boolean filled = entry.state != BookingStore.TIMELINE_UPCOMING;

            View row = inflater.inflate(R.layout.view_reservation_timeline_row, container, false);
            TextView label = row.findViewById(R.id.timelineLabel);
            label.setText(entry.label);
            label.setTextColor(ThemeManager.color(activity, filled ? R.attr.textPrimary : R.attr.textMuted));
            label.setTypeface(null, entry.state == BookingStore.TIMELINE_CURRENT ? Typeface.BOLD : Typeface.NORMAL);

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
                if (b.status == BookingStore.STATUS_CONFIRMED) {
                    dot.setBackgroundTintList(ColorStateList.valueOf(ThemeManager.color(activity, R.attr.successText)));
                } else if (b.status == BookingStore.STATUS_CANCELLED) {
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

    private boolean isCottageKtvBooking(BookingStore.Booking b) {
        return b.roomOption != null && b.roomOption.category == ReservationCatalog.CATEGORY_COTTAGES_KTV;
    }

    private StatusPresentation resolveBookingStatus(BookingStore.Booking b) {
        if (b.status == BookingStore.STATUS_CONFIRMED) {
            return new StatusPresentation(R.attr.successText, R.string.res_status_confirmed, R.string.res_desc_confirmed_booking);
        }
        if (b.status == BookingStore.STATUS_CANCELLED) {
            if (BookingStore.SUB_REJECTED.equals(b.subStatus)) {
                return new StatusPresentation(R.attr.errorText, R.string.res_status_rejected, R.string.res_desc_rejected_booking);
            }
            return new StatusPresentation(R.attr.textMuted, R.string.res_status_cancelled, R.string.res_desc_cancelled_booking);
        }
        if (BookingStore.SUB_ADDITIONAL_CONFIRMATION.equals(b.subStatus)) {
            return new StatusPresentation(R.attr.warningText, R.string.res_status_additional_confirmation,
                    R.string.res_desc_additional_confirmation);
        }
        if (BookingStore.SUB_ALTERNATIVE_PROPOSED.equals(b.subStatus)) {
            return new StatusPresentation(R.attr.infoText, R.string.res_status_action_required, R.string.res_desc_action_required);
        }
        return new StatusPresentation(R.attr.accentPrimary, R.string.res_status_pending, R.string.res_desc_pending);
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

    private String formatBookingGuests(int guestAdults, int guestChildren) {
        return guestAdults + (guestAdults == 1 ? " Adult" : " Adults") + " · "
                + guestChildren + (guestChildren == 1 ? " Child" : " Children");
    }

    /** Same-month compaction (e.g. "Sep 5–7, 2026"), mirroring ReservationFlowController#formatDateRange. */
    private String formatBookingDateRange(long inMillis, long outMillis) {
        if (inMillis < 0 || outMillis < 0) {
            return "";
        }
        if (inMillis == outMillis) {
            return formatDateTime(inMillis);
        }
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(inMillis);
        Calendar bCal = Calendar.getInstance();
        bCal.setTimeInMillis(outMillis);
        if (a.get(Calendar.YEAR) == bCal.get(Calendar.YEAR) && a.get(Calendar.MONTH) == bCal.get(Calendar.MONTH)) {
            String monthDay = new SimpleDateFormat("MMM d", Locale.US).format(new Date(inMillis));
            String dayOnly = new SimpleDateFormat("d", Locale.US).format(new Date(outMillis));
            String year = new SimpleDateFormat("yyyy", Locale.US).format(new Date(inMillis));
            return monthDay + "–" + dayOnly + ", " + year;
        }
        return formatDate(inMillis) + " – " + formatDate(outMillis);
    }

    private String formatDateTime(long millis) {
        if (millis < 0) {
            return "";
        }
        return new SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.US).format(new Date(millis));
    }

    private void resetFlow() {
        checkInMillis = -1;
        checkOutMillis = -1;
        adults = 0;
        children = 0;
        selectedTab = TAB_HOTEL_ROOMS;
        cottageRateType = "";
        cottageDateMillis = -1;
        cottageTimeHour = -1;
        cottageTimeMinute = -1;
        cottageAdults = 1;
        cottageChildren = 0;
        ktvRateType = "";
        ktvDateMillis = -1;
        ktvStartHour = -1;
        ktvStartMinute = -1;
        ktvGuestCount = 1;
        hasSearchedHotelRooms = false;
        availabilityErrored = false;
        defaultRooms = new ArrayList<>();
        isLoadingDefaultRooms = false;
        defaultRoomsLoaded = false;
        cottageItems = new ArrayList<>();
        ktvItems = new ArrayList<>();
        isLoadingCottageKtv = false;
        cottageKtvLoaded = false;
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
