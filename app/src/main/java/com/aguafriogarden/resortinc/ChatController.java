package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.aguafriogarden.resortinc.network.ApiClient;
import com.aguafriogarden.resortinc.network.ChatHistoryResponse;
import com.aguafriogarden.resortinc.network.ChatMessageDto;
import com.aguafriogarden.resortinc.network.ChatSendResponse;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Drives the Chat tab: a single running conversation with the live
 * receptionist backend, reading and writing the same chat_conversations/
 * chat_messages rows the website's receptionist Chat module uses. Polls for
 * new messages every 4s while the tab is visible, matching the cadence of
 * the receptionist's own web thread (resources/views/receptionist/chat).
 */
final class ChatController {

    private static final long POLL_INTERVAL_MS = 4000L;
    private static final long BUBBLE_ANIM_MS = 200L;

    private static final int STATUS_SENDING = 0;
    private static final int STATUS_SENT = 1;
    private static final int STATUS_READ = 2;

    private static final class DisplayMessage {
        final long id;
        final boolean guest;
        final String text;
        final String timeLabel;
        final int status;

        DisplayMessage(long id, boolean guest, String text, String timeLabel, int status) {
            this.id = id;
            this.guest = guest;
            this.text = text;
            this.timeLabel = timeLabel;
            this.status = status;
        }
    }

    private final Activity activity;
    private final View root;
    private final ScrollView scrollView;
    private final LinearLayout messageContainer;
    private final View welcomeCard;
    private final View chipsScroller;
    private final EditText messageField;
    private final ImageView sendButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = this::poll;

    private final List<DisplayMessage> messages = new ArrayList<>();
    private DisplayMessage pendingMessage;
    private long lastMessageId = 0L;
    private boolean polling;

    ChatController(Activity activity) {
        this.activity = activity;
        root = LayoutInflater.from(activity).inflate(R.layout.view_chat_main, null, false);

        scrollView = root.findViewById(R.id.chatScrollView);
        messageContainer = root.findViewById(R.id.chatMessageContainer);
        welcomeCard = root.findViewById(R.id.chatWelcomeCard);
        chipsScroller = root.findViewById(R.id.chatChipsScroller);
        messageField = root.findViewById(R.id.chatMessageField);
        sendButton = root.findViewById(R.id.chatSendButton);

        bindChips();
        AuthUiUtils.afterTextChanged(messageField, this::updateSendButtonState);
        updateSendButtonState();
        sendButton.setOnClickListener(v -> sendTypedMessage());

        renderMessages(false);
        loadHistory();
    }

    View getRootView() {
        return root;
    }

    /** Starts polling for new messages; call when the Chat tab becomes visible. */
    void onShown() {
        if (!polling) {
            polling = true;
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        }
    }

    /** Stops polling; call when the Chat tab is no longer visible. */
    void onHidden() {
        polling = false;
        handler.removeCallbacks(pollRunnable);
    }

    private void bindChips() {
        int[] chipIds = {
                R.id.chipRoomAvailability, R.id.chipBookingRequirements, R.id.chipResortRates,
                R.id.chipPoolInfo, R.id.chipCottageInfo, R.id.chipBookingStatus,
                R.id.chipPaymentInstructions, R.id.chipCancellationPolicy,
                R.id.chipResortAmenities, R.id.chipContactReceptionist,
        };
        for (int chipId : chipIds) {
            TextView chip = root.findViewById(chipId);
            chip.setOnClickListener(v -> sendGuestMessage(chip.getText().toString()));
        }
    }

    private void updateSendButtonState() {
        boolean hasText = messageField.getText().toString().trim().length() > 0;
        sendButton.setEnabled(hasText);
        sendButton.setAlpha(hasText ? 1f : 0.4f);
    }

    private void sendTypedMessage() {
        String text = messageField.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        messageField.setText("");
        sendGuestMessage(text);
    }

    private String bearerToken() {
        String token = ProfileStore.getAuthToken(activity);
        return token.isEmpty() ? null : "Bearer " + token;
    }

    private void loadHistory() {
        String token = bearerToken();
        if (token == null) {
            return;
        }
        ApiClient.chatApi().getConversation(token).enqueue(new Callback<ChatHistoryResponse>() {
            @Override
            public void onResponse(Call<ChatHistoryResponse> call, Response<ChatHistoryResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    messages.clear();
                    appendMessages(response.body().messages);
                    renderMessages(false);
                }
                onShown();
            }

            @Override
            public void onFailure(Call<ChatHistoryResponse> call, Throwable t) {
                Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
                onShown();
            }
        });
    }

    private void poll() {
        if (!polling) {
            return;
        }
        String token = bearerToken();
        if (token == null) {
            schedulePollAgain();
            return;
        }
        ApiClient.chatApi().poll(token, lastMessageId).enqueue(new Callback<ChatHistoryResponse>() {
            @Override
            public void onResponse(Call<ChatHistoryResponse> call, Response<ChatHistoryResponse> response) {
                if (response.isSuccessful() && response.body() != null
                        && response.body().messages != null && !response.body().messages.isEmpty()) {
                    appendMessages(response.body().messages);
                    renderMessages(true);
                }
                schedulePollAgain();
            }

            @Override
            public void onFailure(Call<ChatHistoryResponse> call, Throwable t) {
                schedulePollAgain();
            }
        });
    }

    private void schedulePollAgain() {
        if (polling) {
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        }
    }

    private void appendMessages(List<ChatMessageDto> dtos) {
        if (dtos == null) {
            return;
        }
        for (ChatMessageDto dto : dtos) {
            boolean isGuest = "guest".equals(dto.sender_type);
            int status = isGuest && dto.read_at != null ? STATUS_READ : STATUS_SENT;
            messages.add(new DisplayMessage(dto.id, isGuest, dto.body, dto.created_at, status));
            lastMessageId = Math.max(lastMessageId, dto.id);
        }
    }

    private void sendGuestMessage(String text) {
        if (pendingMessage != null) {
            return; // previous send still in flight
        }
        String token = bearerToken();
        if (token == null) {
            Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
            return;
        }

        String timeLabel = new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date());
        pendingMessage = new DisplayMessage(0, true, text, timeLabel, STATUS_SENDING);
        renderMessages(true);

        ApiClient.chatApi().send(token, text).enqueue(new Callback<ChatSendResponse>() {
            @Override
            public void onResponse(Call<ChatSendResponse> call, Response<ChatSendResponse> response) {
                pendingMessage = null;
                if (response.isSuccessful() && response.body() != null && response.body().message != null) {
                    appendMessages(Collections.singletonList(response.body().message));
                } else if (response.code() == 401) {
                    Toast.makeText(activity, R.string.toast_session_expired, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
                }
                renderMessages(true);
            }

            @Override
            public void onFailure(Call<ChatSendResponse> call, Throwable t) {
                pendingMessage = null;
                Toast.makeText(activity, R.string.toast_network_error, Toast.LENGTH_LONG).show();
                renderMessages(false);
            }
        });
    }

    /** Rebuilds the conversation from the in-memory message list; animates the newest bubble in when requested. */
    private void renderMessages(boolean animateNewest) {
        messageContainer.removeAllViews();

        boolean empty = messages.isEmpty() && pendingMessage == null;
        welcomeCard.setVisibility(empty ? View.VISIBLE : View.GONE);
        chipsScroller.setVisibility(empty ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(activity);
        for (DisplayMessage message : messages) {
            messageContainer.addView(buildBubble(inflater, message));
        }
        if (pendingMessage != null) {
            messageContainer.addView(buildBubble(inflater, pendingMessage));
        }

        if (animateNewest && messageContainer.getChildCount() > 0) {
            View newest = messageContainer.getChildAt(messageContainer.getChildCount() - 1);
            float shiftPx = 8f * activity.getResources().getDisplayMetrics().density;
            newest.setAlpha(0f);
            newest.setTranslationY(shiftPx);
            newest.animate().alpha(1f).translationY(0f).setDuration(BUBBLE_ANIM_MS).start();
        }

        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private View buildBubble(LayoutInflater inflater, DisplayMessage message) {
        View bubble = inflater.inflate(
                message.guest ? R.layout.view_chat_bubble_guest : R.layout.view_chat_bubble_other,
                messageContainer, false);
        ((TextView) bubble.findViewById(R.id.bubbleText)).setText(message.text);
        ((TextView) bubble.findViewById(R.id.bubbleTimestamp)).setText(message.timeLabel);

        if (message.guest) {
            ((TextView) bubble.findViewById(R.id.bubbleStatus)).setText(statusLabel(message.status));
        }
        return bubble;
    }

    private int statusLabel(int status) {
        switch (status) {
            case STATUS_SENDING:
                return R.string.chat_status_sending;
            case STATUS_READ:
                return R.string.chat_status_read;
            default:
                return R.string.chat_status_sent;
        }
    }
}
