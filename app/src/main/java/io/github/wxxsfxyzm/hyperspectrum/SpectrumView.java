// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.LinearGradient;
import android.os.SystemClock;
import android.view.View;
import java.lang.reflect.Method;
import java.util.Arrays;

/** The same six bars persist through pause/resume, with a cover-scoped palette. */
final class SpectrumView extends View {
    interface Lifecycle { void active(SpectrumView view, boolean active); }
    private final Lifecycle lifecycle;
    private final CaptureProbe.Reporter reporter;
    private final View original;
    private final Method cancelAnimation, playAnimation;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SpectrumMotion motion = new SpectrumMotion();
    private boolean allowed, aggregatedVisible, subscribed, ownsOriginal;
    private boolean disposed, drew, fallback = true, pauseSettled;
    private long lastDraw, lastData, awaitingSince;
    private final int[] colors = CoverColors.fallback();
    private final int[] fromColors = colors.clone(), targetColors = colors.clone();
    private final Bitmap colorBitmap = Bitmap.createBitmap(CoverColors.SIZE, CoverColors.SIZE, Bitmap.Config.ARGB_8888);
    private Shader colorShader;
    private boolean originalColors;
    private final Matrix originalBounds = new Matrix();
    private final Matrix colorBounds = new Matrix();
    private boolean paletteDirty = true;
    private long colorStart;
    private final Runnable stale = this::stale;

    SpectrumView(Context context, View original, Lifecycle lifecycle,
                 CaptureProbe.Reporter reporter) throws ReflectiveOperationException {
        super(context);
        this.original = original; this.lifecycle = lifecycle; this.reporter = reporter;
        cancelAnimation = original.getClass().getMethod("cancelAnimation");
        playAnimation = original.getClass().getMethod("playAnimation");
        awaitingSince = SystemClock.uptimeMillis();
        setTag("miui-island-spectrum-v2");
        setClickable(false); setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
    }

    boolean wantsAudio() { return subscribed && motion.isPlaying(); }

    void playing(boolean value) {
        if (!motion.playing(value)) return;
        removeCallbacks(stale); lastDraw = 0;
        if (value) {
            awaitingSince = SystemClock.uptimeMillis();
            lastData = 0; scheduleStale();
        } else {
            fallback = false; pauseSettled = false;
        }
        suppressOriginal(); invalidate();
        lifecycle.active(this, subscribed); // Re-evaluate audio demand without replacing this View.
        reporter.report("spectrum_motion mode=" + (value ? "playing" : "pause_decay")
                + " view=" + System.identityHashCode(this), null);
    }

    void colors(int[] field, boolean original) {
        if (field.length != CoverColors.COUNT) throw new IllegalArgumentException("Invalid color field");
        if (originalColors == original && Arrays.equals(field, targetColors)) return;
        palette(SystemClock.uptimeMillis());
        originalColors = original;
        System.arraycopy(colors, 0, fromColors, 0, colors.length);
        System.arraycopy(field, 0, targetColors, 0, colors.length);
        paletteDirty = true;
        colorStart = SystemClock.uptimeMillis(); invalidate();
    }

    void allow(boolean value) {
        allowed = value; reconcile();
    }

    void disable() {
        // Resume the stock animation while this visible holder still owns its slot.
        restoreOriginal(true);
        allow(false);
        invalidate();
    }

    private void reconcile() {
        boolean active = !disposed && allowed && isAttachedToWindow() && aggregatedVisible
                && isShown() && getWindowVisibility() == VISIBLE;
        if (active == subscribed) return;
        subscribed = active; lastDraw = 0;
        if (!active) {
            removeCallbacks(stale); restoreOriginal(false); motion.hidden();
        } else {
            if (motion.isPlaying()) {
                awaitingSince = SystemClock.uptimeMillis(); lastData = 0; scheduleStale();
            }
            suppressOriginal(); invalidate();
        }
        lifecycle.active(this, active);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); reconcile(); }
    @Override protected void onDetachedFromWindow() {
        aggregatedVisible = false; reconcile(); super.onDetachedFromWindow();
    }
    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible); aggregatedVisible = visible; reconcile();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (lifecycle != null) reconcile();
    }

    void frame(float[] levels, long time, boolean available) {
        if (disposed || !subscribed || !motion.isPlaying()) return;
        // Pending stop deliveries during a holder switch are not an audio failure.
        // A sustained failure is handled by the same 750 ms watchdog as missing FFT.
        if (!available) return;
        removeCallbacks(stale);
        boolean recovering = motion.recovering();
        fallback = false; motion.frame(levels); lastData = time;
        if (!recovering && motion.recovering()) reporter.report("spectrum_silence_recovery event=start"
                + " durationMs=" + SpectrumMotion.RECOVERY_MS + " view=" + System.identityHashCode(this), null);
        suppressOriginal(); invalidate(); scheduleStale();
    }

    private void scheduleStale() {
        if (subscribed && motion.isPlaying()) postDelayed(stale, 800);
    }

    void suppressOriginal() {
        if (disposed || fallback || !subscribed) return;
        try {
            if (!ownsOriginal || original.getVisibility() == VISIBLE) cancelAnimation.invoke(original);
            original.setVisibility(INVISIBLE);
            ownsOriginal = true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            fallback = true; reporter.report("spectrum_view_error stage=suppress", failure);
            restoreOriginal(true);
        }
    }

    private void restoreOriginal(boolean animate) {
        if (!ownsOriginal) return;
        ownsOriginal = false; original.setVisibility(VISIBLE);
        if (animate && allowed && original.isShown()) {
            try { playAnimation.invoke(original); }
            catch (ReflectiveOperationException | RuntimeException failure) {
                reporter.report("spectrum_view_error stage=restore", failure);
            }
        }
    }

    private void stale() {
        if (disposed || !subscribed || !motion.isPlaying()) return;
        if (SystemClock.uptimeMillis() - Math.max(lastData, awaitingSince) >= 750) {
            fallback = true; restoreOriginal(true); invalidate();
            reporter.report("spectrum_view_fallback reason=stale_fft", null);
        }
    }

    void dispose(boolean restore) {
        allowed = false; disposed = true; removeCallbacks(stale);
        paint.setShader(null);
        if (subscribed) { subscribed = false; lifecycle.active(this, false); }
        fallback = true;
        if (restore) {
            allowed = true; restoreOriginal(true); allowed = false;
        } else ownsOriginal = false;
    }

    private boolean palette(long now) {
        if (colorStart == 0 && !paletteDirty) return false;
        float t = colorStart == 0 ? 1 : Math.min(1, (now - colorStart) / 240f);
        boolean changed = paletteDirty;
        for (int i = 0; i < colors.length; i++) {
            int color = CoverColors.blend(fromColors[i], targetColors[i], t);
            changed |= color != colors[i]; colors[i] = color;
        }
        if (changed) {
            if (originalColors) {
                // Stock musicWave moves its two-stop vertical gradient with each bar's height.
                // Use the host's ARGB endpoints directly: no gray veil, saturation or alpha changes.
                colorShader = new LinearGradient(0, 0, 0, 1, colors[0], colors[colors.length - 1], Shader.TileMode.CLAMP);
            } else {
                colorBitmap.setPixels(colors, 0, CoverColors.SIZE, 0, 0, CoverColors.SIZE, CoverColors.SIZE);
                // BitmapShader can retain a native snapshot of a mutable bitmap. Refresh it only
                // during a cover transition; steady audio frames reuse the completed shader.
                BitmapShader bitmapShader = new BitmapShader(colorBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                bitmapShader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                colorShader = bitmapShader;
                colorShader.setLocalMatrix(colorBounds);
            }
            paint.setShader(colorShader);
        }
        paletteDirty = false;
        if (t >= 1) colorStart = 0;
        return t < 1;
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        float density = getResources().getDisplayMetrics().density;
        float width = Math.max(1, Math.min(w * 0.82f, 22 * density));
        // One fixed field spans the rectangle when all six bars are fully extended.
        // A shorter bar clips this field; its current height never stretches the colors.
        colorBounds.setScale(width / CoverColors.SIZE, Math.max(1, h) / (float) CoverColors.SIZE);
        colorBounds.postTranslate((w - width) * 0.5f, 0);
        if (colorShader != null && !originalColors) colorShader.setLocalMatrix(colorBounds);
        super.onSizeChanged(w, h, oldW, oldH);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (fallback || !subscribed || disposed) return;
        long now = SystemClock.uptimeMillis();
        if (motion.isPlaying() && now - Math.max(lastData, awaitingSince) >= 750) { stale(); return; }
        boolean colorMoving = palette(now);
        float elapsed = lastDraw == 0 ? 16 : Math.min(64, now - lastDraw); lastDraw = now;
        boolean recovering = motion.recovering();
        boolean moving = motion.advance(elapsed);
        if (recovering && !motion.recovering()) reporter.report("spectrum_silence_recovery event=finished"
                + " view=" + System.identityHashCode(this), null);
        float density = getResources().getDisplayMetrics().density;
        float width = Math.min(getWidth() * 0.82f, 22 * density);
        float bar = width / (SpectrumDsp.BARS + (SpectrumDsp.BARS - 1) * 0.65f);
        float gap = bar * 0.65f;
        float minHeight = Math.min(bar, 1.8f * density);
        // The stock music icon and album cover share this slot height. Strong peaks can align.
        float maxHeight = Math.max(minHeight, getHeight());
        float left = (getWidth() - width) * 0.5f;
        for (float level : motion.heights) {
            float height = minHeight + (maxHeight - minHeight) * level;
            float y = (getHeight() - height) * 0.5f;
            if (originalColors && colorShader != null) {
                originalBounds.setScale(1, height);
                originalBounds.postTranslate(0, y);
                colorShader.setLocalMatrix(originalBounds);
            }
            canvas.drawRoundRect(left, y, left + bar, y + height, bar * 0.5f, bar * 0.5f, paint);
            left += bar + gap;
        }
        if (!drew) {
            drew = true; int[] xy = new int[2]; getLocationOnScreen(xy);
            reporter.report("spectrum_view_draw x=" + xy[0] + " y=" + xy[1]
                    + " width=" + getWidth() + " height=" + getHeight()
                    + " maxBarHeight=" + maxHeight + " colorMapping=full_spectrum", null);
        }
        if (!motion.isPlaying() && !moving && !pauseSettled) {
            pauseSettled = true;
            reporter.report("spectrum_pause_settled view=" + System.identityHashCode(this), null);
        }
        if (moving || colorMoving) postInvalidateOnAnimation();
    }
}
