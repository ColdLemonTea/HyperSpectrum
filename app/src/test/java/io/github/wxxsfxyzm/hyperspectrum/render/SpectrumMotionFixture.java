// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package io.github.wxxsfxyzm.hyperspectrum.render;

/** Test-only access to rendering for the audio-to-motion regression. */
public final class SpectrumMotionFixture {
    private final SpectrumMotion motion = new SpectrumMotion();
    public void frame(float[] levels) { motion.frame(levels); }
    public void advance(float elapsedMs) { motion.advance(elapsedMs); }
    public float height(int band) { return motion.heights[band]; }
}
