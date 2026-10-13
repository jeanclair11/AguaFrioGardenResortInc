package com.aguafriogarden.resortinc.network;

import java.util.ArrayList;
import java.util.List;

/**
 * Response body for GET /api/booking/ktv-units: one entry per physical KTV unit (not per Type).
 * Each unit is mapped onto a {@link CottageKtvOption} so every existing KTV screen can render it:
 * group_id/variant_id carry the unit id, variant_name the unit's own name, and
 * available_quantity is 1 when the unit is free for the requested slot, else 0.
 */
public class KtvUnitListResponse {
    public List<Unit> ktv_units;

    public static class Unit {
        public int unit_id;
        public String unit_name;
        public int variant_id;
        public String variant_name;
        public String description;
        public String image_path;
        public int capacity;
        public double price;
        public String price_unit;
        public boolean available;
    }

    public List<CottageKtvOption> toOptions() {
        List<CottageKtvOption> options = new ArrayList<>();
        if (ktv_units == null) {
            return options;
        }
        for (Unit unit : ktv_units) {
            CottageKtvOption option = new CottageKtvOption();
            option.unit_id = unit.unit_id;
            option.group_id = unit.unit_id;
            option.variant_id = unit.unit_id;
            option.variant_name = unit.unit_name != null && !unit.unit_name.trim().isEmpty()
                    ? unit.unit_name : unit.variant_name;
            option.description = unit.description;
            option.image_path = unit.image_path;
            option.capacity = unit.capacity;
            option.price = unit.price;
            option.price_unit = unit.price_unit;
            option.available_quantity = unit.available ? 1 : 0;
            options.add(option);
        }
        return options;
    }
}
