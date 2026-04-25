package com.softcraft.freechat.firebase;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.messaging.FirebaseMessaging;
import com.softcraft.freechat.crypto.CryptoManager;
import com.softcraft.freechat.crypto.KeyManager;
import com.softcraft.freechat.model.Chat;
import com.softcraft.freechat.model.Message;
import com.softcraft.freechat.model.User;
import com.softcraft.freechat.utils.Constants;

import java.util.HashMap;
import java.util.Map;

/**
 * All Firebase Realtime Database operations in one place.
 *
 * Database layout:
 *
 * /users/{uid}
 *     uid, phone, displayName, about, publicKey, fcmToken, lastSeen, online, avatarB64
 *
 * /chats/{chatId}
 *     chatType, participants{uid:true}, lastMsg, lastMsgTime,
 *     groupName(optional), groupAdmin(optional)
 *
 * /messages/{chatId}/{messageId}
 *     senderId, cipher, iv, type, timestamp, status
 *
 * /userChats/{uid}/{chatId} = true
 *     (secondary index so we can list a user's chats efficiently)
 *
 * /groupKeys/{groupId}/{uid} = wrappedGroupKey (string)
 *     (each member gets their copy of the group key, encrypted for them)
 *
 * /presence/{uid}
 *     online (boolean), lastSeen (timestamp)
 *
 * /typing/{chatId}/{uid} = true|false
 */
public class FirebaseManager {

    private static final String TAG = "FirebaseManager";

    private static FirebaseDatabase db;
    private static DatabaseReference root;

    public static void init() {
        db   = FirebaseDatabase.getInstance();
        root = db.getReference();
        // Keep certain nodes synced for offline support
        root.child(Constants.DB_USERS).keepSynced(false); // sync on demand only
    }

    private static DatabaseReference ref(String... path) {
        DatabaseReference r = root;
        for (String p : path) r = r.child(p);
        return r;
    }

    // ─────────────────────────────────────────────
    //  USERS
    // ─────────────────────────────────────────────

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    /** Push or update the current user's profile in Firebase */
    public static void saveUser(User user, Callback<Void> cb) {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful()) user.fcmToken = task.getResult();

            ref(Constants.DB_USERS, user.uid).setValue(user)
                    .addOnSuccessListener(unused -> cb.onSuccess(null))
                    .addOnFailureListener(e -> cb.onError(e.getMessage()));
        });
    }

    /** Update only the public key (called after key regeneration) */
    public static void updatePublicKey(String uid, String pubKeyB64) {
        ref(Constants.DB_USERS, uid, Constants.FIELD_PUBLIC_KEY).setValue(pubKeyB64);
    }

    public static void getUser(String uid, Callback<User> cb) {
        ref(Constants.DB_USERS, uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                User u = snapshot.getValue(User.class);
                if (u != null) cb.onSuccess(u);
                else cb.onError("User not found");
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                cb.onError(error.getMessage());
            }
        });
    }

    /** Find user by phone number (exact match) */
    public static void getUserByPhone(String phone, Callback<User> cb) {
        ref(Constants.DB_USERS)
                .orderByChild(Constants.FIELD_PHONE)
                .equalTo(phone)
                .limitToFirst(1)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                        for (DataSnapshot child : snapshot.getChildren()) {
                            User u = child.getValue(User.class);
                            if (u != null) { cb.onSuccess(u); return; }
                        }
                        cb.onError("User not found");
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {
                        cb.onError(error.getMessage());
                    }
                });
    }

    public static void listenUser(String uid, ValueEventListener listener) {
        ref(Constants.DB_USERS, uid).addValueEventListener(listener);
    }

    public static void removeUserListener(String uid, ValueEventListener listener) {
        ref(Constants.DB_USERS, uid).removeEventListener(listener);
    }

    public static void updateAvatar(String uid, String avatarB64, Callback<Void> cb) {
        ref(Constants.DB_USERS, uid, Constants.FIELD_AVATAR_B64).setValue(avatarB64)
                .addOnSuccessListener(u -> cb.onSuccess(null))
                .addOnFailureListener(e -> cb.onError(e.getMessage()));
    }

    public static void updateAbout(String uid, String about) {
        ref(Constants.DB_USERS, uid, Constants.FIELD_ABOUT).setValue(about);
    }

    public static void updateDisplayName(String uid, String name) {
        ref(Constants.DB_USERS, uid, Constants.FIELD_DISPLAY_NAME).setValue(name);
    }

    // ─────────────────────────────────────────────
    //  PRESENCE — online / last seen
    // ─────────────────────────────────────────────

    public static void setOnline(String uid, boolean online) {
        Map<String, Object> update = new HashMap<>();
        update.put("online",   online);
        update.put("lastSeen", ServerValue.TIMESTAMP);
        ref(Constants.DB_PRESENCE, uid).updateChildren(update);

        // Also update user node
        ref(Constants.DB_USERS, uid, Constants.FIELD_ONLINE).setValue(online);
        ref(Constants.DB_USERS, uid, Constants.FIELD_LAST_SEEN).setValue(ServerValue.TIMESTAMP);

        // When we disconnect, automatically set offline
        if (online) {
            Map<String, Object> offlineMap = new HashMap<>();
            offlineMap.put("online",   false);
            offlineMap.put("lastSeen", ServerValue.TIMESTAMP);
            ref(Constants.DB_PRESENCE, uid).onDisconnect().updateChildren(offlineMap);
            ref(Constants.DB_USERS, uid, Constants.FIELD_ONLINE).onDisconnect().setValue(false);
            ref(Constants.DB_USERS, uid, Constants.FIELD_LAST_SEEN)
                    .onDisconnect().setValue(ServerValue.TIMESTAMP);
        }
    }

    public static void listenPresence(String uid, ValueEventListener listener) {
        ref(Constants.DB_PRESENCE, uid).addValueEventListener(listener);
    }

    public static void removePresenceListener(String uid, ValueEventListener listener) {
        ref(Constants.DB_PRESENCE, uid).removeEventListener(listener);
    }

    // ─────────────────────────────────────────────
    //  CHATS
    // ─────────────────────────────────────────────

    /**
     * Creates or updates a chat node. Also adds it to both users' userChats indexes.
     */
    public static void saveChat(Chat chat, Callback<Void> cb) {
        Map<String, Object> fanout = new HashMap<>();
        fanout.put(Constants.DB_CHATS + "/" + chat.chatId, chat);
        for (String uid : chat.participants.keySet()) {
            fanout.put(Constants.DB_USER_CHATS + "/" + uid + "/" + chat.chatId, true);
        }
        root.updateChildren(fanout)
                .addOnSuccessListener(u -> cb.onSuccess(null))
                .addOnFailureListener(e -> cb.onError(e.getMessage()));
    }

    public static void getChat(String chatId, Callback<Chat> cb) {
        ref(Constants.DB_CHATS, chatId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                Chat c = snapshot.getValue(Chat.class);
                if (c != null) { c.chatId = chatId; cb.onSuccess(c); }
                else cb.onError("Chat not found");
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                cb.onError(error.getMessage());
            }
        });
    }

    public static void listenUserChats(String uid, ValueEventListener listener) {
        ref(Constants.DB_USER_CHATS, uid).addValueEventListener(listener);
    }

    public static void removeUserChatsListener(String uid, ValueEventListener listener) {
        ref(Constants.DB_USER_CHATS, uid).removeEventListener(listener);
    }

    public static void listenChat(String chatId, ValueEventListener listener) {
        ref(Constants.DB_CHATS, chatId).addValueEventListener(listener);
    }

    /** Update lastMsg preview and timestamp (called after each sent message) */
    public static void updateChatLastMessage(String chatId, String preview) {
        Map<String, Object> update = new HashMap<>();
        update.put(Constants.FIELD_LAST_MSG,      preview);
        update.put(Constants.FIELD_LAST_MSG_TIME, ServerValue.TIMESTAMP);
        ref(Constants.DB_CHATS, chatId).updateChildren(update);
    }

    // ─────────────────────────────────────────────
    //  MESSAGES
    // ─────────────────────────────────────────────

    /**
     * Sends an encrypted message.
     * Returns the generated message ID via callback.
     */
    public static void sendMessage(String chatId, Message message, Callback<String> cb) {
        DatabaseReference msgRef = ref(Constants.DB_MESSAGES, chatId).push();
        String messageId = msgRef.getKey();
        message.messageId = messageId;
        message.status    = Constants.STATUS_SENT;

        msgRef.setValue(message)
                .addOnSuccessListener(u -> cb.onSuccess(messageId))
                .addOnFailureListener(e -> cb.onError(e.getMessage()));
    }

    /**
     * Listen to all messages in a chat (real-time).
     * Use ChildEventListener in ChatActivity instead for incremental updates.
     */
    public static DatabaseReference getMessagesRef(String chatId) {
        return ref(Constants.DB_MESSAGES, chatId);
    }

    public static void updateMessageStatus(String chatId, String messageId, String status) {
        ref(Constants.DB_MESSAGES, chatId, messageId, Constants.FIELD_STATUS).setValue(status);
    }

    /** Mark all messages from a peer as "read" (batch update) */
    public static void markMessagesRead(String chatId, String myUid,
                                        java.util.List<String> messageIds) {
        Map<String, Object> updates = new HashMap<>();
        for (String id : messageIds) {
            updates.put(Constants.DB_MESSAGES + "/" + chatId + "/" + id
                    + "/" + Constants.FIELD_STATUS, Constants.STATUS_READ);
        }
        root.updateChildren(updates);
    }

    public static void deleteMessage(String chatId, String messageId) {
        ref(Constants.DB_MESSAGES, chatId, messageId).removeValue();
    }

    // ─────────────────────────────────────────────
    //  GROUP KEYS
    // ─────────────────────────────────────────────

    /** Store each member's wrapped group key */
    public static void saveGroupKeys(String groupId, Map<String, String> wrappedKeys,
                                     Callback<Void> cb) {
        Map<String, Object> fanout = new HashMap<>();
        for (Map.Entry<String, String> e : wrappedKeys.entrySet()) {
            fanout.put(Constants.DB_GROUP_KEYS + "/" + groupId + "/" + e.getKey(), e.getValue());
        }
        root.updateChildren(fanout)
                .addOnSuccessListener(u -> cb.onSuccess(null))
                .addOnFailureListener(e -> cb.onError(e.getMessage()));
    }

    /** Fetch this member's wrapped group key */
    public static void getMyGroupKey(String groupId, String uid, Callback<String> cb) {
        ref(Constants.DB_GROUP_KEYS, groupId, uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                        String wrapped = snapshot.getValue(String.class);
                        if (wrapped != null) cb.onSuccess(wrapped);
                        else cb.onError("Group key not found");
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {
                        cb.onError(error.getMessage());
                    }
                });
    }

    // ─────────────────────────────────────────────
    //  TYPING INDICATORS
    // ─────────────────────────────────────────────

    public static void setTyping(String chatId, String uid, boolean typing) {
        DatabaseReference typingRef = ref(Constants.DB_TYPING, chatId, uid);
        typingRef.setValue(typing);
        if (typing) {
            typingRef.onDisconnect().removeValue();
        }
    }

    public static void listenTyping(String chatId, ValueEventListener listener) {
        ref(Constants.DB_TYPING, chatId).addValueEventListener(listener);
    }

    public static void removeTypingListener(String chatId, ValueEventListener listener) {
        ref(Constants.DB_TYPING, chatId).removeEventListener(listener);
    }

    // ─────────────────────────────────────────────
    //  FCM TOKEN
    // ─────────────────────────────────────────────

    public static void updateFcmToken(String uid) {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                ref(Constants.DB_USERS, uid, Constants.FIELD_FCM_TOKEN)
                        .setValue(task.getResult());
            }
        });
    }
}