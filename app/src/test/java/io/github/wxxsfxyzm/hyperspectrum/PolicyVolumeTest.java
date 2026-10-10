// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

import org.junit.Test;
import static org.junit.Assert.*;

public class PolicyVolumeTest {
    private static final String GROUPS = """
             Volume Groups dump:
                -alarm (id: 9)
                  Streams: AUDIO_STREAM_ALARM(4)
                -music (id: 4)
                  Volume Curves Streams/Attributes, Curve points
                  Streams: AUDIO_STREAM_MUSIC(3)
                -notification (id: 5)
                  Streams: AUDIO_STREAM_NOTIFICATION(5)
            """;
    private static String client(int session, int uid, String state) {
        return "      1. Port ID: 71; Session ID: " + session + "; uid " + uid + "; State: " + state + "\n"
                + "         Attributes: { Content type: AUDIO_CONTENT_TYPE_MUSIC Usage: AUDIO_USAGE_MEDIA Source: AUDIO_SOURCE_INVALID }\n"
                + "         Stream: 3; Flags: 00000000; Refcount: 0; InternalMute: No\n";
    }
    private static String output(int io, float db, int active, String clients) {
        return "  1. Port ID: 6; I/O handle: " + io + "; IOProfile name:deep_buffer_out\n"
                + "     Devices: {AUDIO_DEVICE_OUT_BLUETOOTH_A2DP, @:AA:BB}\n"
                + "   - Volume Activities (2):\n"
                + "      id 3: - ActivityCount: 0, StopTime: 0, , Volume: -18.000, MuteCount: 00\n"
                + "      id 4: - ActivityCount: " + active + ", StopTime: 0, , Volume: " + db + ", MuteCount: 00\n"
                + "   - AudioTrack clients (1):\n" + clients;
    }
    private static String effect(int io, int session) {
        return "  1. Effect ID: 91; Attached to I/O handle: " + io + "; Session: " + session + ";\n"
                + "     Music Effect? yes; \"Visualizer\"; Enabled; Active\n";
    }
    private static String snapshot(String outputs, String effects) {
        return " Outputs (2):\n" + outputs + " Inputs (0):\n - Effects (1):\n" + effects + GROUPS;
    }
    private static PolicyVolume.Reading read(String text, int session) {
        return PolicyVolume.read(text, "AA:BB", 10123, session);
    }
    private static void unavailable(String text, int session) {
        assertTrue(Float.isNaN(read(text, session).db()));
        assertEquals(-1, read(text, session).output());
    }

    @Test public void selectsAppliedMusicGroupOnTheCaptureMixerAndIgnoresStandbyOutputs() {
        String text = snapshot(output(13, -19, 0, client(51, 10123, "Inactive"))
                + output(21, -6.5f, 1, client(51, 10123, "Active")), effect(21, 0));
        assertEquals(-6.5f, read(text, 0).db(), 0);
        assertEquals(21, read(text, 0).output());
    }

    @Test public void supportsSessionCaptureWithoutBorrowingAnotherSessionOrUid() {
        String text = snapshot(output(21, -11.9f, 1, client(51, 10123, "Active")), effect(21, 51));
        assertEquals(-11.9f, read(text, 51).db(), 0);
        unavailable(text, 52);
        unavailable(text.replace("uid 10123", "uid 10124"), 51);
        unavailable(text.replace("State: Active", "State: Inactive"), 51);
    }

    @Test public void rejectsOtherDeviceDuplicateRoutesOrCaptureOnTheWrongMixer() {
        String text = snapshot(output(21, -6.5f, 1, client(51, 10123, "Active")), effect(21, 0));
        unavailable(text.replace("AA:BB", "AA:CC"), 0);
        unavailable(text.replace("AUDIO_DEVICE_OUT_BLUETOOTH_A2DP", "AUDIO_DEVICE_OUT_SPEAKER"), 0);
        unavailable(text.replace("@:AA:BB}", "@:AA:BB}{AUDIO_DEVICE_OUT_SPEAKER, @:}"), 0);
        unavailable(snapshot(output(21, -6.5f, 1, client(51, 10123, "Active")), effect(13, 0)), 0);
        unavailable(snapshot(output(21, -6.5f, 1, client(51, 10123, "Active")), effect(21, 0) + effect(13, 0)), 0);
    }

    @Test public void ambiguousActiveMusicMuteAndInvalidAttenuationStayUnavailable() {
        String clients = client(51, 10123, "Active") + client(52, 10123, "Active");
        unavailable(snapshot(output(21, -6.5f, 1, clients), effect(21, 0)), 0);
        assertEquals(-6.5f, read(snapshot(output(21, -6.5f, 1, clients), effect(21, 51)), 51).db(), 0);
        String text = snapshot(output(21, -6.5f, 1, client(51, 10123, "Active")), effect(21, 0));
        unavailable(text.replace("MuteCount: 00", "MuteCount: 01"), 0);
        unavailable(text.replace("InternalMute: No", "InternalMute: Yes"), 0);
        unavailable(text.replace("Volume: -6.5", "Volume: -758.0"), 0);
        unavailable(text.replace("Volume: -6.5", "Volume: NaN"), 0);
        unavailable(snapshot(output(21, -6.5f, 0, client(51, 10123, "Active")), effect(21, 0)), 0);
    }

    @Test public void missingOrChangedPolicyFormatCannotGuessAnInverseGain() {
        String text = snapshot(output(21, -6.5f, 1, client(51, 10123, "Active")), effect(21, 0));
        unavailable(text.replace("Streams: AUDIO_STREAM_MUSIC(3)", "Streams: AUDIO_STREAM_UNKNOWN(3)"), 0);
        unavailable(text.replace("I/O handle:", "New I/O handle:"), 0);
        unavailable(text.replace("\"Visualizer\"; Enabled;", "\"Visualizer\"; Disabled;"), 0);
        unavailable(text.replace("Stream: 3;", "Stream: 4;"), 0);
        unavailable("", 0);
        assertTrue(Float.isNaN(PolicyVolume.read(text, "", 10123, 0).db()));
    }
}
