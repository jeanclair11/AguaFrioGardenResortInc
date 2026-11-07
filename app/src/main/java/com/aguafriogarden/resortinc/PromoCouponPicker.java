package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.app.AlertDialog;

import com.aguafriogarden.resortinc.network.PromoCoupon;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * The picker opened by the Summary page's "Promo Coupons" card. It lists only the coupons the
 * server already judged this booking eligible for (never an ineligible one), as a single choice —
 * one coupon per booking — and offers "Remove coupon" while one is applied, so the guest can
 * change or drop it. With nothing eligible it just says "No available coupons".
 */
final class PromoCouponPicker {

    interface Listener {
        /** The coupon the guest picked, or null when they removed the applied one. */
        void onChosen(PromoCoupon coupon);
    }

    private PromoCouponPicker() {
    }

    static void show(Activity activity, List<PromoCoupon> coupons, PromoCoupon applied, Listener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.promo_coupons_title);

        if (coupons.isEmpty()) {
            builder.setMessage(R.string.promo_coupons_none);
        } else {
            CharSequence[] items = new CharSequence[coupons.size()];
            int checked = -1;
            NumberFormat money = NumberFormat.getInstance(Locale.US);
            money.setMaximumFractionDigits(2);
            for (int i = 0; i < coupons.size(); i++) {
                PromoCoupon coupon = coupons.get(i);
                items[i] = coupon.label + " · " + coupon.name + "\n"
                        + activity.getString(R.string.promo_coupon_you_save,
                        activity.getString(R.string.format_currency, money.format(coupon.discount_amount)));
                if (applied != null && applied.promo_id == coupon.promo_id) {
                    checked = i;
                }
            }
            // Picking a row applies it straight away (only one can be selected at a time).
            builder.setSingleChoiceItems(items, checked, (dialog, which) -> {
                dialog.dismiss();
                listener.onChosen(coupons.get(which));
            });
        }

        if (applied != null) {
            builder.setNeutralButton(R.string.promo_coupon_remove, (dialog, which) -> listener.onChosen(null));
        }
        builder.setNegativeButton(R.string.button_cancel, null);
        builder.show();
    }
}
