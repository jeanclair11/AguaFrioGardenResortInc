package com.aguafriogarden.resortinc.network;

import java.util.ArrayList;
import java.util.List;

/**
 * Response body for GET /api/booking/ktv-units: one entry per physical KTV unit (not per Type),
 * each with the rental the Admin configured for its Type in KTV Room Management (hours, rate type
 * and amount). Mapped onto {@link CottageKtvOption} so every existing KTV screen can render it:
 * group_id/variant_id carry the unit id, variant_name the unit's own name, and
 * available_quantity is 1 when the unit is free (and has a rental configured), else 0.
 */
public class KtvUnitListResponse {
    public List<Unit> ktv_units;

    public static class Package {
        public String label;
        public int hours;
        public double amount;
    }

    public static class Unit {
        public int unit_id;
        public String unit_name;
        public int variant_id;
        public String variant_name;
        public String description;
        public String image_path;
        public int capacity;
        public int max_capacity;
        public int base_capacity;
        public double extra_person_price;
        /** The rental the guest is charged: the Type's configured package. Null until configured. */
        public Package rental;
        public List<Package> packages;
        public double price;
        public String price_unit;
        public boolean available;
    }

    /**
     * The configured rental of a unit. The server sends it as {@code rental}; an older server only
     * lists {@code packages} (ascending by hours), whose first entry is the same single package
     * KTV Room Management allows per Type.
     */
    static Package rentalOf(Unit unit) {
        if (unit.rental != null) {
            return unit.rental.hours > 0 ? unit.rental : null;
        }
        if (unit.packages != null) {
            for (Package p : unit.packages) {
                if (p.hours > 0) {
                    return p;
                }
            }
        }
        return null;
    }

    public List<CottageKtvOption> toOptions() {
        List<CottageKtvOption> options = new ArrayList<>();
        if (ktv_units == null) {
            return options;
        }
        for (Unit unit : ktv_units) {
            Package rental = rentalOf(unit);
            CottageKtvOption option = new CottageKtvOption();
            option.unit_id = unit.unit_id;
            option.group_id = unit.unit_id;
            option.variant_id = unit.unit_id;
            option.variant_name = unit.unit_name != null && !unit.unit_name.trim().isEmpty()
                    ? unit.unit_name : unit.variant_name;
            option.description = unit.description;
            option.image_path = unit.image_path;
            option.capacity = unit.max_capacity > 0 ? unit.max_capacity : unit.capacity;
            option.base_capacity = unit.base_capacity;
            option.extra_person_price = unit.extra_person_price;
            if (rental != null) {
                option.price = rental.amount;
                option.price_unit = rental.hours + (rental.hours == 1 ? " hr" : " hrs");
                option.rental_hours = rental.hours;
                option.rate_label = rental.label;
            } else {
                option.price = unit.price;
                option.price_unit = unit.price_unit;
            }
            // A unit with no rental configured can't be priced or scheduled, so it is never offered.
            option.available_quantity = unit.available && rental != null ? 1 : 0;
            options.add(option);
        }
        return options;
    }
}
