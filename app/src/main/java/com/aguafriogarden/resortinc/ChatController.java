package com.aguafriogarden.resortinc;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * Drives the Chat tab: a single running support conversation. Guest messages
 * simulate a Sending -> Sent -> Delivered -> Read progression before
 * ChatAssistant answers (or escalates); guests can long-press their own
 * bubbles to delete them. DashboardActivity keeps one instance alive across
 * tab switches, same as the other module controllers.
 */
final class ChatController {

    private static final long STATUS_SENT_DELAY_MS = 400L;
    private static final long STATUS_DELIVERED_DELAY_MS = 1000L;
    private static final long REPLY_DELAY_MS = 2000L;
    private static final long BUBBLE_ANIM_MS = 200L;

    private final Activity activity;
    private final View root;
    private final ScrollView scrollView;
    private final LinearLayout messageContainer;
    private final View welcomeCard;
    private final View chipsScroller;
    private final EditText messageField;
    private final ImageView sendButton;
    private final Handler handler = new Handler(Looper.getMainLooper());

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
    }

    View getRootView() {
        return root;
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

    private void sendGuestMessage(String text) {
        int index = ChatStore.addMessage(activity, ChatStore.SENDER_GUEST, text, ChatStore.STATUS_SENDING);
        renderMessages(true);

        handler.postDelayed(() -> {
            ChatStore.updateStatus(activity, index, ChatStore.STATUS_SENT);
            renderMessages(false);
        }, STATUS_SENT_DELAY_MS);

        handler.postDelayed(() -> {
            ChatStore.updateStatus(activity, index, ChatStore.STATUS_DELIVERED);
            renderMessages(false);
        }, STATUS_DELIVERED_DELAY_MS);

        handler.postDelayed(() -> {
            ChatStore.markGuestMessagesRead(activity);
            String faqAnswer = ChatAssistant.respond(activity, text);
            String reply = faqAnswer != null ? faqAnswer : activity.getString(R.string.chat_escalation_message);
            ChatStore.addMessage(activity, ChatStore.SENDER_OTHER, reply, ChatStore.STATUS_SENT);
            renderMessages(true);
        }, REPLY_DELAY_MS);
    }

    /** Rebuilds the conversation from ChatStore; animates the newest bubble in when requested. */
    private void renderMessages(boolean animateNewest) {
        List<ChatStore.Message> messages = ChatStore.getAll(activity);
        messageContainer.removeAllViews();

        boolean empty = messages.isEmpty();
        welcomeCard.setVisibility(empty ? View.VISIBLE : View.GONE);
        chipsScroller.setVisibility(empty ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(activity);
        SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm a", Locale.getDefault());
        for (ChatStore.Message message : messages) {
            boolean isGuest = message.sender == ChatStore.SENDER_GUEST;
            View bubble = inflater.inflate(
                    isGuest ? R.layout.view_chat_bubble_guest : R.layout.view_chat_bubble_other,
                    messageContainer, false);
            ((TextView) bubble.findViewById(R.id.bubbleText)).setText(message.text);
            ((TextView) bubble.findViewById(R.id.bubbleTimestamp))
                    .setText(timeFormat.format(message.timestampMillis));

            if (isGuest) {
                ((TextView) bubble.findViewById(R.id.bubbleStatus)).setText(statusLabel(message.status));
                bubble.findViewById(R.id.bubbleText).setOnLongClickListener(v -> {
                    offerDelete(message.index);
                    return true;
                });
            }

            messageContainer.addView(bubble);
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

    private int statusLabel(int status) {
        switch (status) {
            case ChatStore.STATUS_SENDING:
                return R.string.chat_status_sending;
            case ChatStore.STATUS_DELIVERED:
                return R.string.chat_status_delivered;
            case ChatStore.STATUS_READ:
                return R.string.chat_status_read;
            default:
                return R.string.chat_status_sent;
        }
    }

    private void offerDelete(int index) {
        new AlertDialog.Builder(activity)
                .setItems(new CharSequence[]{
                        activity.getString(R.string.chat_delete_message_action),
                        activity.getString(R.string.button_cancel)
                }, (dialog, which) -> {
                    if (which == 0) {
                        confirmDelete(index);
                    }
                })
                .show();
    }

    private void confirmDelete(int index) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.chat_delete_confirm_title)
                .setMessage(R.string.chat_delete_confirm_message)
                .setPositiveButton(R.string.button_delete, (dialog, which) -> {
                    ChatStore.deleteMessage(activity, index);
                    renderMessages(false);
                })
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }
}
