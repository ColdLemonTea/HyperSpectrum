// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

/** Persistent visual state: releasing audio capture does not discard the last bar heights. */
final class SpectrumMotion {
    static final float PAUSE_MS = 420;
    final float[] heights = new float[SpectrumDsp.BARS];
    private final float[] target = new float[SpectrumDsp.BARS];
    private final float[] pauseFrom = new float[SpectrumDsp.BARS];
    private boolean playing = true;
    private float pauseElapsed = PAUSE_MS;

    boolean isPlaying() { return playing; }

    boolean playing(boolean value) {
        if (playing == value) return false;
        playing = value;
        java.util.Arrays.fill(target, 0);
        if (!value) {
            System.arraycopy(heights, 0, pauseFrom, 0, heights.length);
            pauseElapsed = 0;
        }
        return true;
    }

    void frame(float[] levels) {
        if (playing) System.arraycopy(levels, 0, target, 0, target.length);
    }

    boolean advance(float elapsedMs) {
        if (!playing) {
            pauseElapsed = Math.min(PAUSE_MS, pauseElapsed + Math.max(0, elapsedMs));
            float remaining = 1 - pauseElapsed / PAUSE_MS;
            float scale = remaining * remaining * remaining;
            for (int i = 0; i < heights.length; i++) heights[i] = pauseFrom[i] * scale;
            return pauseElapsed < PAUSE_MS;
        }
        boolean moving = false;
        for (int i = 0; i < heights.length; i++) {
            heights[i] = SpectrumDsp.smooth(heights[i], target[i], elapsedMs);
            moving |= Math.abs(heights[i] - target[i]) > 0.001f;
        }
        return moving;
    }

    void hidden() {
        // A hidden pause needs no off-screen animation. Playing state survives a holder handoff.
        if (!playing) { pauseElapsed = PAUSE_MS; java.util.Arrays.fill(heights, 0); }
    }
}
