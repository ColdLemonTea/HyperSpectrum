// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum.render;

import io.github.wxxsfxyzm.hyperspectrum.config.SpectrumPreferences;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class CoverColorsTest {
    @Test public void originalGradientRetainsHostArgbWithoutArtworkColorAdjustments() {
        int top = 0x997b92a8, bottom = 0x99c18558;
        int[] field = CoverColors.gradient(top, bottom);
        assertEquals(top, at(field, 0, 0));
        assertEquals(bottom, at(field, 11, 11));
        for (int y = 0; y < CoverColors.SIZE; y++) for (int x = 0; x < CoverColors.SIZE; x++) {
            assertEquals(153, at(field, x, y) >>> 24);
            assertEquals(at(field, 0, y), at(field, x, y));
        }
        field[0] = 0;
        assertEquals(top, CoverColors.gradient(top, bottom)[0]);
    }

    @Test public void originalIsDefaultWhileExistingColorSchemeSelectionsPersist() {
        assertEquals(SpectrumPreferences.ORIGINAL, SpectrumPreferences.DEFAULT_COLOR_SCHEME);
        assertEquals(SpectrumPreferences.ORIGINAL, SpectrumPreferences.colorScheme(null));
        assertEquals(SpectrumPreferences.ORIGINAL, SpectrumPreferences.colorScheme("unknown"));
        for (String scheme : new String[]{SpectrumPreferences.HYPEROS, SpectrumPreferences.IOS, SpectrumPreferences.ORIGINAL}) {
            assertEquals(scheme, SpectrumPreferences.colorScheme(scheme));
        }
    }

    private static int[] solid(int color) {
        int[] pixels = new int[48 * 48]; Arrays.fill(pixels, color); return pixels;
    }
    private static int at(int[] field, int x, int y) { return field[y * CoverColors.SIZE + x]; }
    private static int red(int color) { return (color >>> 16) & 255; }
    private static int green(int color) { return (color >>> 8) & 255; }
    private static int blue(int color) { return color & 255; }

    @Test public void sameCoverIsStableAndUsesIndependentUnmodifiedBuffers() {
        int[] pixels = solid(0xffc69c23), original = pixels.clone();
        int[] first = CoverColors.field(pixels, 48, 48);
        assertArrayEquals(original, pixels);
        CoverColors.field(solid(0xff00dd00), 48, 48);
        int[] second = CoverColors.field(pixels, 48, 48);
        assertNotSame(first, second); assertArrayEquals(first, second);
        assertEquals(CoverColors.fingerprint(pixels), CoverColors.fingerprint(original));
        first[0] = 0;
        assertArrayEquals(second, CoverColors.field(pixels, 48, 48));
    }

    @Test public void allFourCoverRegionsRemainAtTheirOwnHorizontalAndVerticalPositions() {
        int[] pixels = new int[48 * 48];
        for (int y = 0; y < 48; y++) for (int x = 0; x < 48; x++) {
            pixels[y * 48 + x] = y < 24 ? (x < 24 ? 0xffff0000 : 0xff00ff00)
                    : (x < 24 ? 0xff0000ff : 0xffffff00);
        }
        int[] field = CoverColors.field(pixels, 48, 48);
        int tl = at(field, 1, 1), tr = at(field, 10, 1), bl = at(field, 1, 10), br = at(field, 10, 10);
        assertTrue(red(tl) > green(tl) * 3 && red(tl) > blue(tl) * 3);
        assertTrue(green(tr) > red(tr) * 3 && green(tr) > blue(tr) * 3);
        assertTrue(blue(bl) > red(bl) * 3 && blue(bl) > green(bl) * 3);
        assertTrue(red(br) > blue(br) * 3 && green(br) > blue(br) * 3);
        assertTrue(CoverColors.distinct(field) > 4);
    }

    @Test public void equalGlobalColorHistogramsWithDifferentLayoutsProduceDifferentFields() {
        int[] a = new int[48 * 48], b = new int[a.length];
        for (int y = 0; y < 48; y++) for (int x = 0; x < 48; x++) {
            a[y * 48 + x] = x < 24 ? 0xffff0000 : 0xff0000ff;
            b[y * 48 + x] = x < 24 ? 0xff0000ff : 0xffff0000;
        }
        int[] leftRed = CoverColors.field(a, 48, 48), leftBlue = CoverColors.field(b, 48, 48);
        assertFalse(Arrays.equals(leftRed, leftBlue));
        assertNotEquals(CoverColors.fingerprint(a), CoverColors.fingerprint(b));
        assertTrue(red(at(leftRed, 2, 6)) > blue(at(leftRed, 2, 6)));
        assertTrue(blue(at(leftBlue, 2, 6)) > red(at(leftBlue, 2, 6)));
        assertEquals(at(leftRed, 2, 6), at(leftBlue, 9, 6));
    }

    @Test public void nonSquareCoversAndFarEdgesContributeWithoutCropping() {
        int[] pixels = new int[48 * 24]; Arrays.fill(pixels, 0xff0000ff);
        for (int y = 0; y < 24; y++) for (int x = 44; x < 48; x++) pixels[y * 48 + x] = 0xffff0000;
        int[] field = CoverColors.field(pixels, 48, 24);
        assertTrue(red(at(field, 11, 6)) > red(at(field, 0, 6)));
        assertTrue(blue(at(field, 0, 6)) > red(at(field, 0, 6)));
    }

    @Test public void smallArtworkDetailsMergeIntoBroadRegionsWithoutKeepingTheirPositions() {
        int[] a = solid(0xff0000ff), b = a.clone();
        for (int y = 0; y < 48; y++) for (int x = 0; x < 48; x++) {
            if (x % 12 < 4 && y % 12 < 4) a[y * 48 + x] = 0xffff0000;
            if (x % 12 >= 8 && y % 12 >= 8) b[y * 48 + x] = 0xffff0000;
        }
        assertNotEquals(CoverColors.fingerprint(a), CoverColors.fingerprint(b));
        int[] field = CoverColors.field(a, 48, 48);
        assertArrayEquals(field, CoverColors.field(b, 48, 48));
        assertEquals("Tiny repeated details must not create patches", 1, CoverColors.distinct(field));
        assertTrue("Small red spots still contribute to the broad color", red(field[0]) >
                red(CoverColors.field(solid(0xff0000ff), 48, 48)[0]));
    }

    @Test public void invisibleRgbCannotTintVisibleRegionsOrMissingArtwork() {
        int[] a = solid(0xff0000ff), b = a.clone();
        for (int y = 0; y < 48; y++) for (int x = 0; x < 12; x++) {
            a[y * 48 + x] = 0x00ff0000; b[y * 48 + x] = 0x0000ff00;
        }
        assertArrayEquals(CoverColors.field(a, 48, 48), CoverColors.field(b, 48, 48));
        assertArrayEquals(CoverColors.fallback(), CoverColors.field(solid(0x00ff0000), 48, 48));
        int[] fallback = CoverColors.fallback(); fallback[0] = 0;
        assertEquals(CoverColors.TOP_DEFAULT, CoverColors.fallback()[0]);
    }

    @Test public void darkChromaticAndAchromaticArtStayLegibleWithoutInventingHues() {
        int[] greenField = CoverColors.field(solid(0xff002000), 48, 48);
        for (int color : greenField) {
            assertTrue(green(color) > red(color) * 2 && green(color) > blue(color) * 2);
            assertTrue(green(color) >= 128); assertEquals(0xee, color >>> 24);
        }
        for (int neutral : new int[]{0xff000000, 0xff333333, 0xffdddddd, 0xffffffff}) {
            for (int color : CoverColors.field(solid(neutral), 48, 48)) {
                assertEquals(red(color), green(color)); assertEquals(green(color), blue(color));
            }
        }
    }

    @Test public void saturatedCoversReceiveASoftGrayVeilWithoutLosingTheirColor() {
        for (int source : new int[]{0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffff00}) {
            int color = CoverColors.field(solid(source), 48, 48)[0];
            int max = Math.max(red(color), Math.max(green(color), blue(color)));
            int min = Math.min(red(color), Math.min(green(color), blue(color)));
            assertTrue("Primaries should be softened", min >= 40 && max <= 210);
            assertTrue("Cover color should remain visible", max - min >= 90);
            assertTrue("No highly saturated neon field", max - min <= 160);
            assertEquals(red(source) > 0, red(color) == max);
            assertEquals(green(source) > 0, green(color) == max);
            assertEquals(blue(source) > 0, blue(color) == max);
        }
    }

    @Test public void invalidSourceDimensionsAreRejected() {
        for (int[] size : new int[][]{{0, 48}, {48, 0}, {3, 768}, {48, 47}}) {
            try { CoverColors.field(new int[48 * 48], size[0], size[1]); fail("Accepted invalid dimensions"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void colorTransitionHasExactEndpointsAndIntermediateColor() {
        int a = 0xeeffff00, b = 0xee00ff00;
        assertEquals(a, CoverColors.blend(a, b, 0));
        assertEquals(b, CoverColors.blend(a, b, 1));
        assertEquals(0xee80ff00, CoverColors.blend(a, b, 0.5f));
    }
}
