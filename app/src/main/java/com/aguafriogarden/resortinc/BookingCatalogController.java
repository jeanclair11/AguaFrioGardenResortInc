package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Drives the Book tab: a catalog of the four service categories, a
 * per-category browse list (with search + filter), and an item detail
 * screen. All content comes from {@link BookingCatalogStore}'s placeholder
 * data (shaped for a future Laravel REST swap). Book Now on the detail
 * screen hands off to the existing Reserve tab booking wizard (see the
 * onBookNow callback) rather than duplicating that flow here. Hotel Rooms is
 * the exception: tapping that category card skips straight into the
 * {@link HotelBookingFlowController} wizard (stay dates -> ... -> success)
 * instead of the browse/detail screens.
 */
final class BookingCatalogController {

    private static final int SCREEN_CATALOG = 0;
    private static final int SCREEN_BROWSE = 1;
    private static final int SCREEN_DETAIL = 2;
    private static final int SCREEN_HOTEL_FLOW = 3;

    private final Activity activity;
    private final Runnable onBookNow;
    private final Runnable onBackToHome;
    private final FrameLayout root;

    private int currentScreen = -1;
    private int browseCategoryId = -1;
    private String browseSearchQuery = "";
    private String browseFilterTag; // null means "All"
    private HotelBookingFlowController hotelBookingFlow;

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

        v.findViewById(R.id.detailBookNowButton).setOnClickListener(view -> onBookNow.run());

        root.removeAllViews();
        root.addView(v);
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
