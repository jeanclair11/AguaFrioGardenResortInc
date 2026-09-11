package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Custom glassmorphism date pickers for birthdate (wheel) and single-date selection
 * (calendar); booking check-in/check-out uses the platform's default {@link DatePickerDialog}.
 */
public final class GlassDatePicker {

    public interface DateCallback {
        void onDatePicked(long millis);
    }

    public interface RangeCallback {
        void onRangePicked(long startMillis, long endMillis);
    }

    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

    private GlassDatePicker() {}

    /**
     * Shows a wheel-style date picker specifically for birthdates.
     */
    public static void showBirthdatePicker(Activity activity, long initialMillis, DateCallback callback) {
        Dialog dialog = createGlassDialog(activity, R.layout.dialog_wheel_date_picker);
        
        NumberPicker monthPicker = dialog.findViewById(R.id.monthPicker);
        NumberPicker dayPicker = dialog.findViewById(R.id.dayPicker);
        NumberPicker yearPicker = dialog.findViewById(R.id.yearPicker);
        
        Calendar cal = Calendar.getInstance();
        if (initialMillis > 0) {
            cal.setTimeInMillis(initialMillis);
        } else {
            cal.add(Calendar.YEAR, -18);
        }

        String[] months = new String[12];
        Calendar mCal = Calendar.getInstance();
        for (int i = 0; i < 12; i++) {
            mCal.set(Calendar.MONTH, i);
            months[i] = new SimpleDateFormat("MMM", Locale.getDefault()).format(mCal.getTime());
        }

        monthPicker.setMinValue(0);
        monthPicker.setMaxValue(11);
        monthPicker.setDisplayedValues(months);
        monthPicker.setValue(cal.get(Calendar.MONTH));

        yearPicker.setMinValue(1900);
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        yearPicker.setMaxValue(currentYear);
        yearPicker.setValue(cal.get(Calendar.YEAR));

        Runnable updateDays = () -> {
            int year = yearPicker.getValue();
            int month = monthPicker.getValue();
            Calendar temp = Calendar.getInstance();
            temp.set(year, month, 1);
            int maxDay = temp.getActualMaximum(Calendar.DAY_OF_MONTH);
            dayPicker.setMinValue(1);
            dayPicker.setMaxValue(maxDay);
        };

        updateDays.run();
        dayPicker.setValue(cal.get(Calendar.DAY_OF_MONTH));

        monthPicker.setOnValueChangedListener((picker, oldVal, newVal) -> updateDays.run());
        yearPicker.setOnValueChangedListener((picker, oldVal, newVal) -> updateDays.run());

        dialog.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.btnOk).setOnClickListener(v -> {
            Calendar result = Calendar.getInstance();
            result.set(yearPicker.getValue(), monthPicker.getValue(), dayPicker.getValue(), 0, 0, 0);
            result.set(Calendar.MILLISECOND, 0);
            callback.onDatePicked(result.getTimeInMillis());
            dialog.dismiss();
        });

        dialog.show();
    }

    /**
     * Shows a calendar-style date picker for bookings.
     */
    public static void showCalendarPicker(Activity activity, long initialMillis, long minMillis, DateCallback callback) {
        showCalendarInternal(activity, initialMillis, minMillis, callback);
    }

    /**
     * Shows the platform's default single-date {@link DatePickerDialog} — used by the Hotel
     * Rooms/Cottage/KTV booking flow's Cottage and KTV Date fields (see
     * HotelBookingFlowController) instead of {@link #showCalendarPicker}, whose custom
     * glassmorphism calendar grid was buggy for guests entering a date.
     */
    public static void showDatePickerDialog(Activity activity, long initialMillis, long minMillis, DateCallback callback) {
        Calendar initial = Calendar.getInstance();
        initial.setTimeInMillis(initialMillis > 0 ? initialMillis : Math.max(minMillis, System.currentTimeMillis()));

        DatePickerDialog dialog = new DatePickerDialog(activity, (view, year, month, day) -> {
            Calendar result = Calendar.getInstance();
            result.set(year, month, day, 0, 0, 0);
            result.set(Calendar.MILLISECOND, 0);
            callback.onDatePicked(result.getTimeInMillis());
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH));
        dialog.getDatePicker().setMinDate(Math.max(minMillis, 0));
        dialog.show();
    }

    /**
     * Shows the platform's default check-in/check-out date range picker (two chained
     * {@link DatePickerDialog}s), used by the Book tab.
     */
    public static void showRangePicker(Activity activity, long startMillis, long endMillis, long minMillis, RangeCallback callback) {
        Calendar checkInInitial = Calendar.getInstance();
        checkInInitial.setTimeInMillis(startMillis > 0 ? startMillis : Math.max(minMillis, System.currentTimeMillis()));

        DatePickerDialog checkInDialog = new DatePickerDialog(activity, (checkInView, year, month, day) -> {
            Calendar checkIn = Calendar.getInstance();
            checkIn.set(year, month, day, 0, 0, 0);
            checkIn.set(Calendar.MILLISECOND, 0);
            long checkInMillis = checkIn.getTimeInMillis();

            Calendar checkOutInitial = Calendar.getInstance();
            checkOutInitial.setTimeInMillis(endMillis > checkInMillis ? endMillis : checkInMillis + ONE_DAY_MS);

            DatePickerDialog checkOutDialog = new DatePickerDialog(activity, (checkOutView, outYear, outMonth, outDay) -> {
                Calendar checkOut = Calendar.getInstance();
                checkOut.set(outYear, outMonth, outDay, 0, 0, 0);
                checkOut.set(Calendar.MILLISECOND, 0);
                callback.onRangePicked(checkInMillis, checkOut.getTimeInMillis());
            }, checkOutInitial.get(Calendar.YEAR), checkOutInitial.get(Calendar.MONTH), checkOutInitial.get(Calendar.DAY_OF_MONTH));
            checkOutDialog.getDatePicker().setMinDate(checkInMillis + ONE_DAY_MS);
            checkOutDialog.show();
        }, checkInInitial.get(Calendar.YEAR), checkInInitial.get(Calendar.MONTH), checkInInitial.get(Calendar.DAY_OF_MONTH));
        checkInDialog.getDatePicker().setMinDate(Math.max(minMillis, 0));
        checkInDialog.show();
    }

    private static void showCalendarInternal(Activity activity, long start, long min, DateCallback callback) {
        Dialog dialog = createGlassDialog(activity, R.layout.dialog_calendar_picker);
        
        TextView titleView = dialog.findViewById(R.id.calendarMonthYear);
        ImageView prev = dialog.findViewById(R.id.calendarPrev);
        ImageView next = dialog.findViewById(R.id.calendarNext);
        GridView grid = dialog.findViewById(R.id.calendarGrid);
        LinearLayout dayLabels = dialog.findViewById(R.id.calendarDayLabels);

        String[] days = {"S", "M", "T", "W", "T", "F", "S"};
        dayLabels.removeAllViews();
        for (String day : days) {
            TextView tv = new TextView(activity);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setText(day);
            tv.setTextColor(ThemeManager.color(activity, R.attr.textMuted));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            dayLabels.addView(tv);
        }

        final Calendar currentMonth = Calendar.getInstance();
        if (start > 0) {
            currentMonth.setTimeInMillis(start);
        }
        currentMonth.set(Calendar.DAY_OF_MONTH, 1);

        final Calendar selectedStart = Calendar.getInstance();
        if (start > 0) selectedStart.setTimeInMillis(start); else selectedStart.setTimeInMillis(-1);

        CalendarAdapter adapter = new CalendarAdapter(activity, currentMonth, selectedStart, min);
        grid.setAdapter(adapter);

        Runnable updateHeader = () -> {
            titleView.setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(currentMonth.getTime()));
            adapter.notifyDataSetChanged();
        };
        updateHeader.run();

        prev.setOnClickListener(v -> {
            currentMonth.add(Calendar.MONTH, -1);
            updateHeader.run();
        });
        next.setOnClickListener(v -> {
            currentMonth.add(Calendar.MONTH, 1);
            updateHeader.run();
        });

        grid.setOnItemClickListener((parent, view, position, id) -> {
            Long time = (Long) adapter.getItem(position);
            if (time == null || time < min) return;
            selectedStart.setTimeInMillis(time);
            adapter.notifyDataSetChanged();
        });

        dialog.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.btnOk).setOnClickListener(v -> {
            if (selectedStart.getTimeInMillis() != -1) {
                callback.onDatePicked(selectedStart.getTimeInMillis());
                dialog.dismiss();
            }
        });

        dialog.show();
    }

    private static Dialog createGlassDialog(Activity activity, int layoutRes) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(layoutRes);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ThemeManager.applyGlassEffect(window);
        }
        return dialog;
    }

    private static class CalendarAdapter extends BaseAdapter {
        private final Activity activity;
        private final Calendar month;
        private final Calendar start;
        private final long min;
        private final List<Long> days = new ArrayList<>();
        private final Calendar today = Calendar.getInstance();

        CalendarAdapter(Activity activity, Calendar month, Calendar start, long min) {
            this.activity = activity;
            this.month = (Calendar) month.clone();
            this.start = start;
            this.min = min;
            today.set(Calendar.HOUR_OF_DAY, 0);
            today.set(Calendar.MINUTE, 0);
            today.set(Calendar.SECOND, 0);
            today.set(Calendar.MILLISECOND, 0);
            rebuild();
        }

        @Override
        public void notifyDataSetChanged() {
            rebuild();
            super.notifyDataSetChanged();
        }

        private void rebuild() {
            days.clear();
            Calendar cal = (Calendar) month.clone();
            cal.set(Calendar.DAY_OF_MONTH, 1);
            int firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1;
            cal.add(Calendar.DAY_OF_MONTH, -firstDayOfWeek);
            
            for (int i = 0; i < 42; i++) {
                days.add(cal.getTimeInMillis());
                cal.add(Calendar.DAY_OF_MONTH, 1);
            }
        }

        @Override
        public int getCount() { return days.size(); }
        @Override
        public Object getItem(int position) { return days.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(activity).inflate(R.layout.item_calendar_day, parent, false);
            }
            
            long time = days.get(position);
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(time);
            
            TextView tv = view.findViewById(R.id.dayText);
            View selectedBg = view.findViewById(R.id.daySelectedBg);
            View rangeBg = view.findViewById(R.id.dayRangeBg);
            View todayIndicator = view.findViewById(R.id.dayTodayIndicator);

            tv.setText(String.valueOf(cal.get(Calendar.DAY_OF_MONTH)));

            boolean isCurrentMonth = cal.get(Calendar.MONTH) == month.get(Calendar.MONTH);
            boolean isPast = isDayBefore(time, min);
            boolean isSelected = isSameDay(time, start.getTimeInMillis());
            boolean isToday = isSameDay(time, today.getTimeInMillis());

            tv.setAlpha(isCurrentMonth && !isPast ? 1.0f : 0.3f);
            selectedBg.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            rangeBg.setVisibility(View.GONE);
            todayIndicator.setVisibility(isToday ? View.VISIBLE : View.GONE);

            if (isSelected) {
                tv.setTextColor(Color.WHITE);
                tv.setAlpha(1.0f);
            } else {
                tv.setTextColor(ThemeManager.color(activity, R.attr.textPrimary));
            }

            return view;
        }

        private boolean isDayBefore(long t1, long t2) {
            if (t1 == -1 || t2 == -1) return false;
            Calendar c1 = Calendar.getInstance(); c1.setTimeInMillis(t1);
            Calendar c2 = Calendar.getInstance(); c2.setTimeInMillis(t2);
            c1.set(Calendar.HOUR_OF_DAY, 0); c1.set(Calendar.MINUTE, 0); c1.set(Calendar.SECOND, 0); c1.set(Calendar.MILLISECOND, 0);
            c2.set(Calendar.HOUR_OF_DAY, 0); c2.set(Calendar.MINUTE, 0); c2.set(Calendar.SECOND, 0); c2.set(Calendar.MILLISECOND, 0);
            return c1.before(c2);
        }

        private boolean isSameDay(long t1, long t2) {
            if (t1 == -1 || t2 == -1) return false;
            Calendar c1 = Calendar.getInstance(); c1.setTimeInMillis(t1);
            Calendar c2 = Calendar.getInstance(); c2.setTimeInMillis(t2);
            return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
                   c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR);
        }
    }
}