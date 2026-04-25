package com.softcraft.freechat;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.softcraft.freechat.crypto.KeyManager;
import com.softcraft.freechat.firebase.FirebaseManager;
import com.softcraft.freechat.model.User;
import com.softcraft.freechat.utils.Constants;
import com.softcraft.freechat.utils.ImageUtils;
import com.softcraft.freechat.utils.SecurePrefs;

import android.util.Base64;
import android.view.View;

public class ProfileSetupActivity extends AppCompatActivity {

    private ImageView ivAvatar;
    private EditText  etName;
    private Button    btnSave;
    private ProgressBar progressBar;

    private String avatarB64 = null;

    private final ActivityResultLauncher<Intent> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    try {
                        byte[] compressed = ImageUtils.bitmapToJpeg(
                                ImageUtils.bytesToBitmap(
                                        readBytes(uri)),
                                Constants.AVATAR_MAX_PX,
                                Constants.AVATAR_QUALITY);
                        avatarB64 = Base64.encodeToString(compressed, Base64.NO_WRAP);
                        ivAvatar.setImageBitmap(ImageUtils.bytesToBitmap(compressed));
                    } catch (Exception e) {
                        Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_setup);

        ivAvatar    = findViewById(R.id.ivAvatar);
        etName      = findViewById(R.id.etDisplayName);
        btnSave     = findViewById(R.id.btnSave);
        progressBar = findViewById(R.id.progressBar);

        ivAvatar.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            pickImageLauncher.launch(i);
        });

        btnSave.setOnClickListener(v -> saveProfile());
    }

    private void saveProfile() {
        String name = etName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            etName.setError("Enter your name");
            return;
        }
        if (name.length() < 2) {
            etName.setError("Name too short");
            return;
        }

        setLoading(true);

        String uid   = FirebaseAuth.getInstance().getCurrentUser().getUid();
        String phone = SecurePrefs.get(Constants.PREF_PHONE, "");
        String pubKey = KeyManager.getMyPublicKeyBase64();

        User user = new User(uid, phone, name, pubKey);
        if (avatarB64 != null) user.avatarB64 = avatarB64;

        FirebaseManager.saveUser(user, new FirebaseManager.Callback<Void>() {
            @Override public void onSuccess(Void result) {
                SecurePrefs.put(Constants.PREF_DISPLAY_NAME, name);
                SecurePrefs.putBoolean(Constants.PREF_PROFILE_DONE, true);
                setLoading(false);
                startActivity(new Intent(ProfileSetupActivity.this, MainActivity.class));
                finish();
            }
            @Override public void onError(String error) {
                setLoading(false);
                Toast.makeText(ProfileSetupActivity.this,
                        "Failed to save profile: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private byte[] readBytes(Uri uri) throws Exception {
        try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnSave.setEnabled(!loading);
    }
}