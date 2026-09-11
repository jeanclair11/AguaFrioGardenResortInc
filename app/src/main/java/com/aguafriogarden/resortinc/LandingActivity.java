package com.aguafriogarden.resortinc;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.CottageKtvOption;
import com.aguafriogarden.resortinc.network.CottageTypeListResponse;
import com.aguafriogarden.resortinc.network.HallTypeListResponse;
import com.aguafriogarden.resortinc.network.KtvTypeListResponse;
import com.aguafriogarden.resortinc.network.ReservationSummary;
import com.aguafriogarden.resortinc.network.RoomTypeAvailability;
import com.aguafriogarden.resortinc.network.RoomTypeListResponse;
import com.bumptech.glide.Glide;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * The app's single Home screen for both logged-out and logged-in guests. Logged out, it's a
 * marketing/browse page (hero, gallery, about) with a top nav for Hotel Rooms/Cottages/KTV
 * Rooms/Function Halls, built from live Room/Cottage/KTV/Facility management data. Logging in
 * (via MainActivity, which is pushed without finishing this Activity — see openLogin()) flips
 * this same screen into a shell for Reserve/Book/Chat/Feedback/Profile/Alerts, replacing the
 * former separate DashboardActivity, while keeping the marketing content and top nav intact
 * underneath a Greeting+Booking Progress block that replaces the Hero card's logo.
 */
public class LandingActivity extends Activity {

    private enum Section {
        HOME, ROOMS, COTTAGES, KTV, HALLS,
        RESERVE, BOOK, CHAT, FEEDBACK, PROFILE, ALERTS, BOOKING_DETAILS, ROOM_OVERVIEW
    }

    /** Bottom-nav order; index into this array lines up with bottomNavItems. */
    private static final Section[] BOTTOM_NAV_SECTIONS =
            {Section.HOME, Section.RESERVE, Section.BOOK, Section.CHAT, Section.FEEDBACK};
    private static final int BOTTOM_NAV_CHAT_INDEX = 3;

    private static final long CONTENT_FADE_MS = 150L;
    private static final long BADGE_REFRESH_MS = 2000L;
    private static final int REQUEST_POST_NOTIFICATIONS = 1001;

    private FrameLayout landingContentContainer;
    private ScrollView landingHomeScroll;
    private HorizontalScrollView navBar;
    private View headerBar;
    private FrameLayout heroOrGreetingSlot;
    private View heroSectionView;
    private View landingLogInButton;
    private View landingBellCluster;
    private View profileButton;
    private View bottomNavDivider;
    private LinearLayout bottomNav;
    private final View[] bottomNavItems = new View[BOTTOM_NAV_SECTIONS.length];

    private Section currentSection = Section.HOME;
    private Section roomOverviewOrigin = Section.ROOMS;
    private boolean navBarVisible = true;
    private boolean loggedIn = false;

    private ReservationFlowController reservationFlow;
    private BookingCatalogController bookingCatalog;
    private DashboardHomeController dashboardHome;
    private ProfileController profileController;
    private FeedbackController feedbackController;
    private ChatController chatController;
    private NotificationController notificationController;

    private final Handler badgeHandler = new Handler(Looper.getMainLooper());
    private final Runnable badgeTick = this::tickBadges;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.apply(this);
        setContentView(R.layout.activity_landing);

        landingContentContainer = findViewById(R.id.landingContentContainer);
        landingHomeScroll = findViewById(R.id.landingHomeScroll);
        navBar = findViewById(R.id.landingNavBar);
        headerBar = findViewById(R.id.landingHeaderBar);
        heroOrGreetingSlot = findViewById(R.id.heroOrGreetingSlot);
        heroSectionView = findViewById(R.id.heroSection);
        landingLogInButton = findViewById(R.id.landingLogInButton);
        landingBellCluster = findViewById(R.id.landingBellCluster);
        profileButton = findViewById(R.id.profileButton);
        bottomNavDivider = findViewById(R.id.bottomNavDivider);
        bottomNav = findViewById(R.id.bottomNav);
        configureScrollableContent(landingHomeScroll);

        findViewById(R.id.navTabHome).setOnClickListener(v -> showHome());
        findViewById(R.id.navTabRooms).setOnClickListener(v -> showCategory(Section.ROOMS));
        findViewById(R.id.navTabCottages).setOnClickListener(v -> showCategory(Section.COTTAGES));
        findViewById(R.id.navTabKtv).setOnClickListener(v -> showCategory(Section.KTV));
        findViewById(R.id.navTabHalls).setOnClickListener(v -> showCategory(Section.HALLS));
        landingLogInButton.setOnClickListener(v -> openLogin());
        landingBellCluster.setOnClickListener(v -> showModule(Section.ALERTS));
        profileButton.setOnClickListener(v -> showModule(Section.PROFILE));

        if (!ProfileStore.getAuthToken(this).isEmpty()) {
            enterLoggedInState();
        }
        if (loggedIn && ChatPollingService.EXTRA_VALUE_CHAT.equals(
                getIntent().getStringExtra(ChatPollingService.EXTRA_OPEN_MODULE))) {
            showModule(Section.CHAT);
        }

        // Predictive back (API 33+, on by targetSdk here) never calls onBackPressed() below
        // unless a callback is registered to claim it first — without this, a second back
        // press exits the app instead of stepping back through the current screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> {
                        if (!consumeBackPress()) {
                            finish();
                        }
                    });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning here after a successful login (MainActivity just finishes itself, see
        // openLogin()) — pick up the now-present session in place instead of a fresh Activity.
        if (!loggedIn && !ProfileStore.getAuthToken(this).isEmpty()) {
            enterLoggedInState();
        }
        if (loggedIn) {
            if (currentSection == Section.CHAT) {
                chatController.onShown();
            } else if (currentSection == Section.FEEDBACK) {
                feedbackController.onShown();
            }
            badgeHandler.post(badgeTick);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (loggedIn) {
            chatController.onHidden();
            badgeHandler.removeCallbacks(badgeTick);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (loggedIn) {
            profileController.onActivityResult(requestCode, resultCode, data);
            bookingCatalog.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (!consumeBackPress()) {
            super.onBackPressed();
        }
    }

    /** Steps back through whichever sub-screen is showing before falling through to the default (exit) behavior. */
    private boolean consumeBackPress() {
        if (currentSection == Section.BOOKING_DETAILS) {
            showHome();
            return true;
        }
        if (currentSection == Section.ROOM_OVERVIEW) {
            showCategory(roomOverviewOrigin);
            return true;
        }
        if (loggedIn) {
            if (currentSection == Section.PROFILE && profileController.handleBackPressed()) {
                return true;
            }
            if (currentSection == Section.FEEDBACK && feedbackController.handleBackPressed()) {
                return true;
            }
            if (currentSection == Section.BOOK && bookingCatalog.handleBackPressed()) {
                return true;
            }
            if (currentSection == Section.RESERVE && reservationFlow.handleBackPressed()) {
                return true;
            }
        }
        return returnToHomeIfShowingCategory();
    }

    /** Returns true if a browse screen (or a logged-in section) was showing and back navigation returned to Home. */
    private boolean returnToHomeIfShowingCategory() {
        if (currentSection != Section.HOME) {
            showHome();
            return true;
        }
        return false;
    }

    private void openLogin() {
        startActivity(new Intent(this, MainActivity.class));
    }

    // ---- Login / logout -----------------------------------------------------

    /**
     * Builds the six post-login controllers (mirrors the former DashboardActivity#onCreate
     * wiring exactly) and flips the chrome into its logged-in state. Safe to call more than
     * once — a no-op once already logged in.
     */
    private void enterLoggedInState() {
        if (loggedIn) {
            return;
        }
        loggedIn = true;

        // Forces ReservationStore's static seeding (and the notifications it pushes) to run
        // immediately, so the Alerts screen isn't empty for a guest who taps the bell before
        // ever opening Reserve (Java only runs a class's static initializer on first use).
        ReservationStore.all();
        reservationFlow = new ReservationFlowController(this, this::showHome);
        bookingCatalog = new BookingCatalogController(this,
                () -> showModule(Section.RESERVE), this::showHome);
        dashboardHome = new DashboardHomeController(this, this::showBookingDetails);
        profileController = new ProfileController(this, this::showHome, this::logout);
        feedbackController = new FeedbackController(this);
        chatController = new ChatController(this);
        notificationController = new NotificationController(this, n -> {
            if (n.type == NotificationStore.Type.CHAT) {
                showModule(Section.CHAT);
            } else {
                showModule(Section.RESERVE);
                reservationFlow.openDetails(n.targetReference);
            }
        });

        heroOrGreetingSlot.removeAllViews();
        heroOrGreetingSlot.addView(dashboardHome.getRootView());

        landingLogInButton.setVisibility(View.GONE);
        landingBellCluster.setVisibility(View.VISIBLE);
        profileButton.setVisibility(View.VISIBLE);

        buildBottomNav();
        bottomNavDivider.setVisibility(View.VISIBLE);
        bottomNav.setVisibility(View.VISIBLE);

        requestNotificationPermissionIfNeeded();
        startChatPollingService();
        badgeHandler.post(badgeTick);

        // Check if guest selected a room before login and show its overview
        if (PendingRoomSelection.has()) {
            showRoomOverview(PendingRoomSelection.take());
        }
    }

    private void logout() {
        stopService(new Intent(this, ChatPollingService.class));
        ProfileStore.clearAuthToken(this);
        badgeHandler.removeCallbacks(badgeTick);
        loggedIn = false;

        reservationFlow = null;
        bookingCatalog = null;
        dashboardHome = null;
        profileController = null;
        feedbackController = null;
        chatController = null;
        notificationController = null;

        heroOrGreetingSlot.removeAllViews();
        heroOrGreetingSlot.addView(heroSectionView);

        // Logged out, the header always stays up (see setNavBarPresent) — but it may have been
        // left GONE by a chrome-hidden section (Reserve/Book/Chat/etc.) visited before logging
        // out, and setNavBarPresent only touches headerBar while loggedIn, so it never resets.
        headerBar.setVisibility(View.VISIBLE);

        landingLogInButton.setVisibility(View.VISIBLE);
        landingBellCluster.setVisibility(View.GONE);
        profileButton.setVisibility(View.GONE);
        bottomNavDivider.setVisibility(View.GONE);
        bottomNav.setVisibility(View.GONE);
        bottomNav.removeAllViews();

        currentSection = Section.HOME;
        swapContent(landingHomeScroll, true);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
        }
    }

    private void startChatPollingService() {
        Intent serviceIntent = new Intent(this, ChatPollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    // ---- Bottom nav: Home/Reserve/Book/Chat/Feedback -------------------------

    private void buildBottomNav() {
        bottomNav.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        int[] labels = {R.string.nav_home, R.string.nav_reservation, R.string.nav_booking, R.string.nav_chat, R.string.nav_feedback};
        int[] icons = {R.drawable.ic_home, R.drawable.ic_reservation, R.drawable.ic_calendar, R.drawable.ic_chat, R.drawable.ic_feedback};
        for (int i = 0; i < BOTTOM_NAV_SECTIONS.length; i++) {
            View item = inflater.inflate(R.layout.view_nav_item, bottomNav, false);
            ((ImageView) item.findViewById(R.id.navIcon)).setImageResource(icons[i]);
            ((TextView) item.findViewById(R.id.navLabel)).setText(labels[i]);
            Section section = BOTTOM_NAV_SECTIONS[i];
            // Home isn't one of showModule()'s swappable sections — it's the same
            // showHome() the top AGUA FRIO tab uses, so both stay in sync.
            item.setOnClickListener(section == Section.HOME ? v -> showHome() : v -> showModule(section));
            bottomNav.addView(item);
            bottomNavItems[i] = item;
        }
    }

    /** Colors the bottom nav icons/labels for whichever section (if any) is active. Logged-out
     *  guests have no bottom nav yet (built in enterLoggedInState()), so this is a no-op then. */
    private void retintBottomNav() {
        if (!loggedIn) {
            return;
        }
        for (int i = 0; i < bottomNavItems.length; i++) {
            boolean active = BOTTOM_NAV_SECTIONS[i] == currentSection;
            int color = ThemeManager.color(this, active ? R.attr.navItemActive : R.attr.navItemInactive);
            ((ImageView) bottomNavItems[i].findViewById(R.id.navIcon)).setColorFilter(color);
            ((TextView) bottomNavItems[i].findViewById(R.id.navLabel)).setTextColor(color);
        }
    }

    /**
     * Switches to one of the six logged-in-only sections (Reserve/Book/Chat/Feedback/Profile/
     * Alerts), retinting the bottom nav and cross-fading the body — mirrors the former
     * DashboardActivity#showModule.
     */
    private void showModule(Section section) {
        if (section == currentSection) {
            return;
        }
        leavingChat();
        currentSection = section;
        retintBottomNav();

        View content;
        if (section == Section.RESERVE) {
            content = reservationFlow.getRootView();
        } else if (section == Section.BOOK) {
            content = bookingCatalog.getRootView();
        } else if (section == Section.CHAT) {
            content = chatController.getRootView();
            chatController.onShown();
        } else if (section == Section.FEEDBACK) {
            content = feedbackController.getRootView();
            feedbackController.onShown();
        } else if (section == Section.PROFILE) {
            content = profileController.getRootView();
        } else {
            content = notificationController.getRootView();
        }

        swapContent(content, false);
        refreshBadges();
    }

    private void showBookingDetails(ReservationSummary booking) {
        leavingChat();
        currentSection = Section.BOOKING_DETAILS;
        retintBottomNav();
        swapContent(dashboardHome.buildDetailsView(booking, this::showHome), false);
    }

    private void leavingChat() {
        if (loggedIn && currentSection == Section.CHAT) {
            chatController.onHidden();
        }
    }

    /** Reflects NotificationStore/UnreadChatStore unread counts onto the bell icon and Chat's nav badge. */
    private void refreshBadges() {
        updateBadge(findViewById(R.id.bellBadge), NotificationStore.unreadCount());
        updateBadge(bottomNavItems[BOTTOM_NAV_CHAT_INDEX].findViewById(R.id.navBadge), UnreadChatStore.unreadCount());
    }

    private void tickBadges() {
        refreshBadges();
        badgeHandler.postDelayed(badgeTick, BADGE_REFRESH_MS);
    }

    private void updateBadge(View badgeView, int count) {
        TextView badge = (TextView) badgeView;
        if (count <= 0) {
            badge.setVisibility(View.GONE);
            return;
        }
        badge.setVisibility(View.VISIBLE);
        badge.setText(count > 9 ? getString(R.string.badge_count_overflow) : String.valueOf(count));
    }

    // ---- Home / browse tabs (Hotel Rooms/Cottages/KTV Rooms/Function Halls) -

    private void showHome() {
        if (currentSection == Section.HOME) {
            return;
        }
        leavingChat();
        swapContent(landingHomeScroll, true);
        currentSection = Section.HOME;
        retintBottomNav();
        if (loggedIn) {
            refreshBadges();
        }
    }

    /** Inflates a fresh browse screen for the tapped section and starts loading its live data. */
    private void showCategory(Section section) {
        if (currentSection == section) {
            return;
        }
        leavingChat();
        View browse = LayoutInflater.from(this).inflate(R.layout.view_landing_category_browse, landingContentContainer, false);
        ((TextView) browse.findViewById(R.id.categoryBrowseTitle)).setText(sectionTitleRes(section));
        LinearLayout container = browse.findViewById(R.id.categoryBrowseContainer);
        TextView emptyState = browse.findViewById(R.id.categoryBrowseEmptyState);

        switch (section) {
            case ROOMS:
                emptyState.setText(R.string.landing_rooms_empty_state);
                fetchRoomTypes(container, emptyState);
                break;
            case COTTAGES:
                emptyState.setText(R.string.landing_category_empty_state);
                fetchCottageTypes(container, emptyState);
                break;
            case KTV:
                emptyState.setText(R.string.landing_category_empty_state);
                fetchKtvTypes(container, emptyState);
                break;
            case HALLS:
                emptyState.setText(R.string.landing_category_empty_state);
                fetchHallTypes(container, emptyState);
                break;
            default:
                break;
        }

        swapContent(browse, true);
        currentSection = section;
        retintBottomNav();
    }

    private int sectionTitleRes(Section section) {
        switch (section) {
            case COTTAGES:
                return R.string.landing_cottages_section_title;
            case KTV:
                return R.string.landing_ktv_section_title;
            case HALLS:
                return R.string.landing_halls_section_title;
            case ROOMS:
            default:
                return R.string.landing_rooms_section_title;
        }
    }

    /**
     * Fades newContent in over whatever's currently showing, then drops the old content once the
     * animation finishes. landingHomeScroll is a retained field (never destroyed), so swapping
     * back to it later just re-adds the same instance. showTopNavBar controls the top nav bar
     * (Agua Frio/Hotel Rooms/Cottages/KTV/Halls): present on Home/browse tabs, fully gone on
     * Reserve/Book/Chat/Feedback/Profile/Alerts/Booking Details, which have their own bottom nav.
     */
    private void swapContent(View newContent, boolean showTopNavBar) {
        List<View> oldChildren = new ArrayList<>();
        for (int i = 0; i < landingContentContainer.getChildCount(); i++) {
            oldChildren.add(landingContentContainer.getChildAt(i));
        }

        landingContentContainer.addView(newContent);
        if (newContent instanceof ScrollView) {
            configureScrollableContent((ScrollView) newContent);
        }
        setNavBarPresent(showTopNavBar);
        newContent.setAlpha(0f);
        newContent.animate()
                .alpha(1f)
                .setDuration(CONTENT_FADE_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    for (View old : oldChildren) {
                        landingContentContainer.removeView(old);
                    }
                })
                .start();
    }

    /**
     * Insets the top of scrollView by navBar's height so its content starts exactly where it
     * does today, but — since clipToPadding is off — content can still scroll up into that
     * inset and show through once navBar translates out of the way. Also wires the scroll
     * listener that drives the show/hide. Safe to call repeatedly on the same retained
     * ScrollView (landingHomeScroll): the inset is only applied once, guarded by a tag.
     */
    private void configureScrollableContent(ScrollView scrollView) {
        if (scrollView.getTag(R.id.landingNavBar) == null) {
            navBar.post(() -> {
                scrollView.setPadding(scrollView.getPaddingLeft(),
                        scrollView.getPaddingTop() + navBar.getHeight(),
                        scrollView.getPaddingRight(), scrollView.getPaddingBottom());
                scrollView.setClipToPadding(false);
            });
            scrollView.setTag(R.id.landingNavBar, Boolean.TRUE);
        }
        scrollView.setOnScrollChangeListener((View.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (scrollY > oldScrollY) {
                setNavBarVisible(true);
            } else if (scrollY < oldScrollY) {
                setNavBarVisible(false);
            }
        });
    }

    /**
     * Per the user's explicit choice: scrolling toward the top of the page hides the nav bar,
     * scrolling further down shows it again — the opposite of the usual collapsing-toolbar
     * convention. navBar overlays the content (see activity_landing.xml) rather than sharing
     * its vertical layout flow, so this is a pure transform with no layout pass — it responds
     * to scroll direction immediately instead of fighting an active touch-scroll.
     */
    private void setNavBarVisible(boolean visible) {
        if (visible == navBarVisible) {
            return;
        }
        navBarVisible = visible;
        navBar.animate().cancel();
        navBar.animate()
                .translationY(visible ? 0f : -navBar.getHeight())
                .setDuration(150)
                .start();
    }

    /**
     * Whether the top chrome — header bar (logo/title/bell/profile) and the Hotel Rooms/
     * Cottages/KTV/Halls nav bar — exists on screen at all. Present (and reset to fully shown)
     * on Home/browse tabs; once logged in, fully removed on Reserve/Book/Chat/Feedback/Profile/
     * Alerts/Booking Details, which navigate via the bottom nav instead — mirrors the original
     * DashboardActivity#updateChromeVisibility (topBarRow shown only on its Home tab). Logged
     * out, the header always stays up (there's no bottom nav to fall back on).
     */
    private void setNavBarPresent(boolean present) {
        navBar.animate().cancel();
        navBar.setTranslationY(0f);
        navBar.setVisibility(present ? View.VISIBLE : View.GONE);
        navBarVisible = present;
        if (loggedIn) {
            headerBar.setVisibility(present ? View.VISIBLE : View.GONE);
        }
    }

    private void fetchRoomTypes(LinearLayout container, TextView emptyState) {
        ApiClient.bookingApi().getRoomTypes().enqueue(new Callback<RoomTypeListResponse>() {
            @Override
            public void onResponse(Call<RoomTypeListResponse> call, Response<RoomTypeListResponse> response) {
                List<RoomTypeAvailability> roomTypes = response.isSuccessful() && response.body() != null
                        && response.body().room_types != null
                        ? response.body().room_types : new ArrayList<>();
                bindRoomTypes(container, emptyState, roomTypes);
            }

            @Override
            public void onFailure(Call<RoomTypeListResponse> call, Throwable t) {
                bindRoomTypes(container, emptyState, new ArrayList<>());
            }
        });
    }

    private void fetchCottageTypes(LinearLayout container, TextView emptyState) {
        ApiClient.bookingApi().getCottageTypes().enqueue(new Callback<CottageTypeListResponse>() {
            @Override
            public void onResponse(Call<CottageTypeListResponse> call, Response<CottageTypeListResponse> response) {
                List<CottageKtvOption> cottages = response.isSuccessful() && response.body() != null
                        && response.body().cottages != null
                        ? response.body().cottages : new ArrayList<>();
                bindCottageKtvOptions(container, emptyState, cottages, getString(R.string.service_type_cottage), true, Section.COTTAGES);
            }

            @Override
            public void onFailure(Call<CottageTypeListResponse> call, Throwable t) {
                bindCottageKtvOptions(container, emptyState, new ArrayList<>(), getString(R.string.service_type_cottage), true, Section.COTTAGES);
            }
        });
    }

    private void fetchKtvTypes(LinearLayout container, TextView emptyState) {
        ApiClient.bookingApi().getKtvTypes().enqueue(new Callback<KtvTypeListResponse>() {
            @Override
            public void onResponse(Call<KtvTypeListResponse> call, Response<KtvTypeListResponse> response) {
                List<CottageKtvOption> ktvRooms = response.isSuccessful() && response.body() != null
                        && response.body().ktv_rooms != null
                        ? response.body().ktv_rooms : new ArrayList<>();
                bindCottageKtvOptions(container, emptyState, ktvRooms, getString(R.string.service_type_ktv), true, Section.KTV);
            }

            @Override
            public void onFailure(Call<KtvTypeListResponse> call, Throwable t) {
                bindCottageKtvOptions(container, emptyState, new ArrayList<>(), getString(R.string.service_type_ktv), true, Section.KTV);
            }
        });
    }

    private void fetchHallTypes(LinearLayout container, TextView emptyState) {
        ApiClient.bookingApi().getHallTypes().enqueue(new Callback<HallTypeListResponse>() {
            @Override
            public void onResponse(Call<HallTypeListResponse> call, Response<HallTypeListResponse> response) {
                List<CottageKtvOption> halls = response.isSuccessful() && response.body() != null
                        && response.body().halls != null
                        ? response.body().halls : new ArrayList<>();
                bindCottageKtvOptions(container, emptyState, halls, getString(R.string.service_type_hall), false, Section.HALLS);
            }

            @Override
            public void onFailure(Call<HallTypeListResponse> call, Throwable t) {
                bindCottageKtvOptions(container, emptyState, new ArrayList<>(), getString(R.string.service_type_hall), false, Section.HALLS);
            }
        });
    }

    private void bindRoomTypes(LinearLayout container, TextView emptyState, List<RoomTypeAvailability> roomTypes) {
        emptyState.setVisibility(roomTypes.isEmpty() ? View.VISIBLE : View.GONE);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (RoomTypeAvailability roomType : roomTypes) {
            View card = inflater.inflate(R.layout.view_landing_room_type_card, container, false);
            bindRoomTypeCard(card, roomType);
            container.addView(card);
        }
    }

    private void bindCottageKtvOptions(LinearLayout container, TextView emptyState, List<CottageKtvOption> options,
                                        String fallbackCategory, boolean bookable, Section origin) {
        emptyState.setVisibility(options.isEmpty() ? View.VISIBLE : View.GONE);
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (CottageKtvOption option : options) {
            View card = inflater.inflate(R.layout.view_landing_room_type_card, container, false);
            bindCottageKtvCard(card, option, fallbackCategory, bookable, origin);
            container.addView(card);
        }
    }

    private void bindRoomTypeCard(View card, RoomTypeAvailability roomType) {
        String category = roomType.category_name != null && !roomType.category_name.trim().isEmpty()
                ? roomType.category_name : getString(R.string.service_type_hotel);
        String name = roomType.variant_name != null && !roomType.variant_name.trim().isEmpty()
                ? roomType.variant_name : category;
        boolean available = roomType.available_quantity > 0;
        CharSequence availabilityText = available
                ? getResources().getQuantityString(R.plurals.room_type_available_count,
                        roomType.available_quantity, roomType.available_quantity)
                : getString(R.string.status_room_type_fully_booked);

        String priceText = getString(R.string.format_price_per_night, formatMoney(roomType.price_per_night));
        bindCard(card, roomType.image_path,
                getString(R.string.format_room_type_with_category, name, category),
                roomType.capacity, roomType.description, priceText,
                available, availabilityText, true, Section.ROOMS,
                name, category, roomType.variant_id, roomType.image_path, priceText);
    }

    private void bindCottageKtvCard(View card, CottageKtvOption option, String fallbackCategory, boolean bookable, Section origin) {
        String category = option.category_name != null && !option.category_name.trim().isEmpty()
                ? option.category_name : fallbackCategory;
        String name = option.variant_name != null && !option.variant_name.trim().isEmpty()
                ? option.variant_name : category;
        boolean available = option.available_quantity > 0;
        CharSequence availabilityText = available
                ? getResources().getQuantityString(R.plurals.category_available_count,
                        option.available_quantity, option.available_quantity)
                : getString(R.string.status_room_type_fully_booked);

        String priceText = getString(R.string.format_price_with_unit, formatMoney(option.price), option.price_unit);
        bindCard(card, option.image_path,
                getString(R.string.format_room_type_with_category, name, category),
                option.capacity, option.description, priceText,
                available, availabilityText, bookable, origin,
                name, category, option.variant_id, option.image_path, priceText);
    }

    /** Shared binder for view_landing_room_type_card.xml, used by all four browse screens. */
    private void bindCard(View card, String imagePath, String titleText, int capacity, String description,
                           String priceText, boolean available, CharSequence availabilityText, boolean bookable, Section origin,
                           String roomTypeName, String categoryName, int variantId, String imagePathForStorage, String pricePerNight) {
        loadImage(card.findViewById(R.id.roomTypeCardImage), imagePath);

        ((TextView) card.findViewById(R.id.roomTypeCardName)).setText(titleText);
        ((TextView) card.findViewById(R.id.roomTypeCardCapacity))
                .setText(getString(R.string.landing_room_capacity_format, capacity));
        ((TextView) card.findViewById(R.id.roomTypeCardDescription)).setText(
                description != null && !description.trim().isEmpty()
                        ? description : getString(R.string.landing_room_description_fallback));
        ((TextView) card.findViewById(R.id.roomTypeCardPrice)).setText(priceText);

        TextView availability = card.findViewById(R.id.roomTypeCardAvailability);
        availability.setText(availabilityText);
        availability.setTextColor(ThemeManager.color(this,
                available ? R.attr.successText : R.attr.errorText));

        View bookButton = card.findViewById(R.id.roomTypeCardBookButton);
        bookButton.setVisibility(bookable ? View.VISIBLE : View.GONE);
        if (bookable) {
            bookButton.setOnClickListener(v -> handleBookTap(origin, roomTypeName, categoryName,
                    capacity, description, pricePerNight, variantId, imagePathForStorage));
        }
    }

    /** Not logged in: BOOK opens Login and remembers room selection. Logged in: BOOK jumps straight into the real booking flow. */
    private void handleBookTap(Section origin, String roomTypeName, String categoryName,
                              int capacity, String description, String pricePerNight, int variantId, String imagePath) {
        if (!loggedIn) {
            int type = origin == Section.COTTAGES ? PendingRoomSelection.TYPE_COTTAGE
                    : origin == Section.KTV ? PendingRoomSelection.TYPE_KTV
                    : PendingRoomSelection.TYPE_ROOM;
            PendingRoomSelection.set(imagePath, roomTypeName, categoryName,
                    capacity, description, pricePerNight, variantId, type);
            openLogin();
            return;
        }
        if (origin == Section.ROOMS) {
            bookingCatalog.showHotelFlow();
        }
        showModule(Section.BOOK);
    }

    /** Loads a photo from the backend, falling back to a placeholder icon. */
    private void loadImage(ImageView imageView, String imagePath) {
        String url = ApiClient.imageUrl(imagePath);
        if (url == null) {
            imageView.setImageResource(R.drawable.ic_bed);
            return;
        }
        Glide.with(this)
                .load(url)
                .placeholder(R.drawable.ic_bed)
                .error(R.drawable.ic_bed)
                .centerCrop()
                .into(imageView);
    }

    private String formatMoney(double amount) {
        return NumberFormat.getInstance(Locale.US).format(Math.round(amount));
    }

    /**
     * Shows a room/cottage/KTV overview screen after login, if an item was selected before
     * login. Keeps the page's standard header (logo/bell/profile) and Hotel Rooms/Cottages/KTV/
     * Halls nav bar visible on top, same as the browse screens — plus this screen's own back
     * arrow + "You Selected This Room/Cottage/KTV Room" header, whose back button (and system
     * back, see consumeBackPress) returns to the browse category the item was picked from
     * (roomOverviewOrigin) rather than all the way to Home.
     */
    private void showRoomOverview(PendingRoomSelection room) {
        leavingChat();
        currentSection = Section.ROOM_OVERVIEW;
        retintBottomNav();

        int headerTitleRes;
        if (room.type == PendingRoomSelection.TYPE_COTTAGE) {
            roomOverviewOrigin = Section.COTTAGES;
            headerTitleRes = R.string.room_overview_header_cottage;
        } else if (room.type == PendingRoomSelection.TYPE_KTV) {
            roomOverviewOrigin = Section.KTV;
            headerTitleRes = R.string.room_overview_header_ktv;
        } else {
            roomOverviewOrigin = Section.ROOMS;
            headerTitleRes = R.string.room_overview_header_room;
        }

        View v = LayoutInflater.from(this).inflate(R.layout.view_room_overview, landingContentContainer, false);

        // Header: this screen's own root isn't a ScrollView (see the layout's header comment),
        // so it misses swapContent()'s automatic nav-bar-height inset — applied manually here.
        navBar.post(() -> v.setPadding(v.getPaddingLeft(), v.getPaddingTop() + navBar.getHeight(),
                v.getPaddingRight(), v.getPaddingBottom()));
        ((TextView) v.findViewById(R.id.roomOverviewHeaderTitle)).setText(headerTitleRes);
        v.findViewById(R.id.roomOverviewBackButton).setOnClickListener(view -> showCategory(roomOverviewOrigin));

        // Image
        ImageView imageView = v.findViewById(R.id.roomOverviewImage);
        loadImage(imageView, room.imagePath);

        // Room details
        ((TextView) v.findViewById(R.id.roomOverviewType)).setText(
                getString(R.string.format_room_type_with_category, room.roomTypeName, room.categoryName));
        ((TextView) v.findViewById(R.id.roomOverviewCapacity)).setText(
                getString(R.string.landing_room_capacity_format, room.capacity));
        ((TextView) v.findViewById(R.id.roomOverviewDescription)).setText(room.description);
        ((TextView) v.findViewById(R.id.roomOverviewPrice)).setText(room.pricePerNight);

        // Book Now button: Hotel Rooms, Cottages and KTV each get their own focused gate screen
        // (see BookingCatalogController#showHotelFlowFromRoomOverview /
        // #showHotelFlowFromCottageOverview / #showHotelFlowFromKtvOverview).
        v.findViewById(R.id.roomOverviewBookNowButton).setOnClickListener(view -> {
            if (room.type == PendingRoomSelection.TYPE_ROOM) {
                bookingCatalog.showHotelFlowFromRoomOverview(room.variantId);
            } else if (room.type == PendingRoomSelection.TYPE_COTTAGE) {
                bookingCatalog.showHotelFlowFromCottageOverview(room.variantId);
            } else if (room.type == PendingRoomSelection.TYPE_KTV) {
                bookingCatalog.showHotelFlowFromKtvOverview(room.variantId);
            }
            showModule(Section.BOOK);
        });

        swapContent(v, true);
    }
}
