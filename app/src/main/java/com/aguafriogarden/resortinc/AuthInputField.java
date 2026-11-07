package com.aguafriogarden.resortinc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

/**
 * Bordered container around one form control (text box, dropdown or date field) on any screen. Its background (bg_auth_input) switches between default, focused, invalid and
 * disabled looks; focus is merged up from the control inside, the other two are reported here:
 * <ul>
 *   <li>invalid: set by {@link AuthFieldError} while this field's error message is showing;</li>
 *   <li>disabled: follows the control inside, so locking an EditText or Spinner restyles the
 *       whole box without the caller touching it.</li>
 * </ul>
 * The whole box is tappable, not just the control inside it, and a Spinner gets a drop-down
 * chevron drawn on the right.
 */
public class AuthInputField extends LinearLayout {

    private static final int[] STATE_INVALID = {R.attr.state_invalid};
    private static final int[] STATE_DISABLED = {R.attr.state_field_disabled};
    private static final int CHEVRON_SIZE_DP = 22;
    private static final int CHEVRON_DISABLED_ALPHA = 110;

    private boolean invalid;
    private View control;
    private Drawable chevron;

    public AuthInputField(Context context) {
        this(context, null);
    }

    public AuthInputField(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AuthInputField(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setAddStatesFromChildren(true);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof EditText || child instanceof Spinner) {
                control = child;
                break;
            }
        }
        if (control instanceof Spinner) {
            Drawable icon = getContext().getDrawable(R.drawable.ic_chevron_down);
            chevron = icon != null ? icon.mutate() : null;
        }

        // A click anywhere in the box (icon, padding, border) reaches the control. Boxes with
        // no control of their own (tappable date rows) keep whatever click handling they have.
        if (control != null) {
            setOnClickListener(v -> forwardClickToControl());
            // setOnClickListener makes the box clickable, which would also make it focusable.
            setFocusable(false);
        }
    }

    /** Marks the field as failing validation (red) or back to normal. */
    public void setInvalid(boolean invalid) {
        if (this.invalid != invalid) {
            this.invalid = invalid;
            refreshDrawableState();
        }
    }

    private boolean isControlDisabled() {
        return !isEnabled() || (control != null && !control.isEnabled());
    }

    private void forwardClickToControl() {
        if (!isEnabled() || !control.isEnabled()) {
            return;
        }
        if (control instanceof EditText && control.isFocusable()) {
            control.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(control, InputMethodManager.SHOW_IMPLICIT);
            }
        } else {
            // Spinner dropdown, or a read-only date field whose click opens a picker.
            control.performClick();
        }
    }

    @Override
    protected int[] onCreateDrawableState(int extraSpace) {
        boolean disabled = isControlDisabled();
        int extra = (invalid ? 1 : 0) + (disabled ? 1 : 0);
        int[] state = super.onCreateDrawableState(extraSpace + extra);
        if (invalid) {
            mergeDrawableStates(state, STATE_INVALID);
        }
        if (disabled) {
            mergeDrawableStates(state, STATE_DISABLED);
        }
        return state;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (chevron == null) {
            return;
        }
        int size = Math.round(CHEVRON_SIZE_DP * getResources().getDisplayMetrics().density);
        int right = getWidth() - getPaddingEnd();
        int top = (getHeight() - size) / 2;
        chevron.setBounds(right - size, top, right, top + size);
        chevron.setAlpha(isControlDisabled() ? CHEVRON_DISABLED_ALPHA : 255);
        chevron.draw(canvas);
    }
}
