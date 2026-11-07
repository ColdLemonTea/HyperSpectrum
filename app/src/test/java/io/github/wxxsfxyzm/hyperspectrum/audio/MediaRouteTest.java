// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package io.github.wxxsfxyzm.hyperspectrum.audio;

import android.media.AudioDeviceInfo;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaRouteTest {
    @Test public void verifiedLocalAnalogAndUsbOutputsAreWired() {
        for (int type : new int[]{AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY}) {
            assertEquals("wired", MediaRoute.classify("others", true, new int[]{type}));
        }
    }

    @Test public void connectedWiredHardwareDoesNotTurnRemotePlaybackIntoLocalPlayback() {
        assertEquals("others", MediaRoute.classify("others", false, new int[]{AudioDeviceInfo.TYPE_USB_HEADSET}));
        assertEquals("cast", MediaRoute.classify("cast", true, new int[]{AudioDeviceInfo.TYPE_USB_HEADSET}));
    }

    @Test public void speakerBluetoothUnknownAndDuplicatedOutputsDoNotQualify() {
        for (int[] outputs : new int[][]{{AudioDeviceInfo.TYPE_BUILTIN_SPEAKER},
                {AudioDeviceInfo.TYPE_BLUETOOTH_A2DP}, {AudioDeviceInfo.TYPE_UNKNOWN}, {},
                {AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}}) {
            assertEquals("others", MediaRoute.classify("others", true, outputs));
        }
    }

    @Test public void existingRoutesKeepTheirMeaningAndWiredAllowsCapture() {
        for (String route : new String[]{"", "local", "headset", "wired"}) {
            assertTrue(MediaRoute.isLocal(route));
            assertEquals(route, MediaRoute.classify(route, true, new int[]{AudioDeviceInfo.TYPE_USB_HEADSET}));
        }
        assertFalse(MediaRoute.isLocal("others"));
        assertFalse(MediaRoute.isLocal("cast"));
        assertFalse(MediaRoute.isLocal(null));
    }
}
