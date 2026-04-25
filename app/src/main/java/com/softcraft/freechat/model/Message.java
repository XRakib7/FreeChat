package com.softcraft.freechat.model;

import com.google.firebase.database.Exclude;
import com.google.firebase.database.IgnoreExtraProperties;

@IgnoreExtraProperties
public class Message {

    public String messageId;
    public String senderId;

    /** AES-256-GCM ciphertext (Base64). For images: encrypted compressed JPEG bytes. */
    public String cipher;

    /** Base64 12-byte GCM IV */
    public String iv;

    /** "text" | "image" | "system" */
    public String type;

    public long   timestamp;

    /** "sending" | "sent" | "delivered" | "read" | "failed" */
    public String status;

    // ── Transient (client-side only, not stored in Firebase) ──
    @Exclude public String decryptedText;   // for type=text
    @Exclude public byte[] decryptedImage; // for type=image (raw JPEG bytes)
    @Exclude public boolean decryptionFailed;

    public Message() {}

    public Message(String senderId, String cipher, String iv, String type) {
        this.senderId  = senderId;
        this.cipher    = cipher;
        this.iv        = iv;
        this.type      = type;
        this.timestamp = System.currentTimeMillis();
        this.status    = "sending";
    }
}