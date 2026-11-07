// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum.audio;

import java.util.Arrays;

/** Android Visualizer packed real FFT: byte 0 is DC, byte 1 is Nyquist. */
public final class FftMetrics {
    private FftMetrics() {}

    public static double[] bands(byte[] fft, int count) {
        if (fft == null || fft.length < 4 || (fft.length & 1) != 0 || count < 1) {
            throw new IllegalArgumentException("An even packed FFT and positive band count are required");
        }
        int bins = fft.length / 2;
        double[] sums = new double[count];
        int[] sizes = new int[count];
        for (int bin = 1; bin < bins; bin++) {
            int band = Math.min(count - 1, (int) (Math.log(bin) / Math.log(bins) * count));
            double re = fft[bin * 2];
            double im = fft[bin * 2 + 1];
            sums[band] += re * re + im * im;
            sizes[band]++;
        }
        for (int band = 0; band < count; band++) {
            sums[band] = sizes[band] == 0 ? 0 : Math.sqrt(sums[band] / sizes[band]);
        }
        return sums;
    }

    public static final class Window {
        public int frames;
        public int nonzeroFrames;
        public int changedFrames;
        public int peak;
        public double energySum;
        private byte[] previous;

        public void add(byte[] fft) {
            // Validate before mutating counters. DC/Nyquist do not count as music evidence.
            bands(fft, 6);
            frames++;
            int framePeak = 0;
            double power = 0;
            for (int i = 2; i < fft.length; i++) {
                int value = fft[i]; // Signed complex components, including -128.
                framePeak = Math.max(framePeak, Math.abs(value));
                power += value * value;
            }
            if (framePeak > 0) nonzeroFrames++;
            if (previous != null && (previous.length != fft.length
                    || !Arrays.equals(previous, 2, previous.length, fft, 2, fft.length))) changedFrames++;
            peak = Math.max(peak, framePeak);
            energySum += Math.sqrt(power / (fft.length - 2));
            previous = fft.clone(); // The callback may reuse its buffer.
        }

        public double meanEnergy() { return frames == 0 ? 0 : energySum / frames; }

        public String signal() {
            if (frames == 0) return "no_callbacks";
            if (nonzeroFrames == 0) return "silent";
            return changedFrames == 0 ? "constant_nonzero" : "changing_nonzero";
        }
    }
}
