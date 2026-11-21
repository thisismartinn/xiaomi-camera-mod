# HyperPaletteMod (draft)

One LSPosed/Vector module (libxposed API 101-102) for the **Palette** feature across two Xiaomi apps, with a settings screen of its own. Package: `io.martin.hyperpalettemod`. It replaces the earlier draft HyperEditorMod and the re-edit step that was briefly added to HyperCameraMod; HyperCameraMod itself is unchanged and installs alongside it.

**Status: draft, untested on a device.** Read from Media Editor 2.10.40.7 / 2.10.43.0.6 and Camera 6.7.000500.0.

## Scope

Enable it for **Camera** (`com.android.camera`) and **Media Editor** (`com.miui.mediaeditor`). The settings screen shows whether both are in the scope and has a button to request them.

## Settings (open the module's app)

| Setting | Default | What it does |
| ------- | ------- | ------------ |
| Open the Palette pad on this phone | on | The Media Editor opens its Saturation / Tone pad only when `Build.DEVICE` is in a hidden list (`madrid`, `venice`, `hongkong`, the 18 series). The module adds this phone to that list. Read when the editor starts. |
| Keep the pad's numbers on one line | on | The pad's number fields are a fixed 16 dp wide, which wraps two-digit values in this phone's font. They are made as wide as `100` needs. |
| Add re-edit data to saved HEIC photos | photos taken with a filter | The camera adds the data the editor needs (an `edit` item and a `micamedit` XMP marker) to each HEIC it saves. Off / photos taken with a filter / every photo. Read on every save. |
| Editor filter id | 58 | The editor filter the added data names, shown selected at 0 % so it does not change the picture. |

The settings are written to the framework's remote preferences (`io.github.libxposed.service`), so a change reaches the hooked apps without restarting this app. Without framework support the defaults apply.

## What the camera part does and does not do

The 17 Ultra camera does not keep the unfiltered picture, so the added data uses the saved photo itself as the source and a neutral operation (filter at 0 %, every Palette value 0). In the editor the pad opens and starts at 0; it adjusts on top of the photo. It does **not** name the filter used or restore the Palette values chosen in the camera, because that would apply them a second time on a photo that already has them. Photos roughly double in size.

How it gets there: the camera's two HEIC save methods are hooked, and a watcher on `DCIM/Camera` adds the data to any finished HEIC the hooks missed. The result is read back and checked (every original item must read the same bytes, the new items must read back exactly) before anything is written; if a check fails the photo is left untouched, and if writing fails the original bytes are written back. A photo that already has re-edit data is skipped.

**Planned, not available yet:** saving the camera's own filter and Palette values so the editor can re-edit them. That needs the unfiltered picture. The camera applies the filter inside the app (a GL step with its `CandySDK`, on a YUV hardware buffer), so the frame exists just before that step; capturing it is still being investigated.

## Log

`adb logcat -s HyperPaletteMod` (or the module log in LSPosed):
- Editor: `Palette device list [madrid, venice, hongkong] -> [nezha, venice, hongkong]`, `hooked LayoutInflater.inflate`.
- Camera: `hooked 2 HEIC save path(s)`, `watching .../DCIM/Camera`, then for each of the first eight saves which path fired and either `added re-edit data ...` or why it was skipped.

## Test

1. Install, enable in LSPosed/Vector with both apps in the scope, open the module's app (it should say *Active*), force-stop Camera and Media Editor.
2. Take a photo with a filter. The new photo should be about twice its usual size.
3. Copy the **original** photo off the phone by cable (chat apps and an iPhone strip the data) and open it in the Media Editor: pick the filter, then tap it again for the pad.

## Build

```bash
./gradlew :palettemod:assembleRelease
```

Needs the Android SDK (compileSdk 37, build-tools 37.0.0). The release build is signed with the debug key, like the other modules.
