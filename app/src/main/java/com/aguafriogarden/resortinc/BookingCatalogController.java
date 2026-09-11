package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Drives the Book tab: a catalog of the four service categories, a
 * per-category browse list (with search + filter), and an item detail
 * screen. All content comes from {@link BookingCatalogStore}'s placeholder
 * data (shaped for a future Laravel REST swap). Book Now on the detail
 * screen hands off to the existing Reserve tab booking wizard (see the
 * onBookNow callback) rather than duplicating that flow here. Hotel Rooms is
 * the exception: tapping that category card skips straight into the
 * {@link HotelBookingFlowController} wizard (stay dates -> ... -> success)
 * instead of the browse/detail screens. The KTV/Pool & Cottage detail screen
 * also shows a decorative "Reservation Details" section (rate type,
 * date/time, guests) visually matching the Reserve tab's Cottage/KTV field
 * groups in {@link ReservationFlowController} — it's local UI state only,
 * reset per item, and isn't submitted anywhere; Book Now still just hands off
 * to the Reserve tab as before.
 */
final class BookingCatalogController {

    private static final int SCREEN_CATALOG = 0;
    private static final int SCREEN_BROWSE = 1;
    private static final int SCREEN_DETAIL = 2;
    private static final int SCREEN_HOTEL_FLOW = 3;

    // Client-side placeholder durations for the KTV rate types, matching the Reserve tab's
    // ReservationFlowController — no backend field for this exists yet (see that class's notes).
    private static final int KTV_REGULAR_HOURS = 5;
    private static final int KTV_CONSUMABLE_HOURS = 3;

    private final Activity activity;
    private final Runnable onBookNow;
    private final Runnable onBackToHome;
    private final FrameLayout root;

    private int currentScreen = -1;
    private int browseCategoryId = -1;
    private String browseSearchQuery = "";
    private String browseFilterTag; // null means "All"
    private HotelBookingFlowController hotelBookingFlow;

    // ---- Detail screen's decorative "Reservation Details" fields (KTV / Pool & Cottage only).
    // Purely local UI state, reset every time a new item's detail screen is entered — Book Now
    // still just hands off to the Reserve tab (see onBookNow), nothing here is submitted. -----
    private String detailRateType = "";
    private long detailDateMillis = -1;
    private int detailTimeHour = -1;
    private int detailTimeMinute = -1;
    private int detailAdults = 1;
    private int detailChildren = 0;
    private int detailGuestCount = 1;

    BookingCatalogController(Activity activity, Runnable onBookNow, Runnable onBackToHome) {
        this.activity = activity;
        this.onBookNow = onBookNow;
        this.onBackToHome = onBackToHome;
        root = new FrameLayout(activity);
        showHotelFlow();
    }

    View getRootView() {
        return root;
    }

    /** Steps back Detail -> Browse -> Catalog; returns false once already on the catalog. */
    boolean handleBackPressed() {
        if (currentScreen == SCREEN_HOTEL_FLOW) {
            return hotelBookingFlow.handleBackPressed();
        }
        if (currentScreen == SCREEN_DETAIL) {
            showBrowse(browseCategoryId);
            return true;
        }
        if (currentScreen == SCREEN_BROWSE) {
            showCatalog();
            return true;
        }
        return false;
    }

    /** Forwards the payment-screenshot picker result to the hotel booking flow, if active. */
    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (hotelBookingFlow != null) {
            hotelBookingFlow.onActivityResult(requestCode, resultCode, data);
        }
    }

    // ---- Catalog screen: the four service categories, minimal cards -----

    private void showCatalog() {
        currentScreen = SCREEN_CATALOG;
        View v = LayoutInflater.from(activity).inflate(R.layout.view_booking_catalog_main, root, false);

        LinearLayout container = v.findViewById(R.id.bookingCategoryContainer);
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (BookingCatalogStore.Category category : BookingCatalogStore.getCategories()) {
            View card = inflater.inflate(R.layout.view_booking_category_card, container, false);
            ((TextView) card.findViewById(R.id.categoryName)).setText(category.nameRes);

            if (category.id == BookingCatalogStore.CATEGORY_HOTEL) {
                card.setOnClickListener(view -> showHotelFlow());
            } else {
                card.setOnClickListener(view -> showBrowse(category.id));
            }

            container.addView(card);
        }

        root.removeAllViews();
        root.addView(v);
    }

    // ---- Hotel Rooms booking flow: stay dates -> ... -> success ---------

    /**
     * Enters the Hotel Rooms flow at its own Select Your Stay screen. Called
     * both when tapping the Hotel Rooms catalog card here and — via
     * DashboardHomeController.BookingHandoff — when the guest taps "Book Now"
     * on the Home screen's Room Details, so that screen never needs its own
     * dates/party picker.
     */
    void showHotelFlow() {
        currentScreen = SCREEN_HOTEL_FLOW;
        if (hotelBookingFlow == null) {
            hotelBookingFlow = new HotelBookingFlowController(activity, onBookNow, onBackToHome);
        }
        root.removeAllViews();
        root.addView(hotelBookingFlow.getRootView());
    }

    /**
     * Same entry as {@link #showHotelFlow()}, but always lands on the focused "Choose Your Stay
     * Dates" gate screen (see HotelBookingFlowController#showRoomDatesEntry) instead of the Book
     * tab's own tabbed Rooms/Cottage/KTV dates+search screen. Used only by LandingActivity's Room
     * Overview "Book Now" for a Hotel Room selection.
     */
    void showHotelFlowFromRoomOverview(int variantId) {
        currentScreen = SCREEN_HOTEL_FLOW;
        if (hotelBookingFlow == null) {
            hotelBookingFlow = new HotelBookingFlowController(activity, onBookNow, onBackToHome);
        }
        hotelBookingFlow.showRoomDatesEntry(variantId);
        root.removeAllViews();
        root.addView(hotelBookingFlow.getRootView());
    }

    /**
     * Same as {@link #showHotelFlow()}, but always lands on the focused "Choose Your Visit Date"
     * gate screen (see HotelBookingFlowController#showCottageDatesEntry) instead of the Book
     * tab's own tabbed Rooms/Cottage/KTV dates+search screen. Used only by LandingActivity's Room
     * Overview "Book Now" for a Cottage selection.
     */
    void showHotelFlowFromCottageOverview(int variantId) {
        currentScreen = SCREEN_HOTEL_FLOW;
        if (hotelBookingFlow == null) {
            hotelBookingFlow = new HotelBookingFlowController(activity, onBookNow, onBackToHome);
        }
        hotelBookingFlow.showCottageDatesEntry(variantId);
        root.removeAllViews();
        root.addView(hotelBookingFlow.getRootView());
    }

    /**
     * Same as {@link #showHotelFlow()}, but always lands on the focused "Plan Your KTV Session"
     * gate screen (see HotelBookingFlowController#showKtvDatesEntry) instead of the Book tab's
     * own tabbed Rooms/Cottage/KTV dates+search screen. Used only by LandingActivity's Room
     * Overview "Book Now" for a KTV selection.
     */
    void showHotelFlowFromKtvOverview(int variantId) {
        currentScreen = SCREEN_HOTEL_FLOW;
        if (hotelBookingFlow == null) {
            hotelBookingFlow = new HotelBookingFlowController(activity, onBookNow, onBackToHome);
        }
        hotelBookingFlow.showKtvDatesEntry(variantId);
        root.removeAllViews();
        root.addView(hotelBookingFlow.getRootView());
    }

    // ---- Browse screen: catalog of items within one category ------------

    private void showBrowse(int categoryId) {
        currentScreen = SCREEN_BROWSE;
        if (categoryId != browseCategoryId) {
            // Fresh category: start its search/filter over.
            browseSearchQuery = "";
            browseFilterTag = null;
        }
        browseCategoryId = categoryId;
        BookingCatalogStore.Category category = BookingCatalogStore.findCategory(categoryId);

        View v = LayoutInflater.from(activity).inflate(R.layout.view_booking_browse_main, root, false);
        View header = v.findViewById(R.id.browseHeaderBar);
        ((TextView) header.findViewById(R.id.headerTitle)).setText(category.nameRes);
        header.findViewById(R.id.headerBackButton).setOnClickListener(view -> showCatalog());

        EditText searchField = v.findViewById(R.id.browseSearchField);
        searchField.setText(browseSearchQuery);
        AuthUiUtils.afterTextChanged(searchField, () -> {
            browseSearchQuery = searchField.getText().toString();
            renderBrowseList(v, categoryId);
        });

        v.findViewById(R.id.browseFilterButton).setOnClickListener(view -> showFilterDialog(v, categoryId));
        updateFilterButtonLabel(v);
        renderBrowseList(v, categoryId);

        root.removeAllViews();
        root.addView(v);
    }

    private void renderBrowseList(View screen, int categoryId) {
        BookingCatalogStore.Category category = BookingCatalogStore.findCategory(categoryId);
        LinearLayout container = screen.findViewById(R.id.browseItemContainer);
        View emptyState = screen.findViewById(R.id.browseEmptyState);
        container.removeAllViews();

        String query = browseSearchQuery.trim().toLowerCase();
        LayoutInflater inflater = LayoutInflater.from(activity);
        boolean any = false;
        for (BookingCatalogStore.ServiceItem item : BookingCatalogStore.getItems(categoryId)) {
            if (!query.isEmpty() && !item.name.toLowerCase().contains(query)) {
                continue;
            }
            if (browseFilterTag != null && !browseFilterTag.equals(item.filterTag)) {
                continue;
            }
            any = true;

            View card = inflater.inflate(R.layout.view_booking_item_card, container, false);
            card.findViewById(R.id.itemImage).setBackgroundResource(category.tileBackgroundRes);
            ((ImageView) card.findViewById(R.id.itemImage)).setImageResource(category.iconRes);
            ((TextView) card.findViewById(R.id.itemName)).setText(item.name);
            ((TextView) card.findViewById(R.id.itemPrice)).setText(priceLabel(item));
            bindAvailabilityBadge(card.findViewById(R.id.itemAvailabilityBadge), item.availability);
            bindCapacity(card.findViewById(R.id.itemCapacity), item);

            card.setOnClickListener(view -> showDetail(item));
            container.addView(card);
        }

        container.setVisibility(any ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(any ? View.GONE : View.VISIBLE);
    }

    private void showFilterDialog(View screen, int categoryId) {
        String[] tags = BookingCatalogStore.getFilterTags(categoryId);
        String[] options = new String[tags.length + 1];
        options[0] = activity.getString(R.string.filter_all);
        System.arraycopy(tags, 0, options, 1, tags.length);

        int checked = 0;
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(browseFilterTag)) {
                checked = i + 1;
                break;
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.filter_dialog_title)
                .setSingleChoiceItems(options, checked, (d, which) -> {
                    browseFilterTag = which == 0 ? null : options[which];
                    d.dismiss();
                    updateFilterButtonLabel(screen);
                    renderBrowseList(screen, categoryId);
                })
                .create();
        ThemeManager.applyGlassEffect(dialog.getWindow());
        dialog.show();
    }

    private void updateFilterButtonLabel(View screen) {
        TextView label = screen.findViewById(R.id.browseFilterLabel);
        label.setText(browseFilterTag != null ? browseFilterTag : activity.getString(R.string.button_filter));
    }

    // ---- Detail screen: full information for one item -------------------

    private void showDetail(BookingCatalogStore.ServiceItem item) {
        currentScreen = SCREEN_DETAIL;
        BookingCatalogStore.Category category = BookingCatalogStore.findCategory(item.categoryId);

        View v = LayoutInflater.from(activity).inflate(R.layout.view_booking_detail_main, root, false);
        View header = v.findViewById(R.id.detailHeaderBar);
        ((TextView) header.findViewById(R.id.headerTitle)).setText(item.name);
        header.findViewById(R.id.headerBackButton).setOnClickListener(view -> showBrowse(browseCategoryId));

        ImageView heroImage = v.findViewById(R.id.detailHeroImage);
        heroImage.setBackgroundResource(category.tileBackgroundRes);
        heroImage.setImageResource(category.iconRes);

        ((TextView) v.findViewById(R.id.detailName)).setText(item.name);
        ((TextView) v.findViewById(R.id.detailPrice)).setText(priceLabel(item));
        bindAvailabilityBadge(v.findViewById(R.id.detailAvailabilityBadge), item.availability);
        bindCapacity(v.findViewById(R.id.detailCapacity), item);
        ((TextView) v.findViewById(R.id.detailDescription)).setText(item.description);

        LinearLayout amenities = v.findViewById(R.id.detailAmenitiesContainer);
        amenities.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (String amenity : item.amenities) {
            View row = inflater.inflate(R.layout.view_booking_amenity_row, amenities, false);
            ((TextView) row.findViewById(R.id.amenityText)).setText(amenity);
            amenities.addView(row);
        }

        bindReservationDetailsSection(v, item.categoryId);

        v.findViewById(R.id.detailBookNowButton).setOnClickListener(view -> onBookNow.run());

        root.removeAllViews();
        root.addView(v);
    }

    /** Binds the decorative "Reservation Details" section (rate type, date/time, guests) shown
     *  only for KTV and Pool & Cottage items — a visual match for the Reserve tab's Cottage/KTV
     *  field groups. Resets to defaults every time a new item's detail screen is entered. */
    private void bindReservationDetailsSection(View v, int categoryId) {
        View section = v.findViewById(R.id.detailReservationSection);
        boolean ktv = categoryId == BookingCatalogStore.CATEGORY_KTV;
        boolean cottage = categoryId == BookingCatalogStore.CATEGORY_POOL;
        if (!ktv && !cottage) {
            section.setVisibility(View.GONE);
            return;
        }
        section.setVisibility(View.VISIBLE);

        detailRateType = "";
        detailDateMillis = -1;
        detailTimeHour = -1;
        detailTimeMinute = -1;
        detailAdults = 1;
        detailChildren = 0;
        detailGuestCount = 1;

        RadioGroup rateGroup = v.findViewById(R.id.detailRateTypeGroup);
        RadioButton optionA = v.findViewById(R.id.detailRateOptionA);
        RadioButton optionB = v.findViewById(R.id.detailRateOptionB);
        optionA.setText(ktv ? R.string.ktv_rate_type_regular : R.string.rate_type_day);
        optionB.setText(ktv ? R.string.ktv_rate_type_consumable : R.string.rate_type_night);
        rateGroup.clearCheck();

        View dateField = v.findViewById(R.id.detailDateField);
        TextView dateText = v.findViewById(R.id.detailDateText);
        View timeField = v.findViewById(R.id.detailTimeField);
        TextView timeText = v.findViewById(R.id.detailTimeText);
        ((TextView) v.findViewById(R.id.detailTimeLabel)).setText(ktv ? R.string.label_start_time : R.string.label_time);
        updateDetailDateField(dateText);
        updateDetailTimeField(timeText);

        View ktvEndDurationRow = v.findViewById(R.id.detailKtvEndDurationRow);
        View cottageGuestsSection = v.findViewById(R.id.detailCottageGuestsSection);
        View ktvGuestSection = v.findViewById(R.id.detailKtvGuestSection);
        ktvEndDurationRow.setVisibility(ktv ? View.VISIBLE : View.GONE);
        cottageGuestsSection.setVisibility(cottage ? View.VISIBLE : View.GONE);
        ktvGuestSection.setVisibility(ktv ? View.VISIBLE : View.GONE);
        if (ktv) {
            updateDetailKtvEndTimeAndDuration(v);
        }

        rateGroup.setOnCheckedChangeListener((group, checkedId) -> {
            String selected = checkedId == R.id.detailRateOptionA
                    ? (ktv ? ReservationCatalog.RATE_TYPE_REGULAR : ReservationCatalog.RATE_TYPE_DAY)
                    : (ktv ? ReservationCatalog.RATE_TYPE_CONSUMABLE : ReservationCatalog.RATE_TYPE_NIGHT);
            detailRateType = selected;
            if (ktv) {
                updateDetailKtvEndTimeAndDuration(v);
            }
        });

        dateField.setOnClickListener(view -> GlassDatePicker.showCalendarPicker(
                activity, detailDateMillis, System.currentTimeMillis() - 1000L, millis -> {
                    detailDateMillis = millis;
                    updateDetailDateField(dateText);
                }));

        timeField.setOnClickListener(view -> {
            Calendar now = Calendar.getInstance();
            int initialHour = detailTimeHour >= 0 ? detailTimeHour : now.get(Calendar.HOUR_OF_DAY);
            int initialMinute = detailTimeMinute >= 0 ? detailTimeMinute : now.get(Calendar.MINUTE);
            TimePickerDialog dialog = new TimePickerDialog(activity, (picker, hour, minute) -> {
                detailTimeHour = hour;
                detailTimeMinute = minute;
                updateDetailTimeField(timeText);
                if (ktv) {
                    updateDetailKtvEndTimeAndDuration(v);
                }
            }, initialHour, initialMinute, false);
            ThemeManager.applyGlassEffect(dialog.getWindow());
            dialog.show();
        });

        if (cottage) {
            TextView adultsValue = v.findViewById(R.id.detailCottageAdultsValue);
            TextView childrenValue = v.findViewById(R.id.detailCottageChildrenValue);
            TextView totalGuestValue = v.findViewById(R.id.detailCottageTotalGuestValue);
            adultsValue.setText(String.valueOf(detailAdults));
            childrenValue.setText(String.valueOf(detailChildren));
            totalGuestValue.setText(String.valueOf(detailAdults + detailChildren));

            v.findViewById(R.id.detailCottageAdultsMinus).setOnClickListener(view -> {
                if (detailAdults > 1) {
                    detailAdults--;
                    adultsValue.setText(String.valueOf(detailAdults));
                    totalGuestValue.setText(String.valueOf(detailAdults + detailChildren));
                }
            });
            v.findViewById(R.id.detailCottageAdultsPlus).setOnClickListener(view -> {
                detailAdults++;
                adultsValue.setText(String.valueOf(detailAdults));
                totalGuestValue.setText(String.valueOf(detailAdults + detailChildren));
            });
            v.findViewById(R.id.detailCottageChildrenMinus).setOnClickListener(view -> {
                if (detailChildren > 0) {
                    detailChildren--;
                    childrenValue.setText(String.valueOf(detailChildren));
                    totalGuestValue.setText(String.valueOf(detailAdults + detailChildren));
                }
            });
            v.findViewById(R.id.detailCottageChildrenPlus).setOnClickListener(view -> {
                detailChildren++;
                childrenValue.setText(String.valueOf(detailChildren));
                totalGuestValue.setText(String.valueOf(detailAdults + detailChildren));
            });
        } else {
            TextView guestCountValue = v.findViewById(R.id.detailKtvGuestCountValue);
            guestCountValue.setText(String.valueOf(detailGuestCount));
            v.findViewById(R.id.detailKtvGuestCountMinus).setOnClickListener(view -> {
                if (detailGuestCount > 1) {
                    detailGuestCount--;
                    guestCountValue.setText(String.valueOf(detailGuestCount));
                }
            });
            v.findViewById(R.id.detailKtvGuestCountPlus).setOnClickListener(view -> {
                detailGuestCount++;
                guestCountValue.setText(String.valueOf(detailGuestCount));
            });
        }
    }

    private void updateDetailDateField(TextView dateText) {
        if (detailDateMillis < 0) {
            dateText.setText(R.string.hint_select_date);
            dateText.setAlpha(0.5f);
        } else {
            dateText.setText(new SimpleDateFormat("MMM d, yyyy", Locale.US).format(new Date(detailDateMillis)));
            dateText.setAlpha(1f);
        }
    }

    private void updateDetailTimeField(TextView timeText) {
        if (detailTimeHour < 0) {
            timeText.setText(R.string.hint_select_time);
            timeText.setAlpha(0.5f);
        } else {
            timeText.setText(formatDetailHourMinute(detailTimeHour, detailTimeMinute));
            timeText.setAlpha(1f);
        }
    }

    /** Recomputes the read-only End Time/Duration fields from Start Time + the selected rate
     *  type's fixed hours, mirroring ReservationFlowController's KTV tab. */
    private void updateDetailKtvEndTimeAndDuration(View v) {
        TextView endTimeText = v.findViewById(R.id.detailKtvEndTimeText);
        TextView durationValue = v.findViewById(R.id.detailKtvDurationValue);
        int hours = ReservationCatalog.RATE_TYPE_REGULAR.equals(detailRateType) ? KTV_REGULAR_HOURS
                : ReservationCatalog.RATE_TYPE_CONSUMABLE.equals(detailRateType) ? KTV_CONSUMABLE_HOURS : 0;

        durationValue.setText(hours <= 0 ? activity.getString(R.string.placeholder_em_dash)
                : activity.getString(R.string.format_duration_hours, hours));

        if (hours <= 0 || detailTimeHour < 0) {
            endTimeText.setText(R.string.placeholder_em_dash);
            endTimeText.setAlpha(0.5f);
            return;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, detailTimeHour);
        calendar.set(Calendar.MINUTE, detailTimeMinute);
        calendar.add(Calendar.HOUR_OF_DAY, hours);
        endTimeText.setText(formatDetailHourMinute(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)));
        endTimeText.setAlpha(1f);
    }

    private String formatDetailHourMinute(int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        return new SimpleDateFormat("h:mm a", Locale.US).format(calendar.getTime());
    }

    // ---- Shared bindings --------------------------------------------------

    private String priceLabel(BookingCatalogStore.ServiceItem item) {
        return item.startingPriceLabel != null
                ? item.startingPriceLabel
                : activity.getString(R.string.booking_price_unavailable);
    }

    private void bindCapacity(TextView capacityView, BookingCatalogStore.ServiceItem item) {
        if (item.capacityLabel != null) {
            capacityView.setText(item.capacityLabel);
            capacityView.setVisibility(View.VISIBLE);
        } else {
            capacityView.setVisibility(View.GONE);
        }
    }

    private void bindAvailabilityBadge(TextView badge, int availability) {
        badge.setText(BookingCatalogStore.availabilityLabelRes(availability));
        badge.setBackgroundResource(BookingCatalogStore.availabilityBadgeRes(availability));
        if (availability == BookingCatalogStore.AVAILABILITY_AVAILABLE) {
            badge.setTextColor(Color.parseColor("#2E7D32"));
        } else if (availability == BookingCatalogStore.AVAILABILITY_LIMITED) {
            badge.setTextColor(Color.parseColor("#B36B00"));
        } else {
            badge.setTextColor(ThemeManager.color(activity, R.attr.textMuted));
        }
    }
}
