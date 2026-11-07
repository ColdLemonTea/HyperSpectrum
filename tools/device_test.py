#!/usr/bin/env python3
# Copyright 2026 wxxsfxyzm
# SPDX-License-Identifier: Apache-2.0

"""Build/install the Java probe, prove API-102 reload, capture and assess FFT logs."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "io.github.wxxsfxyzm.hyperspectrum"
TAG = "IslandSpectrumProbe"


def analyze(text: str) -> dict:
    """Only pair complete consecutive playing/paused windows in one build and PID."""
    summaries = []
    for line in text.splitlines():
        if "capture_summary " not in line or TAG not in line:
            continue
        fields = dict(re.findall(r"([A-Za-z]+)=([^\s]+)", line))
        try:
            fields.update({key: int(fields[key]) for key in
                           ("pid", "session", "frames", "nonzero", "changed", "durationMs")})
            fields["meanEnergy"] = float(fields["meanEnergy"])
        except (KeyError, ValueError):
            continue
        summaries.append(fields)
    pairs = []
    for playing, paused in zip(summaries, summaries[1:]):
        if (playing.get("build"), playing["pid"]) != (paused.get("build"), paused["pid"]):
            continue
        if playing.get("mode") != "playing" or paused.get("mode") != "paused":
            continue
        if paused["session"] != playing["session"] + 1:
            continue
        complete = (playing.get("reason") == paused.get("reason") == "deadline"
                    and playing["durationMs"] >= 12000 and paused["durationMs"] >= 3000)
        changing = (playing.get("signal") == "changing_nonzero" and playing["frames"] >= 20
                    and playing["nonzero"] >= 10 and playing["changed"] >= 10
                    and playing["meanEnergy"] > 0)
        quieter = (paused.get("signal") not in ("error", "no_callbacks")
                   and paused["frames"] >= 10
                   and paused["meanEnergy"] <= playing["meanEnergy"] * 0.2)
        pairs.append({"build": playing.get("build"), "pid": playing["pid"],
                      "playingSession": playing["session"], "complete": complete,
                      "changing": changing, "quieterAfterPause": quieter,
                      "candidate": complete and changing and quieter})
    return {"result": "candidate" if any(p["candidate"] for p in pairs) else "inconclusive",
            "meaning": "FFT data candidate; does not prove per-app isolation or island rendering",
            "pairs": pairs, "summaries": summaries}


class Device:
    def __init__(self, serial: str | None, sdk: Path):
        self.adb = sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        if not self.adb.is_file():
            raise RuntimeError(f"adb missing: {self.adb}; pass --sdk")
        devices = self.run([str(self.adb), "devices"]).splitlines()
        online = [line.split()[0] for line in devices if re.search(r"\sdevice$", line)]
        if serial is None:
            if len(online) != 1:
                raise RuntimeError("Specify --serial with exactly one online target; no device selected")
            serial = online[0]
        if serial not in online:
            raise RuntimeError(f"Device {serial} is not online")
        self.prefix = [str(self.adb), "-s", serial]

    @staticmethod
    def run(args: list[str], **kwargs) -> str:
        result = subprocess.run(args, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=kwargs.pop("timeout", 60), **kwargs)
        if result.returncode:
            raise RuntimeError(f"Command failed ({result.returncode}): {result.stdout.strip()}")
        return result.stdout.strip()

    def adb_run(self, *args: str) -> str:
        return self.run([*self.prefix, *args])

    def root(self, command: str) -> str:
        return self.adb_run("shell", "su -c " + shlex.quote(command))

    def identity(self) -> tuple[str, str]:
        pid = self.adb_run("shell", "pidof com.android.systemui")
        if not re.fullmatch(r"\d+", pid):
            raise RuntimeError(f"Expected one SystemUI process, got {pid!r}")
        stat = self.root("cat /proc/" + pid + "/stat")
        # Fields after final ')' begin at field 3; starttime is field 22.
        return pid, stat[stat.rfind(")") + 2:].split()[19]

    def log_snapshot(self) -> tuple[str, int]:
        text = self.root('path="$(ls -1t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -n 1)"; '
                         '[ -n "$path" ] || exit 1; printf "%s\\n" "$path"; stat -c "%s" "$path"')
        lines = text.splitlines()
        if len(lines) != 2 or not re.fullmatch(r"/data/adb/lspd/log/modules_[^/]+\.log", lines[0]):
            raise RuntimeError("LSPosed module log snapshot unavailable")
        return lines[0], int(lines[1])

    def log_delta(self, before: tuple[str, int]) -> str:
        path, size = self.log_snapshot()
        if path != before[0] or size < before[1]:
            raise RuntimeError("LSPosed log rotated; fresh hot-reload evidence cannot be established")
        return self.root(f"tail -c +{before[1] + 1} {shlex.quote(path)}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path,
                        default=Path(os.environ.get("ANDROID_HOME")
                                     or os.environ.get("ANDROID_SDK_ROOT")
                                     or Path.home() / "Library/Android/sdk"),
                        help="Android SDK path; defaults to ANDROID_HOME, ANDROID_SDK_ROOT, or the macOS SDK directory")
    parser.add_argument("--serial")
    sub = parser.add_subparsers(dest="command", required=True)
    for command, help_text in (("install", "Build/install initial APK; enable its scope manually in LSPosed"),
                               ("reload", "Build/install update and require fresh hot reload with same PID/starttime")):
        build_command = sub.add_parser(command, help=help_text)
        build_command.add_argument("--diagnostic", action="store_true", help="Use the old bounded FFT probe instead of the live UI")
        build_command.add_argument("--release", action="store_true", help="Build and install the signed, optimized Release APK")
        build_command.add_argument("--native", action="store_true", help="Use the exact built native tap; require out/native-tap/manifest.json")
        build_command.add_argument("--native-trial", action="store_true", help="Explicit controlled trial of a quarantined native build; requires --native and an exact installed library hash")
    capture = sub.add_parser("capture", help="Capture fresh module logcat while user plays then pauses")
    capture.add_argument("--seconds", type=int, default=40)
    capture.add_argument("--output", type=Path, default=ROOT / "out" / "capture.log")
    capture.add_argument("--source", choices=("lsposed", "logcat"), default="lsposed")
    assess = sub.add_parser("analyze")
    assess.add_argument("log", type=Path)
    args = parser.parse_args()
    if args.command == "analyze":
        report = analyze(args.log.read_text(encoding="utf-8"))
        print(json.dumps(report, indent=2, ensure_ascii=False))
        return 0 if report["result"] == "candidate" else 2
    device = Device(args.serial, args.sdk)
    if args.command in ("install", "reload"):
        build = "probe-" + uuid.uuid4().hex
        before = identity = None
        if args.command == "reload":
            identity = device.identity()
            before = device.log_snapshot()
        variant = "release" if args.release else "debug"
        task = ":app:assembleRelease" if args.release else ":app:assembleDebug"
        native_args = []
        if args.native_trial and not args.native:
            raise RuntimeError("--native-trial requires --native")
        if args.native:
            native_manifest = json.loads((ROOT / "out/native-tap/manifest.json").read_text())
            if native_manifest.get("do_not_deploy", False) and not args.native_trial:
                raise RuntimeError("Native build is quarantined: " + native_manifest.get("validation_status", "unvalidated"))
            installed_hash = device.root("sha256sum /data/adb/modules/hyperspectrum-tap-zn/lib/libhyperspectrum_tap.so").split()[0]
            if installed_hash != native_manifest["library_sha256"]:
                raise RuntimeError("Installed native library differs from the local build; APK not installed")
            native_args = ["-PnativeCapture=true", "-PnativeTapBuild=" + native_manifest["source_sha256"]]
        Device.run([str(ROOT / "gradlew"), task, f"-PprobeBuild={build}", *native_args,
                    "-PdiagnosticProbe=" + str(args.diagnostic).lower(),
                    "--console=plain"], cwd=ROOT, timeout=600)
        apk = ROOT / f"app/build/outputs/apk/{variant}/app-{variant}.apk"
        if not apk.is_file():
            raise RuntimeError(f"Installable APK missing: {apk}. Release requires complete signing credentials.")
        print(device.adb_run("install", "-r", str(apk)), flush=True)
        output = ROOT / "out" / build
        output.mkdir(parents=True)
        if args.command == "install":
            print(f"Installed {build}. Enable ONLY com.android.systemui in LSPosed. "
                  "Cold activation is separate from reload verification.")
            return 0
        delta = ""
        ready = r".*mediaReady=true" + ("" if args.diagnostic else r".*renderReady=true")
        marker = re.compile(re.escape(f"build={build}") + r" pid=\d+ hot_reloaded " + ready)
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            if device.identity() != identity:
                raise RuntimeError("SystemUI PID/starttime changed: reload verification failed")
            delta = device.log_delta(before)
            if marker.search(delta):
                # Check again after receiving completion, not just before reading the log.
                if device.identity() != identity:
                    raise RuntimeError("SystemUI restarted at reload completion")
                (output / "hot-reload.log").write_text(delta, encoding="utf-8")
                (output / "result.json").write_text(json.dumps({"build": build, "pid": identity[0],
                    "starttime": identity[1], "api102HotReload": "verified"}, indent=2), encoding="utf-8")
                print(f"API 102 reload verified, SystemUI PID/starttime unchanged. Evidence: {output}")
                return 0
            time.sleep(0.5)
        (output / "hot-reload.log").write_text(delta, encoding="utf-8")
        raise RuntimeError("No fresh matching build hot_reloaded/mediaReady=true evidence within 25 seconds")
    if not 5 <= args.seconds <= 60:
        raise RuntimeError("Capture duration must be 5..60 seconds")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    print("现在播放音乐，等待至少 13 秒；再暂停，等待至少 4 秒。不要切换投放设备。", flush=True)
    if args.source == "lsposed":
        before = device.log_snapshot()
        deadline = time.monotonic() + args.seconds
        while time.monotonic() < deadline:
            time.sleep(min(1, max(0, deadline - time.monotonic())))
        # Write only this module's records; other enabled modules may log personal information.
        lines = [line for line in device.log_delta(before).splitlines() if TAG in line]
        args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    else:
        # -T 1 retains at most one prior record; do not clear other applications' log buffers.
        with args.output.open("w", encoding="utf-8") as target:
            process = subprocess.Popen([*device.prefix, "logcat", "-v", "brief", "-T", "1",
                                        "-s", TAG + ":I", "*:S"], stdout=target, stderr=subprocess.PIPE)
            try:
                time.sleep(args.seconds)
            finally:
                process.terminate()
                _, errors = process.communicate(timeout=5)
            if process.returncode not in (0, -15):
                raise RuntimeError("logcat capture failed: " + errors.decode(errors="replace"))
    report = analyze(args.output.read_text(encoding="utf-8"))
    args.output.with_suffix(".json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"{report['result']}; evidence: {args.output}")
    return 0 if report["result"] == "candidate" else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError, subprocess.SubprocessError, ValueError) as failure:
        raise SystemExit(str(failure))
