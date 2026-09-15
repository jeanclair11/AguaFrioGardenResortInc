package com.aguafriogarden.resortinc;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.FrameLayout;

/**
 * Detects a left/right swipe over its content and reports it via {@link OnSwipeListener}, used
 * by LandingActivity's Home/Hotel Rooms/Cottages/KTV/Halls top-nav sections so swiping pages
 * between them the same way tapping a nav tab does. Only intercepts once a drag is clearly more
 * horizontal than vertical past touch slop, so normal vertical scrolling of the ScrollView
 * content is untouched.
 *
 * <p>A nested horizontal scroller (e.g. the home page's gallery strip) uses the exact same
 * horizontal-vs-vertical touch-slop heuristic internally, and since this container is the
 * outermost ancestor, its own {@link #onInterceptTouchEvent} runs first on every event and would
 * otherwise win the race and steal the gesture before the nested scroller ever gets a chance to
 * claim it for itself. See {@link #setSwipeExclusionZone} for how that's avoided.
 */
public final class SwipeNavigationContainer extends FrameLayout {

    interface OnSwipeListener {
        void onSwipeLeft();

        void onSwipeRight();
    }

    private static final float HORIZONTAL_INTENT_RATIO = 1.5f;
    private static final int MIN_SWIPE_SLOP_MULTIPLES = 4;

    private final int touchSlop;
    private final int minFlingVelocity;
    private VelocityTracker velocityTracker;
    private float downX;
    private float downY;
    private boolean draggingHorizontally;
    private boolean swipeEnabled = true;
    private boolean downInsideExclusionZone;
    private View swipeExclusionZone;
    private OnSwipeListener listener;

    public SwipeNavigationContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration config = ViewConfiguration.get(context);
        touchSlop = config.getScaledTouchSlop();
        minFlingVelocity = config.getScaledMinimumFlingVelocity();
    }

    void setOnSwipeListener(OnSwipeListener listener) {
        this.listener = listener;
    }

    /** Disabled while showing a screen swiping doesn't apply to (e.g. Room Overview, Reserve/Book/Chat tabs). */
    void setSwipeEnabled(boolean enabled) {
        swipeEnabled = enabled;
    }

    /**
     * Marks a descendant (e.g. the home page's gallery {@code HorizontalScrollView}) as its own
     * swipeable region: any gesture that starts (ACTION_DOWN) inside its bounds is left alone
     * entirely — this container never intercepts it, regardless of how horizontal the drag turns
     * out to be — so the descendant's own touch handling always gets first and only claim to it.
     * Pass null to clear it.
     */
    void setSwipeExclusionZone(View zone) {
        swipeExclusionZone = zone;
    }

    private boolean isInsideExclusionZone(float x, float y) {
        if (swipeExclusionZone == null || swipeExclusionZone.getWidth() == 0 || swipeExclusionZone.getHeight() == 0
                || !isDescendantOfThis(swipeExclusionZone)) {
            // landingHomeScroll (and the gallery nested inside it) is detached from this
            // container whenever a different top-nav section is showing (see swapContent) — only
            // hit-test once it's actually attached, or offsetDescendantRectToMyCoords throws.
            return false;
        }
        Rect rect = new Rect(0, 0, swipeExclusionZone.getWidth(), swipeExclusionZone.getHeight());
        offsetDescendantRectToMyCoords(swipeExclusionZone, rect);
        return rect.contains((int) x, (int) y);
    }

    private boolean isDescendantOfThis(View view) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent == this) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!swipeEnabled) {
            return false;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                draggingHorizontally = false;
                downInsideExclusionZone = isInsideExclusionZone(downX, downY);
                break;
            case MotionEvent.ACTION_MOVE:
                if (downInsideExclusionZone) {
                    break;
                }
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * HORIZONTAL_INTENT_RATIO) {
                    draggingHorizontally = true;
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!swipeEnabled || downInsideExclusionZone) {
            return false;
        }
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(ev);

        if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            float totalDx = ev.getX() - downX;
            velocityTracker.computeCurrentVelocity(1000);
            float velocityX = velocityTracker.getXVelocity();
            if (draggingHorizontally
                    && (Math.abs(totalDx) > touchSlop * MIN_SWIPE_SLOP_MULTIPLES || Math.abs(velocityX) > minFlingVelocity)) {
                if (listener != null) {
                    if (totalDx < 0) {
                        listener.onSwipeLeft();
                    } else {
                        listener.onSwipeRight();
                    }
                }
            }
            draggingHorizontally = false;
            velocityTracker.recycle();
            velocityTracker = null;
        }
        return true;
    }
}
