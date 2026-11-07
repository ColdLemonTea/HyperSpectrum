// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/** Match the host's <=64px DrawableKt bitmap conversion using private pixel buffers. */
public final class OriginalArtwork {
    public static long fingerprint(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        return CoverColors.fingerprint(pixels) ^ ((long) width << 32) ^ height;
    }

    static long fingerprint(Drawable drawable) {
        int width = dimension(drawable.getIntrinsicWidth()), height = dimension(drawable.getIntrinsicHeight());
        Bitmap bitmap;
        boolean recycle;
        if (drawable instanceof BitmapDrawable bitmapDrawable) {
            Bitmap source = bitmapDrawable.getBitmap();
            bitmap = Bitmap.createScaledBitmap(source, width, height, true);
            recycle = bitmap != source;
        } else {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            recycle = true;
            Rect bounds = new Rect(drawable.getBounds());
            try {
                drawable.setBounds(0, 0, width, height);
                drawable.draw(new Canvas(bitmap));
            } finally { drawable.setBounds(bounds); }
        }
        try { return fingerprint(bitmap); }
        finally { if (recycle) bitmap.recycle(); }
    }

    private static int dimension(int size) { return size >= 1 && size <= 64 ? size : 64; }
    private OriginalArtwork() { }
}
