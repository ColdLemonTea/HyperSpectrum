// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package io.github.wxxsfxyzm.hyperspectrum.render;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaIconTest {
    @Test public void onlyTheVerifiedWiredDeviceIconJoinsTheWaveformIcons() {
        for (String route : new String[]{"", "local", "headset", "wired", "others", "cast"}) {
            assertTrue(MediaIcon.replace("musicWave", route));
            assertTrue(MediaIcon.replace("musicPause", route));
            assertEquals("wired".equals(route), MediaIcon.replace("miui_music_cast_others", route));
            for (String pic : new String[]{"cast", "airplay", "navigation", "", "headset"})
                assertFalse(MediaIcon.replace(pic, route));
        }
    }

    @Test public void waveformStateRetainsPauseAndWiredUsesRealPlaybackState() {
        assertTrue(MediaIcon.playing("musicWave", "wired", false));
        assertFalse(MediaIcon.playing("musicPause", "wired", true));
        assertTrue(MediaIcon.playing("miui_music_cast_others", "wired", true));
        assertFalse(MediaIcon.playing("miui_music_cast_others", "wired", false));
        assertFalse(MediaIcon.playing("miui_music_cast_others", "others", true));
    }
}
