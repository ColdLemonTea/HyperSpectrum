# HyperSpectrum AudioFlinger probe

This is an experimental backend for an exact ROM build. Compatibility with
other ZN audio modules is not guaranteed; generated packages remain quarantined
for explicit device trials.

Independent, default-off research module for Zygisk Next 1.5.2 (API 4), arm64,
Android 17 / HyperOS OS4.0.6.0.XPBCNXM. Unknown audio library builds are rejected.
It computes source PCM frequency bands for a matching HyperSpectrum APK and
also reports bounded diagnostic energy statistics. The IPC ABI is version 2.
The PLT-based revision is required; the earlier inline probe is unsupported.

Build and run the host sanitizer checks with the installed Android SDK CMake
4.1.2 and NDK 30.0.16138531:

```sh
python3 native/spectrum-tap-zn/build.py --sdk SDK_PATH
```

The module ZIP and build manifest are written to `out/native-tap/`. Build the
APK with `-PnativeCapture=true -PnativeTapBuild=SOURCE_HASH`, using the
`source_sha256` from that manifest, or use the device script's `--native` option.
Ordinary APK builds keep the existing Visualizer backend. A native build
uses resting capsules on unavailable data; it does not silently select Visualizer.
Install the ZIP through KernelSU only for an explicit device trial.
A fresh audioserver process is required; these scripts never restart it.
No SELinux rules are bundled.

`ANDROID_HOME` (or `ANDROID_SDK_ROOT`) may replace `--sdk`. Use
`--output out/native-review` for offline builds that must preserve the manifest
of an already installed library. Native source changes require a newly matched
APK; an offline build does not update either device component.

The native APK automatically requests short renewable leases only while its
existing screen/playback/visibility conditions allow capture. It receives only
six derived frequency bands over an authenticated local socket; no JNI is used.
The existing "stop when muted" setting is honored; turn it off to display source
activity at system volume zero.

With no native UI consumer connected, inspect status and arm a maximum 60-second observation lease
using the current media UID/session. I/O handles change across boots and output
recreation; the default selects a unique eligible track, while `--io` pins an
explicit output for diagnostics. Ambiguous tracks produce no spectrum. Every command
requires an explicit ADB serial and root:

```sh
python3 native/spectrum-tap-zn/control.py --sdk SDK_PATH --serial SERIAL status
python3 native/spectrum-tap-zn/control.py --sdk SDK_PATH --serial SERIAL arm --uid UID --session SESSION --seconds 10
python3 native/spectrum-tap-zn/control.py --sdk SDK_PATH --serial SERIAL off
```

The control tool requires fresh status from the exact local build. Build
manifests from another output directory can be selected with `--manifest`.
Source sampling remains disabled at startup and after lease expiry or disconnection.
Remove or disable `hyperspectrum-tap-zn` through KernelSU and restart the device
to retire an installed probe. Updating the SystemUI APK does not update it.

Apache-2.0; see the repository root LICENSE and NOTICE. The probe uses the
[Zygisk Next native ABI](https://github.com/LSPosed/ZygiskNext/blob/v1.5.2/zygisk_next_api.h)
provided by the installed Zygisk Next module; it bundles no hook engine.
