// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpectrumDspTest {
    static byte[] tones(int size, int[] bins, double[] amplitudes) {
        byte[] wave = new byte[size];
        for (int i = 0; i < size; i++) {
            double value = 0;
            for (int j = 0; j < bins.length; j++) value += amplitudes[j] * Math.sin(2 * Math.PI * bins[j] * i / size);
            wave[i] = (byte) Math.max(0, Math.min(255, Math.round(128 + value * 128)));
        }
        return wave;
    }

    static int rmsMb(double rms) { return (int) Math.round(2000 * Math.log10(rms)); }

    @Test public void silenceAndDcDoNotGenerateMovement() {
        byte[] wave = new byte[1024]; java.util.Arrays.fill(wave, (byte) 210);
        assertArrayEquals(new float[6], new SpectrumDsp(1024).levels(wave, 48000000, true, -2600, false), 0);
    }

    @Test public void realTonesLandInTheirFrequencyBand() {
        int[] bins = {2, 7, 16, 36, 68, 171};
        for (int band = 0; band < 6; band++) {
            byte[] wave = tones(1024, new int[]{bins[band]}, new double[]{0.7});
            float[] levels = new SpectrumDsp(1024).levels(wave, 48000000, true, -3400, false);
            assertTrue(levels[band] > 0.1);
            for (int other = 0; other < 6; other++) if (other != band) assertTrue(levels[other] < 0.03);
        }
    }

    @Test public void frequencyMappingUsesActualSampleRate() {
        byte[] wave = tones(1024, new int[]{16}, new double[]{0.7});
        assertTrue(new SpectrumDsp(1024).levels(wave, 48000000, true, -4000, false)[2] > 0.2); // 750 Hz.
        assertTrue(new SpectrumDsp(1024).levels(wave, 24000000, true, -4000, false)[1] > 0.1); // 375 Hz.
    }

    @Test public void rawRmsRestoresQuietPassagesDespiteIdenticalNormalizedCapture() {
        SpectrumDsp dsp = new SpectrumDsp(1024);
        byte[] wave = tones(1024, new int[]{2}, new double[]{0.7});
        float strong = dsp.levels(wave, 48000000, true, rmsMb(0.18 / Math.sqrt(2)), false)[0];
        float quiet = dsp.levels(wave, 48000000, true, rmsMb(0.018 / Math.sqrt(2)), false)[0];
        assertTrue("Strong bass remains visible with headroom", strong > 0.6 && strong < 0.85);
        assertTrue("20 dB quieter must not stay stretched", quiet < 0.2);
        assertTrue(quiet < strong * 0.2);
    }

    @Test public void restoringAmplitudeDoesNotDependOnNormalizedCaptureGain() {
        SpectrumDsp dsp = new SpectrumDsp(1024);
        int rms = rmsMb(0.02 / Math.sqrt(2));
        float a = dsp.levels(tones(1024, new int[]{2}, new double[]{0.75}), 48000000, true, rms, false)[0];
        float b = dsp.levels(tones(1024, new int[]{2}, new double[]{0.35}), 48000000, true, rms, false)[0];
        assertEquals(a, b, 0.01);
    }

    @Test public void weakTrebleRemainsVisibleBesideStrongBass() {
        byte[] wave = tones(1024, new int[]{2, 171}, new double[]{0.65, 0.006});
        float[] levels = new SpectrumDsp(1024).levels(wave, 48000000, true, rmsMb(0.05), false);
        assertTrue(levels[0] > 0.2);
        assertTrue("Last bar retains a weak real component", levels[5] > 0.025);
        assertTrue("Treble is not equalized to bass height", levels[5] < levels[0] * 0.3);
    }

    @Test public void decreasingAudioAmplitudeLowersEveryBandWithoutPerBandNormalization() {
        byte[] wave = tones(1024, new int[]{2, 7, 16, 36, 68, 171}, new double[]{0.55, 0.18, 0.035, 0.015, 0.008, 0.004});
        SpectrumDsp dsp = new SpectrumDsp(1024);
        float[] strong = dsp.levels(wave, 48000000, true, -2800, false);
        float[] quiet = dsp.levels(wave, 48000000, true, -4800, false);
        for (int i = 0; i < 6; i++) assertTrue(quiet[i] < strong[i] * 0.3);
        assertTrue(strong[0] > strong[5]);
        assertTrue(strong[2] < strong[0]);
    }

    @Test public void measuredSilenceAndMuteIgnoreNormalizedResidue() {
        byte[] wave = tones(1024, new int[]{2, 171}, new double[]{0.65, 0.02});
        SpectrumDsp dsp = new SpectrumDsp(1024);
        assertArrayEquals(new float[6], dsp.levels(wave, 48000000, true, -9600, false), 0);
        assertArrayEquals(new float[6], dsp.levels(wave, 48000000, true, -2000, true), 0);
    }

    @Test public void asPlayedFallbackKeepsActualDynamicsWithoutRms() {
        SpectrumDsp dsp = new SpectrumDsp(1024);
        float strong = dsp.levels(tones(1024, new int[]{2}, new double[]{0.18}), 48000000, false, 0, false)[0];
        float quiet = dsp.levels(tones(1024, new int[]{2}, new double[]{0.018}), 48000000, false, 0, false)[0];
        assertTrue(strong > 0.6 && strong < 0.85);
        assertTrue(quiet < 0.25);
    }

    @Test public void heightsRemainFiniteAndBoundedAtMaximumMeasuredLevel() {
        byte[] wave = tones(1024, new int[]{2, 7, 16, 36, 68, 171}, new double[]{0.4, 0.2, 0.1, 0.03, 0.02, 0.01});
        for (float level : new SpectrumDsp(1024).levels(wave, 48000000, true, 1000, false)) {
            assertTrue(Float.isFinite(level) && level >= 0 && level <= 1);
        }
    }

    private static float[] mapMeasured(double... bands) {
        float[] levels = new float[6];
        for (int i = 0; i < 6; i++) levels[i] = SpectrumDsp.mapAmplitude(i, bands[i]);
        return levels;
    }

    @Test public void measuredWeakTrebleCannotOvertakeMuchStrongerMidrange() {
        // Device frame 14:55:13: the previous curve incorrectly put the final bar at 97%.
        float[] levels = mapMeasured(0.016056, 0.029923, 0.023775, 0.010627, 0.002714, 0.003296);
        assertTrue(levels[2] > 0.4 && levels[2] < 0.7);
        assertTrue(levels[3] > levels[5]);
        assertTrue(levels[5] > 0.15 && levels[5] < 0.35);
        assertTrue(levels[5] < levels[2] * 0.65);
    }

    @Test public void measuredVocalMidrangeRemainsProminentWithoutFullHeightTreble() {
        // Device frame 14:55:23: broad, strong midrange used to make both treble bars full height.
        float[] levels = mapMeasured(0.024515, 0.023608, 0.047652, 0.028218, 0.009932, 0.006353);
        assertTrue(levels[2] > 0.8 && levels[3] > 0.8);
        assertTrue(levels[0] < 0.35);
        assertTrue(levels[4] > 0.3 && levels[4] < 0.6);
        assertTrue(levels[5] > 0.3 && levels[5] < 0.6);
    }

    @Test public void ordinaryBassKeepsBeatContrastAndStrongPeakCanStillReachFullSlot() {
        float moderate = SpectrumDsp.mapAmplitude(0, 0.12);
        float louder = SpectrumDsp.mapAmplitude(0, 0.20);
        assertTrue(moderate > 0.35 && moderate < 0.6);
        assertTrue(louder < 0.9 && louder - moderate > 0.2);
        assertTrue(SpectrumDsp.mapAmplitude(0, 0.40) > 0.95);
        assertEquals(0, SpectrumDsp.mapAmplitude(0, 0), 0);
    }

    @Test public void recoveredSustainedBassHasHeadroomInsteadOfPinningTheFirstBar() {
        // Actual source-envelope-02 frames: first bar spent 72..97% of each window above 95%.
        double[][] frames = {
            {0.177935, 0.030057, 0.013464, 0.007021, 0.003332, 0.001835},
            {0.226470, 0.047652, 0.022870, 0.005104, 0.002146, 0.001553},
            {0.205010, 0.012745, 0.015507, 0.007681, 0.003477, 0.000492},
            {0.212377, 0.028859, 0.025817, 0.002495, 0.000777, 0.000947}
        };
        float minimum = 1, maximum = 0;
        for (double[] frame : frames) {
            float[] levels = mapMeasured(frame);
            assertTrue(levels[0] > 0.5 && levels[0] < 0.85);
            assertTrue(levels[0] > levels[1]);
            minimum = Math.min(minimum, levels[0]); maximum = Math.max(maximum, levels[0]);
        }
        assertTrue("Measured bass variations remain visible", maximum - minimum > 0.1);
    }

    @Test public void playbackEnvelopeFallsPromptlyWhileKeepingFastAttack() {
        assertTrue(SpectrumDsp.smooth(0, 1, 28) > 0.6);
        float fall = 1;
        for (int i = 0; i < 4; i++) fall = SpectrumDsp.smooth(fall, 0, 90);
        assertTrue("Quiet music should settle within 360 ms", fall < 0.05);
        assertEquals(0, SpectrumDsp.smooth(0, 0, 16), 0);
    }

    @Test public void smoothingIsIndependentOfFrameRate() {
        float one = SpectrumDsp.smooth(0, 1, 32);
        float two = SpectrumDsp.smooth(SpectrumDsp.smooth(0, 1, 16), 1, 16);
        assertEquals(one, two, 0.0001f);
    }
}
