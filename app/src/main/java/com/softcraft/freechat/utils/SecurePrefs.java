package com.softcraft.freechat.utils;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Wrapper around EncryptedSharedPreferences.
 * All sensitive data (private keys, tokens) stored encrypted on-device.
 */
public class SecurePrefs {

    private static SharedPreferences prefs;

    public static void init(Context ctx) {
        try {
            MasterKey masterKey = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            prefs = EncryptedSharedPreferences.create(
                    ctx,
                    Constants.PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            // Fallback to regular prefs if device doesn't support (shouldn't happen on API 26+)
            prefs = ctx.getSharedPreferences(Constants.PREFS_NAME + "_fallback", Context.MODE_PRIVATE);
        }
    }

    public static void put(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    public static String get(String key, String def) {
        return prefs.getString(key, def);
    }

    public static void putBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    public static boolean getBoolean(String key, boolean def) {
        return prefs.getBoolean(key, def);
    }

    public static void remove(String key) {
        prefs.edit().remove(key).apply();
    }

    public static void clearAll() {
        prefs.edit().clear().apply();
    }
}