package com.aguafriogarden.resortinc;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * Detects a left/right swipe over its content and reports it via {@link OnSwipeListener}, used
 * by LandingActivity's Home/Hotel Rooms/Cottages/KTV/Halls top-nav sections so swiping pages
 * between them the same way tapping a nav tab does. Only intercepts once a drag is clearly more
 * horizontal than vertical past touch slop, so normal vertical scrolling of the ScrollView
 * content is untouched; a nested horizontal scroller (e.g. the home page's gallery strip) stays
 * untouched too, since it calls the standard {@code requestDisallowInterceptTouchEvent(true)}
 * once it starts scrolling, which ViewGroup's own dispatch honors before onInterceptTouchEvent
 * is ever asked.
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
                break;
            case MotionEvent.ACTION_MOVE:
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
        if (!swipeEnabled) {
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
