// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import java.lang.reflect.Method;

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
    private int top = CoverColors.TOP_DEFAULT, bottom = CoverColors.BOTTOM_DEFAULT;
    private int fromTop = top, fromBottom = bottom, targetTop = top, targetBottom = bottom;
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

    void colors(int newTop, int newBottom) {
        if (newTop == targetTop && newBottom == targetBottom) return;
        palette(SystemClock.uptimeMillis());
        fromTop = top; fromBottom = bottom;
        targetTop = newTop; targetBottom = newBottom;
        colorStart = SystemClock.uptimeMillis(); invalidate();
    }

    void allow(boolean value) {
        allowed = value; reconcile();
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
        fallback = false; motion.frame(levels); lastData = time;
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
        if (subscribed) { subscribed = false; lifecycle.active(this, false); }
        fallback = true;
        if (restore) {
            allowed = true; restoreOriginal(true); allowed = false;
        } else ownsOriginal = false;
    }

    private boolean palette(long now) {
        float t = colorStart == 0 ? 1 : Math.min(1, (now - colorStart) / 240f);
        int newTop = CoverColors.blend(fromTop, targetTop, t);
        int newBottom = CoverColors.blend(fromBottom, targetBottom, t);
        if (paint.getShader() == null || newTop != top || newBottom != bottom) {
            top = newTop; bottom = newBottom;
            paint.setShader(new LinearGradient(0, 0, 0, Math.max(1, getHeight()),
                    top, bottom, Shader.TileMode.CLAMP));
        }
        return t < 1;
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        paint.setShader(null); super.onSizeChanged(w, h, oldW, oldH);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (fallback || !subscribed || disposed) return;
        long now = SystemClock.uptimeMillis();
        if (motion.isPlaying() && now - Math.max(lastData, awaitingSince) >= 750) { stale(); return; }
        boolean colorMoving = palette(now);
        float elapsed = lastDraw == 0 ? 16 : Math.min(64, now - lastDraw); lastDraw = now;
        boolean moving = motion.advance(elapsed);
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
            canvas.drawRoundRect(left, y, left + bar, y + height, bar * 0.5f, bar * 0.5f, paint);
            left += bar + gap;
        }
        if (!drew) {
            drew = true; int[] xy = new int[2]; getLocationOnScreen(xy);
            reporter.report("spectrum_view_draw x=" + xy[0] + " y=" + xy[1]
                    + " width=" + getWidth() + " height=" + getHeight()
                    + " maxBarHeight=" + maxHeight, null);
        }
        if (!motion.isPlaying() && !moving && !pauseSettled) {
            pauseSettled = true;
            reporter.report("spectrum_pause_settled view=" + System.identityHashCode(this), null);
        }
        if (moving || colorMoving) postInvalidateOnAnimation();
    }
}
