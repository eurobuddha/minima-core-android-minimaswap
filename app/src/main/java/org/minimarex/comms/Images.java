package org.minimarex.comms;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Shrink a picked image to a JPEG small enough to ride (sealed) in a coin's state[99]. Ported from the
 * pocketShop web shrinker: scale to a max dimension, then step JPEG quality down until it fits the byte
 * budget; if it still won't fit, drop the dimension and retry. The budget is deliberately small because
 * the sealed+encoded blob inflates ~2.6x over the raw JPEG and the whole TxPoW must stay under 64 KB.
 */
public final class Images {

    public static byte[] compressToFit(Context ctx, Uri uri, int targetBytes) {
        try {
            Bitmap bmp = decodeSampled(ctx, uri, 1600);
            if (bmp == null) return null;
            int maxDim = 900;
            for (int attempt = 0; attempt < 6; attempt++) {
                Bitmap scaled = scaleToMax(bmp, maxDim);
                int quality = 80;
                while (true) {
                    byte[] jpeg = jpeg(scaled, quality);
                    if (jpeg.length <= targetBytes) return jpeg;
                    if (quality <= 25) break;        // too big even at low quality → shrink the dimension
                    quality -= 12;
                }
                maxDim = (int) (maxDim * 0.7);
                if (maxDim < 200) return jpeg(scaleToMax(bmp, 200), 30);   // last resort
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] jpeg(Bitmap b, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }

    private static Bitmap decodeSampled(Context ctx, Uri uri, int reqMax) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(is, null, o);
        }
        int sample = 1, w = o.outWidth, h = o.outHeight;
        while (w / sample > reqMax || h / sample > reqMax) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(is, null, o2);
        }
    }

    private static Bitmap scaleToMax(Bitmap b, int maxDim) {
        int w = b.getWidth(), h = b.getHeight();
        if (w <= maxDim && h <= maxDim) return b;
        float r = (w >= h) ? (float) maxDim / w : (float) maxDim / h;
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * r)), Math.max(1, Math.round(h * r)), true);
    }

    private Images() {}
}
