package com.aguafriogarden.resortinc.network;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Response body for GET /api/booking/ktv-units: one entry per physical KTV unit (not per Type),
 * each with the price packages (label, hours, amount) configured for its Type in KTV Room
 * Management. Mapped onto {@link CottageKtvOption} so every existing KTV screen can render it:
 * group_id/variant_id carry the unit id, variant_name the unit's own name, and
 * available_quantity is 1 when the unit is free (and offers the chosen duration), else 0.
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
        public List<Package> packages;
        public double price;
        public String price_unit;
        public boolean available;
    }

    /** Every distinct session length (hours) any KTV unit offers, ascending. */
    public List<Integer> durationOptions() {
        TreeSet<Integer> hours = new TreeSet<>();
        if (ktv_units != null) {
            for (Unit unit : ktv_units) {
                if (unit.packages != null) {
                    for (Package p : unit.packages) {
                        if (p.hours > 0) {
                            hours.add(p.hours);
                        }
                    }
                }
            }
        }
        return new ArrayList<>(hours);
    }

    /**
     * @param hours the chosen session length; a unit with no price package for it is returned
     *              unavailable. 0 (browse) uses each unit's shortest package instead.
     */
    public List<CottageKtvOption> toOptions(int hours) {
        List<CottageKtvOption> options = new ArrayList<>();
        if (ktv_units == null) {
            return options;
        }
        for (Unit unit : ktv_units) {
            Package chosen = null;
            if (unit.packages != null) {
                for (Package p : unit.packages) {
                    if (hours > 0 ? p.hours == hours : (chosen == null || p.hours < chosen.hours)) {
                        chosen = p;
                    }
                }
            }
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
            if (chosen != null) {
                option.price = chosen.amount;
                option.price_unit = chosen.hours + (chosen.hours == 1 ? " hr" : " hrs");
            } else {
                option.price = unit.price;
                option.price_unit = unit.price_unit;
            }
            option.available_quantity = unit.available && (hours <= 0 || chosen != null) ? 1 : 0;
            options.add(option);
        }
        return options;
    }
}
