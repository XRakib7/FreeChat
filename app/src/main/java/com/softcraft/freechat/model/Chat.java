package com.softcraft.freechat.model;

import com.google.firebase.database.Exclude;
import com.google.firebase.database.IgnoreExtraProperties;
import java.util.Map;

@IgnoreExtraProperties
public class Chat {

    public String chatId;
    public Map<String, Boolean> participants; // uid → true
    public String lastMsg;       // preview (NOT encrypted in DB — kept as "[Message]" placeholder)
    public long   lastMsgTime;
    public String chatType;      // "individual" | "group"
    public String groupName;     // null for individual chats
    public String groupAdmin;    // uid of group admin
    public String groupAvatarB64;

    // ── Transient ──
    @Exclude public User   peerUser;      // populated client-side for individual chats
    @Exclude public int    unreadCount;
    @Exclude public boolean muted;

    public Chat() {}

    /** Factory for 1-to-1 chat */
    public static Chat individual(String chatId,
                                  String uid1, String uid2,
                                  String lastMsgPreview) {
        Chat c = new Chat();
        c.chatId      = chatId;
        c.chatType    = "individual";
        c.lastMsg     = lastMsgPreview;
        c.lastMsgTime = System.currentTimeMillis();
        c.participants = new java.util.HashMap<>();
        c.participants.put(uid1, true);
        c.participants.put(uid2, true);
        return c;
    }

    /** Factory for group chat */
    public static Chat group(String chatId, String groupName,
                             String adminUid, Map<String, Boolean> members) {
        Chat c = new Chat();
        c.chatId      = chatId;
        c.chatType    = "group";
        c.groupName   = groupName;
        c.groupAdmin  = adminUid;
        c.participants = members;
        c.lastMsg     = "Group created";
        c.lastMsgTime = System.currentTimeMillis();
        return c;
    }

    /** Returns the chat ID for a 1-to-1 chat (deterministic, order-independent) */
    public static String individualChatId(String uid1, String uid2) {
        // Lexicographically smaller UID first → same ID regardless of who creates the chat
        if (uid1.compareTo(uid2) < 0) {
            return uid1 + "_" + uid2;
        } else {
            return uid2 + "_" + uid1;
        }
    }
}