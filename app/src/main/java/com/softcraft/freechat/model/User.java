package com.softcraft.freechat.model;

import com.google.firebase.database.IgnoreExtraProperties;

@IgnoreExtraProperties
public class User {
    public String uid;
    public String phone;
    public String displayName;
    public String about;
    public String publicKey;   // Base64 X25519 public key
    public String fcmToken;
    public long   lastSeen;
    public boolean online;
    public String avatarB64;   // Optional: Base64 compressed avatar image

    public User() {}

    public User(String uid, String phone, String displayName, String publicKey) {
        this.uid         = uid;
        this.phone       = phone;
        this.displayName = displayName;
        this.publicKey   = publicKey;
        this.about       = "Hey there! I am using Free Chat.";
        this.online      = false;
        this.lastSeen    = System.currentTimeMillis();
    }
}