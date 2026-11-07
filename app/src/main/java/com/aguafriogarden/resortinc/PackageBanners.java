package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.PackageInclusion;
import com.aguafriogarden.resortinc.network.PackagePromo;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The Package Promo carousel shown above the Hotel Rooms / Cottages / KTV Rooms lists: one blue
 * banner per package the admin has on sale, swiped sideways (snapping one banner at a time) with a
 * dot per banner underneath. Each banner's "View Package" opens that package's details. With no
 * package on sale the slot is simply hidden, so the list looks exactly as it did before.
 */
final class PackageBanners {

    private static final int GAP_DP = 12;
    private static final int MAX_INCLUSION_CHIPS = 4;

    private PackageBanners() {
    }

    /**
     * Fills {@code slot} with the carousel for {@code packages}. Called again on every background
     * refresh, so when nothing changed it leaves the carousel alone (rebuilding would snap the
     * guest back to the first banner mid-swipe).
     */
    static void bind(Activity activity, FrameLayout slot, List<PackagePromo> packages, Consumer<PackagePromo> onView) {
        if (packages == null || packages.isEmpty()) {
            slot.removeAllViews();
            slot.setTag(null);
            slot.setVisibility(View.GONE);
            return;
        }
        if (slot.getTag() != null && LiveSync.sameData(slot.getTag(), packages)) {
            return;
        }
        slot.setTag(packages);
        slot.removeAllViews();
        slot.setVisibility(View.VISIBLE);

        int gap = dp(activity, GAP_DP);
        // The browse page pads its content 16dp on each side, so a banner is exactly that wide.
        int pageWidth = activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 32);
        int stride = pageWidth + gap;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        SnapScrollView scroller = new SnapScrollView(activity, stride, packages.size());
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setClipToPadding(false);
        scroller.setPadding(0, dp(activity, 2), 0, dp(activity, 10));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroller.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LayoutInflater inflater = LayoutInflater.from(activity);
        for (PackagePromo promo : packages) {
            View banner = inflater.inflate(R.layout.view_package_banner, row, false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(gap);
            banner.setLayoutParams(params);
            bindBanner(activity, banner, promo, pageWidth, onView);
            row.addView(banner);
        }
        root.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (packages.size() > 1) {
            LinearLayout dots = new LinearLayout(activity);
            dots.setOrientation(LinearLayout.HORIZONTAL);
            dots.setGravity(Gravity.CENTER);
            for (int i = 0; i < packages.size(); i++) {
                View dot = new View(activity);
                LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(activity, 8), dp(activity, 8));
                dotParams.setMargins(dp(activity, 4), 0, dp(activity, 4), 0);
                dots.addView(dot, dotParams);
            }
            root.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            Consumer<Integer> showPage = page -> {
                for (int i = 0; i < dots.getChildCount(); i++) {
                    dots.getChildAt(i).setBackgroundResource(i == page
                            ? R.drawable.bg_progress_dot_filled : R.drawable.bg_package_dot_hollow);
                }
            };
            showPage.accept(0);
            scroller.setOnPageChangeListener(showPage);
        }

        slot.addView(root);
    }

    private static void bindBanner(Activity activity, View banner, PackagePromo promo, int pageWidth, Consumer<PackagePromo> onView) {
        // The photo takes the right half, the text panel the left two thirds (they overlap under
        // the curved divider).
        banner.findViewById(R.id.packageBannerImage).getLayoutParams().width = Math.round(pageWidth * 0.5f);
        banner.findViewById(R.id.packageBannerWave).getLayoutParams().width = Math.round(pageWidth * 0.5f);
        banner.findViewById(R.id.packageBannerContent).getLayoutParams().width = Math.round(pageWidth * 0.66f);

        ImageLoader.load(banner.findViewById(R.id.packageBannerImage), ApiClient.imageUrl(promo.image_path), R.drawable.ic_bed);
        ((TextView) banner.findViewById(R.id.packageBannerName)).setText(promo.name);
        bindInclusions(activity, banner.findViewById(R.id.packageBannerInclusions), promo.inclusions);

        ((TextView) banner.findViewById(R.id.packageBannerPrice)).setText(formatPeso(promo.price));
        TextView regular = banner.findViewById(R.id.packageBannerRegularPrice);
        TextView save = banner.findViewById(R.id.packageBannerSave);
        if (promo.regular_price != null && promo.savings != null && promo.savings > 0) {
            regular.setText(formatPeso(promo.regular_price));
            regular.setPaintFlags(regular.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            regular.setVisibility(View.VISIBLE);
            save.setText(activity.getString(R.string.package_save_format, formatPeso(promo.savings)));
            save.setVisibility(View.VISIBLE);
        } else {
            regular.setVisibility(View.GONE);
            save.setVisibility(View.GONE);
        }

        View.OnClickListener open = ClickGuard.wrap(v -> onView.accept(promo));
        banner.findViewById(R.id.packageBannerViewButton).setOnClickListener(open);
        banner.setOnClickListener(open);
    }

    /** "1 Standard Room | 1 Family Cottage" / "2 Food Menu A" — two per line, at most four chips. */
    private static void bindInclusions(Activity activity, LinearLayout container, List<PackageInclusion> inclusions) {
        container.removeAllViews();
        if (inclusions == null || inclusions.isEmpty()) {
            return;
        }
        int total = inclusions.size();
        int shown = Math.min(total, MAX_INCLUSION_CHIPS);
        LinearLayout line = null;
        for (int i = 0; i < shown; i++) {
            if (i % 2 == 0) {
                line = new LinearLayout(activity);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lineParams.topMargin = i == 0 ? 0 : dp(activity, 4);
                container.addView(line, lineParams);
            } else {
                View divider = new View(activity);
                divider.setBackgroundResource(R.drawable.bg_package_divider);
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(activity, 1), dp(activity, 12));
                dividerParams.setMargins(dp(activity, 6), 0, dp(activity, 6), 0);
                line.addView(divider, dividerParams);
            }
            boolean overflow = total > MAX_INCLUSION_CHIPS && i == shown - 1;
            line.addView(inclusionChip(activity, inclusions.get(i), overflow ? total - (shown - 1) : 0),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private static View inclusionChip(Context context, PackageInclusion inclusion, int moreCount) {
        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);

        if (moreCount == 0) {
            ImageView icon = new ImageView(context);
            icon.setImageResource(iconFor(inclusion.kind));
            icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            chip.addView(icon, new LinearLayout.LayoutParams(dp(context, 14), dp(context, 14)));
        }

        TextView text = new TextView(context);
        text.setText(moreCount > 0
                ? context.getString(R.string.package_more_format, moreCount)
                : inclusion.quantity + " " + inclusion.name);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setMaxLines(1);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginStart(moreCount == 0 ? dp(context, 4) : 0);
        chip.addView(text, textParams);
        return chip;
    }

    /** The glyph for an inclusion kind ("room", "cottage", "food", "amenity"). */
    static int iconFor(String kind) {
        if ("room".equals(kind)) {
            return R.drawable.ic_bed;
        }
        if ("cottage".equals(kind)) {
            return R.drawable.ic_home;
        }
        if ("food".equals(kind)) {
            return R.drawable.ic_food;
        }
        return R.drawable.ic_star;
    }

    /** "₱5,000" (whole pesos) or "₱5,000.50". */
    static String formatPeso(double amount) {
        NumberFormat format = NumberFormat.getInstance(Locale.US);
        boolean whole = Math.abs(amount - Math.rint(amount)) < 0.005;
        format.setMinimumFractionDigits(whole ? 0 : 2);
        format.setMaximumFractionDigits(whole ? 0 : 2);
        return "₱" + format.format(amount);
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    /**
     * A HorizontalScrollView that always comes to rest on a whole banner: a flick moves one banner
     * in its direction, a slow drag settles on the nearest. It also keeps the page's own gestures
     * (the vertical scroll, and the swipe between the Hotel/Cottage/KTV tabs) from fighting the
     * banner swipe: a touch that starts here is ours until it turns out to be a vertical drag.
     */
    private static final class SnapScrollView extends HorizontalScrollView {
        private final int stride;
        private final int pages;
        private final int touchSlop;
        private Consumer<Integer> pageListener;
        private int lastPage = -1;
        private boolean flung;
        private float downX;
        private float downY;

        SnapScrollView(Context context, int stride, int pages) {
            super(context);
            this.stride = stride;
            this.pages = pages;
            this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        void setOnPageChangeListener(Consumer<Integer> listener) {
            this.pageListener = listener;
        }

        @Override
        protected void onScrollChanged(int l, int t, int oldl, int oldt) {
            super.onScrollChanged(l, t, oldl, oldt);
            int page = Math.max(0, Math.min(pages - 1, Math.round(l / (float) stride)));
            if (pageListener != null && page != lastPage) {
                lastPage = page;
                pageListener.accept(page);
            }
        }

        @Override
        public void fling(int velocityX) {
            flung = true;
            float position = getScrollX() / (float) stride;
            int page = velocityX > 0 ? (int) Math.floor(position) + 1 : (int) Math.ceil(position) - 1;
            smoothScrollToPage(page);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    flung = false;
                    downX = event.getX();
                    downY = event.getY();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = Math.abs(event.getX() - downX);
                    float dy = Math.abs(event.getY() - downY);
                    if (dy > touchSlop && dy > dx) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    break;
                default:
                    break;
            }
            boolean handled = super.onTouchEvent(event);
            int action = event.getActionMasked();
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && !flung) {
                smoothScrollToPage(Math.round(getScrollX() / (float) stride));
            }
            return handled;
        }

        private void smoothScrollToPage(int page) {
            int clamped = Math.max(0, Math.min(pages - 1, page));
            smoothScrollTo(clamped * stride, 0);
        }
    }
}
