package com.aguafriogarden.resortinc;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Shown in place of an image that has no URL or failed to load: the same gray block as the
 * skeleton with a small, centered icon (bed, star...), so the container keeps its size and
 * reads as "no picture" rather than as something still loading. No intrinsic size, so it fills
 * the ImageView without being cropped or stretched.
 */
final class ImageFallbackDrawable extends Drawable {

    private static final float ICON_FRACTION = 0.38f;

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Drawable icon;

    ImageFallbackDrawable(Context context, int iconRes) {
        TypedArray colors = context.obtainStyledAttributes(
                new int[]{R.attr.authFieldDisabledBackground});
        basePaint.setColor(colors.getColor(0, 0xFFE5E7EB));
        colors.recycle();

        Drawable raw = context.getDrawable(iconRes);
        icon = raw != null ? raw.mutate() : null;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        if (icon == null) {
            return;
        }
        int size = Math.round(Math.min(bounds.width(), bounds.height()) * ICON_FRACTION);
        int left = bounds.centerX() - size / 2;
        int top = bounds.centerY() - size / 2;
        icon.setBounds(left, top, left + size, top + size);
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawRect(getBounds(), basePaint);
        if (icon != null) {
            icon.draw(canvas);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        basePaint.setAlpha(alpha);
        if (icon != null) {
            icon.setAlpha(alpha);
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        basePaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
