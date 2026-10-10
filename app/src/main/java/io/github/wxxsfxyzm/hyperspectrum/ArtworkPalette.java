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
    interface Listener { void colors(int[] field, boolean original); }
    private final Consumer<Runnable> worker;
    private final Handler main;
    private final Listener listener;
    private final CaptureProbe.Reporter reporter;
    private final Map<String, int[]> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, int[]> entry) { return size() > 8; }
    };
    private Icon requestedIcon;
    private String requestedTrack;
    private Context requestedContext;
    private String scheme = SpectrumPreferences.DEFAULT_COLOR_SCHEME;
    private long request;
    private boolean closed;
    // Main-looper owned: only the official host's computed colors, never a second MiPalette call.
    private final Map<Long, int[]> originalColors = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, int[]> entry) { return size() > 8; }
    };
    private Long originalCover;

    ArtworkPalette(Consumer<Runnable> worker, Handler main, Listener listener, CaptureProbe.Reporter reporter) {
        this.worker = worker; this.main = main; this.listener = listener; this.reporter = reporter;
    }

    void artwork(Context context, String track, Icon icon) {
        artwork(context, track, icon, false);
    }

    private void artwork(Context context, String track, Icon icon, boolean force) {
        if (closed) return;
        if (!force && Objects.equals(track, requestedTrack) && icon == requestedIcon) return;
        requestedTrack = track; requestedIcon = icon; requestedContext = context;
        String requestedScheme = scheme;
        long ticket = ++request;
        originalCover = null;
        worker.accept(() -> {
            int[] colors = CoverColors.fallback();
            long hash = 0;
            Long hostHash = null;
            try {
                if (icon != null) {
                    Drawable drawable = icon.loadDrawable(context);
                    if (drawable != null) {
                        hostHash = OriginalArtwork.fingerprint(drawable);
                        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
                        try {
                            drawable.setBounds(0, 0, 48, 48);
                            drawable.draw(new Canvas(bitmap));
                            int[] pixels = new int[48 * 48];
                            bitmap.getPixels(pixels, 0, 48, 0, 0, 48, 48);
                            hash = CoverColors.fingerprint(pixels);
                            String cacheKey = Long.toHexString(hash) + ":" + requestedScheme;
                            int[] cached = cache.get(cacheKey);
                            if (cached == null) {
                                cached = SpectrumPreferences.HYPEROS.equals(requestedScheme)
                                        ? CoverColors.hyperOsField(pixels) : CoverColors.field(pixels, 48, 48);
                                cache.put(cacheKey, cached);
                            }
                            colors = cached;
                        } finally { bitmap.recycle(); }
                    }
                }
            } catch (RuntimeException failure) { reporter.report("spectrum_palette_error", failure); }
            int[] result = colors; long fingerprint = hash; Long originalFingerprint = hostHash;
            main.post(() -> {
                if (closed || ticket != request) return;
                originalCover = originalFingerprint;
                if (SpectrumPreferences.ORIGINAL.equals(requestedScheme)) {
                    int[] host = originalColors.get(originalCover);
                    listener.colors(host == null ? CoverColors.gradient(0xffffffff, 0xffffffff) : host.clone(), true);
                    reporter.report("spectrum_original_match cover=" + originalCover + " available=" + (host != null), null);
                    return;
                }
                listener.colors(result.clone(), false);
                reporter.report("spectrum_palette cover=" + Long.toHexString(fingerprint)
                        + " field=" + CoverColors.SIZE + "x" + CoverColors.SIZE
                        + " sampling=" + CoverColors.SAMPLE_SIZE + "x" + CoverColors.SAMPLE_SIZE
                        + " colors=" + CoverColors.distinct(result) + " mapping=full_spectrum", null);
            });
        });
    }

    void original(long cover, int top, int bottom) {
        if (closed) return;
        int[] colors = CoverColors.gradient(top, bottom);
        originalColors.put(cover, colors);
        if (SpectrumPreferences.ORIGINAL.equals(scheme) && Objects.equals(originalCover, cover)) {
            listener.colors(colors.clone(), true);
            reporter.report("spectrum_original_match cover=" + cover + " available=true", null);
        }
    }

    void scheme(String value) {
        String next = SpectrumPreferences.colorScheme(value);
        if (scheme.equals(next)) return;
        scheme = next;
        if (requestedContext != null) {
            artwork(requestedContext, requestedTrack, requestedIcon, true);
        }
    }

    void close() { closed = true; request++; requestedIcon = null; requestedContext = null; originalColors.clear(); }
}
