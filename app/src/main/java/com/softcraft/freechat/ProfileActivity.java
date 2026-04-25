package com.softcraft.freechat;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.firebase.auth.FirebaseAuth;
import com.softcraft.freechat.firebase.FirebaseManager;
import com.softcraft.freechat.model.User;
import com.softcraft.freechat.utils.Constants;
import com.softcraft.freechat.utils.ImageUtils;
import com.softcraft.freechat.utils.SecurePrefs;

/** View and edit your own profile. */
public class ProfileActivity extends AppCompatActivity {

    private ImageView ivAvatar;
    private EditText  etName, etAbout;
    private Button    btnSave;
    private String    myUid;
    private String    avatarB64;

    private final ActivityResultLauncher<Intent> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    try {
                        byte[] compressed = ImageUtils.bitmapToJpeg(
                                BitmapFactory.decodeStream(getContentResolver().openInputStream(uri)),
                                Constants.AVATAR_MAX_PX, Constants.AVATAR_QUALITY);
                        avatarB64 = Base64.encodeToString(compressed, Base64.NO_WRAP);
                        ivAvatar.setImageBitmap(BitmapFactory.decodeByteArray(compressed, 0, compressed.length));
                    } catch (Exception e) {
                        Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Profile");
        }

        ivAvatar = findViewById(R.id.ivAvatar);
        etName   = findViewById(R.id.etName);
        etAbout  = findViewById(R.id.etAbout);
        btnSave  = findViewById(R.id.btnSave);

        ivAvatar.setOnClickListener(v ->
                pickImageLauncher.launch(new Intent(Intent.ACTION_PICK,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI)));

        btnSave.setOnClickListener(v -> saveChanges());

        loadMyProfile();
    }

    private void loadMyProfile() {
        FirebaseManager.getUser(myUid, new FirebaseManager.Callback<User>() {
            @Override public void onSuccess(User user) {
                etName.setText(user.displayName);
                etAbout.setText(user.about);
                if (user.avatarB64 != null && !user.avatarB64.isEmpty()) {
                    byte[] b = Base64.decode(user.avatarB64, Base64.NO_WRAP);
                    ivAvatar.setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.length));
                    avatarB64 = user.avatarB64;
                }
            }
            @Override public void onError(String error) {}
        });
    }

    private void saveChanges() {
        String name  = etName.getText().toString().trim();
        String about = etAbout.getText().toString().trim();
        if (TextUtils.isEmpty(name)) { etName.setError("Name required"); return; }

        FirebaseManager.updateDisplayName(myUid, name);
        FirebaseManager.updateAbout(myUid, about);
        SecurePrefs.put(Constants.PREF_DISPLAY_NAME, name);

        if (avatarB64 != null) {
            FirebaseManager.updateAvatar(myUid, avatarB64, new FirebaseManager.Callback<Void>() {
                @Override public void onSuccess(Void r) {}
                @Override public void onError(String e) {}
            });
        }
        Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show();
    }

    @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }
}