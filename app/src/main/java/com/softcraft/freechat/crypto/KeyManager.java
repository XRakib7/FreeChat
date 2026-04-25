package com.softcraft.freechat.crypto;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.softcraft.freechat.utils.Constants;
import com.softcraft.freechat.utils.SecurePrefs;

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages long-term and session keys for Free Chat.
 *
 * Long-term keys:
 *   - Generated once on first launch
 *   - Private key stored in EncryptedSharedPreferences (AES-256-GCM, tied to Android Keystore)
 *   - Public key pushed to Firebase so peers can derive shared secrets
 *
 * Session keys (in-memory cache):
 *   - Derived on first message send/receive per chat
 *   - Cached in memory only — never persisted to disk
 *   - Cleared when app process is killed (adds security; user re-derives on next launch)
 */
public class KeyManager {

    private static final String TAG = "KeyManager";

    // In-memory session key cache: chatId → 32-byte AES key
    private static final Map<String, byte[]> sessionKeyCache = new HashMap<>();

    // In-memory group key cache: groupId → 32-byte group key material
    private static final Map<String, byte[]> groupKeyCache = new HashMap<>();

    // Singleton long-term key pair (loaded once from SecurePrefs)
    private static CryptoManager.X25519KeyPair myKeyPair;

    // ─────────────────────────────────────────────
    //  Initialization
    // ─────────────────────────────────────────────

    /**
     * Must be called once on app start (Application.onCreate).
     * Loads or generates the long-term X25519 key pair.
     */
    public static synchronized void init(Context ctx) {
        SecurePrefs.init(ctx);

        String privB64 = SecurePrefs.get(Constants.PREF_PRIVATE_KEY, null);
        String pubB64  = SecurePrefs.get(Constants.PREF_PUBLIC_KEY,  null);

        if (privB64 != null && pubB64 != null) {
            // Load existing key pair
            try {
                X25519PrivateKeyParameters priv = CryptoManager.privateKeyFromBase64(privB64);
                X25519PublicKeyParameters  pub  = CryptoManager.publicKeyFromBase64(pubB64);
                myKeyPair = new CryptoManager.X25519KeyPair(priv, pub);
                Log.d(TAG, "Loaded existing key pair.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to load keys, regenerating: " + e.getMessage());
                generateAndSaveKeyPair();
            }
        } else {
            generateAndSaveKeyPair();
        }
    }

    private static void generateAndSaveKeyPair() {
        myKeyPair = CryptoManager.generateKeyPair();
        SecurePrefs.put(Constants.PREF_PRIVATE_KEY,
                CryptoManager.privateKeyToBase64(myKeyPair.privateKey));
        SecurePrefs.put(Constants.PREF_PUBLIC_KEY,
                CryptoManager.publicKeyToBase64(myKeyPair.publicKey));
        Log.d(TAG, "Generated new X25519 key pair.");
    }

    // ─────────────────────────────────────────────
    //  Public key getters
    // ─────────────────────────────────────────────

    public static X25519PrivateKeyParameters getMyPrivateKey() {
        return myKeyPair.privateKey;
    }

    public static X25519PublicKeyParameters getMyPublicKey() {
        return myKeyPair.publicKey;
    }

    /** Returns Base64-encoded public key for uploading to Firebase */
    public static String getMyPublicKeyBase64() {
        return CryptoManager.publicKeyToBase64(myKeyPair.publicKey);
    }

    // ─────────────────────────────────────────────
    //  Session Key — Individual Chats
    // ─────────────────────────────────────────────

    /**
     * Returns the AES-256 session key for a 1-to-1 chat.
     * Derived once and cached in memory.
     *
     * @param chatId      Firebase chat node key
     * @param peerPubB64  peer's public key (Base64) from Firebase
     */
    public static synchronized byte[] getOrDeriveSessionKey(String chatId, String peerPubB64)
            throws Exception {
        if (sessionKeyCache.containsKey(chatId)) {
            return sessionKeyCache.get(chatId);
        }
        X25519PublicKeyParameters peerPub = CryptoManager.publicKeyFromBase64(peerPubB64);
        byte[] key = CryptoManager.deriveSessionKey(myKeyPair.privateKey, peerPub, chatId);
        sessionKeyCache.put(chatId, key);
        return key;
    }

    // ─────────────────────────────────────────────
    //  Group Key — Group Chats
    // ─────────────────────────────────────────────

    /** Generates a fresh random group key (32 bytes) */
    public static byte[] generateGroupKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /** Cache a decrypted group key for a group */
    public static synchronized void cacheGroupKey(String groupId, byte[] rawKey) {
        groupKeyCache.put(groupId, rawKey);
    }

    /** Get cached group key, or null if not yet decrypted */
    public static synchronized byte[] getGroupKey(String groupId) {
        return groupKeyCache.get(groupId);
    }

    /**
     * Derives the AES session key from the group key material.
     * Groups use HKDF on the raw group key with the group ID as context.
     */
    public static synchronized byte[] getOrDeriveGroupSessionKey(String groupId, byte[] rawGroupKey)
            throws Exception {
        String cacheKey = "group:" + groupId;
        if (sessionKeyCache.containsKey(cacheKey)) {
            return sessionKeyCache.get(cacheKey);
        }
        byte[] key = CryptoManager.deriveGroupSessionKey(rawGroupKey, groupId);
        sessionKeyCache.put(cacheKey, key);
        return key;
    }

    // ─────────────────────────────────────────────
    //  Key rotation / cleanup
    // ─────────────────────────────────────────────

    /** Evict a chat's session key from cache (call on chat close) */
    public static synchronized void evictSessionKey(String chatId) {
        sessionKeyCache.remove(chatId);
    }

    /** Wipe all in-memory session keys (call on logout) */
    public static synchronized void clearAllKeys() {
        for (byte[] key : sessionKeyCache.values()) {
            if (key != null) java.util.Arrays.fill(key, (byte) 0);
        }
        sessionKeyCache.clear();
        for (byte[] key : groupKeyCache.values()) {
            if (key != null) java.util.Arrays.fill(key, (byte) 0);
        }
        groupKeyCache.clear();
    }

    /** Wipe all stored keys (call on account deletion) */
    public static void nukeLocalKeys() {
        clearAllKeys();
        SecurePrefs.remove(Constants.PREF_PRIVATE_KEY);
        SecurePrefs.remove(Constants.PREF_PUBLIC_KEY);
        myKeyPair = null;
    }
}