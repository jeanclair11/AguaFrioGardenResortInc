package com.aguafriogarden.resortinc;

import android.content.Context;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

/**
 * The one way every photo in the app is put on screen. Glide decodes and downsamples to the
 * view's size off the main thread; until the picture is ready the view shows a skeleton the
 * exact size of the picture, then cross-fades to it. A missing URL or a failed load shows a
 * neutral fallback with an icon instead of an empty box. Pictures already in Glide's memory
 * cache appear instantly, with no skeleton flash.
 */
final class ImageLoader {

    private static final int CROSSFADE_MS = 200;

    private ImageLoader() {
    }

    /**
     * Loads a backend photo into {@code view}. A null {@code url} (the item has no photo) shows
     * the fallback straight away.
     */
    static void load(ImageView view, String url, int fallbackIconRes) {
        Context context = view.getContext();
        if (url == null) {
            Glide.with(view).clear(view);
            view.setImageDrawable(new ImageFallbackDrawable(context, fallbackIconRes));
            return;
        }
        Glide.with(view)
                .load(url)
                .placeholder(new SkeletonDrawable(context))
                .error(new ImageFallbackDrawable(context, fallbackIconRes))
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade(CROSSFADE_MS))
                .into(view);
    }

    /**
     * Loads a bundled photo into {@code view} off the main thread, downsampled to the view's
     * size, instead of letting the layout inflater decode the full-size file synchronously.
     */
    static void loadResource(ImageView view, int drawableRes, int fallbackIconRes) {
        Context context = view.getContext();
        Glide.with(view)
                .load(drawableRes)
                .placeholder(new SkeletonDrawable(context))
                .error(new ImageFallbackDrawable(context, fallbackIconRes))
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade(CROSSFADE_MS))
                .into(view);
    }
}
