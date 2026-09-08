package com.aguafriogarden.resortinc;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
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

import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.AvailabilityResponse;
import com.aguafriogarden.resortinc.network.CottageKtvListResponse;
import com.aguafriogarden.resortinc.network.CottageKtvOption;
import com.aguafriogarden.resortinc.network.RoomListResponse;
import com.aguafriogarden.resortinc.network.RoomSummary;
import com.aguafriogarden.resortinc.network.RoomTypeAvailability;
import com.bumptech.glide.Glide;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Drives the Reservation Module reached from the Reserve tab: a merged
 * search-and-browse screen (reservation dates/party size in a collapsible
 * drag sheet, mirroring {@link HotelBookingFlowController}'s Book tab
 * screen, overlaying the available-accommodations list below it), a single
 * accommodation selection, review, guest information, a payment-free
 * summary, submission, and a status-driven My Reservations / Details flow.
 * This is a frontend prototype backed by {@link ReservationStore} (in-memory
 * mock data, no backend) — see that class's javadoc for what is and isn't
 * simulated. One instance is kept alive by {@link DashboardActivity} for as
 * long as the app is open, so in-progress answers survive switching tabs and
 * back.
 */
final class ReservationFlowController {

    private static final int SCREEN_DATES = 0;
    private static final int SCREEN_REVIEW = 2;
    private static final int SCREEN_GUEST_INFO = 3;
    private static final int SCREEN_SUMMARY = 4;
    private static final int SCREEN_SUBMITTED = 5;
    private static final int SCREEN_MY_LIST = 6;
    private static final int SCREEN_DETAILS = 7;

    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

    private static final int PANEL_ANIMATION_MS = 220;
    private static final float PANEL_TAP_SLOP_PX = 16f;

    private final Activity activity;
    private final Runnable onBackToHome;
    private final FrameLayout root;

    private int currentScreen = -1;

    // ---- Search panel drag/collapse state (UI-only, reset each time the merged
    // search screen binds) --------------------------------------------------
    private int panelCollapseDistance = 0;
    private float panelFraction = 0f; // 0 = fully expanded, 1 = fully collapsed
    private ValueAnimator panelAnimator;

    // ---- Category tab: Hotel Rooms / Cottage / KTV each get their own field group and
    // card style below (see #updateCategoryTabs / #renderRoomOptions) -----------------
    private static final int TAB_HOTEL_ROOMS = 0;
    private static final int TAB_COTTAGE = 1;
    private static final int TAB_KTV = 2;
    private int selectedTab = TAB_HOTEL_ROOMS;

    // ---- Dates + guests (Hotel Rooms tab) -----------------------------------
    private long checkInMillis = -1;
    private long checkOutMillis = -1;
    private int adults = 1;
    private int children = 0;

    // ---- Live Hotel Rooms catalog (real Admin Web data, see HotelBookingFlowController's
    // identical fetchDefaultRooms/fetchAvailability) — before a dated search, only the
    // date-less default catalog is known (no real per-room quantity yet); after Search,
    // real dated availability replaces it. --------------------------------------------
    private boolean hasSearchedHotelRooms = false;
    private boolean availabilityErrored = false;
    private List<RoomSummary> defaultRooms = new ArrayList<>();
    private boolean isLoadingDefaultRooms = false;
    private boolean defaultRoomsLoaded = false;
    private List<RoomTypeAvailability> availableRoomTypes = new ArrayList<>();
    private boolean isLoadingAvailability = false;

    // ---- Rate type / date / time / guests (Cottage tab) ----------------------
    private String cottageRateType = "";
    private long cottageDateMillis = -1;
    private int cottageTimeHour = -1;
    private int cottageTimeMinute = -1;
    private int cottageAdults = 1;
    private int cottageChildren = 0;

    // ---- Rate type / date / time / guests (KTV tab) ---------------------------
    // Duration is a client-side placeholder (see plan/memory: no backend rate-type field
    // exists yet) — End Time is derived from Start Time + the selected rate type's hours.
    private static final int KTV_REGULAR_HOURS = 5;
    private static final int KTV_CONSUMABLE_HOURS = 3;
    private String ktvRateType = "";
    private long ktvDateMillis = -1;
    private int ktvStartHour = -1;
    private int ktvStartMinute = -1;
    private int ktvGuestCount = 1;

    // ---- Accommodation selection: only one option may have qty > 0 at a time, EXCEPT
    // while browsing the Cottage tab, where multiple cottage types may carry a quantity
    // at once (see #renderRoomOptions) until a card's Reserve button finalizes one. -----
    private final Map<Integer, Integer> roomQuantities = new LinkedHashMap<>();

    // ---- Cottages & KTV catalog: fetched from the Admin Web's Cottage Management / KTV
    // Management data via GET /api/booking/cottages-ktv, not hardcoded ------------------
    private List<ReservationCatalog.RoomOption> cottageOptions = new ArrayList<>();
    private List<ReservationCatalog.RoomOption> ktvOptions = new ArrayList<>();
    private boolean isLoadingCottageKtv = false;
    private boolean cottageKtvLoaded = false;

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
            case SCREEN_REVIEW:
                showScreen(SCREEN_DATES);
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
                return R.layout.view_reservation_search;
        }
    }

    private void bindScreen(int screen, View v) {
        switch (screen) {
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
                bindDatesAndRooms(v);
                break;
        }
    }

    // ---- Screen 1: search (dates/guests) + browse (available accommodations) --

    private void bindDatesAndRooms(View v) {
        v.findViewById(R.id.reservationMyListLink).setOnClickListener(view -> showScreen(SCREEN_MY_LIST));

        View tabRooms = v.findViewById(R.id.reservationTabRooms);
        View tabCottage = v.findViewById(R.id.reservationTabCottage);
        View tabKtv = v.findViewById(R.id.reservationTabKtv);

        View checkIn = v.findViewById(R.id.reservationCheckInField);
        View checkOut = v.findViewById(R.id.reservationCheckOutField);
        TextView checkInDateText = v.findViewById(R.id.reservationCheckInDateText);
        TextView checkInDayText = v.findViewById(R.id.reservationCheckInDayText);
        TextView checkOutDateText = v.findViewById(R.id.reservationCheckOutDateText);
        TextView checkOutDayText = v.findViewById(R.id.reservationCheckOutDayText);
        TextView checkInError = v.findViewById(R.id.reservationCheckInError);
        TextView checkOutError = v.findViewById(R.id.reservationCheckOutError);

        TextView adultsValue = v.findViewById(R.id.reservationAdultsValue);
        TextView childrenValue = v.findViewById(R.id.reservationChildrenValue);

        updateDateField(checkInDateText, checkInDayText, checkInMillis);
        updateDateField(checkOutDateText, checkOutDayText, checkOutMillis);
        adultsValue.setText(String.valueOf(adults));
        childrenValue.setText(String.valueOf(children));

        View.OnClickListener openDatePicker = view -> GlassDatePicker.showRangePicker(activity, checkInMillis, checkOutMillis,
                System.currentTimeMillis() - 1000L, (start, end) -> {
                    checkInMillis = start;
                    checkOutMillis = end;
                    updateDateField(checkInDateText, checkInDayText, checkInMillis);
                    updateDateField(checkOutDateText, checkOutDayText, checkOutMillis);
                    checkInError.setVisibility(View.GONE);
                    checkOutError.setVisibility(View.GONE);
                });
        checkIn.setOnClickListener(openDatePicker);
        checkOut.setOnClickListener(openDatePicker);

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

        LinearLayout container = v.findViewById(R.id.reservationRoomsContainer);

        bindCottageFields(v);
        bindKtvFields(v);

        updateCategoryTabs(v, tabRooms, tabCottage, tabKtv);

        tabRooms.setOnClickListener(view -> {
            if (selectedTab != TAB_HOTEL_ROOMS) {
                selectedTab = TAB_HOTEL_ROOMS;
                updateCategoryTabs(v, tabRooms, tabCottage, tabKtv);
                renderRoomOptions(v, container);
            }
        });
        tabCottage.setOnClickListener(view -> {
            if (selectedTab != TAB_COTTAGE) {
                selectedTab = TAB_COTTAGE;
                updateCategoryTabs(v, tabRooms, tabCottage, tabKtv);
                renderRoomOptions(v, container);
            }
        });
        tabKtv.setOnClickListener(view -> {
            if (selectedTab != TAB_KTV) {
                selectedTab = TAB_KTV;
                updateCategoryTabs(v, tabRooms, tabCottage, tabKtv);
                renderRoomOptions(v, container);
            }
        });

        v.findViewById(R.id.reservationSearchButton).setOnClickListener(view -> {
            boolean valid;
            if (selectedTab == TAB_HOTEL_ROOMS) {
                valid = validateDates(checkInError, checkOutError);
            } else if (selectedTab == TAB_COTTAGE) {
                valid = validateCottageFields(v);
            } else {
                valid = validateKtvFields(v);
            }
            if (valid) {
                if (selectedTab == TAB_HOTEL_ROOMS) {
                    fetchAvailability(v, container);
                }
                animatePanelTo(v, 1f);
            }
        });

        v.findViewById(R.id.reservationResultsChangeDatesButton).setOnClickListener(view -> expandPanel(v));
        v.findViewById(R.id.reservationResultsTryAgainButton).setOnClickListener(view -> fetchAvailability(v, container));

        v.findViewById(R.id.reservationKtvProceedButton).setOnClickListener(view -> {
            if (!validateKtvFields(v)) {
                expandPanel(v);
                return;
            }
            if (selectedKtvOption() == null) {
                Toast.makeText(activity, R.string.toast_select_ktv_room_required, Toast.LENGTH_LONG).show();
                expandPanel(v);
                return;
            }
            showScreen(SCREEN_REVIEW);
        });

        renderRoomOptions(v, container);
        fetchCottagesKtv(v, container);
        fetchDefaultRooms(v, container);

        setUpDragPanel(v);
    }

    /** Binds the Cottage tab's own rate type / date / time / adults-children-total guest
     *  fields, independent from both the Hotel Rooms and KTV field groups. */
    private void bindCottageFields(View v) {
        View cottageDateField = v.findViewById(R.id.reservationCottageDateField);
        TextView cottageDateText = v.findViewById(R.id.reservationCottageDateText);
        TextView cottageDateError = v.findViewById(R.id.reservationCottageDateError);
        View cottageTimeField = v.findViewById(R.id.reservationCottageTimeField);
        TextView cottageTimeText = v.findViewById(R.id.reservationCottageTimeText);
        TextView cottageTimeError = v.findViewById(R.id.reservationCottageTimeError);
        RadioGroup rateGroup = v.findViewById(R.id.reservationCottageRateTypeGroup);
        TextView rateError = v.findViewById(R.id.reservationCottageRateTypeError);
        TextView adultsValue = v.findViewById(R.id.reservationCottageAdultsValue);
        TextView childrenValue = v.findViewById(R.id.reservationCottageChildrenValue);
        TextView totalGuestValue = v.findViewById(R.id.reservationCottageTotalGuestValue);

        updateCottageDateField(cottageDateText);
        updateCottageTimeField(cottageTimeText);
        adultsValue.setText(String.valueOf(cottageAdults));
        childrenValue.setText(String.valueOf(cottageChildren));
        totalGuestValue.setText(String.valueOf(cottageAdults + cottageChildren));
        if (ReservationCatalog.RATE_TYPE_DAY.equals(cottageRateType)) {
            rateGroup.check(R.id.reservationCottageRateDay);
        } else if (ReservationCatalog.RATE_TYPE_NIGHT.equals(cottageRateType)) {
            rateGroup.check(R.id.reservationCottageRateNight);
        } else {
            rateGroup.clearCheck();
        }

        rateGroup.setOnCheckedChangeListener((group, checkedId) -> {
            cottageRateType = checkedId == R.id.reservationCottageRateDay
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

        v.findViewById(R.id.reservationCottageAdultsMinus).setOnClickListener(view -> {
            if (cottageAdults > 1) {
                cottageAdults--;
                adultsValue.setText(String.valueOf(cottageAdults));
                totalGuestValue.setText(String.valueOf(cottageAdults + cottageChildren));
            }
        });
        v.findViewById(R.id.reservationCottageAdultsPlus).setOnClickListener(view -> {
            cottageAdults++;
            adultsValue.setText(String.valueOf(cottageAdults));
            totalGuestValue.setText(String.valueOf(cottageAdults + cottageChildren));
        });
        v.findViewById(R.id.reservationCottageChildrenMinus).setOnClickListener(view -> {
            if (cottageChildren > 0) {
                cottageChildren--;
                childrenValue.setText(String.valueOf(cottageChildren));
                totalGuestValue.setText(String.valueOf(cottageAdults + cottageChildren));
            }
        });
        v.findViewById(R.id.reservationCottageChildrenPlus).setOnClickListener(view -> {
            cottageChildren++;
            childrenValue.setText(String.valueOf(cottageChildren));
            totalGuestValue.setText(String.valueOf(cottageAdults + cottageChildren));
        });
    }

    /** Binds the KTV tab's own rate type / date / start-end time / duration / guest count
     *  fields. End Time and Duration are read-only, recomputed whenever the rate type or
     *  start time changes (see {@link #updateKtvEndTimeAndDuration}). */
    private void bindKtvFields(View v) {
        View ktvDateField = v.findViewById(R.id.reservationKtvDateField);
        TextView ktvDateText = v.findViewById(R.id.reservationKtvDateText);
        TextView ktvDateError = v.findViewById(R.id.reservationKtvDateError);
        View ktvStartTimeField = v.findViewById(R.id.reservationKtvStartTimeField);
        TextView ktvStartTimeText = v.findViewById(R.id.reservationKtvStartTimeText);
        TextView ktvStartTimeError = v.findViewById(R.id.reservationKtvStartTimeError);
        RadioGroup rateGroup = v.findViewById(R.id.reservationKtvRateTypeGroup);
        TextView rateError = v.findViewById(R.id.reservationKtvRateTypeError);
        TextView guestCountValue = v.findViewById(R.id.reservationKtvGuestCountValue);

        updateKtvDateField(ktvDateText);
        updateKtvStartTimeField(ktvStartTimeText);
        updateKtvEndTimeAndDuration(v);
        guestCountValue.setText(String.valueOf(ktvGuestCount));
        if (ReservationCatalog.RATE_TYPE_REGULAR.equals(ktvRateType)) {
            rateGroup.check(R.id.reservationKtvRateRegular);
        } else if (ReservationCatalog.RATE_TYPE_CONSUMABLE.equals(ktvRateType)) {
            rateGroup.check(R.id.reservationKtvRateConsumable);
        } else {
            rateGroup.clearCheck();
        }

        rateGroup.setOnCheckedChangeListener((group, checkedId) -> {
            ktvRateType = checkedId == R.id.reservationKtvRateRegular
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

        v.findViewById(R.id.reservationKtvGuestCountMinus).setOnClickListener(view -> {
            if (ktvGuestCount > 1) {
                ktvGuestCount--;
                guestCountValue.setText(String.valueOf(ktvGuestCount));
            }
        });
        v.findViewById(R.id.reservationKtvGuestCountPlus).setOnClickListener(view -> {
            ktvGuestCount++;
            guestCountValue.setText(String.valueOf(ktvGuestCount));
        });
    }

    /** Loads the Cottages & KTV Type/Category catalog from the Admin Web's Cottage Management /
     *  KTV Management data (see {@link CottageKtvOption}) — no mobile-side hardcoded Type or
     *  Category values. Runs once per flow instance; new keys are added to roomQuantities
     *  without disturbing any Hotel Rooms selection already in progress. */
    private void fetchCottagesKtv(View v, LinearLayout container) {
        if (isLoadingCottageKtv || cottageKtvLoaded) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            return;
        }
        isLoadingCottageKtv = true;
        if (selectedTab == TAB_COTTAGE || selectedTab == TAB_KTV) {
            renderRoomOptions(v, container);
        }

        ApiClient.bookingApi().getCottagesKtv("Bearer " + token).enqueue(new Callback<CottageKtvListResponse>() {
            @Override
            public void onResponse(Call<CottageKtvListResponse> call, Response<CottageKtvListResponse> response) {
                isLoadingCottageKtv = false;
                cottageKtvLoaded = true;
                cottageOptions = new ArrayList<>();
                ktvOptions = new ArrayList<>();
                if (response.isSuccessful() && response.body() != null) {
                    if (response.body().cottages != null) {
                        for (CottageKtvOption item : response.body().cottages) {
                            cottageOptions.add(toRoomOption(item, ReservationCatalog.BOOKING_TYPE_COTTAGE));
                        }
                    }
                    if (response.body().ktv_rooms != null) {
                        for (CottageKtvOption item : response.body().ktv_rooms) {
                            ktvOptions.add(toRoomOption(item, ReservationCatalog.BOOKING_TYPE_KTV));
                        }
                    }
                }
                for (ReservationCatalog.RoomOption option : cottageOptions) {
                    if (!roomQuantities.containsKey(option.id)) {
                        roomQuantities.put(option.id, 0);
                    }
                }
                for (ReservationCatalog.RoomOption option : ktvOptions) {
                    if (!roomQuantities.containsKey(option.id)) {
                        roomQuantities.put(option.id, 0);
                    }
                }
                if (selectedTab == TAB_COTTAGE || selectedTab == TAB_KTV) {
                    renderRoomOptions(v, container);
                }
            }

            @Override
            public void onFailure(Call<CottageKtvListResponse> call, Throwable t) {
                isLoadingCottageKtv = false;
                cottageKtvLoaded = true;
                cottageOptions = new ArrayList<>();
                ktvOptions = new ArrayList<>();
                if (selectedTab == TAB_COTTAGE || selectedTab == TAB_KTV) {
                    renderRoomOptions(v, container);
                }
            }
        });
    }

    private ReservationCatalog.RoomOption toRoomOption(CottageKtvOption item, String bookingType) {
        return new ReservationCatalog.RoomOption(item.group_id, ReservationCatalog.CATEGORY_COTTAGES_KTV,
                item.category_name, item.variant_name, item.description, item.capacity,
                (int) Math.round(item.price), item.price_unit, item.available_quantity, bookingType,
                item.image_path);
    }

    /** Loads the general (date-less) Hotel Rooms catalog so accommodations are visible the
     *  moment the guest opens the Reserve tab, before they've picked dates — mirrors
     *  HotelBookingFlowController#fetchDefaultRooms exactly (same endpoint/DTO). A real, dated
     *  {@link #fetchAvailability} search takes over once it succeeds. Runs once per flow
     *  instance. */
    private void fetchDefaultRooms(View v, LinearLayout container) {
        if (isLoadingDefaultRooms || defaultRoomsLoaded || hasSearchedHotelRooms) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            return;
        }
        isLoadingDefaultRooms = true;
        if (selectedTab == TAB_HOTEL_ROOMS) {
            renderRoomOptions(v, container);
        }

        ApiClient.bookingApi().getRooms("Bearer " + token).enqueue(new Callback<RoomListResponse>() {
            @Override
            public void onResponse(Call<RoomListResponse> call, Response<RoomListResponse> response) {
                isLoadingDefaultRooms = false;
                defaultRoomsLoaded = true;
                defaultRooms = response.isSuccessful() && response.body() != null && response.body().rooms != null
                        ? response.body().rooms : new ArrayList<>();
                for (RoomSummary room : defaultRooms) {
                    if (!roomQuantities.containsKey(room.id)) {
                        roomQuantities.put(room.id, 0);
                    }
                }
                if (selectedTab == TAB_HOTEL_ROOMS) {
                    renderRoomOptions(v, container);
                }
            }

            @Override
            public void onFailure(Call<RoomListResponse> call, Throwable t) {
                isLoadingDefaultRooms = false;
                defaultRoomsLoaded = true;
                defaultRooms = new ArrayList<>();
                if (selectedTab == TAB_HOTEL_ROOMS) {
                    renderRoomOptions(v, container);
                }
            }
        });
    }

    /** Checks real room availability on the shared backend for the selected dates/guests,
     *  mirroring HotelBookingFlowController#fetchAvailability exactly (same endpoint/DTO). */
    private void fetchAvailability(View v, LinearLayout container) {
        if (isLoadingAvailability) {
            return;
        }
        String token = ProfileStore.getAuthToken(activity);
        if (token.isEmpty()) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }
        isLoadingAvailability = true;
        renderRoomOptions(v, container);

        String checkInStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(checkInMillis));
        String checkOutStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(checkOutMillis));

        ApiClient.bookingApi().checkAvailability("Bearer " + token, checkInStr, checkOutStr, adults, children)
                .enqueue(new Callback<AvailabilityResponse>() {
                    @Override
                    public void onResponse(Call<AvailabilityResponse> call, Response<AvailabilityResponse> response) {
                        isLoadingAvailability = false;
                        if (response.isSuccessful() && response.body() != null) {
                            availableRoomTypes = response.body().room_types != null
                                    ? response.body().room_types : new ArrayList<>();
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
                        renderRoomOptions(v, container);
                    }

                    @Override
                    public void onFailure(Call<AvailabilityResponse> call, Throwable t) {
                        isLoadingAvailability = false;
                        availabilityErrored = true;
                        Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
                        renderRoomOptions(v, container);
                    }
                });
    }

    private ReservationCatalog.RoomOption toRoomOption(RoomSummary room) {
        // Real per-room quantity isn't knowable before a dated search — cap at 0 so the
        // stepper stays disabled until Search runs, mirroring HotelBookingFlowController's
        // "BOOK NOW here can't jump to Review yet" default-catalog behavior.
        return new ReservationCatalog.RoomOption(room.id, ReservationCatalog.CATEGORY_HOTEL_ROOMS,
                resolveRoomCategoryLabel(room), room.name, room.description, room.capacity,
                (int) Math.round(room.price_per_night), "night", 0, "", room.image_path);
    }

    private ReservationCatalog.RoomOption toRoomOption(RoomTypeAvailability room) {
        return new ReservationCatalog.RoomOption(room.group_id, ReservationCatalog.CATEGORY_HOTEL_ROOMS,
                room.category_name, room.variant_name, room.description, room.capacity,
                (int) Math.round(room.price_per_night), "night", room.available_quantity, "", room.image_path);
    }

    /** The room's category/building label (e.g. "Villa", "Claricon"), trying every field name
     *  this backend uses for that concept since the real one on this endpoint isn't confirmed —
     *  mirrors HotelBookingFlowController#resolveRoomCategoryLabel. */
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

    /** Validates the check-in/check-out fields against the existing reservation rules
     *  (required, not in the past, checkout after checkin), showing inline field errors. */
    private boolean validateDates(TextView checkInError, TextView checkOutError) {
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
        return valid;
    }

    /** Validates the Cottage tab's rate type/date/time fields, showing inline field errors. */
    private boolean validateCottageFields(View v) {
        TextView rateError = v.findViewById(R.id.reservationCottageRateTypeError);
        TextView dateError = v.findViewById(R.id.reservationCottageDateError);
        TextView timeError = v.findViewById(R.id.reservationCottageTimeError);
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

    /** Validates the KTV tab's rate type/date/start-time fields, showing inline field errors.
     *  End Time/Duration are derived, not user-entered, so they need no validation of their own. */
    private boolean validateKtvFields(View v) {
        TextView rateError = v.findViewById(R.id.reservationKtvRateTypeError);
        TextView dateError = v.findViewById(R.id.reservationKtvDateError);
        TextView timeError = v.findViewById(R.id.reservationKtvStartTimeError);
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

    /** Combines the selected Cottage date and time into a single instant, or -1 if either is
     *  still unset. Used as both the "check-in" and "check-out" millis for a Cottage
     *  reservation, signaling to display code that it's a single-moment schedule, not a range. */
    private long combinedCottageMillis() {
        if (cottageDateMillis < 0 || cottageTimeHour < 0) {
            return -1;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(cottageDateMillis);
        calendar.set(Calendar.HOUR_OF_DAY, cottageTimeHour);
        calendar.set(Calendar.MINUTE, cottageTimeMinute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /** Combines the selected KTV date and start time into a single instant, or -1 if either is
     *  still unset — the KTV equivalent of {@link #combinedCottageMillis()}. */
    private long combinedKtvMillis() {
        if (ktvDateMillis < 0 || ktvStartHour < 0) {
            return -1;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(ktvDateMillis);
        calendar.set(Calendar.HOUR_OF_DAY, ktvStartHour);
        calendar.set(Calendar.MINUTE, ktvStartMinute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /** Picks the right combined date+time for a Cottages/KTV option (they use independent
     *  date/time state — see the Cottage vs. KTV field groups) — the single instant used as
     *  both "check-in" and "check-out" for such a reservation. */
    private long combinedScheduleMillis(ReservationCatalog.RoomOption option) {
        if (option == null) {
            return -1;
        }
        for (ReservationCatalog.RoomOption ktv : ktvOptions) {
            if (ktv.id == option.id) {
                return combinedKtvMillis();
            }
        }
        return combinedCottageMillis();
    }

    /** The KTV room currently selected via its card's Select toggle, or null if none — the
     *  KTV equivalent of {@link #selectedOption()}, scoped to just the ktvOptions list. */
    private ReservationCatalog.RoomOption selectedKtvOption() {
        for (ReservationCatalog.RoomOption option : ktvOptions) {
            Integer qty = roomQuantities.get(option.id);
            if (qty != null && qty > 0) {
                return option;
            }
        }
        return null;
    }

    private boolean isKtv(ReservationCatalog.RoomOption option) {
        if (option == null) {
            return false;
        }
        for (ReservationCatalog.RoomOption ktv : ktvOptions) {
            if (ktv.id == option.id) {
                return true;
            }
        }
        return false;
    }

    private boolean isCottageOptionId(int id) {
        for (ReservationCatalog.RoomOption cottage : cottageOptions) {
            if (cottage.id == id) {
                return true;
            }
        }
        return false;
    }

    /** Cottage/KTV each carry their own independent guest-count fields (Cottage:
     *  adults+children; KTV: a single guest count, mapped to "adults" here with no children,
     *  since the shared submission/display schema only has an adults/children pair). */
    private int adultsFor(ReservationCatalog.RoomOption option) {
        if (option == null || !isCottageKtv(option)) {
            return adults;
        }
        return isKtv(option) ? ktvGuestCount : cottageAdults;
    }

    private int childrenFor(ReservationCatalog.RoomOption option) {
        if (option == null || !isCottageKtv(option)) {
            return children;
        }
        return isKtv(option) ? 0 : cottageChildren;
    }

    /** The fixed duration (hours) for the given KTV rate type placeholder, or 0 if unset. */
    private int ktvDurationHours(String rateType) {
        if (ReservationCatalog.RATE_TYPE_REGULAR.equals(rateType)) {
            return KTV_REGULAR_HOURS;
        }
        if (ReservationCatalog.RATE_TYPE_CONSUMABLE.equals(rateType)) {
            return KTV_CONSUMABLE_HOURS;
        }
        return 0;
    }

    // ---- Search panel collapse/drag mechanics (mirrors HotelBookingFlowController) --

    private void setUpDragPanel(View v) {
        View panelContent = v.findViewById(R.id.reservationSearchPanelContent);
        View dragHandle = v.findViewById(R.id.reservationDragHandle);
        ScrollView resultsScrollView = v.findViewById(R.id.reservationResultsScrollView);

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

    private void setPanelFraction(View v, float fraction) {
        panelFraction = fraction;
        View dragSheet = v.findViewById(R.id.reservationDragSheet);
        View dragHandle = v.findViewById(R.id.reservationDragHandle);
        ScrollView resultsScrollView = v.findViewById(R.id.reservationResultsScrollView);

        dragSheet.setTranslationY(-fraction * panelCollapseDistance);
        resultsScrollView.setPadding(
                resultsScrollView.getPaddingLeft(),
                dragHandle.getHeight() + Math.round((1f - fraction) * panelCollapseDistance),
                resultsScrollView.getPaddingRight(),
                resultsScrollView.getPaddingBottom());
    }

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

    private void expandPanel(View v) {
        animatePanelTo(v, 0f);
    }

    /** Shows the loading/empty/results state for the active tab, mirroring
     *  HotelBookingFlowController#renderAvailabilityResults exactly (single section header +
     *  flat card list, no inline per-category banners), then populates the accommodation
     *  cards below it — stepper cards for Hotel Rooms/Cottage, select-toggle cards for KTV. */
    private void renderRoomOptions(View v, LinearLayout container) {
        View loading = v.findViewById(R.id.reservationResultsLoading);
        View emptyState = v.findViewById(R.id.reservationResultsEmptyState);
        View errorState = v.findViewById(R.id.reservationResultsErrorState);
        View sectionHeader = v.findViewById(R.id.reservationResultsSectionHeader);
        TextView sectionLabel = v.findViewById(R.id.reservationResultsSectionLabel);
        ImageView sectionIcon = v.findViewById(R.id.reservationResultsSectionIcon);
        TextView resultsSubtitle = v.findViewById(R.id.reservationResultsSubtitle);
        View cottageTotalRow = v.findViewById(R.id.reservationCottageTotalUnitsRow);
        TextView cottageTotalValue = v.findViewById(R.id.reservationCottageTotalUnitsValue);
        View ktvFooter = v.findViewById(R.id.reservationKtvFooter);
        TextView ktvTotalValue = v.findViewById(R.id.reservationKtvTotalSelectedValue);
        Button ktvProceedButton = v.findViewById(R.id.reservationKtvProceedButton);
        Button hotelReserveButton = v.findViewById(R.id.reservationHotelReserveButton);
        container.removeAllViews();

        boolean hotelRooms = selectedTab == TAB_HOTEL_ROOMS;
        boolean cottage = selectedTab == TAB_COTTAGE;
        boolean ktv = selectedTab == TAB_KTV;

        hotelReserveButton.setVisibility(hotelRooms ? View.VISIBLE : View.GONE);
        cottageTotalRow.setVisibility(cottage ? View.VISIBLE : View.GONE);
        ktvFooter.setVisibility(ktv ? View.VISIBLE : View.GONE);
        updateHotelReserveButton(hotelReserveButton, v);
        updateCottageTotalUnits(cottageTotalValue);
        updateKtvFooter(ktvTotalValue, ktvProceedButton);

        resultsSubtitle.setText(!hotelRooms || hasSearchedHotelRooms
                ? R.string.label_available_accommodations_subtitle
                : R.string.label_available_accommodations_subtitle_browse);

        boolean hotelLoading = hotelRooms && (isLoadingAvailability || (!hasSearchedHotelRooms && isLoadingDefaultRooms));
        if ((!hotelRooms && isLoadingCottageKtv) || hotelLoading) {
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

        // Hotel Rooms comes from the Admin Web's live room catalog (default catalog before a
        // dated search, real dated availability after — see fetchDefaultRooms/fetchAvailability);
        // Cottage/KTV come from cottageOptions/ktvOptions, fetched live in fetchCottagesKtv().
        List<ReservationCatalog.RoomOption> options;
        if (hotelRooms) {
            options = new ArrayList<>();
            int totalGuests = adults + children;
            if (hasSearchedHotelRooms) {
                for (RoomTypeAvailability room : availableRoomTypes) {
                    if (meetsCapacity(room.capacity, totalGuests)) {
                        options.add(toRoomOption(room));
                    }
                }
            } else {
                for (RoomSummary room : defaultRooms) {
                    if (meetsCapacity(room.capacity, totalGuests)) {
                        options.add(toRoomOption(room));
                    }
                }
            }
        } else {
            options = cottage ? cottageOptions : ktvOptions;
        }

        if (options.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            sectionHeader.setVisibility(View.GONE);
            container.setVisibility(View.GONE);
            return;
        }
        emptyState.setVisibility(View.GONE);
        sectionIcon.setImageResource(hotelRooms ? R.drawable.ic_bed : cottage ? R.drawable.ic_pool : R.drawable.ic_mic);
        sectionLabel.setText(hotelRooms ? R.string.label_section_rooms
                : cottage ? R.string.label_select_desired_cottage_type : R.string.label_select_ktv_room);
        sectionHeader.setVisibility(View.VISIBLE);
        container.setVisibility(View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(activity);
        for (ReservationCatalog.RoomOption option : options) {
            View card = hotelRooms
                    ? buildHotelRoomCard(inflater, container, option, v)
                    : buildCottageKtvCard(inflater, container, option, cottage, v);
            container.addView(card);
        }
    }

    /** Hotel Rooms card: unchanged compact stepper-item style (this tab isn't part of the
     *  accommodation-card restyle — only Cottage/KTV are). */
    private View buildHotelRoomCard(LayoutInflater inflater, LinearLayout container,
            ReservationCatalog.RoomOption option, View v) {
        View card = inflater.inflate(R.layout.view_hotel_stepper_item, container, false);
        loadImage(card.findViewById(R.id.stepperItemImage), option.imagePath, R.drawable.ic_bed);
        ((TextView) card.findViewById(R.id.stepperItemName)).setText(option.variantName);
        TextView description = card.findViewById(R.id.stepperItemDescription);
        description.setText(option.description);
        description.setVisibility(View.VISIBLE);
        TextView meta = card.findViewById(R.id.stepperItemMeta);
        meta.setText(activity.getString(R.string.format_capacity, option.capacity));
        meta.setVisibility(View.VISIBLE);
        ((TextView) card.findViewById(R.id.stepperItemPrice)).setText(activity.getString(
                R.string.format_price_per_unit, formatMoney(option.pricePerNight), option.priceUnit));
        card.findViewById(R.id.stepperItemAvailableUnits).setVisibility(View.GONE);
        card.findViewById(R.id.stepperItemQuantityRow).setVisibility(View.VISIBLE);
        card.findViewById(R.id.stepperItemSelectButton).setVisibility(View.GONE);
        // One Reserve action for the whole tab now (reservationHotelReserveButton), not per card.
        card.findViewById(R.id.stepperItemReserveButton).setVisibility(View.GONE);

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
                renderRoomOptions(v, container);
            }
        });
        plus.setOnClickListener(view -> {
            int current = roomQuantities.get(option.id);
            if (current < option.maxQuantity) {
                // Hotel Rooms: only one accommodation may be selected at a time.
                for (Integer key : roomQuantities.keySet()) {
                    if (!key.equals(option.id)) {
                        roomQuantities.put(key, 0);
                    }
                }
                roomQuantities.put(option.id, current + 1);
                renderRoomOptions(v, container);
            }
        });

        return card;
    }

    /** Cottage/KTV card: the accommodation-card style (hero photo, availability badge) — Cottage
     *  keeps a quantity stepper + Reserve button, KTV gets a single Select/Selected toggle (see
     *  the KTV footer's Proceed button for what actually advances to Review). */
    private View buildCottageKtvCard(LayoutInflater inflater, LinearLayout container,
            ReservationCatalog.RoomOption option, boolean cottage, View v) {
        View card = inflater.inflate(R.layout.view_accommodation_card, container, false);
        loadImage(card.findViewById(R.id.accommodationImage), option.imagePath, cottage ? R.drawable.ic_pool : R.drawable.ic_mic);

        boolean available = option.maxQuantity > 0;
        TextView badge = card.findViewById(R.id.accommodationAvailabilityBadge);
        badge.setText(available ? R.string.booking_status_available : R.string.booking_status_no_rooms_available);
        badge.setBackgroundResource(available ? R.drawable.bg_badge_available_solid : R.drawable.bg_badge_full_solid);

        ((TextView) card.findViewById(R.id.accommodationName)).setText(option.categoryName + " (" + option.variantName + ")");
        ((TextView) card.findViewById(R.id.accommodationCapacity))
                .setText(activity.getString(R.string.format_capacity, option.capacity));
        TextView bedInfo = card.findViewById(R.id.accommodationBedInfo);
        if (option.description != null && !option.description.trim().isEmpty()) {
            bedInfo.setText(option.description);
            bedInfo.setVisibility(View.VISIBLE);
        } else {
            bedInfo.setVisibility(View.GONE);
        }
        ((TextView) card.findViewById(R.id.accommodationAvailableUnits)).setText(
                activity.getString(R.string.format_available_units, option.maxQuantity));
        card.findViewById(R.id.accommodationAvailableUnits).setVisibility(View.VISIBLE);
        ((TextView) card.findViewById(R.id.accommodationPrice)).setText(activity.getString(
                R.string.format_price_per_unit, formatMoney(option.pricePerNight), option.priceUnit));
        card.findViewById(R.id.accommodationBookNowButton).setVisibility(View.GONE);

        View quantityRow = card.findViewById(R.id.accommodationQuantityRow);
        Button reserveButton = card.findViewById(R.id.accommodationReserveButton);
        Button selectButton = card.findViewById(R.id.accommodationSelectButton);
        int qty = roomQuantities.get(option.id);

        if (!cottage) {
            // KTV rooms are a single mutually-exclusive pick, not a quantity.
            quantityRow.setVisibility(View.GONE);
            reserveButton.setVisibility(View.GONE);
            selectButton.setVisibility(View.VISIBLE);
            boolean selected = qty > 0;
            bindKtvSelectButton(selectButton, selected);
            selectButton.setOnClickListener(view -> {
                for (Integer key : roomQuantities.keySet()) {
                    roomQuantities.put(key, key.equals(option.id) && !selected ? 1 : 0);
                }
                renderRoomOptions(v, container);
            });
            return card;
        }

        quantityRow.setVisibility(View.VISIBLE);
        selectButton.setVisibility(View.GONE);
        reserveButton.setVisibility(View.VISIBLE);

        TextView qtyValue = card.findViewById(R.id.accommodationQuantityValue);
        ImageView minus = card.findViewById(R.id.accommodationQuantityMinus);
        ImageView plus = card.findViewById(R.id.accommodationQuantityPlus);
        qtyValue.setText(String.valueOf(qty));
        setStepperEnabled(plus, qty < option.maxQuantity);

        minus.setOnClickListener(view -> {
            int current = roomQuantities.get(option.id);
            if (current > 0) {
                roomQuantities.put(option.id, current - 1);
                renderRoomOptions(v, container);
            }
        });
        plus.setOnClickListener(view -> {
            int current = roomQuantities.get(option.id);
            if (current < option.maxQuantity) {
                // Several cottage types may carry a quantity at once while browsing (see the
                // class-level selection model note) — but a reservation is still one category
                // at a time, so clear anything picked on the Hotel Rooms/KTV tabs.
                for (Integer key : roomQuantities.keySet()) {
                    if (!key.equals(option.id) && !isCottageOptionId(key)) {
                        roomQuantities.put(key, 0);
                    }
                }
                roomQuantities.put(option.id, current + 1);
                renderRoomOptions(v, container);
            }
        });

        // Reserve: enabled once this card is the selected one (qty > 0). Validates the Cottage
        // fields, finalizes this card's quantity as the one committed line item (discarding any
        // other cottage type's in-progress quantity), then proceeds straight to Review.
        reserveButton.setEnabled(qty > 0);
        reserveButton.setAlpha(qty > 0 ? 1f : 0.4f);
        reserveButton.setOnClickListener(view -> {
            if (!validateCottageFields(v)) {
                expandPanel(v);
                return;
            }
            for (Integer key : roomQuantities.keySet()) {
                if (!key.equals(option.id)) {
                    roomQuantities.put(key, 0);
                }
            }
            showScreen(SCREEN_REVIEW);
        });
        return card;
    }

    /** Styles the KTV card's toggle button for its current selected/unselected state. */
    private void bindKtvSelectButton(Button button, boolean selected) {
        button.setText(selected ? R.string.button_selected : R.string.button_select);
        button.setBackgroundResource(selected ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        button.setTextColor(selected ? activity.getColor(R.color.button_primary_text)
                : ThemeManager.color(activity, R.attr.textPrimary));
    }

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

    private void updateCottageTotalUnits(TextView cottageTotalValue) {
        int total = 0;
        for (ReservationCatalog.RoomOption option : cottageOptions) {
            Integer qty = roomQuantities.get(option.id);
            if (qty != null) {
                total += qty;
            }
        }
        cottageTotalValue.setText(String.valueOf(total));
    }

    private void updateKtvFooter(TextView ktvTotalValue, Button ktvProceedButton) {
        boolean selected = selectedKtvOption() != null;
        ktvTotalValue.setText(selected ? "1" : "0");
        ktvProceedButton.setEnabled(selected);
        ktvProceedButton.setAlpha(selected ? 1f : 0.4f);
    }

    /** One Reserve action for whichever Hotel Rooms card currently carries a quantity —
     *  replaces the old per-card Reserve button; still only one room type at a time
     *  (unchanged exclusivity in the "+" handler above), just a single shared action. */
    private void updateHotelReserveButton(Button hotelReserveButton, View v) {
        boolean selected = false;
        List<Integer> ids = new ArrayList<>();
        if (hasSearchedHotelRooms) {
            for (RoomTypeAvailability room : availableRoomTypes) {
                ids.add(room.group_id);
            }
        } else {
            for (RoomSummary room : defaultRooms) {
                ids.add(room.id);
            }
        }
        for (Integer id : ids) {
            Integer qty = roomQuantities.get(id);
            if (qty != null && qty > 0) {
                selected = true;
                break;
            }
        }
        hotelReserveButton.setEnabled(selected);
        hotelReserveButton.setAlpha(selected ? 1f : 0.4f);
        hotelReserveButton.setOnClickListener(view -> {
            if (!validateDates(v.findViewById(R.id.reservationCheckInError), v.findViewById(R.id.reservationCheckOutError))) {
                expandPanel(v);
                return;
            }
            showScreen(SCREEN_REVIEW);
        });
    }

    // ---- Screen 3: Review Your Reservation ----------------------------------

    private void bindReview(View v) {
        bindHeader(v, R.id.reservationReviewHeaderBar, R.string.reservation_review_title, () -> showScreen(SCREEN_DATES));

        ReservationCatalog.RoomOption option = selectedOption();
        boolean cottageKtv = isCottageKtv(option);

        View rowCheckIn = v.findViewById(R.id.reviewResRowCheckIn);
        View rowCheckOut = v.findViewById(R.id.reviewResRowCheckOut);
        View rowNights = v.findViewById(R.id.reviewResRowNights);
        if (cottageKtv) {
            bindRow(rowCheckIn, R.string.label_date_time, formatDateTime(combinedScheduleMillis(option)));
            rowCheckOut.setVisibility(View.GONE);
            rowNights.setVisibility(View.GONE);
        } else {
            bindRow(rowCheckIn, R.string.label_stay_check_in, formatDate(checkInMillis));
            bindRow(rowCheckOut, R.string.label_stay_check_out, formatDate(checkOutMillis));
            bindRow(rowNights, R.string.label_stay_nights, formatNights(nights()));
        }
        bindRow(v.findViewById(R.id.reviewResRowGuests), R.string.label_stay_guests,
                formatGuests(adultsFor(option), childrenFor(option)));

        LinearLayout container = v.findViewById(R.id.reviewAccommodationContainer);
        container.removeAllViews();
        if (option != null) {
            int qty = selectedQuantity();
            View row = LayoutInflater.from(activity).inflate(R.layout.view_hotel_review_room_row, container, false);
            ((ImageView) row.findViewById(R.id.reviewRoomImage)).setImageResource(
                    cottageKtv ? R.drawable.ic_pool : R.drawable.ic_bed);
            ((TextView) row.findViewById(R.id.reviewRoomName)).setText(option.categoryName + " · " + option.variantName);
            ((TextView) row.findViewById(R.id.reviewRoomMeta))
                    .setText(activity.getString(R.string.format_capacity, option.capacity));
            ((TextView) row.findViewById(R.id.reviewRoomPrice)).setText(activity.getString(
                    R.string.format_price_per_unit, formatMoney(option.pricePerNight), option.priceUnit));
            ((TextView) row.findViewById(R.id.reviewRoomQtyNights))
                    .setText(activity.getString(R.string.format_quantity, qty));
            int estimatedValue = estimatedValue(option, qty);
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

        ReservationCatalog.RoomOption option = selectedOption();
        boolean cottageKtv = isCottageKtv(option);

        View rowCheckIn = v.findViewById(R.id.summaryRowCheckIn);
        View rowCheckOut = v.findViewById(R.id.summaryRowCheckOut);
        View rowNights = v.findViewById(R.id.summaryRowNights);
        if (cottageKtv) {
            bindRow(rowCheckIn, R.string.label_date_time, formatDateTime(combinedScheduleMillis(option)));
            rowCheckOut.setVisibility(View.GONE);
            rowNights.setVisibility(View.GONE);
        } else {
            bindRow(rowCheckIn, R.string.label_stay_check_in, formatDate(checkInMillis));
            bindRow(rowCheckOut, R.string.label_stay_check_out, formatDate(checkOutMillis));
            bindRow(rowNights, R.string.label_number_of_nights, String.valueOf(nights()));
        }
        int summaryAdults = adultsFor(option);
        int summaryChildren = childrenFor(option);
        bindRow(v.findViewById(R.id.summaryRowAdults), R.string.label_billing_adults, String.valueOf(summaryAdults));
        bindRow(v.findViewById(R.id.summaryRowChildren), R.string.label_billing_children, String.valueOf(summaryChildren));
        bindRow(v.findViewById(R.id.summaryRowTotalGuests), R.string.label_total_guests,
                String.valueOf(summaryAdults + summaryChildren));

        LinearLayout accommodationContainer = v.findViewById(R.id.summaryAccommodationContainer);
        accommodationContainer.removeAllViews();
        int estimatedValue = 0;
        if (option != null) {
            int qty = selectedQuantity();
            estimatedValue = estimatedValue(option, qty);
            View row = LayoutInflater.from(activity).inflate(R.layout.view_hotel_billing_line_row, accommodationContainer, false);
            ((TextView) row.findViewById(R.id.lineName)).setText(option.categoryName + " · " + option.variantName);
            TextView meta = row.findViewById(R.id.lineMeta);
            meta.setText(activity.getString(R.string.format_capacity, option.capacity) + " · Qty " + qty + " · "
                    + activity.getString(R.string.format_price_per_unit, formatMoney(option.pricePerNight), option.priceUnit));
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
                showScreen(SCREEN_DATES);
                return;
            }
            long submitCheckIn = checkInMillis;
            long submitCheckOut = checkOutMillis;
            if (isCottageKtv(selected)) {
                long combined = combinedScheduleMillis(selected);
                submitCheckIn = combined;
                submitCheckOut = combined;
            }
            lastSubmitted = ReservationStore.submit(activity, submitCheckIn, submitCheckOut,
                    adultsFor(selected), childrenFor(selected),
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
            ((TextView) card.findViewById(R.id.cardReservationType)).setText(
                    isCottageKtv(r.roomOption) ? R.string.reservation_type_cottage_ktv : R.string.reservation_type_hotel);
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
            v.findViewById(R.id.detailsViewOtherRoomsButton).setOnClickListener(view -> showScreen(SCREEN_DATES));
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
                return findOption(entry.getKey());
            }
        }
        return null;
    }

    /** Looks up a RoomOption by id across the static Hotel Rooms catalog and both
     *  live-fetched Cottage/KTV catalogs, since a selected id could be from any of them. */
    private ReservationCatalog.RoomOption findOption(int id) {
        for (ReservationCatalog.RoomOption cottage : cottageOptions) {
            if (cottage.id == id) {
                return cottage;
            }
        }
        for (ReservationCatalog.RoomOption ktvRoom : ktvOptions) {
            if (ktvRoom.id == id) {
                return ktvRoom;
            }
        }
        if (hasSearchedHotelRooms) {
            for (RoomTypeAvailability room : availableRoomTypes) {
                if (room.group_id == id) {
                    return toRoomOption(room);
                }
            }
        } else {
            for (RoomSummary room : defaultRooms) {
                if (room.id == id) {
                    return toRoomOption(room);
                }
            }
        }
        return null;
    }

    private int selectedQuantity() {
        ReservationCatalog.RoomOption option = selectedOption();
        return option == null ? 0 : roomQuantities.get(option.id);
    }

    private boolean isCottageKtv(ReservationCatalog.RoomOption option) {
        return option != null && option.category == ReservationCatalog.CATEGORY_COTTAGES_KTV;
    }

    /** Cottages & KTV items are priced per hour/day-use for the single selected slot (no nights
     *  multiplier); Hotel Rooms items are priced per night for the selected date range. */
    private int estimatedValue(ReservationCatalog.RoomOption option, int qty) {
        if (isCottageKtv(option)) {
            return option.pricePerNight * qty;
        }
        return option.pricePerNight * qty * nights();
    }

    private void resetInProgressFields() {
        checkInMillis = -1;
        checkOutMillis = -1;
        adults = 1;
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
        cottageOptions = new ArrayList<>();
        ktvOptions = new ArrayList<>();
        isLoadingCottageKtv = false;
        cottageKtvLoaded = false;
        hasSearchedHotelRooms = false;
        availabilityErrored = false;
        defaultRooms = new ArrayList<>();
        isLoadingDefaultRooms = false;
        defaultRoomsLoaded = false;
        availableRoomTypes = new ArrayList<>();
        isLoadingAvailability = false;
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

    private String formatWeekday(long millis) {
        return new SimpleDateFormat("EEEE", Locale.US).format(new Date(millis));
    }

    /** Toggles tab styling and which field group (Hotel Rooms / Cottage / KTV) is visible. */
    private void updateCategoryTabs(View v, View tabRooms, View tabCottage, View tabKtv) {
        int accentColor = ThemeManager.color(activity, R.attr.accentPrimary);
        int mutedColor = ThemeManager.color(activity, R.attr.textOnScreenMuted);

        styleTab(tabRooms, R.id.reservationTabRoomsText, R.id.reservationTabRoomsIcon,
                selectedTab == TAB_HOTEL_ROOMS, accentColor, mutedColor);
        styleTab(tabCottage, R.id.reservationTabCottageText, R.id.reservationTabCottageIcon,
                selectedTab == TAB_COTTAGE, accentColor, mutedColor);
        styleTab(tabKtv, R.id.reservationTabKtvText, R.id.reservationTabKtvIcon,
                selectedTab == TAB_KTV, accentColor, mutedColor);

        v.findViewById(R.id.reservationHotelRoomsDatesRow).setVisibility(selectedTab == TAB_HOTEL_ROOMS ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.reservationHotelGuestsSection).setVisibility(selectedTab == TAB_HOTEL_ROOMS ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.reservationCottageFieldsGroup).setVisibility(selectedTab == TAB_COTTAGE ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.reservationKtvFieldsGroup).setVisibility(selectedTab == TAB_KTV ? View.VISIBLE : View.GONE);

        // Switching tabs changes the search panel's content height, so re-measure it for
        // the drag/collapse mechanics (see setUpDragPanel) instead of leaving a stale distance.
        View panelContent = v.findViewById(R.id.reservationSearchPanelContent);
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
     *  type's fixed duration (see {@link #ktvDurationHours}); called whenever either changes. */
    private void updateKtvEndTimeAndDuration(View v) {
        TextView endTimeText = v.findViewById(R.id.reservationKtvEndTimeText);
        TextView durationValue = v.findViewById(R.id.reservationKtvDurationValue);
        int hours = ktvDurationHours(ktvRateType);

        if (hours <= 0) {
            durationValue.setText(R.string.placeholder_em_dash);
        } else {
            durationValue.setText(activity.getString(R.string.format_duration_hours, hours));
        }

        if (hours <= 0 || ktvStartHour < 0) {
            endTimeText.setText(R.string.hint_select_time);
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
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.HOUR_OF_DAY, cottageTimeHour);
            calendar.set(Calendar.MINUTE, cottageTimeMinute);
            cottageTimeText.setText(new SimpleDateFormat("h:mm a", Locale.US).format(calendar.getTime()));
            cottageTimeText.setAlpha(1f);
        }
    }

    private void setStepperEnabled(ImageView button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
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
        if (millis < 0) {
            return "";
        }
        return new SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.US).format(new Date(millis));
    }

    /** A Cottages & KTV reservation stores the same instant as both "check-in" and "check-out"
     *  (see {@link #combinedCottageMillis()}); render that as a single date+time instead of a
     *  zero-length range. */
    private String formatDateRange(long inMillis, long outMillis) {
        if (inMillis < 0 || outMillis < 0) {
            return "";
        }
        if (inMillis == outMillis) {
            return formatDateTime(inMillis);
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
