package com.aguafriogarden.resortinc.network;

/**
 * One Promo Coupon the booking is eligible for, from POST /api/booking/promo-coupons. These are
 * the admin's Promo Management "Promo Discount" promos; the backend already filtered out every
 * one the booking doesn't qualify for, so nothing ineligible ever reaches the app.
 */
public class PromoCoupon {
    public int promo_id;
    public String name;
    public String description;
    /** Ready-to-show text: "₱100 OFF" or "3% OFF". */
    public String label;
    /** "fixed" or "percentage". */
    public String discount_type;
    public double value;
    /** What this coupon takes off this particular booking, in pesos. */
    public double discount_amount;
}
