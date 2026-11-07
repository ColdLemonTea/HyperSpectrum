// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package io.github.wxxsfxyzm.hyperspectrum.render;

/** Private artwork image, before the view-space blur and bar mask. */
final class AppleArtwork {
    static final int SIZE = 48, COUNT = SIZE * SIZE;
    record Treatment(double luminance, double saturation, double black, double white) { }
    static Treatment treatment(int[] pixels) {
        if (pixels.length == 0) throw new IllegalArgumentException("Empty artwork");
        Analysis analysis = new Analysis(); analysis.add(pixels, pixels.length);
        return analysis.treatment();
    }
    /** Full source pixels, before the independent display-sized color field. */
    static final class Analysis {
        private double total;
        private long count;
        private long hash = 0xcbf29ce484222325L;
        void add(int[] pixels, int length) {
            if (length < 0 || length > pixels.length) throw new IllegalArgumentException("Invalid pixel count");
            for (int i = 0; i < length; i++) {
                int p = pixels[i];
                double a = (p >>> 24) / 255.0;
                double r = ((p >>> 16) & 255) * a / 255;
                double g = ((p >>> 8) & 255) * a / 255, b = (p & 255) * a / 255;
                total += Math.sqrt(0.299 * r * r + 0.587 * g * g + 0.114 * b * b);
                hash ^= p & 0xffffffffL; hash *= 0x100000001b3L;
            }
            count += length;
        }
        Treatment treatment() {
            if (count == 0) throw new IllegalArgumentException("Empty artwork");
            double l = total / count, white = Math.max(0, 0.6 - l);
            return new Treatment(l, 1.25 + 4 * white, Math.max(0, l - 0.7), white);
        }
        long fingerprint() { return hash; }
    }
    static int[] fallback() {
        int[] pixels = new int[COUNT]; java.util.Arrays.fill(pixels, 0xff999999); return pixels;
    }
    static int[] field(int[] pixels) {
        if (pixels.length != COUNT) throw new IllegalArgumentException("Expected private 48x48 artwork");
        return field(pixels, treatment(pixels));
    }
    static int[] field(int[] pixels, Treatment t) {
        if (pixels.length != COUNT) throw new IllegalArgumentException("Expected private 48x48 artwork");
        int[] result = new int[COUNT];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i]; double a = (p >>> 24) / 255.0;
            double r = (p >>> 16) & 255, g = (p >>> 8) & 255, b = p & 255;
            // SwiftUI Image reference uses the RGB saturation matrix in its
            // 8-bit image pass; Color views have a different optimized path.
            double y = 0.213 * r + 0.715 * g + 0.072 * b;
            int rr = channel(y + (r - y) * t.saturation, a, t);
            int gg = channel(y + (g - y) * t.saturation, a, t);
            int bb = channel(y + (b - y) * t.saturation, a, t);
            double alpha = 1 - (1 - a) * (1 - t.black) * (1 - t.white);
            result[i] = (int) Math.round(alpha * 255) << 24 | rr << 16 | gg << 8 | bb;
        }
        return result;
    }
    private static int channel(double value, double alpha, Treatment t) {
        double a = 1 - (1 - alpha) * (1 - t.black) * (1 - t.white);
        // Match the measured Image pass quantization before scrim overlays.
        double saturated = Math.round(Math.max(0, Math.min(255, value)));
        double premultiplied = saturated * alpha * (1 - t.black) * (1 - t.white) + 255 * t.white;
        return a == 0 ? 0 : (int) Math.round(Math.max(0, Math.min(255, premultiplied / a)));
    }
    private AppleArtwork() { }
}
