// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.wxxsfxyzm.hyperspectrum.VolumeCompensation.CaptureVolume.*;

public class VolumeCompensationTest {
    @Test public void externalAndUnknownNeverAddPlaybackVolumeToTheCapture() {
        for (float db : new float[]{Float.NEGATIVE_INFINITY, -60, -30, -20, -10, 0}) {
            assertEquals(VolumeCompensation.DISPLAY_GAIN, VolumeCompensation.gain(EXTERNAL, db, 0), 0);
            assertEquals(VolumeCompensation.DISPLAY_GAIN, VolumeCompensation.gain(UNKNOWN, db, 0), 0);
        }
    }

    @Test public void absoluteVolumeRecoversOnlyMeasuredPhoneSideAttenuation() {
        double expected = VolumeCompensation.DISPLAY_GAIN * Math.pow(10, 6.0 / 20);
        for (float headphoneDb : new float[]{-60, -20, -10, -0.5f}) {
            assertEquals(expected, VolumeCompensation.appliedGain(EXTERNAL, headphoneDb, -0.5f, -6.5f), 0.00000001);
        }
        VolumeCompensation c = new VolumeCompensation();
        c.target(VolumeCompensation.appliedGain(EXTERNAL, -20, -0.5f, -6.5f), 1000, true);
        c.target(VolumeCompensation.appliedGain(EXTERNAL, -10, -0.5f, -6.5f), 1100, false);
        assertFalse(c.settling(1100));
    }

    @Test public void appliedSoftwareCurveReplacesTheNominalInverseRatherThanMultiplyingIt() {
        assertEquals(VolumeCompensation.gain(SOFTWARE, -31, -0.5f),
                VolumeCompensation.appliedGain(SOFTWARE, -20, -0.5f, -31), 0);
        assertEquals(VolumeCompensation.gain(SOFTWARE, -20, -0.5f),
                VolumeCompensation.appliedGain(SOFTWARE, -20, -0.5f, Float.NaN), 0);
        assertEquals(VolumeCompensation.DISPLAY_GAIN,
                VolumeCompensation.appliedGain(UNKNOWN, -20, -0.5f, -31), 0);
    }

    @Test public void fixedAndVariableBluetoothAttenuationPreserveTheSameSixSourceBands() {
        byte[] wave = SpectrumDspTest.tones(1024, new int[]{2, 7, 16, 36, 68, 171},
                new double[]{0.4, 0.2, 0.1, 0.03, 0.02, 0.01});
        SpectrumDsp dsp = new SpectrumDsp(1024);
        float[] reference = dsp.levels(wave, 48000000, true, -1450, false, VolumeCompensation.DISPLAY_GAIN);
        for (float appliedDb : new float[]{-6.5f, -11.9f, -20, -31, -45}) {
            double gain = VolumeCompensation.appliedGain(EXTERNAL, -20, -0.5f, appliedDb);
            assertArrayEquals(reference, dsp.levels(wave, 48000000, true,
                    -1450 + Math.round((appliedDb + 0.5f) * 100), false, gain), 0.00001f);
        }
    }

    @Test public void softwareVolumeIsUndoneRelativeToItsMaximum() {
        double reference = VolumeCompensation.DISPLAY_GAIN;
        assertEquals(reference * Math.pow(10, 12 / 20.0), VolumeCompensation.gain(SOFTWARE, -15, -3), 0.000001);
        assertEquals(reference, VolumeCompensation.gain(SOFTWARE, -3, -3), 0);
        assertEquals(reference * 10, VolumeCompensation.gain(SOFTWARE, -20, 0), 0.000001);
    }

    @Test public void invalidOrSilentCurvesDoNotInventAnInverseGain() {
        for (float db : new float[]{Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, 3}) {
            assertEquals(VolumeCompensation.DISPLAY_GAIN, VolumeCompensation.gain(SOFTWARE, db, 0), 0);
        }
        assertEquals(VolumeCompensation.DISPLAY_GAIN, VolumeCompensation.gain(SOFTWARE, -12, Float.NaN), 0);
        assertTrue(Double.isFinite(VolumeCompensation.gain(SOFTWARE, -1000, 0)));
    }

    @Test public void onlyVolumeTransitionsAreSmoothedAndRouteResetDoesNotReuseOldGain() {
        VolumeCompensation c = new VolumeCompensation();
        c.target(1, 1000, true);
        c.target(0.2, 1000, false);
        double middle = c.value(1080);
        assertTrue(middle > 0.2 && middle < 0.6);
        assertEquals(0.2, c.value(2000), 0.00001);
        c.target(1, 2000, true);
        assertEquals(1, c.value(2000), 0);
        c.target(35, 2000, false);
        assertEquals(35, c.value(4000), 0.00001);
        c.target(VolumeCompensation.DISPLAY_GAIN, 4000, true);
        assertEquals(VolumeCompensation.DISPLAY_GAIN, c.value(4000), 0);
    }

    @Test public void softwareVolumeWaitsForFreshRmsButHeadphoneVolumeDoesNot() {
        VolumeCompensation c = new VolumeCompensation();
        c.target(VolumeCompensation.gain(SOFTWARE, -20, 0), 1000, true);
        assertFalse(c.settling(1000));
        c.target(VolumeCompensation.gain(SOFTWARE, -40, 0), 1100, false);
        assertTrue(c.settling(1599));
        assertFalse(c.settling(1600));
        c.target(VolumeCompensation.gain(SOFTWARE, -50, 0), 1600, false);
        c.target(VolumeCompensation.gain(SOFTWARE, -50, 0), 1800, false);
        assertTrue(c.settling(2099));
        assertFalse(c.settling(2100)); // Polling an unchanged volume does not prolong the hold.
        c.target(VolumeCompensation.gain(EXTERNAL, -50, 0), 2200, true);
        c.target(VolumeCompensation.gain(EXTERNAL, -10, 0), 2300, false);
        assertFalse(c.settling(2300));
    }

    @Test public void normalizedAndAsPlayedApplyOneCommonGainBeforeTheDisplayCurve() {
        byte[] wave = SpectrumDspTest.tones(1024, new int[]{2, 7, 16, 36, 68, 171},
                new double[]{0.4, 0.2, 0.1, 0.03, 0.02, 0.01});
        for (boolean normalized : new boolean[]{true, false}) {
            SpectrumDsp dsp = new SpectrumDsp(1024);
            float[] original = dsp.levels(wave, 48000000, normalized, -2200, false);
            double[] originalBands = dsp.bandAmplitudes().clone();
            float[] corrected = dsp.levels(wave, 48000000, normalized, -2200, false, 0.25);
            for (int i = 0; i < 6; i++) {
                assertEquals(originalBands[i] * 0.25, dsp.bandAmplitudes()[i], 0.00000001);
                assertTrue(corrected[i] < original[i]);
            }
        }
    }

    @Test public void compensationKeepsQuietPassagesQuietAndDoesNotNormalizeThemBackUp() {
        byte[] wave = SpectrumDspTest.tones(1024, new int[]{2}, new double[]{0.7});
        SpectrumDsp dsp = new SpectrumDsp(1024);
        float strong = dsp.levels(wave, 48000000, true, -1400, false, VolumeCompensation.DISPLAY_GAIN)[0];
        float quiet = dsp.levels(wave, 48000000, true, -3400, false, VolumeCompensation.DISPLAY_GAIN)[0];
        assertTrue(strong > 0.3);
        assertTrue(quiet < strong * 0.2);
        assertArrayEquals(new float[6], dsp.levels(wave, 48000000, true, -1400, true, VolumeCompensation.DISPLAY_GAIN), 0);
    }

    @Test public void changingSpeakerVolumePreservesAllSixBarsAndSongDynamics() {
        byte[] wave = SpectrumDspTest.tones(1024, new int[]{2, 7, 16, 36, 68, 171},
                new double[]{0.4, 0.2, 0.1, 0.03, 0.02, 0.01});
        SpectrumDsp dsp = new SpectrumDsp(1024);
        float[] reference = dsp.levels(wave, 48000000, true, -1400, false,
                VolumeCompensation.gain(EXTERNAL, -20, 0));
        float[] quietReference = dsp.levels(wave, 48000000, true, -3400, false,
                VolumeCompensation.gain(EXTERNAL, -20, 0));
        for (int db : new int[]{0, -10, -20, -40, -60}) {
            double gain = VolumeCompensation.gain(SOFTWARE, db, 0);
            assertArrayEquals(reference, dsp.levels(wave, 48000000, true, -1400 + db * 100, false, gain), 0.00001f);
            float[] quiet = dsp.levels(wave, 48000000, true, -3400 + db * 100, false, gain);
            assertArrayEquals(quietReference, quiet, 0.00001f);
            for (int i = 0; i < 6; i++) assertTrue(quiet[i] < reference[i] * 0.2);
        }
    }

    @Test public void inverseVolumeNeverCreatesSpectrumFromSilentPcmOrRms() {
        byte[] silent = new byte[1024]; java.util.Arrays.fill(silent, (byte) 128);
        byte[] residue = SpectrumDspTest.tones(1024, new int[]{2}, new double[]{0.7});
        SpectrumDsp dsp = new SpectrumDsp(1024);
        double gain = VolumeCompensation.gain(SOFTWARE, -80, 0);
        assertArrayEquals(new float[6], dsp.levels(silent, 48000000, true, -2000, false, gain), 0);
        assertArrayEquals(new float[6], dsp.levels(residue, 48000000, true, -9600, false, gain), 0);
        for (float level : dsp.levels(residue, 48000000, true, -1000, false, gain)) {
            assertTrue(Float.isFinite(level) && level >= 0 && level <= 1);
        }
    }

    @Test public void asPlayedRecoveryAlsoUndoesVolumeWhenPcmHasEnoughResolution() {
        SpectrumDsp dsp = new SpectrumDsp(1024);
        float reference = dsp.levels(SpectrumDspTest.tones(1024, new int[]{2}, new double[]{0.4}),
                48000000, false, 0, false, VolumeCompensation.DISPLAY_GAIN)[0];
        for (int db : new int[]{-6, -12, -24}) {
            double attenuation = Math.pow(10, db / 20.0);
            byte[] wave = SpectrumDspTest.tones(1024, new int[]{2}, new double[]{0.4 * attenuation});
            assertEquals(reference, dsp.levels(wave, 48000000, false, 0, false,
                    VolumeCompensation.gain(SOFTWARE, db, 0))[0], 0.04f);
        }
    }
}
