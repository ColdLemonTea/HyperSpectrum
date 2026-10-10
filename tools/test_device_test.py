# Copyright 2026 wxxsfxyzm
# SPDX-License-Identifier: Apache-2.0

import unittest
from device_test import analyze


def summary(session, mode, *, build="one", pid=42, signal="changing_nonzero",
            frames=240, nonzero=230, changed=220, energy=12, reason="deadline"):
    duration = 12000 if mode == "playing" else 3000
    return (f"I/IslandSpectrumProbe: build={build} pid={pid} capture_summary session={session} "
            f"mode={mode} reason={reason} durationMs={duration} frames={frames} "
            f"nonzero={nonzero} changed={changed} peak=32 meanEnergy={energy} signal={signal}")


class EvidenceTest(unittest.TestCase):
    def test_changing_music_and_quiet_pause_are_candidate(self):
        data = summary(1, "playing") + "\n" + summary(2, "paused", energy=0, signal="silent")
        self.assertEqual("candidate", analyze(data)["result"])

    def test_zero_or_no_callbacks_not_candidate(self):
        for signal in ("silent", "no_callbacks", "constant_nonzero", "error"):
            data = summary(1, "playing", signal=signal) + "\n" + summary(2, "paused", energy=0)
            self.assertEqual("inconclusive", analyze(data)["result"])

    def test_never_pairs_different_generation_or_pid(self):
        for kwargs in ({"build": "two"}, {"pid": 43}):
            data = summary(1, "playing") + "\n" + summary(2, "paused", energy=0, **kwargs)
            self.assertEqual("inconclusive", analyze(data)["result"])

    def test_short_or_hot_reload_window_not_candidate(self):
        for reason in ("hot_reload", "island_removed", "media_changed", "setup_failed"):
            data = summary(1, "playing", reason=reason) + "\n" + summary(2, "paused", energy=0)
            self.assertEqual("inconclusive", analyze(data)["result"])

    def test_background_mix_still_active_is_inconclusive(self):
        data = summary(1, "playing") + "\n" + summary(2, "paused", energy=10)
        self.assertEqual("inconclusive", analyze(data)["result"])

    def test_missing_session_does_not_pair(self):
        data = summary(1, "playing") + "\n" + summary(3, "paused", energy=0)
        self.assertEqual("inconclusive", analyze(data)["result"])

    def test_malformed_summary_ignored(self):
        self.assertEqual([], analyze("IslandSpectrumProbe capture_summary frames=broken")["summaries"])


if __name__ == "__main__":
    unittest.main()
