// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

import org.junit.Test;
import static org.junit.Assert.*;

public class FftMetricsTest {
    @Test public void excludesDcAndNyquist() {
        byte[] fft = new byte[512];
        fft[0] = 127; fft[1] = -128;
        assertArrayEquals(new double[6], FftMetrics.bands(fft, 6), 0);
        FftMetrics.Window window = new FftMetrics.Window();
        window.add(fft);
        assertEquals("silent", window.signal());
        assertEquals(0, window.meanEnergy(), 0);
    }
    @Test public void computesComplexMagnitudeAndHandlesSignedMinimum() {
        byte[] fft = new byte[8];
        fft[2] = 3; fft[3] = 4;
        assertEquals(5, FftMetrics.bands(fft, 6)[0], 0);
        fft[2] = -128; fft[3] = 0;
        FftMetrics.Window window = new FftMetrics.Window(); window.add(fft);
        assertEquals(128, window.peak);
        assertEquals("constant_nonzero", window.signal());
    }
    @Test public void detectsChangeWhenCallbackReusesBuffer() {
        byte[] fft = new byte[512]; fft[20] = 4;
        FftMetrics.Window window = new FftMetrics.Window();
        window.add(fft); window.add(fft);
        fft[20] = 12; window.add(fft);
        assertEquals(1, window.changedFrames);
        assertEquals(3, window.nonzeroFrames);
        assertEquals("changing_nonzero", window.signal());
    }
    @Test public void distinguishesNoDataFromSilence() {
        FftMetrics.Window window = new FftMetrics.Window();
        assertEquals("no_callbacks", window.signal());
        window.add(new byte[512]);
        assertEquals("silent", window.signal());
    }
    @Test public void dcChangesDoNotProveChangingMusic() {
        byte[] fft = new byte[512]; fft[20] = 4;
        FftMetrics.Window window = new FftMetrics.Window(); window.add(fft);
        fft[0] = 100; fft[1] = 32; window.add(fft);
        assertEquals("constant_nonzero", window.signal());
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsTruncatedFft() {
        FftMetrics.bands(new byte[5], 6);
    }
}
