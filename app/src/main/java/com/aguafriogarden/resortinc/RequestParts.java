package com.aguafriogarden.resortinc;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

/**
 * Builds upload-ready request parts for the profile screens and the sign-up wizard: photos are
 * downscaled, rotated upright and re-encoded as JPEG so multi-megabyte camera pictures don't time
 * out a slow connection.
 */
final class RequestParts {

    /** Longest edge, in pixels, of an uploaded photo; phone cameras produce far more than the server needs. */
    private static final int UPLOAD_MAX_EDGE_PX = 1600;
    private static final int UPLOAD_JPEG_QUALITY = 80;

    private RequestParts() {
    }

    static RequestBody text(String value) {
        return RequestBody.create(value != null ? value : "", MediaType.parse("text/plain"));
    }

    /** Null when there is nothing to send, so a missing photo is simply left out of the request. */
    static MultipartBody.Part file(String fieldName, String filename, RequestBody body) {
        return body != null ? MultipartBody.Part.createFormData(fieldName, filename, body) : null;
    }

    /**
     * The picked gallery image (uri) or an already-decoded bitmap as scaled JPEG bytes, or null when
     * neither can be read. A uri wins over the bitmap.
     */
    static byte[] jpegBytes(ContentResolver resolver, Uri uri, Bitmap bitmap) {
        try {
            Bitmap source = bitmap;
            if (uri != null) {
                source = decodeScaled(resolver, uri);
            }
            if (source == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaleDown(source).compress(Bitmap.CompressFormat.JPEG, UPLOAD_JPEG_QUALITY, out);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static RequestBody jpegBody(ContentResolver resolver, Uri uri, Bitmap bitmap) {
        byte[] jpeg = jpegBytes(resolver, uri, bitmap);
        return jpeg != null ? jpegBody(jpeg) : null;
    }

    static RequestBody jpegBody(byte[] jpeg) {
        return RequestBody.create(jpeg, MediaType.parse("image/jpeg"));
    }

    /** Decodes a gallery image at reduced size (never loading the full-resolution bitmap) and applies its EXIF rotation. */
    static Bitmap decodeScaled(ContentResolver resolver, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            BitmapFactory.decodeStream(in, null, bounds);
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (opts.inSampleSize * 2) >= UPLOAD_MAX_EDGE_PX) {
            opts.inSampleSize *= 2;
        }
        Bitmap decoded;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            decoded = BitmapFactory.decodeStream(in, null, opts);
        }
        if (decoded == null) {
            return null;
        }

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in != null) {
                orientation = new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
            default: return decoded;
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
    }

    private static Bitmap scaleDown(Bitmap src) {
        int longest = Math.max(src.getWidth(), src.getHeight());
        if (longest <= UPLOAD_MAX_EDGE_PX) {
            return src;
        }
        float ratio = (float) UPLOAD_MAX_EDGE_PX / longest;
        return Bitmap.createScaledBitmap(src,
                Math.round(src.getWidth() * ratio), Math.round(src.getHeight() * ratio), true);
    }
}
