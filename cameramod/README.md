# HyperCameraMod

An LSPosed/Vector module (libxposed API 101-102) that turns on features the Xiaomi Camera on HyperOS 4 already contains but leaves off for the Xiaomi 17 Ultra. It is separate from HyperFilmFilters, which adds the film filters.

## What it does

| Feature | What changes in the camera |
| ------- | -------------------------- |
| Palette and Parameters | The filter edit panel gets the Palette tab and the full Parameters list (tone, saturation, cold/warm, cyan/magenta, noise, soft light, texture, vignette), plus saving, sharing and importing filter presets. A preset saved in the Leica UI is shown in the filter strip by its thumbnail only (the Leica UI cannot show a text name for it). The two tabs are made as wide as their labels need (the camera makes them 60 dp wide, which fits two Chinese words, not "Palette" and "Parameters"). |
| Original colour style | "Original" appears next to Leica Authentic and Leica Vibrant in the photography style picker. In the Leica UI it gets its own "ORIG" badge, because the camera would otherwise draw it as the Authentic badge. |
| Leica Essential mode in the standard UI | The Leica Essential mode (the M3/M9 camera) is added to the standard UI's mode strip, which only the Leica UI has. See Use. |
| Leica Essential CMOS (M10R), off by default | "CMOS" is added to the Leica Essential style list, between B&W (M3) and CCD (M9), once enabled (see Use). |

Original colour style and CMOS only change the photo if the phone's camera hardware layer implements them. The camera sends the chosen style to the hardware layer; the module only makes the choice available.

**CMOS is off by default.** The Xiaomi 17 Ultra's hardware layer (`camera.qcom.core.so`, `com.qti.chi.override.so`) has the Leica Essential processing for M3 and M9 only. A CMOS shot comes back as an ordinary picture, without the Leica processing and without the `legendmode` tag the Gallery uses for its LEICA button. Enable it only to experiment, or on a phone whose firmware supports it.

## Camera versions

The module does not check the camera's version number. Each feature looks for what it needs and is skipped, with a log line, when it is not there.

| Feature | How it is found | Version dependence |
| ------- | --------------- | ------------------ |
| Palette and Parameters | The system property `camera.feature.styleVersion`, which the camera reads to decide whether to build the full Parameters list. | None. |
| Original colour style | The camera characteristic `xiaomi.capabilities.stylizationTypeMask`, bit 3. | None. |
| Essential mode in the standard UI | On unless `camera.feature.essential_std` is 0. The mode order class `v2.S` and `LegendaryEnter`. | Tested: camera 6.7.000500.0. |
| CMOS (M10R) | Only when `camera.feature.m10r` is 1. The Leica Essential list class, by a layout ("profile") that names it. | Tested: camera 6.7.000280.0 (HyperOS 4.0.5.0). 6.7.000070.0 already has the entry. |

When the Leica Essential list is not one the module knows, it leaves the camera unchanged and shows a toast in Camera, once per camera version.

## How it works

1. **Palette and Parameters.** The camera reads `camera.feature.styleVersion` through `android.os.SystemProperties` and builds the full Parameters list when it is 2 or more. The module returns 2 for that key when the real value is lower. The same effect without the module is `setprop camera.feature.styleVersion 2` as root, which resets on reboot.
2. **Original colour style.** The camera shows Original only when bit 3 of `xiaomi.capabilities.stylizationTypeMask` is set. The module sets the bit when the camera reads the characteristic.
3. **CMOS.** The camera's Leica Essential list is built in `s2.A`. In this build the CMOS entry is compiled out, along with three of its images. The module appends the entry right after the camera builds that list (not when it is read: the style picker keeps the list and counts its items once, so growing it later crashes the picker); the item class has no constructor in this build, so the instance is made without one, and answers the three image ids from its own assets. The new entry starts as a copy of the CCD (M9) entry, so the fields the Leica UI and the standard UI fill in are there, with the CMOS strings and images swapped in. The CMOS title string is also missing from this build, so the module supplies it ("Modern vintage", in the phone's language where the camera had a translation).
4. **Original badge.** The Leica UI picks a badge from a mapping that knows only two styles, so Original shares the Authentic badge. The module swaps the three image ids of the Original item for its own vector badge, and does the same for the badge the camera shows in the top bar for the chosen style, only when the camera picked an Authentic Leica UI drawable for Original.

## Use

1. Install the module and enable it in LSPosed or Vector. Set its scope to **Camera** only.
2. Force-stop Camera and open it again.

To add the CMOS entry to the Leica Essential list, run as root and force-stop Camera:

```bash
su -c "setprop camera.feature.m10r 1"
```

It resets on reboot (set it to 0 or reboot to turn it off). To keep it, run the command from a boot script such as Magisk's `service.sh`.

The Leica Essential mode is added to the standard UI's mode strip, in front of "More", with no setting needed. To turn it off, run as root and force-stop Camera:

```bash
su -c "setprop camera.feature.essential_std 0"
```

The setting resets on reboot (the mode comes back). Tested on camera 6.7.000500.0 (Xiaomi 17 Ultra, standard UI).

If the camera misbehaves, disable the module and force-stop Camera to restore the stock behaviour.

## Logs

The module logs to logcat under the tag `HyperCameraMod`:

```bash
su -c "logcat -d | grep HyperCameraMod"
```

## Build

A Gradle module in this repository. It needs the Android SDK (compileSdk 37, build-tools 37.0.0); put `sdk.dir=...` in `local.properties`, then:

```bash
./gradlew :cameramod:assembleRelease
```

## Disclaimer

An unofficial community module, not affiliated with Xiaomi or Leica. The three CMOS images in `src/main/assets/m10r` are taken from Xiaomi Camera. It hooks internal camera code and is provided as is, for personal use. Use it at your own risk.

## Palette re-edit data in saved photos (experimental)

The Media Editor opens its Palette pad (Saturation and Tone) only for a photo that carries re-edit data: an `edit` item and a `micamedit` XMP marker in the HEIC. The 18 Pro camera writes them. The 17 Ultra camera does not (it saves the filter name, not the Palette values), so the pad never opens for its photos, even with [HyperEditorMod](../editormod/README.md) installed.

The module adds that data to each HEIC the camera saves, right after the file is written. It is **on by default for photos taken with a filter**; `camera.feature.palette_edit` changes that (read on every save, no restart):

| Value | Photos changed |
| ----- | -------------- |
| `0` | none (off) |
| `1` (default) | HEIC photos taken with a filter |
| `2` | every HEIC photo |

```bash
adb shell su -c "setprop camera.feature.palette_edit 2"
```

`camera.feature.palette_edit_filter` (default `58`) sets the editor filter the data names.

**Two ways in.** Which save path a photo takes was read from the camera code, not run, so the module uses both: the camera's two HEIC save methods are hooked, and a watcher on `DCIM/Camera` picks up any finished HEIC the hooks missed (two seconds after its last write, once its size is steady). A photo that already has the data is skipped, so both can run on the same file.

**What it adds, and what it cannot.** The 17 Ultra camera does not keep the unfiltered picture (the filter is applied by a separate service from the camera's render tag), so the added data uses the saved photo itself as the source and a neutral operation (filter 58 at 0 %, every Palette value 0). In the editor the pad starts at 0 and adjusts on top of the photo; it does not name the filter used or re-open the Palette values chosen in the camera. Re-opening those would apply them a second time on a photo that already has them. The photo roughly doubles in size, because the source image is stored inside it.

**Safety.** The result is read back and checked (every original item must read the same bytes, the new items must read back exactly) before anything is written; if a check fails the photo is left untouched. If writing the new file fails, the original bytes are written back. Photos that already have re-edit data are skipped.

**Log** (`adb logcat -s HyperCameraMod`):
- `hooked 2 HEIC save path(s)` and `watching /storage/emulated/0/DCIM/Camera ...` at camera start.
- For each of the first eight saves: `normal HEIC save finished` or `parallel HEIC save finished` (which hook fired and the current setting).
- `added palette re-edit data on the ...` for the first photo that gets the data, or `skipped ... no filter` / `already in the photo` with the reason it did not.

Then copy the **original** photo off the phone by cable (a chat app or an iPhone strips the data) and open it in the editor with HyperEditorMod on. The file should be about twice its normal size.

Camera layout read: 6.7.000500.0 (classes `n7.f` and `W8.e`). Not run on a device yet.
