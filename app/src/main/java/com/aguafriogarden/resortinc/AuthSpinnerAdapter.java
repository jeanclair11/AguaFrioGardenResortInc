package com.aguafriogarden.resortinc;

import android.content.Context;
import android.content.res.TypedArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

/**
 * Adapter for every form dropdown. When the list starts with a "Select ..." prompt (position 0)
 * it is shown in the placeholder gray; real choices use the entered-text color, and the whole
 * selection greys out while its spinner is disabled.
 */
final class AuthSpinnerAdapter extends ArrayAdapter<String> {

    private final int textColor;
    private final int hintColor;
    private final int disabledColor;
    private final boolean hasPrompt;

    /** A dropdown whose first item is the "Select ..." prompt. */
    AuthSpinnerAdapter(Context context, List<String> items) {
        this(context, items, true);
    }

    AuthSpinnerAdapter(Context context, List<String> items, boolean hasPrompt) {
        super(context, R.layout.item_auth_spinner, items);
        this.hasPrompt = hasPrompt;
        setDropDownViewResource(R.layout.item_auth_spinner_dropdown);

        TypedArray colors = context.obtainStyledAttributes(new int[]{
                R.attr.authFieldText, R.attr.authFieldHint, R.attr.authFieldDisabledText});
        textColor = colors.getColor(0, 0);
        hintColor = colors.getColor(1, 0);
        disabledColor = colors.getColor(2, 0);
        colors.recycle();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView view = (TextView) super.getView(position, convertView, parent);
        if (!parent.isEnabled()) {
            view.setTextColor(disabledColor);
        } else {
            view.setTextColor(hasPrompt && position == 0 ? hintColor : textColor);
        }
        return view;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        TextView view = (TextView) super.getDropDownView(position, convertView, parent);
        view.setTextColor(hasPrompt && position == 0 ? hintColor : textColor);
        return view;
    }
}
