# HyperEditorMod (draft)

An LSPosed/Vector module (libxposed API 101-102) for the Xiaomi **Media Editor** (`com.miui.mediaeditor`). It lets the editor open its **Palette pad** (Saturation and Tone) on a phone that is not in the editor's built-in device list. Package: `io.martin.hypereditormod`.

**Status: draft, untested on a device.** Built and checked statically against Media Editor 2.10.40.7 (18 Pro) and 2.10.43.0.6 (17 Ultra).

## What it changes

The editor opens the pad only when `Build.DEVICE` is in a hidden `String[]` in class `gt.a`, which holds `madrid`, `venice` and `hongkong` (the 18 series). The same array is checked at four places of the Palette UI. On the 17 Ultra (`nezha`) the check fails, so the editor names the filter a photo used but never shows the pad.

The module finds that array when the editor starts (by its content, not by a field name) and, if this phone is not already in it, writes `Build.DEVICE` over the first entry. A phone that is already listed is left alone. Nothing else in the editor is touched.

## What it does not change

The pad shows values stored **in the photo**. The 18 Pro camera writes them (a HEIC item called `edit` plus a `micamedit` XMP marker). The 17 Ultra camera does not, so a 17 Ultra shot opens without a pad even with this module. Test with an **original** 18 Pro Palette photo copied by cable or file manager (a chat app or an iPhone strips the data).

Details and the file format: [docs/media-editor-findings.md](https://github.com/thisismartinn/hyper-camera-mod/blob/claude/intelligent-darwin-aioit5/docs/media-editor-findings.md) in `hyper-camera-mod`.

## Use

1. Install the APK and enable the module in LSPosed or Vector. Set its scope to **Media Editor** (`com.miui.mediaeditor`) only.
2. Force-stop the editor: `adb shell am force-stop com.miui.mediaeditor`.
3. Open an original 18 Pro Palette photo in the editor, pick its filter, then tap the selected filter again. The pad should open.
4. Check the log: `adb logcat -s HyperEditorMod` (or the module log in LSPosed). A working start prints:
   `Palette device list [madrid, venice, hongkong] -> [nezha, venice, hongkong] (this phone: nezha)`.
   If it prints `is already in the editor's Palette device list`, the phone was listed and the cause is elsewhere.
5. To switch it off without uninstalling: disable it in LSPosed, or `setprop hypereditor.palette_gate 0` (root) and force-stop the editor.

If nothing is logged at all, the editor UI is probably hosted by another process on that phone; send the process name and the scope can be widened.

## Build

```bash
./gradlew :editormod:assembleRelease
```

Needs the Android SDK (compileSdk 37, build-tools 37.0.0). The release build is signed with the debug key, like the other modules.
