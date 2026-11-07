package com.aguafriogarden.resortinc;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * Loading placeholder for an image: a flat block in the field-gray with a soft highlight
 * sweeping across it. It has no intrinsic size, so inside an ImageView it fills exactly the
 * space the picture will take and the layout never shifts when the image arrives.
 *
 * The sweep reschedules itself from draw(), so it only runs while the drawable is on screen
 * and stops by itself once the ImageView swaps it for the real picture (the callback is
 * cleared) - there is nothing to start, stop or leak.
 */
final class SkeletonDrawable extends Drawable {

    private static final long SWEEP_MS = 1300L;
    private static final long FRAME_MS = 16L;
    private static final float BAND_FRACTION = 0.6f;

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sweepMatrix = new Matrix();
    private final Runnable tick = this::invalidateSelf;
    private final int baseColor;
    private final int highlightColor;

    private LinearGradient sweep;
    private float sweepWidth;

    SkeletonDrawable(Context context) {
        TypedArray colors = context.obtainStyledAttributes(new int[]{
                R.attr.authFieldDisabledBackground, R.attr.authFieldBackground});
        baseColor = colors.getColor(0, 0xFFE5E7EB);
        highlightColor = colors.getColor(1, 0xFFF3F6FB);
        colors.recycle();
        basePaint.setColor(baseColor);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        sweepWidth = Math.max(1f, bounds.width() * BAND_FRACTION);
        sweep = new LinearGradient(0f, 0f, sweepWidth, 0f,
                new int[]{baseColor, highlightColor, baseColor},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        sweepPaint.setShader(sweep);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        canvas.drawRect(bounds, basePaint);

        float phase = (SystemClock.uptimeMillis() % SWEEP_MS) / (float) SWEEP_MS;
        float x = bounds.left - sweepWidth + phase * (bounds.width() + sweepWidth);
        sweepMatrix.setTranslate(x, 0f);
        sweep.setLocalMatrix(sweepMatrix);

        int save = canvas.save();
        canvas.clipRect(bounds);
        canvas.drawRect(x, bounds.top, x + sweepWidth, bounds.bottom, sweepPaint);
        canvas.restoreToCount(save);

        unscheduleSelf(tick);
        scheduleSelf(tick, SystemClock.uptimeMillis() + FRAME_MS);
    }

    @Override
    public void setAlpha(int alpha) {
        basePaint.setAlpha(alpha);
        sweepPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        basePaint.setColorFilter(colorFilter);
        sweepPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
