// Copyright 2026 wxxsfxyzm
// SPDX-License-Identifier: Apache-2.0

package io.github.wxxsfxyzm.hyperspectrum;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AudioSessionSelectorTest {
    @Test public void selectsOnlyTheRequestedAppsActiveMediaSession() {
        var candidates = List.of(new AudioSessionSelector.Candidate(20, 7, true, true),
                new AudioSessionSelector.Candidate(10, 3, false, true),
                new AudioSessionSelector.Candidate(10, 4, true, false),
                new AudioSessionSelector.Candidate(10, 5, true, true));
        assertEquals(5, AudioSessionSelector.select(10, candidates).sessionId());
    }

    @Test public void unknownAndInvalidSessionsNeverSelectTheOutputMix() {
        var candidates = List.of(new AudioSessionSelector.Candidate(10, 0, true, true),
                new AudioSessionSelector.Candidate(10, -1, true, true),
                new AudioSessionSelector.Candidate(-1, 6, true, true));
        assertEquals(-1, AudioSessionSelector.select(10, candidates).sessionId());
        assertEquals(-1, AudioSessionSelector.select(-1, candidates).sessionId());
        assertEquals(-1, AudioSessionSelector.select(11, candidates).sessionId());
        assertEquals(-1, AudioSessionSelector.select(10, List.of()).sessionId());
    }

    @Test public void multiplePlayersSharingOneSessionAreUnambiguous() {
        var candidate = new AudioSessionSelector.Candidate(10, 5, true, true);
        assertEquals(5, AudioSessionSelector.select(10, List.of(candidate, candidate)).sessionId());
    }

    @Test public void crossfadeWithIndependentSessionsMustNotGuess() {
        var first = new AudioSessionSelector.Candidate(10, 5, true, true);
        var second = new AudioSessionSelector.Candidate(10, 6, true, true);
        for (var candidates : List.of(List.of(first, second), List.of(second, first))) {
            var result = AudioSessionSelector.select(10, candidates);
            assertEquals(-1, result.sessionId());
            assertEquals("ambiguous_media_sessions", result.reason());
        }
        assertEquals(6, AudioSessionSelector.select(10,
                List.of(new AudioSessionSelector.Candidate(10, 5, false, true), second)).sessionId());
    }
}
