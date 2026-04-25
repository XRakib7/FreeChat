package com.softcraft.freechat.crypto;

import android.util.Base64;
import android.util.Log;

import com.softcraft.freechat.utils.Constants;

import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator;
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Core cryptography engine for Free Chat.
 *
 * Encryption stack:
 *  - Key Exchange : X25519 ECDH  (Curve25519, via BouncyCastle lightweight API)
 *  - Key Derivation: HKDF-SHA256
 *  - Symmetric    : AES-256-GCM  (authenticated encryption, via Android JCE)
 *
 * No JCE provider registration needed — BC lightweight API used directly for X25519.
 * AES-GCM runs on Android's built-in Conscrypt provider.
 *
 * SECURITY PROPERTIES:
 *  - Perfect forward secrecy: not provided in this static-key version (add
 *    ephemeral keys per message for PFS — described in comments).
 *  - Authenticated encryption: AES-GCM provides both confidentiality and
 *    integrity. A forged ciphertext will fail decryption with an exception.
 *  - Zero-knowledge server: Firebase only stores ciphertext + IV. The shared
 *    secret never leaves the device.
 */
public class CryptoManager {

    private static final String TAG = "CryptoManager";
    private static final String AES_GCM = "AES/GCM/NoPadding";

    // ─────────────────────────────────────────────
    //  X25519 Key Generation
    // ─────────────────────────────────────────────

    public static X25519KeyPair generateKeyPair() {
        X25519KeyPairGenerator gen = new X25519KeyPairGenerator();
        gen.init(new X25519KeyGenerationParameters(new SecureRandom()));
        org.bouncycastle.crypto.AsymmetricCipherKeyPair pair = gen.generateKeyPair();
        X25519PrivateKeyParameters priv = (X25519PrivateKeyParameters) pair.getPrivate();
        X25519PublicKeyParameters  pub  = (X25519PublicKeyParameters)  pair.getPublic();
        return new X25519KeyPair(priv, pub);
    }

    // ─────────────────────────────────────────────
    //  Serialization helpers
    // ─────────────────────────────────────────────

    public static String publicKeyToBase64(X25519PublicKeyParameters pub) {
        return Base64.encodeToString(pub.getEncoded(), Base64.NO_WRAP);
    }

    public static X25519PublicKeyParameters publicKeyFromBase64(String b64) {
        byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
        return new X25519PublicKeyParameters(bytes, 0);
    }

    public static String privateKeyToBase64(X25519PrivateKeyParameters priv) {
        return Base64.encodeToString(priv.getEncoded(), Base64.NO_WRAP);
    }

    public static X25519PrivateKeyParameters privateKeyFromBase64(String b64) {
        byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
        return new X25519PrivateKeyParameters(bytes, 0);
    }

    // ─────────────────────────────────────────────
    //  ECDH + HKDF — Derive Shared Session Key
    // ─────────────────────────────────────────────

    /**
     * Derives a 256-bit AES session key for a 1-to-1 chat.
     * Both peers derive the SAME key from their own private key
     * and the other's public key.
     *
     * @param myPriv    caller's X25519 private key
     * @param peerPub   peer's X25519 public key (from Firebase)
     * @param chatId    unique chat identifier (used as HKDF context to
     *                  prevent cross-chat key reuse)
     */
    public static byte[] deriveSessionKey(
            X25519PrivateKeyParameters myPriv,
            X25519PublicKeyParameters peerPub,
            String chatId) throws Exception {

        // ECDH — produces 32 bytes of shared material
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(myPriv);
        byte[] sharedSecret = new byte[agreement.getAgreementSize()];
        agreement.calculateAgreement(peerPub, sharedSecret, 0);

        // HKDF-Extract + HKDF-Expand
        byte[] info = (Constants.HKDF_INFO + ":" + chatId).getBytes(StandardCharsets.UTF_8);
        byte[] salt = new byte[32]; // zero salt → HKDF uses HMAC(zeros, IKM)
        return hkdf(sharedSecret, salt, info, 32);
    }

    /**
     * Derives a session key from a raw group key (32 random bytes).
     * Used when the group key has already been established and distributed.
     */
    public static byte[] deriveGroupSessionKey(byte[] groupKeyMaterial, String groupId)
            throws Exception {
        byte[] info = (Constants.HKDF_INFO + ":group:" + groupId).getBytes(StandardCharsets.UTF_8);
        byte[] salt = new byte[32];
        return hkdf(groupKeyMaterial, salt, info, 32);
    }

    // ─────────────────────────────────────────────
    //  AES-256-GCM Encrypt / Decrypt
    // ─────────────────────────────────────────────

    /**
     * Encrypts plaintext bytes with AES-256-GCM.
     * A fresh random 96-bit IV is generated for every call.
     *
     * @return EncryptedPayload containing Base64(ciphertext+tag) and Base64(iv)
     */
    public static EncryptedPayload encrypt(byte[] plaintext, byte[] key) throws Exception {
        byte[] iv = new byte[Constants.GCM_IV_BYTES];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(Constants.GCM_TAG_BITS, iv));

        byte[] ciphertext = cipher.doFinal(plaintext);

        return new EncryptedPayload(
                Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                Base64.encodeToString(iv, Base64.NO_WRAP)
        );
    }

    /**
     * Convenience overload for encrypting a String message.
     */
    public static EncryptedPayload encryptString(String plaintext, byte[] key) throws Exception {
        return encrypt(plaintext.getBytes(StandardCharsets.UTF_8), key);
    }

    /**
     * Decrypts an AES-256-GCM ciphertext.
     * Throws an AEADBadTagException (subclass of BadPaddingException)
     * if the data has been tampered with — never silently returns wrong data.
     *
     * @return decrypted plaintext bytes
     */
    public static byte[] decrypt(String ciphertextB64, String ivB64, byte[] key) throws Exception {
        byte[] ciphertext = Base64.decode(ciphertextB64, Base64.NO_WRAP);
        byte[] iv         = Base64.decode(ivB64, Base64.NO_WRAP);

        Cipher cipher = Cipher.getInstance(AES_GCM);
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(Constants.GCM_TAG_BITS, iv));

        return cipher.doFinal(ciphertext);
    }

    /**
     * Convenience overload that returns a decrypted String.
     */
    public static String decryptToString(String ciphertextB64, String ivB64, byte[] key)
            throws Exception {
        return new String(decrypt(ciphertextB64, ivB64, key), StandardCharsets.UTF_8);
    }

    // ─────────────────────────────────────────────
    //  Group Key Wrapping — encrypt a group key for a specific member
    // ─────────────────────────────────────────────

    /**
     * Wraps (encrypts) a group key for delivery to a specific member.
     * Uses an ephemeral X25519 key pair so the wrapping is unique each time.
     *
     * Returns: Base64(ephemeralPubKey_32bytes || ciphertext || tag || iv)
     * packed as a single string for Firebase storage.
     */
    public static String wrapGroupKey(byte[] groupKeyRaw,
                                      X25519PublicKeyParameters memberPubKey,
                                      String groupId) throws Exception {
        // Generate ephemeral key pair
        X25519KeyPair ephemeral = generateKeyPair();

        // ECDH with member's long-term public key
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(ephemeral.privateKey);
        byte[] sharedSecret = new byte[agreement.getAgreementSize()];
        agreement.calculateAgreement(memberPubKey, sharedSecret, 0);

        // Derive wrapping key
        byte[] wrapKey = hkdf(sharedSecret, new byte[32],
                ("GroupKeyWrap:" + groupId).getBytes(StandardCharsets.UTF_8), 32);

        // Encrypt group key
        EncryptedPayload enc = encrypt(groupKeyRaw, wrapKey);

        // Pack: ephemeralPub(32) + ":" + cipher + ":" + iv
        String ephemeralPubB64 = Base64.encodeToString(
                ephemeral.publicKey.getEncoded(), Base64.NO_WRAP);
        return ephemeralPubB64 + ":" + enc.ciphertext + ":" + enc.iv;
    }

    /**
     * Unwraps (decrypts) the group key using the member's own private key.
     */
    public static byte[] unwrapGroupKey(String wrappedKey,
                                        X25519PrivateKeyParameters memberPrivKey,
                                        String groupId) throws Exception {
        String[] parts = wrappedKey.split(":", 3);
        if (parts.length != 3) throw new IllegalArgumentException("Invalid wrapped key format");

        X25519PublicKeyParameters ephemeralPub = publicKeyFromBase64(parts[0]);

        // ECDH with ephemeral public key
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(memberPrivKey);
        byte[] sharedSecret = new byte[agreement.getAgreementSize()];
        agreement.calculateAgreement(ephemeralPub, sharedSecret, 0);

        // Derive wrapping key
        byte[] wrapKey = hkdf(sharedSecret, new byte[32],
                ("GroupKeyWrap:" + groupId).getBytes(StandardCharsets.UTF_8), 32);

        // Decrypt group key
        return decrypt(parts[1], parts[2], wrapKey);
    }

    // ─────────────────────────────────────────────
    //  HKDF-SHA256 (RFC 5869)
    // ─────────────────────────────────────────────

    private static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance(Constants.HKDF_ALGO);

        // Extract
        mac.init(new SecretKeySpec(salt, Constants.HKDF_ALGO));
        byte[] prk = mac.doFinal(ikm);

        // Expand
        mac.init(new SecretKeySpec(prk, Constants.HKDF_ALGO));
        byte[] okm = new byte[length];
        byte[] t   = new byte[0];
        int    pos = 0;
        for (int i = 1; pos < length; i++) {
            mac.update(t);
            mac.update(info);
            mac.update((byte) i);
            t = mac.doFinal();
            int copy = Math.min(t.length, length - pos);
            System.arraycopy(t, 0, okm, pos, copy);
            pos += copy;
        }
        Arrays.fill(prk, (byte) 0); // wipe PRK from memory
        return okm;
    }

    // ─────────────────────────────────────────────
    //  Data classes
    // ─────────────────────────────────────────────

    public static class X25519KeyPair {
        public final X25519PrivateKeyParameters privateKey;
        public final X25519PublicKeyParameters  publicKey;
        X25519KeyPair(X25519PrivateKeyParameters priv, X25519PublicKeyParameters pub) {
            this.privateKey = priv;
            this.publicKey  = pub;
        }
    }

    public static class EncryptedPayload {
        public final String ciphertext; // Base64(AES-GCM ciphertext + 16-byte tag)
        public final String iv;         // Base64(12-byte IV)
        public EncryptedPayload(String ciphertext, String iv) {
            this.ciphertext = ciphertext;
            this.iv = iv;
        }
    }
}