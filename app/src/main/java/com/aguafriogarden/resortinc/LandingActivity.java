package com.aguafriogarden.resortinc;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
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
 * underneath a Greeting block that replaces the Hero card's logo.
 */
public class LandingActivity extends Activity {

    private enum Section {
        HOME, ROOMS, COTTAGES, KTV, HALLS,
        RESERVE, BOOK, CHAT, FEEDBACK, PROFILE, ALERTS, ROOM_OVERVIEW
    }

    /** Bottom-nav order; index into this array lines up with bottomNavItems. */
    private static final Section[] BOTTOM_NAV_SECTIONS =
            {Section.HOME, Section.RESERVE, Section.BOOK, Section.CHAT, Section.FEEDBACK};
    private static final int BOTTOM_NAV_CHAT_INDEX = 3;

    /** Top-nav order; index into this array lines up with topNavItems. Swiping left/right over
     *  the Home content steps forward/backward through this same order (see #stepTopNav). */
    private static final Section[] TOP_NAV_SECTIONS =
            {Section.HOME, Section.ROOMS, Section.COTTAGES, Section.KTV, Section.HALLS};

    private static final long CONTENT_FADE_MS = 150L;
    private static final long CONTENT_SLIDE_MS = 220L;
    private static final long BADGE_REFRESH_MS = 2000L;
    private static final int REQUEST_POST_NOTIFICATIONS = 1001;
    private static final String STATE_SECTION = "section";

    private SwipeNavigationContainer landingContentContainer;
    private ScrollView landingHomeScroll;
    private HorizontalScrollView navBar;
    private View headerBar;
    private FrameLayout heroOrGreetingSlot;
    private View heroSectionView;
    private View landingLogInButton;
    private View landingBellCluster;
    private ImageView profileButton;
    private View bottomNavDivider;
    private LinearLayout bottomNav;
    private final View[] bottomNavItems = new View[BOTTOM_NAV_SECTIONS.length];
    private final View[] topNavItems = new View[TOP_NAV_SECTIONS.length];

    private Section currentSection = Section.HOME;
    private Section roomOverviewOrigin = Section.ROOMS;
    private boolean navBarVisible = true;
    private boolean loggedIn = false;
    private int activeThemeMode;

    private ReserveCatalogController reserveCatalog;
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
        activeThemeMode = ThemeManager.mode(this);
        setContentView(R.layout.activity_landing);
        ThemeManager.playPendingFade(this);

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
        // The gallery strip is its own horizontal swipe region — a swipe that starts on it must
        // only scroll the gallery, never step the top-nav tabs (see setSwipeExclusionZone).
        HorizontalScrollView galleryScroll = findViewById(R.id.landingGalleryScroll);
        landingContentContainer.setSwipeExclusionZone(galleryScroll);
        setupInfiniteGallery(galleryScroll, findViewById(R.id.landingGalleryContent));

        topNavItems[0] = findViewById(R.id.navTabHome);
        topNavItems[1] = findViewById(R.id.navTabRooms);
        topNavItems[2] = findViewById(R.id.navTabCottages);
        topNavItems[3] = findViewById(R.id.navTabKtv);
        topNavItems[4] = findViewById(R.id.navTabHalls);
        topNavItems[0].setOnClickListener(v -> showHome());
        topNavItems[1].setOnClickListener(v -> showCategory(Section.ROOMS));
        topNavItems[2].setOnClickListener(v -> showCategory(Section.COTTAGES));
        topNavItems[3].setOnClickListener(v -> showCategory(Section.KTV));
        topNavItems[4].setOnClickListener(v -> showCategory(Section.HALLS));
        landingLogInButton.setOnClickListener(v -> openLogin());
        landingBellCluster.setOnClickListener(v -> showModule(Section.ALERTS));
        profileButton.setOnClickListener(v -> showModule(Section.PROFILE));
        findViewById(R.id.shareAppButton).setOnClickListener(v -> shareApp());

        landingContentContainer.setOnSwipeListener(new SwipeNavigationContainer.OnSwipeListener() {
            @Override
            public void onSwipeLeft() {
                stepTopNav(1);
            }

            @Override
            public void onSwipeRight() {
                stepTopNav(-1);
            }
        });
        updateSwipeChrome();

        if (!ProfileStore.getAuthToken(this).isEmpty()) {
            enterLoggedInState();
        }
        restoreSection(savedInstanceState);
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
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SECTION, currentSection.name());
    }

    /**
     * After Activity.recreate() — e.g. the theme toggle (ThemeManager#setMode, tapped from
     * Profile's dark mode switch) recreates whichever Activity is currently on screen — returns
     * to whichever tab/category was showing instead of resetting to Home, which is what a bare
     * recreate() would otherwise do since currentSection has no other persistence. ROOM_OVERVIEW
     * isn't restorable this way (its backing data isn't saved here), so that falls back to Home
     * same as before.
     */
    private void restoreSection(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }
        Section section;
        try {
            section = Section.valueOf(savedInstanceState.getString(STATE_SECTION, ""));
        } catch (IllegalArgumentException e) {
            return;
        }
        if (section == Section.HOME || section == Section.ROOM_OVERVIEW) {
            return;
        }
        if (indexOfTopNavSection(section) >= 0) {
            showCategory(section);
        } else if (loggedIn) {
            showModule(section);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ThemeManager.recreateIfThemeChanged(this, activeThemeMode)) {
            return;
        }
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
            reserveCatalog.onActivityResult(requestCode, resultCode, data);
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
            if (currentSection == Section.RESERVE && reserveCatalog.handleBackPressed()) {
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

    /** "Share the App" section's Share App button: hands off to the system share sheet with a
     *  plain invite message (no app-store link exists yet to include). */
    private void shareApp() {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_app_invite_text));
        startActivity(Intent.createChooser(shareIntent, getString(R.string.button_share_app)));
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

        reserveCatalog = new ReserveCatalogController(this, this::showHome);
        bookingCatalog = new BookingCatalogController(this,
                () -> showModule(Section.RESERVE), this::showHome);
        dashboardHome = new DashboardHomeController(this);
        profileController = new ProfileController(this, this::showHome, this::logout, this::bindHeaderAvatar);
        feedbackController = new FeedbackController(this);
        chatController = new ChatController(this);
        notificationController = new NotificationController(this, n -> {
            if (n.type == NotificationStore.Type.CHAT) {
                showModule(Section.CHAT);
            } else {
                // ReservationStore-backed reservation details no longer exist (the Reserve tab's
                // old ReservationFlowController module was fully replaced by ReserveCatalogController
                // — see that class) — a notification tap can only land back on Reserve's own gate.
                showModule(Section.RESERVE);
            }
        });

        heroOrGreetingSlot.removeAllViews();
        heroOrGreetingSlot.addView(dashboardHome.getRootView());

        landingLogInButton.setVisibility(View.GONE);
        landingBellCluster.setVisibility(View.VISIBLE);
        profileButton.setVisibility(View.VISIBLE);
        bindHeaderAvatar();

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

    /**
     * Shows the guest's uploaded profile picture on the header's profile icon once logged in —
     * same picture/fallback logic as ProfileController's own avatar (see that class's
     * bindAvatar), kept in sync here via the onAvatarChanged callback passed into
     * ProfileController's constructor so a picture change is reflected immediately without
     * needing to reopen the app.
     */
    private void bindHeaderAvatar() {
        Bitmap picture = ProfileStore.loadAvatar(this);
        if (picture != null) {
            profileButton.setPadding(0, 0, 0, 0);
            profileButton.setImageTintList(null);
            profileButton.setScaleType(ImageView.ScaleType.CENTER_CROP);
            profileButton.setBackgroundResource(R.drawable.bg_module_icon_circle);
            profileButton.setClipToOutline(true);
            profileButton.setImageBitmap(picture);
        } else {
            profileButton.setPadding(8, 8, 8, 8);
            profileButton.setBackground(null);
            profileButton.setClipToOutline(false);
            profileButton.setImageResource(R.drawable.ic_person);
            profileButton.setImageTintList(ColorStateList.valueOf(ThemeManager.color(this, R.attr.onTopBar)));
            profileButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
    }

    private void logout() {
        stopService(new Intent(this, ChatPollingService.class));
        ProfileStore.clearAuthToken(this);
        badgeHandler.removeCallbacks(badgeTick);
        loggedIn = false;

        reserveCatalog = null;
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

    // ---- Top nav: Home/Rooms/Cottages/KTV/Halls, plus swipe-to-page ----------

    /** Colors the top nav tabs for whichever Home/Rooms/Cottages/KTV/Halls section (if any) is
     *  active — same idea as retintBottomNav(), but these tabs are plain text with no icon. */
    private void retintTopNav() {
        for (int i = 0; i < topNavItems.length; i++) {
            boolean active = TOP_NAV_SECTIONS[i] == currentSection;
            ((TextView) topNavItems[i]).setTextColor(
                    ThemeManager.color(this, active ? R.attr.navItemActive : R.attr.textOnScreenMuted));
        }
    }

    private int indexOfTopNavSection(Section section) {
        for (int i = 0; i < TOP_NAV_SECTIONS.length; i++) {
            if (TOP_NAV_SECTIONS[i] == section) {
                return i;
            }
        }
        return -1;
    }

    /** Keeps the top nav highlight and the swipe gesture in sync with whichever section is
     *  showing. Swiping only makes sense while browsing Home/Rooms/Cottages/KTV/Halls, so it's
     *  switched off for the logged-in Reserve/Book/Chat/Feedback/Profile/Alerts tabs and the
     *  Room/Cottage/KTV Overview screen, none of which are part of that ring. */
    private void updateSwipeChrome() {
        retintTopNav();
        landingContentContainer.setSwipeEnabled(indexOfTopNavSection(currentSection) >= 0);
    }

    /** Swipe left moves to the next section to the right (+1); swipe right moves to the previous
     *  one (-1). Clamps at the first/last tab rather than wrapping around. */
    private void stepTopNav(int direction) {
        int index = indexOfTopNavSection(currentSection);
        int nextIndex = index + direction;
        if (index < 0 || nextIndex < 0 || nextIndex >= TOP_NAV_SECTIONS.length) {
            return;
        }
        Section next = TOP_NAV_SECTIONS[nextIndex];
        if (next == Section.HOME) {
            showHome(direction);
        } else {
            showCategory(next, direction);
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
        setBottomNavHidden(false);
        retintBottomNav();
        updateSwipeChrome();

        View content;
        if (section == Section.RESERVE) {
            content = reserveCatalog.getRootView();
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
        showHome(0);
    }

    /** slideDirection: 0 fades in (tap navigation); +1/-1 slides in from the right/left (swipe —
     *  see #stepTopNav), matching the finger's drag direction instead of cross-fading. */
    private void showHome(int slideDirection) {
        if (currentSection == Section.HOME) {
            return;
        }
        leavingChat();
        swapContent(landingHomeScroll, true, slideDirection);
        currentSection = Section.HOME;
        setBottomNavHidden(false);
        retintBottomNav();
        updateSwipeChrome();
        if (loggedIn) {
            refreshBadges();
        }
    }

    /** Inflates a fresh browse screen for the tapped section and starts loading its live data. */
    private void showCategory(Section section) {
        showCategory(section, 0);
    }

    /** slideDirection: 0 fades in (tap navigation); +1/-1 slides in from the right/left (swipe —
     *  see #stepTopNav), matching the finger's drag direction instead of cross-fading. */
    private void showCategory(Section section, int slideDirection) {
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

        swapContent(browse, true, slideDirection);
        currentSection = section;
        setBottomNavHidden(false);
        retintBottomNav();
        updateSwipeChrome();
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

    /** Fades newContent in — see the 3-arg overload for the full contract; slideDirection 0. */
    private void swapContent(View newContent, boolean showTopNavBar) {
        swapContent(newContent, showTopNavBar, 0);
    }

    /**
     * Swaps in newContent over whatever's currently showing, then drops the old content once the
     * animation finishes. landingHomeScroll is a retained field (never destroyed), so swapping
     * back to it later just re-adds the same instance. showTopNavBar controls the top nav bar
     * (Agua Frio/Hotel Rooms/Cottages/KTV/Halls): present on Home/browse tabs, fully gone on
     * Reserve/Book/Chat/Feedback/Profile/Alerts/Booking Details, which have their own bottom nav.
     * slideDirection 0 cross-fades (tap navigation, and every non-top-nav caller); +1/-1 instead
     * slides newContent in from the right/left while the old content slides out the opposite
     * side in lockstep, matching the swipe gesture that triggered it (see #stepTopNav) instead of
     * a directionless fade.
     */
    private void swapContent(View newContent, boolean showTopNavBar, int slideDirection) {
        List<View> oldChildren = new ArrayList<>();
        for (int i = 0; i < landingContentContainer.getChildCount(); i++) {
            oldChildren.add(landingContentContainer.getChildAt(i));
        }
        // A rapid follow-up swipe can retrigger this before the previous transition's
        // withEndAction has run; cancelling first and resetting both properties normalizes
        // whichever one the previous transition didn't touch (fade never resets translationX,
        // slide never resets alpha), so a persisted view like landingHomeScroll can't get stuck
        // off-screen or invisible from a leftover animation value.
        newContent.animate().cancel();
        newContent.setAlpha(1f);
        newContent.setTranslationX(0f);

        landingContentContainer.addView(newContent);
        if (newContent instanceof ScrollView) {
            configureScrollableContent((ScrollView) newContent);
        }
        setNavBarPresent(showTopNavBar);

        if (slideDirection == 0) {
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
            return;
        }

        float width = landingContentContainer.getWidth();
        newContent.setTranslationX(slideDirection > 0 ? width : -width);
        newContent.animate()
                .translationX(0f)
                .setDuration(CONTENT_SLIDE_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    for (View old : oldChildren) {
                        landingContentContainer.removeView(old);
                        old.setTranslationX(0f);
                    }
                })
                .start();
        for (View old : oldChildren) {
            old.animate()
                    .translationX(slideDirection > 0 ? -width : width)
                    .setDuration(CONTENT_SLIDE_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
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
     * Makes the home page's photo gallery feel endlessly loopable in both directions without a
     * ViewPager2/RecyclerView dependency (this project stays on plain framework widgets — see
     * SwipeNavigationContainer). content actually holds three back-to-back copies of the same 5
     * tiles (copy A/B/C, see activity_landing.xml), each exactly copyWidth wide, so shifting
     * scrollX by exactly one copyWidth always lands on pixel-identical content one copy over —
     * regardless of where that shift happens to occur. The guest starts on copy B (one
     * copyWidth of buffer on either side); once scrollX actually hits the real floor (0, out of
     * left-side buffer) or ceiling (out of right-side buffer), this silently shifts by one
     * copyWidth to restore a full buffer in that direction, so a true edge is never reached in
     * ordinary use and the strip just appears to keep going forever either way.
     */
    private void setupInfiniteGallery(HorizontalScrollView galleryScroll, LinearLayout content) {
        content.post(() -> {
            // content's own paddingStart/paddingEnd only appear once, before copy A and after
            // copy C — excluded here so copyWidth is exactly one copy's pixel width (the true
            // repeat period), not skewed by that one-time lead-in/trail-off space.
            int copyWidth = (content.getWidth() - content.getPaddingLeft() - content.getPaddingRight()) / 3;
            if (copyWidth <= 0) {
                return;
            }
            galleryScroll.scrollTo(content.getPaddingLeft() + copyWidth, 0);
            galleryScroll.setOnScrollChangeListener((View.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                int maxScrollX = content.getWidth() - galleryScroll.getWidth();
                if (scrollX <= 0) {
                    galleryScroll.scrollTo(scrollX + copyWidth, 0);
                } else if (scrollX >= maxScrollX) {
                    galleryScroll.scrollTo(scrollX - copyWidth, 0);
                }
            });
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

    /**
     * Hides the bottom nav for the duration of the focused Hotel Rooms/Cottage/KTV booking or
     * reservation wizard (see HotelBookingFlowController), which has its own per-screen header
     * and back button and isn't meant to be left via the bottom tabs mid-flow. Only meaningful
     * while logged in — logout() already forces the bar GONE and removes its items outright.
     */
    private void setBottomNavHidden(boolean hidden) {
        if (!loggedIn) {
            return;
        }
        bottomNavDivider.setVisibility(hidden ? View.GONE : View.VISIBLE);
        bottomNav.setVisibility(hidden ? View.GONE : View.VISIBLE);
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
            bookButton.setOnClickListener(v -> handleCardTap(origin, roomTypeName, categoryName,
                    capacity, description, pricePerNight, variantId, imagePathForStorage,
                    PendingRoomSelection.PURPOSE_BOOK));
        }

        View reserveButton = card.findViewById(R.id.roomTypeCardReserveButton);
        reserveButton.setVisibility(bookable ? View.VISIBLE : View.GONE);
        if (bookable) {
            reserveButton.setOnClickListener(v -> handleCardTap(origin, roomTypeName, categoryName,
                    capacity, description, pricePerNight, variantId, imagePathForStorage,
                    PendingRoomSelection.PURPOSE_RESERVE));
        }
    }

    /**
     * BOOK and RESERVE share the same Room/Cottage/KTV Overview screen (see #showRoomOverview) —
     * only its CTA button's label and destination differ, driven by purpose (PURPOSE_BOOK/
     * PURPOSE_RESERVE). Not logged in: remembers the selection and opens Login; once logged in,
     * {@link #onCreate} restores it via showRoomOverview. Logged in: goes straight there.
     */
    private void handleCardTap(Section origin, String roomTypeName, String categoryName,
                              int capacity, String description, String pricePerNight, int variantId,
                              String imagePath, int purpose) {
        int type = origin == Section.COTTAGES ? PendingRoomSelection.TYPE_COTTAGE
                : origin == Section.KTV ? PendingRoomSelection.TYPE_KTV
                : PendingRoomSelection.TYPE_ROOM;
        PendingRoomSelection.set(imagePath, roomTypeName, categoryName,
                capacity, description, pricePerNight, variantId, type, purpose);
        if (!loggedIn) {
            openLogin();
            return;
        }
        showRoomOverview(PendingRoomSelection.take());
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
        updateSwipeChrome();

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

        // CTA button: both BOOK and RESERVE send Hotel Rooms/Cottages/KTV each to their own
        // focused gate screen (see BookingCatalogController#showHotelFlowFromRoomOverview /
        // #showHotelFlowFromCottageOverview / #showHotelFlowFromKtvOverview) — identical flow
        // either way, except RESERVE ends at the dedicated Reservation Summary screen instead of
        // that category's own Billing/Payment (see HotelBookingFlowController's reserveMode).
        Button ctaButton = v.findViewById(R.id.roomOverviewBookNowButton);
        boolean reserve = room.purpose == PendingRoomSelection.PURPOSE_RESERVE;
        ctaButton.setText(reserve ? R.string.button_reserve_now : R.string.button_book_now);
        ctaButton.setOnClickListener(view -> {
            Runnable backToThisOverview = () -> showRoomOverview(room);
            if (room.type == PendingRoomSelection.TYPE_ROOM) {
                bookingCatalog.showHotelFlowFromRoomOverview(room.variantId, backToThisOverview, reserve);
            } else if (room.type == PendingRoomSelection.TYPE_COTTAGE) {
                bookingCatalog.showHotelFlowFromCottageOverview(room.variantId, backToThisOverview, reserve);
            } else if (room.type == PendingRoomSelection.TYPE_KTV) {
                bookingCatalog.showHotelFlowFromKtvOverview(room.variantId, backToThisOverview, reserve);
            }
            showModule(Section.BOOK);
            // The wizard that just opened has its own per-screen header/back button, not the
            // bottom tabs — hidden until the guest backs out to this Overview or all the way
            // home (see setBottomNavHidden, showRoomOverview, showHome).
            setBottomNavHidden(true);
        });

        setBottomNavHidden(false);
        swapContent(v, true);
    }
}
