// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package io.github.wxxsfxyzm.hyperspectrum.render;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class AppleArtworkTest {
    private static int[] solid(int color) { int[] p = new int[AppleArtwork.COUNT]; Arrays.fill(p, color); return p; }
    @Test public void opaqueColorsMatchSwiftUiImageReferencePixels() {
        // macOS SwiftUI ImageRenderer, sRGB RGBA8 Image (not a Color view),
        // saturation -> black overlay -> white overlay -> compositingGroup.
        int[][] cases = {
                {0xffff0000, 0xffff0e0e}, {0xff0000ff, 0xff4343ff},
                {0xff104050, 0xff6092ad}, {0xffd08020, 0xffe47e03},
                {0xff00ff00, 0xff00ee00}, {0xff404040, 0xff838383}
        };
        for (int[] sample : cases) {
            assertEquals(Integer.toHexString(sample[0]), sample[1], AppleArtwork.field(solid(sample[0]))[0]);
        }
    }
    @Test public void sourceAnalysisPrecedesImageResamplingAndSupportsPartialRows() {
        int[] source = {0xffff0000, 0xff0000ff};
        var full = AppleArtwork.treatment(source);
        assertEquals((Math.sqrt(0.299) + Math.sqrt(0.114)) / 2, full.luminance(), 1e-12);
        var analysis = new AppleArtwork.Analysis();
        analysis.add(new int[]{source[0], 0xffffffff}, 1);
        analysis.add(new int[]{source[1], 0xffffffff}, 1);
        assertEquals(full, analysis.treatment());
        assertEquals(CoverColors.fingerprint(source), analysis.fingerprint());
        int[] resampled = solid(0xff800080);
        assertTrue(full.luminance() > AppleArtwork.treatment(resampled).luminance());
        assertNotEquals(AppleArtwork.field(resampled)[0], AppleArtwork.field(resampled, full)[0]);
    }
    @Test public void treatmentMatchesDarkMiddleAndBrightReferenceParameters() {
        var dark = AppleArtwork.treatment(solid(0xff404040));
        assertEquals(64 / 255.0, dark.luminance(), 0.000001);
        assertEquals(0.6 - 64 / 255.0, dark.white(), 0.000001);
        assertEquals(1.25 + 4 * dark.white(), dark.saturation(), 0.000001);
        assertEquals(0, dark.black(), 0);
        var mid = AppleArtwork.treatment(solid(0xffa6a6a6));
        assertEquals(1.25, mid.saturation(), 0.000001);
        assertEquals(0, mid.white(), 0); assertEquals(0, mid.black(), 0);
        var bright = AppleArtwork.treatment(solid(0xffe6e6e6));
        assertEquals(230 / 255.0 - 0.7, bright.black(), 0.000001);
        assertEquals(0, bright.white(), 0);
    }
    @Test public void fullSpatialImageIsPrivateAndRetainsLocationBeforeViewSpaceBlur() {
        int[] pixels = solid(0xffff0000);
        for (int y = 0; y < 48; y++) for (int x = 24; x < 48; x++) pixels[y * 48 + x] = 0xff0000ff;
        int[] original = pixels.clone(), a = AppleArtwork.field(pixels), b = AppleArtwork.field(pixels);
        assertArrayEquals(original, pixels); assertArrayEquals(a, b); assertNotSame(a, b);
        assertNotEquals(a[0], a[47]);
        assertEquals(a[0], a[48 * 47]);
        assertEquals(255, a[0] >>> 24);
        a[0] = 0; assertNotEquals(a[0], b[0]);
    }
    @Test public void invisibleRgbDoesNotTintAndNeutralArtworkRemainsNeutral() {
        assertArrayEquals(AppleArtwork.field(solid(0x00ff0000)), AppleArtwork.field(solid(0x0000ff00)));
        for (int c : AppleArtwork.field(solid(0xff333333))) {
            assertEquals((c >>> 16) & 255, (c >>> 8) & 255);
            assertEquals((c >>> 8) & 255, c & 255);
        }
    }
}
