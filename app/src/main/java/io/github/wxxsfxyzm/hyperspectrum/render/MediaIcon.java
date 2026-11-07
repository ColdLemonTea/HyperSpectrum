// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package io.github.wxxsfxyzm.hyperspectrum.render;

/** Only media waveform icons and an independently verified wired device icon. */
final class MediaIcon {
    static boolean wiredDevice(String pic, String route) {
        return "miui_music_cast_others".equals(pic) && "wired".equals(route);
    }
    static boolean replace(String pic, String route) {
        return "musicWave".equals(pic) || "musicPause".equals(pic) || wiredDevice(pic, route);
    }
    static boolean playing(String pic, String route, boolean mediaPlaying) {
        return "musicWave".equals(pic) || (wiredDevice(pic, route) && mediaPlaying);
    }
    private MediaIcon() { }
}
