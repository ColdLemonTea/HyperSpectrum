// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpectrumMotionTest {
    private SpectrumMotion playing() {
        SpectrumMotion state = new SpectrumMotion();
        state.frame(new float[]{0.8f, 0.7f, 0.6f, 0.5f, 0.4f, 0.3f});
        for (int i = 0; i < 30; i++) state.advance(16);
        return state;
    }

    @Test public void pauseKeepsCurrentHeightsAndDecaysToRest() {
        SpectrumMotion state = playing();
        float before = state.heights[0];
        state.playing(false);
        assertEquals(before, state.heights[0], 0);
        assertTrue(state.advance(100));
        assertTrue(state.heights[0] > 0 && state.heights[0] < before);
        assertFalse(state.advance(SpectrumMotion.PAUSE_MS));
        assertArrayEquals(new float[6], state.heights, 0);
    }

    @Test public void repeatedPauseBindsDoNotRestartDecay() {
        SpectrumMotion state = playing();
        state.playing(false); state.advance(200);
        assertFalse(state.playing(false));
        assertFalse(state.advance(220));
        assertArrayEquals(new float[6], state.heights, 0);
    }

    @Test public void RapidResumeRetainsHeightsAndRejectsOldTarget() {
        SpectrumMotion state = playing();
        state.playing(false); state.advance(100);
        float before = state.heights[0];
        state.playing(true);
        assertEquals(before, state.heights[0], 0);
        state.advance(16); // Waiting for new audio must not jump back to the old playing peak.
        assertTrue(state.heights[0] < before);
        state.frame(new float[]{1, 1, 1, 1, 1, 1}); state.advance(16);
        assertTrue(state.heights[0] > before);
    }

    @Test public void LateAudioCannotReactivatePausedBars() {
        SpectrumMotion state = playing();
        state.playing(false); state.advance(SpectrumMotion.PAUSE_MS);
        state.frame(new float[]{1, 1, 1, 1, 1, 1}); state.advance(16);
        assertArrayEquals(new float[6], state.heights, 0);
    }

    @Test public void HiddenPauseFinishesWithoutDrawing() {
        SpectrumMotion state = playing();
        state.playing(false); state.hidden();
        assertArrayEquals(new float[6], state.heights, 0);
        assertFalse(state.advance(16));
    }
}
