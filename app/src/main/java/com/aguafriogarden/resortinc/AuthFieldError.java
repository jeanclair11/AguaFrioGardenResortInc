package com.aguafriogarden.resortinc;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Inline validation message under a form field. Showing or hiding it also flips the
 * {@link AuthInputField} (or a standalone multi-line EditText) above it between invalid and
 * normal, so every existing showError / setVisibility(GONE) call restyles the field with no
 * extra wiring.
 */
public class AuthFieldError extends TextView {

    public AuthFieldError(Context context) {
        super(context);
    }

    public AuthFieldError(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AuthFieldError(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView != this) {
            return;
        }
        boolean invalid = visibility == VISIBLE;
        View field = fieldAbove();
        if (field instanceof AuthInputField) {
            ((AuthInputField) field).setInvalid(invalid);
        } else if (field != null) {
            // Standalone EditText: bg_auth_input shows its invalid look for the activated state.
            field.setActivated(invalid);
        }
    }

    /**
     * The field this message belongs to: the nearest AuthInputField or EditText before it among
     * its siblings, looking past other TextViews (password strength hint, character counter) but
     * stopping at anything else, so messages for non-field controls (ID photo, terms) match
     * nothing.
     */
    private View fieldAbove() {
        ViewParent parent = getParent();
        if (!(parent instanceof ViewGroup)) {
            return null;
        }
        ViewGroup siblings = (ViewGroup) parent;
        for (int i = siblings.indexOfChild(this) - 1; i >= 0; i--) {
            View sibling = siblings.getChildAt(i);
            if (sibling instanceof AuthInputField || sibling instanceof EditText) {
                return sibling;
            }
            if (!(sibling instanceof TextView)) {
                return null;
            }
        }
        return null;
    }
}
