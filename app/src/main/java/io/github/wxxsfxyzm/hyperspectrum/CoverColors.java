// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

/** Deterministic, cover-scoped palette with private pixel buffers, independent of MiPalette. */
final class CoverColors {
    static final int TOP_DEFAULT = 0xeedddddd;
    static final int BOTTOM_DEFAULT = 0xcc999999;

    static long fingerprint(int[] pixels) {
        long hash = 0xcbf29ce484222325L;
        for (int pixel : pixels) { hash ^= pixel & 0xffffffffL; hash *= 0x100000001b3L; }
        return hash;
    }

    static int[] extract(int[] pixels) {
        double[] weights = new double[4096], red = new double[4096];
        double[] green = new double[4096], blue = new double[4096];
        for (int pixel : pixels) {
            if ((pixel >>> 24) < 128) continue;
            int r = (pixel >>> 16) & 255, g = (pixel >>> 8) & 255, b = pixel & 255;
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            float saturation = max == 0 ? 0 : (max - min) / (float) max;
            if (max < 30 || saturation < 0.15f) continue;
            int bucket = (r >> 4) << 8 | (g >> 4) << 4 | b >> 4;
            double weight = (0.4 + saturation) * ((pixel >>> 24) / 255.0);
            weights[bucket] += weight;
            red[bucket] += r * weight; green[bucket] += g * weight; blue[bucket] += b * weight;
        }
        int best = 0;
        for (int i = 1; i < weights.length; i++) if (weights[i] > weights[best]) best = i;
        if (weights[best] == 0) return new int[]{TOP_DEFAULT, BOTTOM_DEFAULT};
        float r = (float) (red[best] / weights[best] / 255);
        float g = (float) (green[best] / weights[best] / 255);
        float b = (float) (blue[best] / weights[best] / 255);
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float hue = max == r ? (g - b) / delta : max == g ? 2 + (b - r) / delta : 4 + (r - g) / delta;
        if (hue < 0) hue += 6;
        float saturation = Math.min(0.8f, Math.max(0.45f, delta / max));
        return new int[]{hsv(0xee, hue, saturation * 0.85f, 0.94f), hsv(0xcc, hue, saturation, 0.68f)};
    }

    private static int hsv(int alpha, float hue, float saturation, float value) {
        int sector = (int) hue;
        float f = hue - sector, p = value * (1 - saturation);
        float q = value * (1 - saturation * f), t = value * (1 - saturation * (1 - f));
        float[] values = switch (sector) {
            case 0 -> new float[]{value, t, p}; case 1 -> new float[]{q, value, p};
            case 2 -> new float[]{p, value, t}; case 3 -> new float[]{p, q, value};
            case 4 -> new float[]{t, p, value}; default -> new float[]{value, p, q};
        };
        return alpha << 24 | Math.round(values[0] * 255) << 16
                | Math.round(values[1] * 255) << 8 | Math.round(values[2] * 255);
    }

    static int blend(int from, int to, float fraction) {
        fraction = Math.max(0, Math.min(1, fraction));
        int color = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 255, b = (to >>> shift) & 255;
            color |= Math.round(a + (b - a) * fraction) << shift;
        }
        return color;
    }
}
