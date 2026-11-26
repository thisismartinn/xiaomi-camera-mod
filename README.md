# Xiaomi Camera Mod

Three LSPosed/Vector modules (libxposed API 101-102) for Xiaomi apps on HyperOS: two for the Camera and one for the Palette in the Media Editor and the Camera. Each does one job, installs on its own and has its own package, so you can use any of them.

| Module | What it does | Package |
| ------ | ------------ | ------- |
| [HyperFilmFilters](app/README.md) | Brings all twelve film filters (600F, BF70, R600, P100F, F-50, KC64, V250, H400, KP160, FC400, C-50D, KG200) back to the filter panel and the filter strip, in the standard UI and the Leica UI. | `io.martin.hyperfilters` |
| [HyperCameraMod](cameramod/README.md) | Turns on features the camera already contains: the Palette and Parameters panel, the Original colour style, Leica Essential mode in the standard UI, Portrait Mode EV adjustment (including Camera Ring support), and the optional Leica Essential CMOS style. | `io.martin.hypercameramod` |
| [HyperPaletteMod](palettemod/README.md) | Palette for the Media Editor and the Camera, with a settings screen: unlocks the editor's Saturation / Tone pad on unsupported devices, saves non-destructive HEIC and JPEG re-edit data (unfiltered source + live filter/Palette values) with CandySDK outer rendering, and filters the shutter thumbnail in-place. | `io.martin.hyperpalettemod` |

All three are developed and tested on the Xiaomi 17 Ultra (Leica edition) with Xiaomi Camera 6.7 on HyperOS 4. They do not check the camera's version number: they look for what they need and, when it is not there, leave the app unchanged and log it. Each module's README lists what it looks for.

## Use

1. Install the module you want and enable it in LSPosed or Vector. Set its scope to the app it works on: **Camera** for HyperFilmFilters and HyperCameraMod, **Camera** and **Media Editor** (`com.miui.mediaeditor`) for HyperPaletteMod.
2. Force-stop Camera (and Media Editor for HyperPaletteMod) and open it again.

If an app misbehaves, disable the module and force-stop the app to restore stock behaviour. Nothing on the system partition is changed.

## Build

A Gradle project with three modules. It needs the Android SDK (compileSdk 37, build-tools 37.0.0); put `sdk.dir=...` in `local.properties`, then:

```bash
./gradlew assembleRelease
```

or `:app:assembleRelease` / `:cameramod:assembleRelease` / `:palettemod:assembleRelease` for one module. Release builds use the keystore configured via `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` when present, and otherwise fall back to the debug key.

## Disclaimer

Unofficial community modules, not affiliated with Xiaomi. They hook internal app code and are provided as is, for personal use. Use them at your own risk.
