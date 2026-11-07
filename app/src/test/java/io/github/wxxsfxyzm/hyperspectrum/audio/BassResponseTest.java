// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum.audio;

import io.github.wxxsfxyzm.hyperspectrum.render.SpectrumMotionFixture;

import org.junit.Test;
import static org.junit.Assert.*;

public class BassResponseTest {
    @Test public void sustainedBassAndSmallVariationsKeepTheFixedResponse() {
        BassResponse bass = new BassResponse();
        double[] amplitudes = {0.20, 0.178, 0.226, 0.205, 0.212, 0.20, 0.18};
        for (int i = 0; i < 140; i++) {
            double amplitude = amplitudes[i % amplitudes.length];
            assertEquals(SpectrumDsp.mapAmplitude(0, amplitude), bass.level(amplitude, i * 50), 0);
        }
        assertEquals(0, bass.onsets());
    }

    @Test public void realAttackReachesFullHeightThroughExistingViewSmoothingThenFalls() {
        BassResponse bass = new BassResponse();
        SpectrumMotionFixture motion = new SpectrumMotionFixture();
        float[] frame = new float[6];
        frame[0] = bass.level(0.05, 0); motion.frame(frame); motion.advance(50);
        frame[0] = bass.level(0.18, 50);
        assertTrue(frame[0] > 0.95);
        motion.frame(frame); motion.advance(50);
        frame[0] = bass.level(0.05, 100); motion.frame(frame); motion.advance(50);
        assertTrue("A short measured attack must survive the UI smoothing", motion.height(0) > 0.95);
        for (int now = 150; now <= 500; now += 50) {
            frame[0] = bass.level(0.05, now); motion.frame(frame); motion.advance(50);
        }
        assertTrue(motion.height(0) < 0.3);
        assertEquals(1, bass.onsets());
    }

    @Test public void quietPassagesAndQuantizationDustDoNotBecomeFullHeightAttacks() {
        BassResponse bass = new BassResponse();
        for (int i = 0; i < 100; i++) {
            double amplitude = (i % 2 == 0) ? 0.001 : 0.018;
            assertEquals(SpectrumDsp.mapAmplitude(0, amplitude), bass.level(amplitude, i * 50), 0);
        }
        assertEquals(0, bass.onsets());
    }

    @Test public void oneAttackCanRefineItsPeakWithoutRetriggeringOnEachFrame() {
        BassResponse bass = new BassResponse();
        bass.level(0.05, 0);
        float first = bass.level(0.11, 50);
        float peak = bass.level(0.18, 100);
        assertTrue(peak > first);
        assertEquals(1, bass.onsets());
        for (int now = 150; now < 800; now += 50) bass.level(0.18, now);
        assertEquals(1, bass.onsets());
        assertEquals(SpectrumDsp.mapAmplitude(0, 0.18), bass.level(0.18, 800), 0.0001);
    }

    @Test public void silenceAndCaptureGapsClearTheAccent() {
        BassResponse bass = new BassResponse();
        bass.level(0.05, 0); bass.level(0.18, 50);
        assertEquals(0, bass.level(0, 100), 0);
        assertEquals(SpectrumDsp.mapAmplitude(0, 0.18), bass.level(0.18, 500), 0);
        assertEquals(0, bass.level(Double.NaN, 550), 0);
    }
}
