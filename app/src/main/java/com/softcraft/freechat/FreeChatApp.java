package com.softcraft.freechat;

import android.app.Application;
import android.util.Log;

import com.google.firebase.database.FirebaseDatabase;
import com.softcraft.freechat.crypto.KeyManager;
import com.softcraft.freechat.utils.SecurePrefs;

public class FreeChatApp extends Application {

    private static final String TAG = "FreeChatApp";

    @Override
    public void onCreate() {
        super.onCreate();

        // Enable Firebase offline persistence (messages queued when offline)
        FirebaseDatabase.getInstance().setPersistenceEnabled(true);

        // Initialize encrypted local storage and crypto keys
        KeyManager.init(this);

        Log.d(TAG, "FreeChatApp initialized. Public key: "
                + KeyManager.getMyPublicKeyBase64().substring(0, 8) + "...");
    }
}