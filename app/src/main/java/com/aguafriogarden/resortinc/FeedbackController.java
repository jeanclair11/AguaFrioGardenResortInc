package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.FeedbackEntryDto;
import com.aguafriogarden.resortinc.network.FeedbackListResponse;
import com.aguafriogarden.resortinc.network.FeedbackSubmitResponse;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Drives the Feedback tab: a history of the guest's own submitted feedback
 * (or an empty state) plus a Write Feedback sub-screen, reached and left via
 * its own header back arrow. Reads/writes the same feedback rows the
 * website's guest feedback form and Admin Feedback Module use, so a mobile
 * submission shows up for the admin and an admin's response shows up here.
 */
final class FeedbackController {

    private static final int SCREEN_MAIN = 0;
    private static final int SCREEN_WRITE = 1;

    private static final int MAX_MESSAGE_LENGTH = 500;

    private final Activity activity;
    private final FrameLayout root;

    private View currentScreenView;
    private int currentScreen = -1;
    private int selectedRating;
    private List<FeedbackEntryDto> entries = new ArrayList<>();

    FeedbackController(Activity activity) {
        this.activity = activity;
        root = new FrameLayout(activity);
        showScreen(SCREEN_MAIN);
        refresh();
    }

    View getRootView() {
        return root;
    }

    /** Refetches the guest's feedback history; call when the tab becomes visible. */
    void onShown() {
        refresh();
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

    private String bearerToken() {
        String token = ProfileStore.getAuthToken(activity);
        return token.isEmpty() ? null : "Bearer " + token;
    }

    private void refresh() {
        String token = bearerToken();
        if (token == null) {
            return;
        }
        ApiClient.feedbackApi().getFeedback(token).enqueue(new Callback<FeedbackListResponse>() {
            @Override
            public void onResponse(Call<FeedbackListResponse> call, Response<FeedbackListResponse> response) {
                if (response.isSuccessful() && response.body() != null && response.body().feedback != null) {
                    entries = response.body().feedback;
                    if (currentScreen == SCREEN_MAIN && currentScreenView != null) {
                        bindMain(currentScreenView);
                    }
                }
            }

            @Override
            public void onFailure(Call<FeedbackListResponse> call, Throwable t) {
                // Silent: a background refresh failing (e.g. no connection) shouldn't
                // interrupt the guest or clear what's already on screen.
            }
        });
    }

    // ---- Main feedback screen ----------------------------------------

    private void bindMain(View v) {
        v.findViewById(R.id.writeFeedbackButton).setOnClickListener(view -> showScreen(SCREEN_WRITE));

        LinearLayout container = v.findViewById(R.id.feedbackListContainer);
        container.removeAllViews();

        boolean empty = entries.isEmpty();
        v.findViewById(R.id.feedbackEmptyState).setVisibility(empty ? View.VISIBLE : View.GONE);
        container.setVisibility(empty ? View.GONE : View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(activity);
        for (FeedbackEntryDto entry : entries) {
            View card = inflater.inflate(R.layout.view_feedback_card, container, false);
            bindAvatar(card.findViewById(R.id.feedbackCardAvatar));
            ((TextView) card.findViewById(R.id.feedbackCardName)).setText(ProfileStore.getFullName(activity));
            ((TextView) card.findViewById(R.id.feedbackCardDate)).setText(entry.date);

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
                        activity.getString(R.string.feedback_response_date_format, entry.responded_at));
                ((TextView) card.findViewById(R.id.feedbackCardResponseMessage))
                        .setText(entry.response);
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

        String token = bearerToken();
        if (token == null) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }

        setSubmitting(v, true);
        ApiClient.feedbackApi().submit(token, selectedRating, message)
                .enqueue(new Callback<FeedbackSubmitResponse>() {
                    @Override
                    public void onResponse(Call<FeedbackSubmitResponse> call, Response<FeedbackSubmitResponse> response) {
                        setSubmitting(v, false);
                        if (response.isSuccessful()) {
                            refresh();
                            showSuccessDialog();
                        } else if (response.code() == 401) {
                            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
                        }
                    }

                    @Override
                    public void onFailure(Call<FeedbackSubmitResponse> call, Throwable t) {
                        setSubmitting(v, false);
                        Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
                    }
                });
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
            ThemeManager.applyGlassEffect(dialog.getWindow());
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
