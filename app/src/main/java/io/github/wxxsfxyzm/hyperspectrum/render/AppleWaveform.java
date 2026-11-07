// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package io.github.wxxsfxyzm.hyperspectrum.render;

/** Independently implemented formulas recovered from the local MediaCoreUI reference. */
final class AppleWaveform {
    static final float SAMPLE_MS = 80, PAUSE_MS = 900;
    static float compactExtent(float slotWidth, float slotHeight) {
        // Fit the reference square to the host icon slot. Its logical16-point
        // size defines proportions, not an additional Android content inset.
        return Math.max(0, Math.min(slotWidth, slotHeight));
    }
    // SwiftUI's measured physical blur sigma, scaled with the compact square.
    static float artworkBlur(float extent) { return extent * (6f / 16); }
    static float artworkBlurRadius(float extent) {
        // RenderEffect accepts HWUI radius, not Gaussian sigma. HWUI converts
        // radius to sigma with radius * 0.57735 + 0.5 (utils/Blur.cpp).
        // Passing SwiftUI's value directly makes colored regions too distinct.
        float sigma = artworkBlur(extent);
        return sigma > 0.5f ? (sigma - 0.5f) / 0.57735f : 0;
    }
    static float baseWidth(float width) { return width / 8 * 5 / 6; }
    static float width(float base, float level) { return base * (1 + 0.12f * level); }
    static float height(float base, float height, float level) { return Math.max(base, Math.min(height, height * level)); }
    static float left(float width, int index, float currentWidth) {
        float maxWidth = baseWidth(width) * 1.12f;
        // Six-bar frequency table has seven boundaries. MediaCoreUI divides
        // remaining space by boundaryCount-2 and places each bar in a max-width slot.
        float gap = (width - 6 * maxWidth) / 5;
        return index * (maxWidth + gap) + (maxWidth - currentWidth) / 2;
    }
    static float radius(float width) { return Math.round(width / 2); }
    static float ease(float fraction, boolean pause) {
        float x = Math.max(0, Math.min(1, fraction));
        // Solve the timing curve's x(t), then evaluate y(t). Using the time
        // fraction directly as t changes both Apple's easeOut and pause timing.
        double lo = 0, hi = 1;
        double x1 = pause ? 0.33 : 0, x2 = pause ? 0.67 : 0.58;
        for (int i = 0; i < 24; i++) {
            double t = (lo + hi) / 2, u = 1 - t;
            double bx = 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t;
            if (bx < x) lo = t; else hi = t;
        }
        double t = (lo + hi) / 2, u = 1 - t;
        // easeOut=(0,0,0.58,1); pause=(0.33,1,0.67,1).
        return x == 0 ? 0 : x == 1 ? 1 : (float) ((pause ? 3 * u * u * t : 0) + 3 * u * t * t + t * t * t);
    }
    private AppleWaveform() { }
}
