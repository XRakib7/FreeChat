package com.softcraft.freechat;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.softcraft.freechat.firebase.FirebaseManager;
import com.softcraft.freechat.utils.Constants;
import com.softcraft.freechat.utils.SecurePrefs;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        FirebaseManager.init();

        new Handler(Looper.getMainLooper()).postDelayed(this::route, 1200);
    }

    private void route() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        if (user == null) {
            // Not logged in → go to phone auth
            startActivity(new Intent(this, AuthActivity.class));
        } else if (!SecurePrefs.getBoolean(Constants.PREF_PROFILE_DONE, false)) {
            // Logged in but profile not set up
            startActivity(new Intent(this, ProfileSetupActivity.class));
        } else {
            // Fully set up → go to chat list
            startActivity(new Intent(this, MainActivity.class));
        }
        finish();
    }
}