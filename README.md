# Xiaomi Camera Mod

Three LSPosed/Vector modules (libxposed API 101-102) for Xiaomi apps on HyperOS: two for the Camera and one (draft) for the Media Editor. Each does one job, installs on its own and has its own package, so you can use any of them.

| Module | What it does | Package |
| ------ | ------------ | ------- |
| [HyperFilmFilters](app/README.md) | Brings the film filters R600, P100F, F-50 and KC64 (and KP160 in the Leica UI) back to the filter panel and the filter strip, in the standard UI and the Leica UI. | `io.martin.hyperfilters` |
| [HyperCameraMod](cameramod/README.md) | Turns on three features the camera already contains: the Palette and Parameters panel, the Original colour style, and the Leica Essential CMOS style. | `io.martin.hypercameramod` |
| [HyperEditorMod](editormod/README.md) | **Draft, untested.** Lets the Media Editor open its Palette pad (Saturation and Tone) on a phone outside the editor's built-in device list. | `io.martin.hypereditormod` |

Both are developed and tested on the Xiaomi 17 Ultra (Leica edition) with Xiaomi Camera 6.7 on HyperOS 4. They do not check the camera's version number: they look for what they need and, when it is not there, leave the camera unchanged and say so. Each module's README lists what it looks for.

## Use

1. Install the module you want and enable it in LSPosed or Vector. Set its scope to the app it works on: **Camera** for the two camera modules, **Media Editor** (`com.miui.mediaeditor`) for HyperEditorMod.
2. Force-stop Camera and open it again.

If the camera misbehaves, disable the module and force-stop Camera to restore the stock behaviour. Nothing on the system partition is changed.

## Build

A Gradle project with three modules. It needs the Android SDK (compileSdk 37, build-tools 37.0.0); put `sdk.dir=...` in `local.properties`, then:

```bash
./gradlew assembleRelease
```

or `:app:assembleRelease` / `:cameramod:assembleRelease` / `:editormod:assembleRelease` for one module. The release builds are signed with the debug key.

## Disclaimer

Unofficial community modules, not affiliated with Xiaomi. They hook internal camera code and are provided as is, for personal use. Use them at your own risk.
