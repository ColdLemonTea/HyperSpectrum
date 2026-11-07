// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0
package io.github.wxxsfxyzm.hyperspectrum.audio;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class NativeSpectrumProtocolTest {
    private static final String BUILD = "a".repeat(64);
    private static ByteBuffer packet(int type) {
        ByteBuffer b = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x48535450).putInt(2).putInt(type).putInt(128).putLong(42).putLong(7);
        return b;
    }
    @Test public void authenticatesThePinnedProducerBuild() {
        ByteBuffer b = packet(1); b.position(32); b.put(BUILD.getBytes(StandardCharsets.US_ASCII));
        b.putInt(96, 4); b.putInt(100, 2993);
        assertEquals(42, NativeSpectrumProtocol.hello(b.array(), BUILD).instance());
        assertThrows(IllegalArgumentException.class, () -> NativeSpectrumProtocol.hello(b.array(), "b".repeat(64)));
        b.putInt(96, 3);
        assertThrows(IllegalArgumentException.class, () -> NativeSpectrumProtocol.hello(b.array(), BUILD));
    }
    @Test public void decodesRealAmplitudesAndRejectsNonFiniteSignal() {
        ByteBuffer b = packet(3);
        b.putLong(32, 9).putLong(40, 1000000000L);
        b.putInt(48, 10343).putInt(52, 273).putInt(56, 93).putInt(60, 1);
        for (int i = 0; i < 6; i++) b.putFloat(64 + i * 4, i * 0.01f);
        b.putFloat(88, 0.25f).putInt(92, 44100).putInt(96, 56).putInt(100, 2);
        b.putLong(104, 1233).putLong(112, 512).putLong(120, 0);
        var f = NativeSpectrumProtocol.frame(b.array());
        assertEquals(7, f.generation()); assertEquals(56, f.track()); assertEquals(1233, f.callbacks());
        assertEquals(0.05f, f.bands()[5], 0.00001f);
        assertTrue(NativeSpectrumProtocol.usable(f, 42, 7, 10343, 273, 1100, 8));
        assertFalse(NativeSpectrumProtocol.usable(f, 42, 8, 10343, 273, 1100, 8));
        assertFalse(NativeSpectrumProtocol.usable(f, 43, 7, 10343, 273, 1100, 8));
        assertFalse(NativeSpectrumProtocol.usable(f, 42, 7, 10344, 273, 1100, 8));
        assertFalse(NativeSpectrumProtocol.usable(f, 42, 7, 10343, 273, 1300, 8));
        assertFalse(NativeSpectrumProtocol.usable(f, 42, 7, 10343, 273, 900, 8));
        assertFalse(NativeSpectrumProtocol.usable(f, 42, 7, 10343, 273, 1100, 9));
        b.putInt(100, 12);
        assertEquals(12, NativeSpectrumProtocol.frame(b.array()).channels());
        b.putInt(100, 13);
        assertThrows(IllegalArgumentException.class, () -> NativeSpectrumProtocol.frame(b.array()));
        b.putInt(100, 0);
        assertThrows(IllegalArgumentException.class, () -> NativeSpectrumProtocol.frame(b.array()));
        b.putInt(100, 2);
        b.putInt(56, 213);
        assertTrue(NativeSpectrumProtocol.usable(NativeSpectrumProtocol.frame(b.array()), 42, 7, 10343, 273, 1100, 8));
        b.putInt(56, 0);
        assertFalse(NativeSpectrumProtocol.usable(NativeSpectrumProtocol.frame(b.array()), 42, 7, 10343, 273, 1100, 8));
        b.putFloat(64, Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> NativeSpectrumProtocol.frame(b.array()));
    }
    @Test public void encodesExplicitInactiveRequestsWithGeneration() {
        byte[] bytes = NativeSpectrumProtocol.request(42, 11, 3, 10343, 273, false);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(128, bytes.length); assertEquals(2, b.getInt(8));
        assertEquals(11, b.getLong(24)); assertEquals(3, b.getLong(32));
        assertEquals(10343, b.getInt(48)); assertEquals(0, b.getInt(56)); assertEquals(0, b.getInt(60));
        b.putInt(4, 1);
        assertThrows(IllegalArgumentException.class, () -> NativeSpectrumProtocol.frame(bytes));
    }
}
