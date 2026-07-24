package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * Drives the Feedback tab: a history of the guest's own submitted feedback
 * (or an empty state) plus a Write Feedback sub-screen, reached and left via
 * its own header back arrow. Submission is simulated with a short delay
 * (see DELAY_MS) since there's no backend yet; FeedbackStore already shapes
 * the data (adminResponse/respondedAtMillis) for a real reply to show up
 * once one exists.
 */
final class FeedbackController {

    private static final int SCREEN_MAIN = 0;
    private static final int SCREEN_WRITE = 1;

    private static final long DELAY_MS = 1200L;
    private static final int MAX_MESSAGE_LENGTH = 500;

    private final Activity activity;
    private final FrameLayout root;

    private View currentScreenView;
    private int currentScreen = -1;
    private int selectedRating;

    FeedbackController(Activity activity) {
        this.activity = activity;
        root = new FrameLayout(activity);
        showScreen(SCREEN_MAIN);
    }

    View getRootView() {
        return root;
    }

    /** Steps out of Write Feedback; returns false once already on the main screen. */
    boolean handleBackPressed() {
        if (currentScreen == SCREEN_WRITE) {
            showScreen(SCREEN_MAIN);
            return true;
        }
        return false;
    }

    private void showScreen(int screen) {
        currentScreen = screen;
        View v = LayoutInflater.from(activity)
                .inflate(screen == SCREEN_WRITE ? R.layout.view_feedback_write
                        : R.layout.view_feedback_main, root, false);
        if (screen == SCREEN_WRITE) {
            bindWrite(v);
        } else {
            bindMain(v);
        }
        root.removeAllViews();
        root.addView(v);
        currentScreenView = v;
    }

    // ---- Main feedback screen ----------------------------------------

    private void bindMain(View v) {
        v.findViewById(R.id.writeFeedbackButton).setOnClickListener(view -> showScreen(SCREEN_WRITE));

        List<FeedbackStore.Entry> entries = FeedbackStore.getAll(activity);
        LinearLayout container = v.findViewById(R.id.feedbackListContainer);
        container.removeAllViews();

        boolean empty = entries.isEmpty();
        v.findViewById(R.id.feedbackEmptyState).setVisibility(empty ? View.VISIBLE : View.GONE);
        container.setVisibility(empty ? View.GONE : View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(activity);
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy", Locale.getDefault());
        for (FeedbackStore.Entry entry : entries) {
            View card = inflater.inflate(R.layout.view_feedback_card, container, false);
            bindAvatar(card.findViewById(R.id.feedbackCardAvatar));
            ((TextView) card.findViewById(R.id.feedbackCardName)).setText(ProfileStore.getFullName(activity));
            ((TextView) card.findViewById(R.id.feedbackCardDate))
                    .setText(dateFormat.format(entry.submittedAtMillis));

            TextView badge = card.findViewById(R.id.feedbackCardStatusBadge);
            if (entry.isResponded()) {
                badge.setText(R.string.status_responded);
                badge.setBackgroundResource(R.drawable.bg_badge_success);
            } else {
                badge.setText(R.string.status_waiting_response);
                badge.setBackgroundResource(R.drawable.bg_badge_pending);
            }

            LinearLayout stars = card.findViewById(R.id.feedbackCardStars);
            float density = activity.getResources().getDisplayMetrics().density;
            int starSize = Math.round(16 * density);
            int starMargin = Math.round(2 * density);
            for (int i = 0; i < 5; i++) {
                ImageView star = new ImageView(activity);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(starSize, starSize);
                lp.setMarginEnd(starMargin);
                star.setLayoutParams(lp);
                star.setImageResource(i < entry.rating ? R.drawable.ic_star : R.drawable.ic_star_outline);
                star.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                stars.addView(star);
            }

            ((TextView) card.findViewById(R.id.feedbackCardMessage)).setText(entry.message);

            View responseBox = card.findViewById(R.id.feedbackCardResponseBox);
            if (entry.isResponded()) {
                responseBox.setVisibility(View.VISIBLE);
                ((TextView) card.findViewById(R.id.feedbackCardResponseDate)).setText(
                        activity.getString(R.string.feedback_response_date_format,
                                dateFormat.format(entry.respondedAtMillis)));
                ((TextView) card.findViewById(R.id.feedbackCardResponseMessage))
                        .setText(entry.adminResponse);
            } else {
                responseBox.setVisibility(View.GONE);
            }

            container.addView(card);
        }
    }

    private void bindAvatar(ImageView avatar) {
        Bitmap picture = ProfileStore.loadAvatar(activity);
        if (picture != null) {
            avatar.setPadding(0, 0, 0, 0);
            avatar.setImageTintList(null);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setImageBitmap(picture);
        }
        avatar.setClipToOutline(true);
    }

    // ---- Write Feedback screen ----------------------------------------

    private void bindWrite(View v) {
        selectedRating = 0;

        View header = v.findViewById(R.id.headerBar);
        ((TextView) header.findViewById(R.id.headerTitle)).setText(R.string.write_feedback_title);
        header.findViewById(R.id.headerBackButton).setOnClickListener(view -> showScreen(SCREEN_MAIN));

        int[] starIds = {R.id.fwStar1, R.id.fwStar2, R.id.fwStar3, R.id.fwStar4, R.id.fwStar5};
        for (int i = 0; i < starIds.length; i++) {
            int rating = i + 1;
            v.findViewById(starIds[i]).setOnClickListener(view -> {
                selectedRating = rating;
                updateStarSelector(v, starIds);
                v.findViewById(R.id.fwRatingError).setVisibility(View.GONE);
            });
        }
        updateStarSelector(v, starIds);

        EditText messageField = v.findViewById(R.id.fwMessageField);
        TextView charCounter = v.findViewById(R.id.fwCharCounter);
        charCounter.setText(activity.getString(R.string.feedback_char_counter_format, 0, MAX_MESSAGE_LENGTH));
        AuthUiUtils.afterTextChanged(messageField, () -> {
            charCounter.setText(activity.getString(R.string.feedback_char_counter_format,
                    messageField.getText().length(), MAX_MESSAGE_LENGTH));
            v.findViewById(R.id.fwMessageError).setVisibility(View.GONE);
        });

        v.findViewById(R.id.fwSubmitButton).setOnClickListener(view -> attemptSubmit(v));
    }

    private void updateStarSelector(View v, int[] starIds) {
        for (int i = 0; i < starIds.length; i++) {
            ((ImageView) v.findViewById(starIds[i])).setImageResource(
                    i < selectedRating ? R.drawable.ic_star : R.drawable.ic_star_outline);
        }
    }

    private void attemptSubmit(View v) {
        EditText messageField = v.findViewById(R.id.fwMessageField);
        String message = messageField.getText().toString().trim();

        boolean valid = true;
        if (selectedRating == 0) {
            showError(v.findViewById(R.id.fwRatingError), R.string.error_rating_required);
            valid = false;
        } else {
            v.findViewById(R.id.fwRatingError).setVisibility(View.GONE);
        }
        if (message.isEmpty()) {
            showError(v.findViewById(R.id.fwMessageError), R.string.error_feedback_message_required);
            valid = false;
        } else {
            v.findViewById(R.id.fwMessageError).setVisibility(View.GONE);
        }
        if (!valid) {
            return;
        }

        setSubmitting(v, true);
        int rating = selectedRating;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            FeedbackStore.addFeedback(activity, rating, message);
            setSubmitting(v, false);
            showSuccessDialog();
        }, DELAY_MS);
    }

    private void setSubmitting(View v, boolean submitting) {
        Button submitButton = v.findViewById(R.id.fwSubmitButton);
        submitButton.setEnabled(!submitting);
        v.findViewById(R.id.fwSubmittingRow).setVisibility(submitting ? View.VISIBLE : View.GONE);
    }

    private void showSuccessDialog() {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.view_feedback_success_dialog, null, false);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(false)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialogView.findViewById(R.id.feedbackSuccessOkButton).setOnClickListener(view -> {
            dialog.dismiss();
            showScreen(SCREEN_MAIN);
        });
        dialog.show();
    }

    private void showError(View errorView, int messageRes) {
        ((TextView) errorView).setText(messageRes);
        errorView.setVisibility(View.VISIBLE);
    }
}
