package com.softcraft.freechat.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.softcraft.freechat.ChatActivity;
import com.softcraft.freechat.R;
import com.softcraft.freechat.firebase.FirebaseManager;
import com.softcraft.freechat.utils.Constants;

import java.util.Map;

/**
 * Firebase Cloud Messaging service.
 *
 * Receives push notifications for new messages when the app is in background.
 * NOTE: The notification payload contains only the sender name and chat ID
 * (never the message content — content is always E2E encrypted in the DB).
 *
 * For FCM to work with E2E encryption:
 * - Server sends only metadata (who sent, which chat)
 * - Client fetches and decrypts the actual message from RTDB on tap
 *
 * This means your Firebase Cloud Functions (or your backend) should send
 * notifications like:
 *   { "data": { "chatId": "...", "senderName": "Alice", "type": "text" } }
 * Never put plaintext message content in the FCM payload.
 */
public class FCMService extends FirebaseMessagingService {

    private static final String TAG = "FCMService";
    private static final String CHANNEL_ID   = "freechat_messages";
    private static final String CHANNEL_NAME = "Messages";

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.d(TAG, "FCM token refreshed: " + token.substring(0, 8) + "...");

        // Update token in Firebase
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            FirebaseManager.updateFcmToken(user.getUid());
        }
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        Map<String, String> data = remoteMessage.getData();
        if (data.isEmpty()) return;

        String chatId     = data.get("chatId");
        String senderName = data.get("senderName");
        String chatType   = data.get("chatType");
        String type       = data.get("type");

        if (chatId == null || senderName == null) return;

        // Build notification body based on message type — NEVER shows decrypted content
        String body;
        if (Constants.MSG_TYPE_IMAGE.equals(type)) {
            body = "📷 Photo";
        } else {
            body = "New message"; // Content encrypted — can only say "new message"
        }

        showNotification(chatId, senderName, body, chatType, data.get("peerUid"), data.get("chatName"));
    }

    private void showNotification(String chatId, String senderName, String body,
                                  String chatType, String peerUid, String chatName) {
        createNotificationChannel();

        // Tap opens ChatActivity with the right chat
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(Constants.EXTRA_CHAT_ID,   chatId);
        intent.putExtra(Constants.EXTRA_CHAT_TYPE,  chatType != null ? chatType : Constants.CHAT_INDIVIDUAL);
        intent.putExtra(Constants.EXTRA_PEER_UID,   peerUid);
        intent.putExtra(Constants.EXTRA_CHAT_NAME,  chatName != null ? chatName : senderName);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(this, chatId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(senderName)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setDefaults(NotificationCompat.DEFAULT_ALL);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(chatId.hashCode(), builder.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Free Chat encrypted messages");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}