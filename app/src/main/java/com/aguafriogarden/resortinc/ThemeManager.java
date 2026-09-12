package com.aguafriogarden.resortinc;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;

/**
 * Handles the app's two theme modes (Light, Dark) without AppCompat: the
 * choice is persisted and applied per activity with setTheme(), and every
 * screen resolves its colors through the semantic ?attr slots the themes fill.
 *
 * Switching themes plays a gentle crossfade: the outgoing look is captured as
 * a screenshot, laid over the rebuilt screen, and dissolved away.
 */
final class ThemeManager {

    static final int MODE_LIGHT = 0;
    static final int MODE_DARK = 1;

    private static final String PREFS = "theme";
    private static final String KEY_MODE = "mode";

    private static final long THEME_FADE_MS = 700L;
    private static final long BACKDROP_FADE_MS = 6000L;

    // Snapshot of the outgoing theme, handed across the recreate() to the
    // new activity instance (same process, so statics survive).
    private static Bitmap pendingSnapshot;

    private ThemeManager() {
    }

    static int mode(Context context) {
        return prefs(context).getInt(KEY_MODE, MODE_DARK);
    }

    /** Call before setContentView so the activity inflates with the chosen theme. */
    static void apply(Activity activity) {
        activity.setTheme(mode(activity) == MODE_LIGHT
                ? R.style.Theme_AguaFrio_Light : R.style.Theme_AguaFrio_Dark);
    }

    /**
     * Call from onResume, passing back whatever mode(activity) returned when this instance was
     * created: recreates the activity if the persisted theme has since changed — e.g. toggled
     * from a different Activity in the back stack (this app's Activity/setTheme() approach, with
     * no AppCompatDelegate, otherwise only updates whichever single Activity did the toggling;
     * every other already-running Activity keeps showing the old theme until force-closed and
     * reopened). Returns true if a recreate was triggered, so the caller can skip the rest of its
     * own onResume work — it's about to be torn down anyway.
     */
    static boolean recreateIfThemeChanged(Activity activity, int themeModeAtCreate) {
        if (mode(activity) != themeModeAtCreate) {
            activity.recreate();
            return true;
        }
        return false;
    }

    /** Resolves one of the theme's semantic color attributes (R.attr.*). */
    static int color(Activity activity, int attr) {
        TypedValue value = new TypedValue();
        activity.getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    /**
     * The toggle flips Light <-> Dark, its icon showing the current mode.
     * Call after setContentView: this also finishes a fade started by the
     * previous instance.
     */
    static void bindToggle(Activity activity, ImageView toggle) {
        toggle.setImageResource(mode(activity) == MODE_LIGHT ? R.drawable.ic_sun : R.drawable.ic_moon);
        toggle.setOnClickListener(v ->
                setMode(activity, mode(activity) == MODE_LIGHT ? MODE_DARK : MODE_LIGHT));
        playPendingFade(activity);
    }

    /** Switches to the given mode (snapshot + recreate), e.g. from a Profile toggle. */
    static void setMode(Activity activity, int mode) {
        if (mode == mode(activity)) {
            return;
        }
        captureSnapshot(activity);
        prefs(activity).edit().putInt(KEY_MODE, mode).apply();
        activity.recreate();
    }

    /**
     * Slow, continuous crossfade between the active theme's pair of header
     * photos. Returns the running animator so the caller can cancel it in
     * onStop; only the overlay's alpha animates, the layout never moves.
     */
    static Animator startHeaderCrossfade(Activity activity, ImageView base, ImageView overlay) {
        boolean night = mode(activity) == MODE_DARK;
        base.setImageResource(night ? R.drawable.agua_night_mode : R.drawable.agua_light_mode);
        overlay.setImageResource(night ? R.drawable.agua_night_mode2 : R.drawable.agua_light_mode2);
        overlay.setAlpha(0f);

        ObjectAnimator fade = ObjectAnimator.ofFloat(overlay, View.ALPHA, 0f, 1f);
        fade.setDuration(BACKDROP_FADE_MS);
        fade.setRepeatCount(ObjectAnimator.INFINITE);
        fade.setRepeatMode(ObjectAnimator.REVERSE);
        // Eases in and out at the extremes so each photo lingers briefly.
        fade.setInterpolator(new AccelerateDecelerateInterpolator());
        fade.start();
        return fade;
    }

    /** Photographs the current screen so the new theme can fade in under it. */
    private static void captureSnapshot(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        if (decor.getWidth() == 0 || decor.getHeight() == 0) {
            return;
        }
        Bitmap snapshot = Bitmap.createBitmap(
                decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(snapshot));
        pendingSnapshot = snapshot;
    }

    /**
     * Dissolves the previous theme's screenshot into the rebuilt screen. Call after
     * setContentView from every Activity that can be recreated by setMode() — bindToggle() does
     * this for MainActivity's own toggle button; an Activity recreated by a theme switch
     * triggered elsewhere (e.g. LandingActivity, via Profile's dark mode switch) needs to call
     * this directly instead, or the captured snapshot is never shown and leaks until some later,
     * unrelated bindToggle() call plays a stale screenshot over the wrong screen.
     */
    static void playPendingFade(Activity activity) {
        Bitmap snapshot = pendingSnapshot;
        if (snapshot == null) {
            return;
        }
        pendingSnapshot = null;

        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        ImageView overlay = new ImageView(activity);
        overlay.setImageBitmap(snapshot);
        overlay.setScaleType(ImageView.ScaleType.FIT_XY);
        decor.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overlay.animate()
                .alpha(0f)
                .setDuration(THEME_FADE_MS)
                .setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .withEndAction(() -> {
                    decor.removeView(overlay);
                    snapshot.recycle();
                })
                .start();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Applies a glassmorphism background blur and dim effect to the given window.
     * Works on Android 12 (API 31) and above.
     */
    static void applyGlassEffect(Window window) {
        if (window == null) return;
        
        // Dim background
        window.setDimAmount(0.5f);

        // Blur background (API 31+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.setBlurBehindRadius(60);
            window.setAttributes(lp);
        }
    }
}
