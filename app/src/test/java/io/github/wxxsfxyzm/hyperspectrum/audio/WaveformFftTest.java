// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum.audio;

import org.junit.Test;
import static org.junit.Assert.*;

public class WaveformFftTest {
    @Test public void knownSineRetainsAmplitudeAndRmsWithHannWindow() {
        WaveformFft fft = new WaveformFft(1024);
        fft.analyze(SpectrumDspTest.tones(1024, new int[]{16}, new double[]{0.6}));
        assertEquals(0.6, fft.amplitude(16), 0.002);
        assertEquals(0.6 / Math.sqrt(2), fft.rms(), 0.002);
        assertTrue(fft.amplitude(40) < 0.0005);
    }

    @Test public void floatingTransformRetainsWeakTrebleInMixedWaveform() {
        WaveformFft fft = new WaveformFft(1024);
        fft.analyze(SpectrumDspTest.tones(1024, new int[]{2, 171}, new double[]{0.65, 0.006}));
        assertEquals(0.65, fft.amplitude(2), 0.002);
        assertEquals(0.006, fft.amplitude(171), 0.001);
    }

    @Test public void dcOffsetIsRemovedAndReuseClearsPreviousSignalWithoutChangingInput() {
        WaveformFft fft = new WaveformFft(512);
        byte[] signal = SpectrumDspTest.tones(512, new int[]{8}, new double[]{0.5});
        byte[] saved = signal.clone(); fft.analyze(signal);
        assertArrayEquals(saved, signal);
        byte[] dc = new byte[512]; java.util.Arrays.fill(dc, (byte) 220); fft.analyze(dc);
        assertEquals(0, fft.rms(), 0);
        for (int bin = 1; bin < 256; bin++) assertEquals(0, fft.amplitude(bin), 0);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectNonPowerOfTwoSize() {
        new WaveformFft(1000);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectMismatchedFrameSize() {
        new WaveformFft(1024).analyze(new byte[512]);
    }
}
