# HyperPaletteMod

An LSPosed/Vector module (libxposed API 101-102) for the **Palette** feature across two Xiaomi apps, with a settings screen of its own. Package: `io.martin.hyperpalettemod`. It installs alongside HyperCameraMod and HyperFilmFilters.

Developed and tested on the Xiaomi 17 Ultra (Leica edition) with Xiaomi Camera `6.7.000500.0` and Media Editor `2.10.40.7` / `2.10.43.0.6` on HyperOS 4.

## Scope

Enable it for **Camera** (`com.android.camera`) and **Media Editor** (`com.miui.mediaeditor`). The settings screen shows whether both are in the scope and has a button to request them.

## Settings (open the module's app)

| Setting | Default | What it does |
| ------- | ------- | ------------ |
| Enable Palette pad | on | The Media Editor opens its Saturation / Tone pad only when `Build.DEVICE` is in a built-in list (`madrid`, `venice`, `hongkong`, the 18 series). The module adds this phone to that list. Read when the editor starts. |
| Single-line pad values | on | The pad's number fields are a fixed 16 dp wide, which wraps two-digit values in this phone's font. They are widened to fit `100` on one line. |
| Non-destructive Palette re-edit | on | Routes the camera's filter through the Java pipeline so the module receives the unfiltered source frame and the live filter + Palette values. Force-stop Camera after changing. |
| HEIC re-edit data | Filtered photos | Controls which saved HEIC photos get re-edit metadata (`edit` item + `micamedit` XMP): **Off**, **Filtered photos**, or **Every photo**. Read on every save. |
| Fallback filter ID | `58` | Editor filter ID used only if the camera's filter ID cannot be mapped automatically. |
| Force-stop Camera & Media Editor | — | Convenience button in the settings screen to restart both apps after changing settings. |

Settings are stored in the framework's remote preferences (`io.github.libxposed.service`), so changes reach the hooked apps without restarting the module app. Without framework support the defaults apply.

## How it works

### Media Editor (`com.miui.mediaeditor`)
- Adds the current `Build.DEVICE` to the editor's Palette allow-list so the Saturation / Tone pad opens for photos with re-edit data.
- Hooks `LayoutInflater.inflate` to widen the Palette pad's numeric labels so two-digit values stay on one line.

### Camera (`com.android.camera`)
- **Non-destructive Palette re-edit (default ON):**
  - Captures the unfiltered HEIC frame and the camera's live `MIVIRenderTag` `@StyleAdjustEffect` filter/Palette parameters (mapping 17 Ultra filter IDs to Media Editor filter IDs dynamically).
  - Renders the filtered outer HEIC on-device via the camera's own `CandySDK` (RGBA Bitmap mode) and Jetpack `HeifWriter` (quality 95).
  - Copies the original EXIF onto the rendered HEIC, strips any stale unfiltered `XMTH` quick-view JPEG, replaces the EXIF IFD1 thumbnail in-place with a filtered 240 px JPEG, and embeds the unfiltered HEIC + live filter/Palette record inside the `edit` protobuf (`micamedit` XMP + `iref cdsc` references). Gallery displays the filtered photo, while Media Editor opens the Palette pad with the exact filter and Palette values for non-destructive re-editing.
  - Hooks the camera's HEIC save writers (`v7.j.b` `TaskDataStorageWriter`, `n7.M.J` `FileChannel` writer, `n7.M.x` / `n7.M.B`, `n7.f.c`, `W8.e.e`) so the filtered re-editable HEIC is written immediately on save, with a `DCIM/Camera` `FileObserver` fallback (2500 ms delay).
  - Filters the Camera's immediate round shutter thumbnail (`A4.v1.v0`) in-place (with orientation handling and rapid-shot style matching) so the shutter circle shows the filtered look right away.
- **Neutral fallback (when Non-destructive Palette re-edit is OFF):**
  - Embeds the saved HEIC itself as the source with a neutral operation (0 % filter, 0 Palette values) so the editor's Palette pad still opens and adjusts on top of the photo.
- **Safety checks:** Every modified HEIC is read back and verified before writing; photos that already contain re-edit data are skipped.

## Use

1. Install the module, enable it in LSPosed or Vector, and set its scope to **Camera** and **Media Editor**.
2. Open **HyperPaletteMod** (it should show *Active*), adjust settings if needed, and tap **Force-stop Camera & Media Editor**.
3. Take a HEIC photo with a filter/Palette look and open it in Gallery → Edit: the filter and Palette pad should open with the live values ready for non-destructive re-editing.

## Logs

```bash
su -c "logcat -d | grep HyperPaletteMod"
```

## Build

```bash
./gradlew :palettemod:assembleRelease
```

Needs the Android SDK (`compileSdk 37`, `build-tools 37.0.0`, Java 21). Uses the release keystore when `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` are set, otherwise falls back to the debug key.
