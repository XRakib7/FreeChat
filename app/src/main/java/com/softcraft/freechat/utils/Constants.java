package com.softcraft.freechat.utils;

public final class Constants {

    private Constants() {}

    // Firebase Database paths
    public static final String DB_USERS        = "users";
    public static final String DB_CHATS        = "chats";
    public static final String DB_MESSAGES     = "messages";
    public static final String DB_USER_CHATS   = "userChats";
    public static final String DB_GROUP_KEYS   = "groupKeys";
    public static final String DB_PRESENCE     = "presence";
    public static final String DB_TYPING       = "typing";

    // User fields
    public static final String FIELD_UID           = "uid";
    public static final String FIELD_PHONE         = "phone";
    public static final String FIELD_DISPLAY_NAME  = "displayName";
    public static final String FIELD_PUBLIC_KEY    = "publicKey";
    public static final String FIELD_FCM_TOKEN     = "fcmToken";
    public static final String FIELD_LAST_SEEN     = "lastSeen";
    public static final String FIELD_ONLINE        = "online";
    public static final String FIELD_ABOUT         = "about";
    public static final String FIELD_AVATAR_B64    = "avatarB64";

    // Message fields
    public static final String FIELD_SENDER_ID     = "senderId";
    public static final String FIELD_CIPHER        = "cipher";
    public static final String FIELD_IV            = "iv";
    public static final String FIELD_TYPE          = "type";
    public static final String FIELD_TIMESTAMP     = "timestamp";
    public static final String FIELD_STATUS        = "status";

    // Chat fields
    public static final String FIELD_PARTICIPANTS  = "participants";
    public static final String FIELD_LAST_MSG      = "lastMsg";
    public static final String FIELD_LAST_MSG_TIME = "lastMsgTime";
    public static final String FIELD_CHAT_TYPE     = "chatType";
    public static final String FIELD_GROUP_NAME    = "groupName";
    public static final String FIELD_GROUP_ADMIN   = "groupAdmin";

    // Message types — add video and voice
    public static final String MSG_TYPE_TEXT   = "text";
    public static final String MSG_TYPE_IMAGE  = "image";
    public static final String MSG_TYPE_VIDEO  = "video";
    public static final String MSG_TYPE_VOICE  = "voice";
    public static final String MSG_TYPE_SYSTEM = "system";
    // Message status
    public static final String STATUS_SENDING   = "sending";
    public static final String STATUS_SENT      = "sent";
    public static final String STATUS_DELIVERED = "delivered";
    public static final String STATUS_READ      = "read";
    public static final String STATUS_FAILED    = "failed";

    // Chat types
    public static final String CHAT_INDIVIDUAL = "individual";
    public static final String CHAT_GROUP      = "group";

    // Intent extras
    public static final String EXTRA_CHAT_ID      = "chatId";
    public static final String EXTRA_PEER_UID     = "peerUid";
    public static final String EXTRA_CHAT_NAME    = "chatName";
    public static final String EXTRA_CHAT_TYPE    = "chatType";
    public static final String EXTRA_USER         = "user";
    public static final String EXTRA_IMAGE_BYTES  = "imageBytes";

    // Crypto constants
    public static final int AES_KEY_SIZE_BITS = 256;
    public static final int GCM_IV_BYTES      = 12;
    public static final int GCM_TAG_BITS      = 128;
    public static final String HKDF_INFO      = "FreeChatV1-AES256GCM";
    public static final String HKDF_ALGO      = "HmacSHA256";

    // Image constraints
    public static final int IMAGE_MAX_PX     = 1024; // max width or height
    public static final int IMAGE_QUALITY    = 75;   // JPEG quality
    public static final int AVATAR_MAX_PX    = 256;
    public static final int AVATAR_QUALITY   = 70;

    // Prefs keys
    public static final String PREFS_NAME         = "fc_secure_prefs";
    public static final String PREF_PRIVATE_KEY   = "ec_private_key";
    public static final String PREF_PUBLIC_KEY    = "ec_public_key";
    public static final String PREF_UID           = "uid";
    public static final String PREF_PHONE         = "phone";
    public static final String PREF_DISPLAY_NAME  = "displayName";
    public static final String PREF_PROFILE_DONE  = "profileSetupDone";

    // Misc
    public static final int PICK_IMAGE_REQUEST = 1001;
    public static final int CAMERA_REQUEST     = 1002;
}