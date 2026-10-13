package com.aguafriogarden.resortinc.network;

/**
 * One rate row in a {@link CottageKtvOption}'s {@code price_breakdown} — e.g. "Day Use" ₱500,
 * "Night Use" ₱600, "Overnight" ₱1,100 for a Time-Based Cottage Type, or a single "Single Rate"
 * row for a 24-Hour one. Cottages only for now (see {@code CottageVariant::priceBreakdown()} on
 * the backend); KTV/Halls leave {@code price_breakdown} null and keep their flat price display.
 */
public class PriceBreakdownEntry {
    public String label;
    public double amount;
}
