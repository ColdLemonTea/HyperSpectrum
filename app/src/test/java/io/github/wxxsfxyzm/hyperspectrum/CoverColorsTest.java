// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

import org.junit.Test;
import static org.junit.Assert.*;

public class CoverColorsTest {
    @Test public void SameCoverIsStableAcrossIndependentDecodes() {
        int[] pixels = {0xffc69c23, 0xffcaa025, 0xff080808, 0xffffffff, 0xffc69c23};
        int[] copy = pixels.clone();
        assertEquals(CoverColors.fingerprint(pixels), CoverColors.fingerprint(copy));
        assertArrayEquals(CoverColors.extract(pixels), CoverColors.extract(copy));
        CoverColors.extract(new int[]{0xff00dd00, 0xff00cc00}); // Unrelated cover cannot contaminate it.
        assertArrayEquals(CoverColors.extract(pixels), CoverColors.extract(copy));
    }

    @Test public void PixelOrderingDoesNotChangeChosenHue() {
        int[] a = {0xffff2222, 0xffff2222, 0xff2222ff, 0xff2222ff};
        int[] b = {a[3], a[2], a[1], a[0]};
        assertArrayEquals(CoverColors.extract(a), CoverColors.extract(b));
    }

    @Test public void TransparentAndAchromaticArtUseNeutralFallback() {
        assertArrayEquals(new int[]{CoverColors.TOP_DEFAULT, CoverColors.BOTTOM_DEFAULT},
                CoverColors.extract(new int[]{0x00ff0000, 0xff050505, 0xffffffff, 0xff777777}));
    }

    @Test public void DominantSaturatedColorSurvivesBlackBackground() {
        int[] pixels = new int[100]; java.util.Arrays.fill(pixels, 0xff000000);
        pixels[0] = pixels[1] = 0xff00aa00;
        int top = CoverColors.extract(pixels)[0];
        assertTrue(((top >>> 8) & 255) > ((top >>> 16) & 255));
        assertEquals(0xee, top >>> 24);
    }

    @Test public void ColorTransitionHasExactEndpointsAndIntermediateColor() {
        int a = 0xeeffff00, b = 0xee00ff00;
        assertEquals(a, CoverColors.blend(a, b, 0));
        assertEquals(b, CoverColors.blend(a, b, 1));
        assertEquals(0xee80ff00, CoverColors.blend(a, b, 0.5f));
    }
}
