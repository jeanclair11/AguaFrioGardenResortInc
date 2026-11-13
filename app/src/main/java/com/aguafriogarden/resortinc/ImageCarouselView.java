package com.aguafriogarden.resortinc;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * A swipeable, snapping row of photos for the Hotel Rooms / Cottages browse cards, with a small
 * dot indicator over the bottom of the picture. Swipe left/right to move between photos.
 *
 * <p>Only the photo and its dots live here: the card's badges, text, price and buttons stay in
 * the card layout, so the card looks and works exactly as before. Photos load lazily through
 * {@link ImageLoader} — the visible one and its neighbours — so a card with five photos doesn't
 * fetch all five up front. A card with a single photo (or none) is a plain static picture: no dots,
 * no scrolling, and it never takes touches from the page, so the browse screens' swipe-between-
 * sections gesture still works over it.
 *
 * <p>With two or more photos the carousel claims horizontal drags (the page-level
 * {@link SwipeNavigationContainer} would otherwise steal them) but hands vertical drags straight
 * back to the page's ScrollView.
 */
public final class ImageCarouselView extends FrameLayout {

    private final PagerScrollView scroller;
    private final LinearLayout pages;
    private final LinearLayout dotRow;
    private final List<ImageView> pageViews = new ArrayList<>();
    private final List<View> dotViews = new ArrayList<>();
    private final int touchSlop;
    private final float density;

    private List<String> urls = new ArrayList<>();
    private boolean[] loaded = new boolean[0];
    private int fallbackIconRes = R.drawable.ic_bed;
    private int pageWidth;
    private int currentPage;

    public ImageCarouselView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        scroller = new PagerScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(OVER_SCROLL_NEVER);
        pages = new LinearLayout(context);
        pages.setOrientation(LinearLayout.HORIZONTAL);
        scroller.addView(pages, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        addView(scroller, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        dotRow = new LinearLayout(context);
        dotRow.setOrientation(LinearLayout.HORIZONTAL);
        dotRow.setGravity(Gravity.CENTER);
        dotRow.setPadding(dp(8), dp(5), dp(8), dp(5));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(12));
        pill.setColor(Color.argb(90, 0, 0, 0));
        dotRow.setBackground(pill);
        LayoutParams dotParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dotParams.bottomMargin = dp(10);
        addView(dotRow, dotParams);
        dotRow.setVisibility(GONE);

        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * Shows {@code imageUrls} in order (main image first). Null/blank entries are skipped; an empty
     * list shows the fallback icon, like a card with no photo always has.
     */
    void setImages(List<String> imageUrls, int fallbackIconRes) {
        this.fallbackIconRes = fallbackIconRes;
        urls = new ArrayList<>();
        if (imageUrls != null) {
            for (String url : imageUrls) {
                if (url != null && !url.trim().isEmpty() && !urls.contains(url)) {
                    urls.add(url);
                }
            }
        }
        int count = Math.max(urls.size(), 1);
        loaded = new boolean[count];
        currentPage = 0;

        pages.removeAllViews();
        pageViews.clear();
        dotRow.removeAllViews();
        dotViews.clear();
        for (int i = 0; i < count; i++) {
            ImageView page = new ImageView(getContext());
            page.setScaleType(ImageView.ScaleType.CENTER_CROP);
            page.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            pages.addView(page, new LinearLayout.LayoutParams(pageWidth, LayoutParams.MATCH_PARENT));
            pageViews.add(page);
        }
        if (count > 1) {
            for (int i = 0; i < count; i++) {
                View dot = new View(getContext());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
                lp.leftMargin = dp(3);
                lp.rightMargin = dp(3);
                dotRow.addView(dot, lp);
                dotViews.add(dot);
            }
            dotRow.setVisibility(VISIBLE);
        } else {
            dotRow.setVisibility(GONE);
        }
        updateDots();
        scroller.scrollTo(0, 0);
        loadNearby();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || w == pageWidth) {
            return;
        }
        pageWidth = w;
        for (ImageView page : pageViews) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) page.getLayoutParams();
            lp.width = w;
            page.setLayoutParams(lp);
        }
        post(() -> scroller.scrollTo(currentPage * pageWidth, 0));
        loadNearby();
    }

    /** Loads the current photo and the ones either side of it, once each, but only once the
     *  carousel has a width (Glide sizes the decode to the view). */
    private void loadNearby() {
        if (pageWidth <= 0) {
            return;
        }
        for (int i = Math.max(0, currentPage - 1); i <= Math.min(pageViews.size() - 1, currentPage + 1); i++) {
            if (!loaded[i]) {
                loaded[i] = true;
                ImageLoader.load(pageViews.get(i), i < urls.size() ? urls.get(i) : null, fallbackIconRes);
            }
        }
    }

    private void onPageScrolled(int scrollX) {
        if (pageWidth <= 0 || pageViews.isEmpty()) {
            return;
        }
        int page = Math.max(0, Math.min(pageViews.size() - 1, Math.round(scrollX / (float) pageWidth)));
        if (page != currentPage) {
            currentPage = page;
            updateDots();
            loadNearby();
        }
    }

    private void updateDots() {
        for (int i = 0; i < dotViews.size(); i++) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(i == currentPage ? Color.WHITE : Color.argb(130, 255, 255, 255));
            dotViews.get(i).setBackground(dot);
        }
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    /** HorizontalScrollView that settles on exactly one photo after a drag or fling, and keeps
     *  the page's own swipe/scroll gestures from stealing a horizontal photo swipe. */
    private final class PagerScrollView extends HorizontalScrollView {
        private float downX;
        private float downY;
        private boolean verticalClaimed;
        private boolean flingHandled;

        PagerScrollView(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (pageViews.size() > 1) {
                ViewParent parent = getParent();
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = ev.getX();
                        downY = ev.getY();
                        verticalClaimed = false;
                        flingHandled = false;
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!verticalClaimed) {
                            float dx = Math.abs(ev.getX() - downX);
                            float dy = Math.abs(ev.getY() - downY);
                            if (dy > touchSlop && dy > dx) {
                                // Clearly a vertical scroll of the page, not a photo swipe.
                                verticalClaimed = true;
                                if (parent != null) {
                                    parent.requestDisallowInterceptTouchEvent(false);
                                }
                            }
                        }
                        break;
                    default:
                        break;
                }
            }
            return super.dispatchTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            boolean handled = super.onTouchEvent(ev);
            int action = ev.getActionMasked();
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                    && pageWidth > 0 && !flingHandled) {
                snapTo(Math.round(getScrollX() / (float) pageWidth));
            }
            return handled;
        }

        /** A fling moves at most one photo in the direction of the swipe. */
        @Override
        public void fling(int velocityX) {
            if (pageWidth <= 0) {
                super.fling(velocityX);
                return;
            }
            flingHandled = true;
            float position = getScrollX() / (float) pageWidth;
            snapTo(velocityX > 0 ? (int) Math.ceil(position) : (int) Math.floor(position));
        }

        private void snapTo(int page) {
            int target = Math.max(0, Math.min(pageViews.size() - 1, page));
            smoothScrollTo(target * pageWidth, 0);
        }

        @Override
        protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            onPageScrolled(l);
        }
    }
}
