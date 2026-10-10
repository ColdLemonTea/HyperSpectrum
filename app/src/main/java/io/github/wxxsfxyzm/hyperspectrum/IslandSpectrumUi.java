// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Main-looper owner of every replacement View and subscription. */
final class IslandSpectrumUi {
    private static final class Slot {
        final Object holder; final View original; final SpectrumView view;
        boolean shown = true;
        Slot(Object holder, View original, SpectrumView view) {
            this.holder = holder; this.original = original; this.view = view;
        }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CaptureProbe.Reporter reporter;
    private final Map<Object, Slot> slots = new IdentityHashMap<>();
    private final Set<SpectrumView> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private final LiveSpectrum engine;
    private final ArtworkPalette palette;
    private volatile boolean closed;
    private volatile Object[][] reloadState;
    private boolean playing, interactive = true;
    private boolean enabled = SpectrumPreferences.DEFAULT_ENABLED;
    private String key;
    private Context context;
    private boolean screenRegistered;
    private int[] coverColors = CoverColors.fallback();
    private boolean originalColors, originalSeeded;
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            interactive = Intent.ACTION_SCREEN_ON.equals(intent.getAction()); demand();
        }
    };

    IslandSpectrumUi(CaptureProbe.Reporter reporter) {
        this.reporter = reporter;
        engine = new LiveSpectrum(reporter, main, (levels, time, healthy) -> {
            if (!closed) for (SpectrumView view : new ArrayList<>(active)) view.frame(levels, time, healthy);
        });
        palette = new ArtworkPalette(engine::execute, main, (colors, original) -> {
            coverColors = colors;
            originalColors = original;
            for (Slot slot : slots.values()) slot.view.colors(colors, original);
        }, reporter);
    }

    private void main(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) { if (!closed) action.run(); }
        else main.post(() -> { if (!closed) action.run(); });
    }

    void enabled(boolean value) {
        main(() -> {
            if (enabled == value) return;
            enabled = value;
            for (Slot slot : slots.values()) {
                if (value) slot.view.allow(slot.shown);
                else slot.view.disable();
            }
            demand();
            reporter.report("spectrum_enabled value=" + value + " slots=" + slots.size(), null);
        });
    }

    void colorScheme(String value) { main(() -> palette.scheme(value)); }

    void originalColors(long cover, int top, int bottom) {
        main(() -> palette.original(cover, top, bottom));
    }

    void stopWhenMuted(boolean value) { engine.stopWhenMuted(value); }

    void media(Context ctx, String notificationKey, Boolean isPlaying, String route,
               String packageName, int userId, String track, Icon artwork) {
        main(() -> {
            if (context == null) {
                context = ctx;
                engine.audio(ctx);
                PowerManager power = ctx.getSystemService(PowerManager.class);
                interactive = power == null || power.isInteractive();
                IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                try {
                    ctx.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED);
                    screenRegistered = true;
                } catch (RuntimeException failure) {
                    reporter.report("spectrum_screen_receiver_unavailable", failure);
                }
            }
            key = notificationKey;
            playing = Boolean.TRUE.equals(isPlaying)
                    && (route.isEmpty() || route.equals("local") || route.equals("headset"));
            engine.media(packageName, userId);
            palette.artwork(ctx, track, artwork);
            demand();
        });
    }

    void removed(String removedKey) {
        main(() -> { if (java.util.Objects.equals(key, removedKey)) { playing = false; demand(); } });
    }

    void holder(Object holder, String event) {
        main(() -> apply(holder, event));
    }

    private void apply(Object holder, String event) {
        try {
            seedOriginalColors(holder);
            Slot slot = slots.get(holder);
            if (event.equals("onDetach")) { remove(holder, false); return; }
            if (event.equals("onHidden")) {
                if (slot != null) { slot.shown = false; slot.view.allow(false); }
                return;
            }
            Object info = field(holder, "picInfo");
            String pic = info == null ? "" : String.valueOf(info.getClass().getMethod("getPic").invoke(info));
            if (!pic.equals("musicWave") && !pic.equals("musicPause")) { remove(holder, false); return; }
            if (slot == null && event.equals("animation")) return;
            if (slot == null) {
                FrameLayout container = (FrameLayout) field(holder, "iconContainer");
                View original = (View) field(holder, "lottieView");
                if (container == null || original == null || original.getParent() != container) return;
                SpectrumView view = new SpectrumView(container.getContext(), original,
                        (v, enabled) -> { if (enabled) active.add(v); else active.remove(v); demand(); }, reporter);
                slot = new Slot(holder, original, view);
                slots.put(holder, slot);
                view.colors(coverColors, originalColors);
                view.playing(pic.equals("musicWave"));
                container.addView(view, new FrameLayout.LayoutParams((FrameLayout.LayoutParams) original.getLayoutParams()));
                reporter.report("spectrum_view_install holder=" + System.identityHashCode(holder)
                        + " slots=" + slots.size(), null);
            }
            if (event.equals("onShow")) slot.shown = true;
            slot.view.playing(pic.equals("musicWave"));
            slot.view.allow(enabled && slot.shown);
            slot.view.suppressOriginal(); // Also catches the end of asynchronous Lottie composition.
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reporter.report("spectrum_view_error stage=bind", failure);
            remove(holder, true);
        }
    }

    private void seedOriginalColors(Object holder) {
        if (originalSeeded) return;
        try {
            Bundle bundle = (Bundle) holder.getClass().getMethod("getBitmapBundle").invoke(holder);
            Icon album = bundle == null ? null : bundle.getParcelable("miui_media_album_icon", Icon.class);
            if (album == null) return;
            int top = ((Number) field(holder, "gradientTopColor")).intValue();
            int bottom = ((Number) field(holder, "gradientBottomColor")).intValue();
            if ((top >>> 24) != 153 || (bottom >>> 24) != 153) return;
            originalSeeded = true;
            Context ctx = (Context) holder.getClass().getMethod("getContext").invoke(holder);
            engine.execute(() -> {
                try {
                    android.graphics.drawable.Drawable drawable = album.loadDrawable(ctx);
                    if (drawable == null) return;
                    long cover = OriginalArtwork.fingerprint(drawable);
                    originalColors(cover, top, bottom);
                    reporter.report("spectrum_original_backfill cover=" + cover + " top=" + Integer.toHexString(top)
                            + " bottom=" + Integer.toHexString(bottom), null);
                } catch (RuntimeException failure) { reporter.report("spectrum_original_backfill_error", failure); }
            });
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reporter.report("spectrum_original_backfill_unavailable", failure);
        }
    }

    private void demand() {
        if (!closed) {
            int count = 0;
            for (SpectrumView view : active) if (view.wantsAudio()) count++;
            engine.demand(enabled && playing && interactive && count > 0, count);
        }
    }

    private void remove(Object holder, boolean restore) {
        Slot slot = slots.remove(holder);
        if (slot == null) return;
        slot.view.dispose(restore);
        if (slot.view.getParent() instanceof FrameLayout parent) parent.removeView(slot.view);
        reporter.report("spectrum_view_remove holder=" + System.identityHashCode(holder)
                + " slots=" + slots.size(), null);
    }

    static Object field(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(object); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    /** Return only host holders/booleans, after removing every old-generation View/listener. */
    Object[][] prepareReload() {
        if (reloadState != null) return reloadState;
        AtomicReference<Object[][]> snapshot = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Runnable cleanup = () -> {
            try {
                Object[][] state = new Object[slots.size()][2]; int index = 0;
                for (Slot slot : slots.values()) state[index++] = new Object[]{slot.holder, slot.shown};
                closed = true;
                palette.close();
                if (screenRegistered) {
                    try { context.unregisterReceiver(screen); }
                    catch (RuntimeException failure) { reporter.report("spectrum_screen_unregister_error", failure); }
                    screenRegistered = false;
                }
                engine.close();
                for (Object holder : slots.keySet().toArray()) remove(holder, true);
                active.clear(); main.removeCallbacksAndMessages(null);
                reloadState = state; snapshot.set(state);
            } catch (RuntimeException failure) {
                reporter.report("spectrum_reload_cleanup_error", failure);
            } finally { done.countDown(); }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run();
        else {
            main.post(cleanup);
            try { if (!done.await(2, TimeUnit.SECONDS)) return null; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
        }
        return snapshot.get();
    }

    void restore(Object[][] state) {
        main(() -> {
            for (Object[] entry : state) {
                if (entry.length < 2) continue;
                apply(entry[0], "bind");
                if (Boolean.FALSE.equals(entry[1])) apply(entry[0], "onHidden");
            }
            reporter.report("spectrum_views_restored slots=" + slots.size(), null);
        });
    }
}
