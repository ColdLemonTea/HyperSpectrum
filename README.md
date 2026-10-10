# HyperSpectrum

An LSPosed module that replaces Xiaomi HyperOS 4's fixed Lottie animation
with six bars driven by the actual audio spectrum.

The bars use artwork colors and settle smoothly when playback pauses. Casting
and other island icons keep their stock appearance.

## Compatibility

| Platform | Required components | Status |
| --- | --- | --- |
| Android 17 / HyperOS 4 | LSPosed supporting libxposed API 102; compatible SystemUI and island plugin | Validated on the test device |
| Android 16 / API 36 | The same SystemUI and island plugin interfaces | Minimum SDK; not validated |

Compatibility depends on the ROM's SystemUI and island plugin. Other system
audio may also affect the spectrum.

## Installation

1. Install the module APK.
2. Enable **Miui Island Spectrum** in LSPosed and select only **SystemUI**
   (`com.android.systemui`).
3. Restart the device for initial activation.

## Build

Use a full Git checkout, Android SDK 37, and JDK 25 for the Gradle daemon.
Set `ANDROID_HOME` or specify `sdk.dir` in a local, untracked `local.properties`.

For a signed APK, copy `keystore.properties.example` to `keystore.properties`
and configure your own signing key locally. Keep signing keys and credentials
outside Git.

Build the LSPosed release APK:

Linux/macOS:

```shell
./gradlew :app:assembleRelease
```

Windows:

```powershell
.\gradlew.bat :app:assembleRelease
```

The signed APK is `app/build/outputs/apk/release/app-release.apk`.
Without signing credentials, the output is `app-release-unsigned.apk` in the same
directory.

## License

Copyright 2026 wxxsfxyzm. Apache License 2.0. See [LICENSE](LICENSE).
