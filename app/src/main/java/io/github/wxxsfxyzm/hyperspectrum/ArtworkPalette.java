// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Handler;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Decode privately on the shared worker, cache by pixels, reject obsolete artwork results. */
final class ArtworkPalette {
    interface Listener { void colors(int top, int bottom); }
    private final Consumer<Runnable> worker;
    private final Handler main;
    private final Listener listener;
    private final CaptureProbe.Reporter reporter;
    private final Map<Long, int[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, int[]> entry) { return size() > 8; }
    };
    private Icon requestedIcon;
    private String requestedTrack;
    private long request;
    private boolean closed;

    ArtworkPalette(Consumer<Runnable> worker, Handler main, Listener listener, CaptureProbe.Reporter reporter) {
        this.worker = worker; this.main = main; this.listener = listener; this.reporter = reporter;
    }

    void artwork(Context context, String track, Icon icon) {
        if (closed) return;
        if (Objects.equals(track, requestedTrack) && icon == requestedIcon) return;
        requestedTrack = track; requestedIcon = icon;
        long ticket = ++request;
        worker.accept(() -> {
            int[] colors = {CoverColors.TOP_DEFAULT, CoverColors.BOTTOM_DEFAULT};
            long hash = 0;
            try {
                if (icon != null) {
                    Drawable drawable = icon.loadDrawable(context);
                    if (drawable != null) {
                        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
                        try {
                            drawable.setBounds(0, 0, 48, 48);
                            drawable.draw(new Canvas(bitmap));
                            int[] pixels = new int[48 * 48];
                            bitmap.getPixels(pixels, 0, 48, 0, 0, 48, 48);
                            hash = CoverColors.fingerprint(pixels);
                            int[] cached = cache.get(hash);
                            if (cached == null) { cached = CoverColors.extract(pixels); cache.put(hash, cached); }
                            colors = cached;
                        } finally { bitmap.recycle(); }
                    }
                }
            } catch (RuntimeException failure) { reporter.report("spectrum_palette_error", failure); }
            int[] result = colors; long fingerprint = hash;
            main.post(() -> {
                if (closed || ticket != request) return;
                listener.colors(result[0], result[1]);
                reporter.report("spectrum_palette cover=" + Long.toHexString(fingerprint)
                        + " top=" + Integer.toHexString(result[0]) + " bottom=" + Integer.toHexString(result[1]), null);
            });
        });
    }

    void close() { closed = true; request++; requestedIcon = null; }
}
