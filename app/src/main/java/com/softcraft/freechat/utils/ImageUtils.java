package com.softcraft.freechat.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Image compression and encoding utilities.
 *
 * WHY BASE64 IN FIREBASE RTDB?
 * Firebase Storage is not available on the free (Spark) plan's restrictions
 * we want to avoid. Realtime Database accepts strings, so we:
 *   1. Scale the image down to max 1024px
 *   2. Compress as JPEG (quality 75)
 *   3. Encrypt the JPEG bytes with AES-256-GCM
 *   4. Base64-encode the encrypted bytes → store as a string in RTDB
 *
 * A 1024px JPEG at quality 75 is typically 80–200 KB.
 * Base64 overhead ≈ 33%, so 107–267 KB stored per image.
 * Firebase RTDB free plan: 1 GB storage, 10 GB/month download.
 * This is suitable for moderate usage.
 */
public class ImageUtils {

    private static final String TAG = "ImageUtils";

    /**
     * Reads a URI into a compressed JPEG byte array ready for encryption.
     * Handles EXIF rotation so images always appear upright.
     */
    public static byte[] uriToCompressedJpeg(Context ctx, Uri uri) throws IOException {
        // Decode bounds first
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, opts);
        }

        // Calculate inSampleSize
        opts.inSampleSize    = calculateInSampleSize(opts, Constants.IMAGE_MAX_PX, Constants.IMAGE_MAX_PX);
        opts.inJustDecodeBounds = false;

        Bitmap bitmap;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(is, null, opts);
        }
        if (bitmap == null) throw new IOException("Failed to decode image");

        // Fix EXIF rotation
        bitmap = fixRotation(ctx, uri, bitmap);

        // Scale down further if still too large
        bitmap = scaleBitmap(bitmap, Constants.IMAGE_MAX_PX);

        // Compress to JPEG
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, Constants.IMAGE_QUALITY, baos);
        bitmap.recycle();

        byte[] result = baos.toByteArray();
        Log.d(TAG, "Compressed image: " + result.length + " bytes");
        return result;
    }

    /**
     * Decodes a byte array to a Bitmap (for display after decryption).
     */
    public static Bitmap bytesToBitmap(byte[] data) {
        return BitmapFactory.decodeByteArray(data, 0, data.length);
    }

    /**
     * Compresses a Bitmap to JPEG bytes for avatar upload.
     */
    public static byte[] bitmapToJpeg(Bitmap bitmap, int maxPx, int quality) {
        bitmap = scaleBitmap(bitmap, maxPx);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
        return baos.toByteArray();
    }

    // ─────────────────────────────────────────────
    //  Internal helpers
    // ─────────────────────────────────────────────

    private static int calculateInSampleSize(BitmapFactory.Options opts, int reqW, int reqH) {
        int h = opts.outHeight, w = opts.outWidth;
        int inSampleSize = 1;
        if (h > reqH || w > reqW) {
            int halfH = h / 2, halfW = w / 2;
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private static Bitmap scaleBitmap(Bitmap src, int maxPx) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxPx && h <= maxPx) return src;
        float scale = Math.min((float) maxPx / w, (float) maxPx / h);
        Bitmap scaled = Bitmap.createScaledBitmap(src, (int)(w*scale), (int)(h*scale), true);
        src.recycle();
        return scaled;
    }

    private static Bitmap fixRotation(Context ctx, Uri uri, Bitmap bitmap) {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) return bitmap;
            ExifInterface exif = new ExifInterface(is);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int degrees = 0;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:  degrees = 90;  break;
                case ExifInterface.ORIENTATION_ROTATE_180: degrees = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: degrees = 270; break;
            }
            if (degrees != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(degrees);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                        bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                bitmap.recycle();
                return rotated;
            }
        } catch (Exception e) {
            Log.w(TAG, "Exif rotation fix failed: " + e.getMessage());
        }
        return bitmap;
    }
}