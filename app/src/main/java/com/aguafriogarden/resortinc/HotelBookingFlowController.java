package com.aguafriogarden.resortinc;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
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
import android.widget.ScrollView;
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
import java.util.Arrays;
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

    private static final int PAYMENT_NONE = 0;
    private static final int PAYMENT_FULL = 1;
    private static final int PAYMENT_PARTIAL = 2;

    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;
    private static final double PARTIAL_PAYMENT_RATE = 0.30;
    private static final int REQUEST_PAYMENT_SCREENSHOT = 4101;

    private static final int CATEGORY_HOTEL_ROOMS = 0;
    private static final int CATEGORY_COTTAGES_KTV = 1;

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
    private int selectedCategory = CATEGORY_HOTEL_ROOMS;
    private boolean hasSearchedHotelRooms = false;
    private boolean availabilityErrored = false;
    private List<RoomSummary> defaultRooms = new ArrayList<>();
    private boolean isLoadingDefaultRooms = false;
    private boolean defaultRoomsLoaded = false;
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
        View tabCottagesKtv = v.findViewById(R.id.hotelTabCottagesKtv);

        updateDateField(checkInDateText, checkInDayText, checkInMillis);
        updateDateField(checkOutDateText, checkOutDayText, checkOutMillis);
        adultsValue.setText(String.valueOf(adults));
        childrenValue.setText(String.valueOf(children));
        updateCategoryTabs(tabRooms, tabCottagesKtv);

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

        tabRooms.setOnClickListener(view -> {
            if (selectedCategory != CATEGORY_HOTEL_ROOMS) {
                selectedCategory = CATEGORY_HOTEL_ROOMS;
                updateCategoryTabs(tabRooms, tabCottagesKtv);
                renderAvailabilityResults(v);
            }
        });
        tabCottagesKtv.setOnClickListener(view -> {
            if (selectedCategory != CATEGORY_COTTAGES_KTV) {
                selectedCategory = CATEGORY_COTTAGES_KTV;
                updateCategoryTabs(tabRooms, tabCottagesKtv);
                renderAvailabilityResults(v);
            }
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
            if (adults + children < 1) {
                Toast.makeText(activity, R.string.toast_guests_required, Toast.LENGTH_LONG).show();
                valid = false;
            }
            if (valid) {
                runAvailabilitySearch(v);
            }
        });

        v.findViewById(R.id.hotelResultsChangeDatesButton).setOnClickListener(view -> expandPanel(v));
        v.findViewById(R.id.hotelResultsTryAgainButton).setOnClickListener(view -> runAvailabilitySearch(v));

        setUpDragPanel(v);

        renderAvailabilityResults(v);
        fetchDefaultRooms(v);
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
        if (selectedCategory == CATEGORY_HOTEL_ROOMS) {
            fetchAvailability(v);
        } else {
            // Cottages & KTV has no backend yet; it's already showing its static preview.
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
        container.removeAllViews();

        TextView resultsSubtitle = v.findViewById(R.id.hotelResultsSubtitle);
        boolean showingLiveSearch = selectedCategory != CATEGORY_HOTEL_ROOMS || hasSearchedHotelRooms;
        resultsSubtitle.setText(showingLiveSearch
                ? R.string.label_available_accommodations_subtitle
                : R.string.label_available_accommodations_subtitle_browse);

        if (isLoadingAvailability || (selectedCategory == CATEGORY_HOTEL_ROOMS && !hasSearchedHotelRooms && isLoadingDefaultRooms)) {
            loading.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
            errorState.setVisibility(View.GONE);
            sectionHeader.setVisibility(View.GONE);
            container.setVisibility(View.GONE);
            return;
        }
        loading.setVisibility(View.GONE);

        if (selectedCategory == CATEGORY_HOTEL_ROOMS && hasSearchedHotelRooms && availabilityErrored) {
            errorState.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
            sectionHeader.setVisibility(View.GONE);
            container.setVisibility(View.GONE);
            return;
        }
        errorState.setVisibility(View.GONE);

        LayoutInflater inflater = LayoutInflater.from(activity);
        if (selectedCategory == CATEGORY_HOTEL_ROOMS && hasSearchedHotelRooms) {
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
        } else if (selectedCategory == CATEGORY_HOTEL_ROOMS) {
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
            List<BookingCatalogStore.ServiceItem> items = new ArrayList<>();
            items.addAll(Arrays.asList(BookingCatalogStore.getItems(BookingCatalogStore.CATEGORY_KTV)));
            items.addAll(Arrays.asList(BookingCatalogStore.getItems(BookingCatalogStore.CATEGORY_POOL)));
            if (items.isEmpty()) {
                emptyState.setVisibility(View.VISIBLE);
                sectionHeader.setVisibility(View.GONE);
                container.setVisibility(View.GONE);
                return;
            }
            emptyState.setVisibility(View.GONE);
            sectionIcon.setImageResource(R.drawable.ic_pool);
            sectionLabel.setText(R.string.tab_cottages_ktv);
            sectionHeader.setVisibility(View.VISIBLE);
            container.setVisibility(View.VISIBLE);
            for (BookingCatalogStore.ServiceItem item : items) {
                container.addView(buildCatalogCard(inflater, container, item));
            }
        }
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

    private View buildCatalogCard(LayoutInflater inflater, ViewGroup parent, BookingCatalogStore.ServiceItem item) {
        View card = inflater.inflate(R.layout.view_accommodation_card, parent, false);
        BookingCatalogStore.Category category = BookingCatalogStore.findCategory(item.categoryId);

        ImageView image = card.findViewById(R.id.accommodationImage);
        image.setBackgroundResource(category.tileBackgroundRes);
        image.setImageResource(category.iconRes);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setColorFilter(Color.WHITE);
        int pad = (int) (32 * activity.getResources().getDisplayMetrics().density);
        image.setPadding(pad, pad, pad, pad);

        TextView badge = card.findViewById(R.id.accommodationAvailabilityBadge);
        badge.setText(BookingCatalogStore.availabilityLabelRes(item.availability));
        badge.setBackgroundResource(catalogAvailabilityBadgeRes(item.availability));

        ((TextView) card.findViewById(R.id.accommodationName))
                .setText(formatShowcaseName(item.name, item.filterTag));
        TextView capacity = card.findViewById(R.id.accommodationCapacity);
        if (item.capacityLabel != null) {
            capacity.setText(item.capacityLabel);
            capacity.setVisibility(View.VISIBLE);
        } else {
            capacity.setVisibility(View.GONE);
        }
        card.findViewById(R.id.accommodationBedInfo).setVisibility(View.GONE);

        ((TextView) card.findViewById(R.id.accommodationPrice)).setText(item.startingPriceLabel != null
                ? item.startingPriceLabel : activity.getString(R.string.booking_price_unavailable));

        card.findViewById(R.id.accommodationBookNowButton).setOnClickListener(view -> onBookNowOtherCategory.run());
        return card;
    }

    private int catalogAvailabilityBadgeRes(int availability) {
        if (availability == BookingCatalogStore.AVAILABILITY_LIMITED) {
            return R.drawable.bg_badge_limited_solid;
        } else if (availability == BookingCatalogStore.AVAILABILITY_FULL) {
            return R.drawable.bg_badge_full_solid;
        }
        return R.drawable.bg_badge_available_solid;
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

    private void updateCategoryTabs(View tabRooms, View tabCottagesKtv) {
        boolean hotelSelected = selectedCategory == CATEGORY_HOTEL_ROOMS;
        int accentColor = ThemeManager.color(activity, R.attr.accentPrimary);
        int mutedColor = ThemeManager.color(activity, R.attr.textOnScreenMuted);

        tabRooms.setBackgroundResource(hotelSelected ? R.drawable.bg_tab_pill_selected : R.drawable.bg_tab_pill_unselected);
        ((TextView) tabRooms.findViewById(R.id.hotelTabRoomsText)).setTextColor(hotelSelected ? accentColor : mutedColor);
        ((ImageView) tabRooms.findViewById(R.id.hotelTabRoomsIcon)).setColorFilter(hotelSelected ? accentColor : mutedColor);

        tabCottagesKtv.setBackgroundResource(hotelSelected ? R.drawable.bg_tab_pill_unselected : R.drawable.bg_tab_pill_selected);
        ((TextView) tabCottagesKtv.findViewById(R.id.hotelTabCottagesKtvText)).setTextColor(hotelSelected ? mutedColor : accentColor);
        ((ImageView) tabCottagesKtv.findViewById(R.id.hotelTabCottagesKtvIcon)).setColorFilter(hotelSelected ? mutedColor : accentColor);
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

    private void resetFlow() {
        checkInMillis = -1;
        checkOutMillis = -1;
        adults = 0;
        children = 0;
        selectedCategory = CATEGORY_HOTEL_ROOMS;
        hasSearchedHotelRooms = false;
        availabilityErrored = false;
        defaultRooms = new ArrayList<>();
        isLoadingDefaultRooms = false;
        defaultRoomsLoaded = false;
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
