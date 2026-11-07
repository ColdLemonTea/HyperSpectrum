// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum.render;

import io.github.wxxsfxyzm.hyperspectrum.BuildConfig;
import io.github.wxxsfxyzm.hyperspectrum.audio.CaptureProbe;
import io.github.wxxsfxyzm.hyperspectrum.config.SpectrumPreferences;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Rect;
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
            int[] colors = BuildConfig.NATIVE_CAPTURE ? AppleArtwork.fallback() : CoverColors.fallback();
            long hash = 0;
            Long hostHash = null;
            try {
                if (icon != null) {
                    Drawable drawable = icon.loadDrawable(context);
                    if (drawable != null) {
                        if (!BuildConfig.NATIVE_CAPTURE) hostHash = OriginalArtwork.fingerprint(drawable);
                        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
                        Rect savedBounds = new Rect(drawable.getBounds());
                        try {
                            drawable.setBounds(0, 0, 48, 48);
                            drawable.draw(new Canvas(bitmap));
                            int[] pixels = new int[48 * 48];
                            bitmap.getPixels(pixels, 0, 48, 0, 0, 48, 48);
                            AppleArtwork.Analysis analysis = BuildConfig.NATIVE_CAPTURE ? analyzeArtwork(drawable) : null;
                            hash = analysis == null ? CoverColors.fingerprint(pixels) : analysis.fingerprint();
                            String cacheKey = Long.toHexString(hash) + ":" + (BuildConfig.NATIVE_CAPTURE
                                    ? "mac_artwork_v2:" + Long.toHexString(CoverColors.fingerprint(pixels)) : requestedScheme);
                            int[] cached = cache.get(cacheKey);
                            if (cached == null) {
                                cached = BuildConfig.NATIVE_CAPTURE ? AppleArtwork.field(pixels, analysis.treatment()) : SpectrumPreferences.HYPEROS.equals(requestedScheme)
                                        ? CoverColors.hyperOsField(pixels) : CoverColors.field(pixels, 48, 48);
                                cache.put(cacheKey, cached);
                            }
                            colors = cached;
                        } finally { drawable.setBounds(savedBounds); bitmap.recycle(); }
                    }
                }
            } catch (RuntimeException failure) { reporter.report("spectrum_palette_error", failure); }
            int[] result = colors; long fingerprint = hash; Long originalFingerprint = hostHash;
            main.post(() -> {
                if (closed || ticket != request) return;
                originalCover = originalFingerprint;
                if (!BuildConfig.NATIVE_CAPTURE && SpectrumPreferences.ORIGINAL.equals(requestedScheme)) {
                    int[] host = originalColors.get(originalCover);
                    listener.colors(host == null ? CoverColors.gradient(0xffffffff, 0xffffffff) : host.clone(), true);
                    reporter.report("spectrum_original_match cover=" + originalCover + " available=" + (host != null), null);
                    return;
                }
                listener.colors(result.clone(), false);
                reporter.report("spectrum_palette cover=" + Long.toHexString(fingerprint)
                        + " field=" + (BuildConfig.NATIVE_CAPTURE ? "48x48 treatment=mac_artwork" : "12x12 sampling=4x4")
                        + " colors=" + CoverColors.distinct(result) + " mapping=full_spectrum", null);
            });
        });
    }

    private static AppleArtwork.Analysis analyzeArtwork(Drawable drawable) {
        Bitmap privateBitmap;
        if (drawable instanceof BitmapDrawable image &&
                (long) image.getBitmap().getWidth() * image.getBitmap().getHeight() <= 1024 * 1024) {
            // Snapshot the full source once, including hardware-backed covers.
            // Never read a moving host bitmap in pieces or recycle its storage.
            privateBitmap = image.getBitmap().copy(Bitmap.Config.ARGB_8888, false);
            if (privateBitmap == null) throw new IllegalStateException("Artwork copy failed");
        } else {
            int width = Math.max(1, drawable.getIntrinsicWidth()), height = Math.max(1, drawable.getIntrinsicHeight());
            double scale = Math.min(1.0, 1024.0 / Math.max(width, height));
            width = Math.max(1, (int) Math.round(width * scale)); height = Math.max(1, (int) Math.round(height * scale));
            privateBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Rect bounds = new Rect(drawable.getBounds());
            try {
                drawable.setBounds(0, 0, width, height); drawable.draw(new Canvas(privateBitmap));
            } catch (RuntimeException failure) {
                privateBitmap.recycle(); throw failure;
            } finally { drawable.setBounds(bounds); }
        }
        try {
            int width = privateBitmap.getWidth(), height = privateBitmap.getHeight();
            int rowCount = Math.min(32, height);
            int[] rows = new int[width * rowCount];
            AppleArtwork.Analysis analysis = new AppleArtwork.Analysis();
            for (int y = 0; y < height; y += rowCount) {
                int count = Math.min(rowCount, height - y);
                privateBitmap.getPixels(rows, 0, width, 0, y, width, count);
                analysis.add(rows, width * count);
            }
            return analysis;
        } finally { privateBitmap.recycle(); }
    }

    void original(long cover, int top, int bottom) {
        if (closed || BuildConfig.NATIVE_CAPTURE) return;
        int[] colors = CoverColors.gradient(top, bottom);
        originalColors.put(cover, colors);
        if (!BuildConfig.NATIVE_CAPTURE && SpectrumPreferences.ORIGINAL.equals(scheme) && Objects.equals(originalCover, cover)) {
            listener.colors(colors.clone(), true);
            reporter.report("spectrum_original_match cover=" + cover + " available=true", null);
        }
    }

    void scheme(String value) {
        if (BuildConfig.NATIVE_CAPTURE) return;
        String next = SpectrumPreferences.colorScheme(value);
        if (scheme.equals(next)) return;
        scheme = next;
        if (requestedContext != null) {
            artwork(requestedContext, requestedTrack, requestedIcon, true);
        }
    }

    void close() { closed = true; request++; requestedIcon = null; requestedContext = null; originalColors.clear(); }
}
