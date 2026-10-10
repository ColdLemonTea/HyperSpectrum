// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

import org.junit.Test;
import static org.junit.Assert.*;

public class CaptureChannelsTest {
    @Test public void normalizedStereoEnvelopeIsPreservedWhenPaddedToTwelveChannels() {
        double stereoRms = 0.15;
        double paddedRms = Math.sqrt((stereoRms * stereoRms * 2) / 12);
        assertEquals(stereoRms, paddedRms * CaptureChannels.gain(2, 12, true), 0.000000001);
        // Test the actual DSP envelope restoration, with mB measurement rounding.
        byte[] waveform = SpectrumDspTest.tones(1024, new int[]{2, 7, 16, 36, 68, 171},
                new double[]{0.3, 0.2, 0.1, 0.03, 0.02, 0.01});
        SpectrumDsp stereo = new SpectrumDsp(1024), padded = new SpectrumDsp(1024);
        stereo.levels(waveform, 48000000, true, (int) (2000 * Math.log10(stereoRms)), false, 0.35);
        padded.levels(waveform, 48000000, true, (int) (2000 * Math.log10(paddedRms)), false,
                0.35 * CaptureChannels.gain(2, 12, true));
        for (int i = 0; i < 6; i++) {
            double original = stereo.bandAmplitudes()[i];
            assertEquals(original, padded.bandAmplitudes()[i], original * 0.002);
        }
    }

    @Test public void asPlayedRecoversTheChannelAveragedWaveformWithoutRms() {
        double stereoSample = (0.2 + 0.4) / 2;
        double paddedSample = (0.2 + 0.4) / 12;
        assertEquals(stereoSample, paddedSample * CaptureChannels.gain(2, 12, false), 0.000000001);
    }

    @Test public void monoUnknownUnpaddedAndDownmixedFormatsKeepTheFixedGain() {
        for (boolean normalized : new boolean[]{true, false}) {
            for (int[] channels : new int[][]{{0, 12}, {1, 12}, {2, 0}, {2, 2}, {12, 2}, {2, 25}}) {
                assertEquals(1, CaptureChannels.gain(channels[0], channels[1], normalized), 0);
            }
        }
    }
}
