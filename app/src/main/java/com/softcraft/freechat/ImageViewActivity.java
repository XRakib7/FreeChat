package com.softcraft.freechat;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.softcraft.freechat.utils.Constants;

/** Full-screen image viewer. Shows decrypted image bytes as a Bitmap. */
public class ImageViewActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_view);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Photo");
        }

        ImageView imageView = findViewById(R.id.imageView);
        byte[] imgBytes = getIntent().getByteArrayExtra(Constants.EXTRA_IMAGE_BYTES);

        if (imgBytes != null) {
            Bitmap bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.length);
            imageView.setImageBitmap(bmp);
        } else {
            imageView.setImageResource(R.drawable.ic_broken_image);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }
}