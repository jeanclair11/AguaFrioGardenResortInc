package com.aguafriogarden.resortinc;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Philippine province/city/barangay hierarchy for the registration address
 * fields. Backed today by a bundled asset (assets/ph_address.json, sourced
 * from PSA/COMELEC administrative records), but every method is
 * callback-based so this can later be swapped for a real backend/API call
 * without changing any call site.
 */
final class AddressRepository {

    interface ListCallback {
        void onResult(List<String> options);
    }

    private static final String ASSET_FILE = "ph_address.json";

    private static List<Province> provinces;

    private static final class Province {
        String name;
        List<City> cities;
    }

    private static final class City {
        String name;
        List<String> barangays;
    }

    private AddressRepository() {
    }

    static void getProvinces(Context context, ListCallback callback) {
        List<Province> all = load(context);
        List<String> names = new ArrayList<>(all.size());
        for (Province p : all) {
            names.add(p.name);
        }
        callback.onResult(names);
    }

    static void getCities(Context context, String provinceName, ListCallback callback) {
        for (Province p : load(context)) {
            if (p.name.equals(provinceName)) {
                List<String> names = new ArrayList<>(p.cities.size());
                for (City c : p.cities) {
                    names.add(c.name);
                }
                callback.onResult(names);
                return;
            }
        }
        callback.onResult(new ArrayList<>());
    }

    static void getBarangays(Context context, String provinceName, String cityName, ListCallback callback) {
        for (Province p : load(context)) {
            if (p.name.equals(provinceName)) {
                for (City c : p.cities) {
                    if (c.name.equals(cityName)) {
                        callback.onResult(new ArrayList<>(c.barangays));
                        return;
                    }
                }
            }
        }
        callback.onResult(new ArrayList<>());
    }

    private static synchronized List<Province> load(Context context) {
        if (provinces == null) {
            Type type = new TypeToken<List<Province>>() { }.getType();
            try (InputStreamReader reader = new InputStreamReader(
                    context.getAssets().open(ASSET_FILE), StandardCharsets.UTF_8)) {
                provinces = new Gson().fromJson(reader, type);
            } catch (IOException e) {
                provinces = new ArrayList<>();
            }
        }
        return provinces;
    }
}
