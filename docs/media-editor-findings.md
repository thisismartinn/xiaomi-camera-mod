# Media Editor findings: Legend M10R (cloud) and Palette re-edit

Static analysis of the Media Editor APK `com.miui.mediaeditor` 2.10.40.7 (versionCode 210994070), the build that ships the Palette pad on the Xiaomi 18 Pro. Nothing here was run on a device. Class names are the obfuscated names in that build and will differ in other builds.

Each claim is marked **[code]** (read directly in the dex) or **[inferred]** (a reading of the code, not confirmed). Section 5 lists what is still unknown.

## 1. How the editor decides a photo is a Legend photo

The resolver is `lv/t0` (`LegendaryVersionResolver`). It returns a `LegendaryModeInfo(version, type, ...)`. The type enum is `legendary/e`:

| Enum | Name string | Meaning |
| ---- | ----------- | ------- |
| `c` | `Legend.MONOPAN` / `M3` | Monopan |
| `d` | `Legend.M9` / `M9` | CCD |
| `e` | `Legend.M10R` / `M10R` | CMOS |
| `f` | `Legend.TELEPHOTO_CLOUD` | telephoto cloud (`Legend.zoom` / `Default.zoom`) |

**[code]** Two EXIF generations are read, V2 first:

- **V2 (MakerNote).** Keys `MomentVersion`, `MomentType`, `MomentSubMode`, `MomentPro`, plus `depthMapVersion`. Needs `MomentVersion >= 2`. `MomentSubMode`: 1 = M9, 2 = M3, **3 = M10R**. `depthMapVersion >= 4` sets a boolean in the mode info (read here as a depth/bokeh-capable flag; the name is not in the code).
- **V1 (`legendmode` byte).** Only 1 = M9 and 2 = M3. **M10R cannot be expressed in V1**, so a photo with only the old `legendmode` tag can never resolve to M10R.

So the first requirement for M10R is a V2 MakerNote with `MomentVersion >= 2` and `MomentSubMode = 3`. This matches the camera mod README note that a CMOS shot without the camera's own tags is treated as an ordinary picture.

The render variants are in `legendary/g`: `M3_NORMAL`, `M3_BOKEH`, `M9_NORMAL`, `M9_BOKEH`, `M10R_NORMAL`, `M10R_BOKEH`. **[inferred]** `_BOKEH` needs depth data in the photo.

On export `cf/x` (`LegendExifTagHandler`) clears or keeps these tags (`clearLegendTags`, `handleExportTags`), so an edited copy can lose its Legend status.

## 2. M10R renders in the cloud

The Gallery/Editor sends the job to a Xiaomi server (confirmed by testing on an 18 Pro: M10R runs in the cloud).

**[code]** Flow:

1. `LegendaryActivity` (action `com.miui.mediaeditor.action.legendary`) calls `LegendaryViewModel.renderPicByLegendaryType`, which enqueues through `LegendaryJobManager` (`rv/r`).
2. Upload through `/aiPhotoCloud/api/file/upload` or `/aiPhotoCloud/api/file/batchUploadByByte`.
3. Create the task: `/aiPhotoCloud/api/legendV2/create` (`CreateLegendV2TaskParams`).
4. Poll `/aiPhotoCloud/api/legendV2/getResult`; list with `/legendV2/list`. V1 has `/legend/create`.
5. Result entity `CloudLegendResult`: `taskId`, `createTime`, `status`, `message`, `imgUrl`, `requestType`, `progress`, `remaining_time_seconds`, `current_step`, `errCode`.
6. Retry and cancel use `RetryLegendTask*` and `ChangeLegendTaskStatus*` (the latter carries an OAID).

`LegendCloudConfig` only holds timeouts (`timeOut`, `maxInterval`), no per-mode switches.

A local part also exists: `LegendModeSdkLoader` downloads a `legend_mode` resource directory, and `liblegend_refine_jni.so` / `liblegendrefine.so` do the refine and clean steps (`legend_clean_v8`, `legend_zoom_clean_v1`). Rendering shows an error if the SDK download fails.

## 3. What is needed before the cloud job runs

**[code]** Client-side gates in order:

1. The photo resolves to M10R (section 1).
2. User agreement (CTA). `checkXmLegendCloudEntryIfNeeded` stops with "CTA not allowed" unless the agreement was accepted. If it was not, the editor launches the Xiaomi AI app (`com.miui.voiceassist`) to get it (`ensureXiaoaiCta`, `launchXiaoaiForCta`).
3. A signed-in Xiaomi account ("CTA agreed, check login").
4. Network.
5. The legend SDK resources downloaded.

**[code]** Quota (the "VIP" part). `am/g` (`AiPointsRepository`) fetches a billing pre-check (`PreCheckBillingResult`: free count, first-sale month, points, deadline). The editor shows a hint such as remaining count before render. Server errors map to `fx/a` including `QUOTA_EXHAUSTED_FREE` and `QUOTA_EXHAUSTED_MEMBER`. The upgrade page is `https://api.miclaw.xiaomi.com/h5/vip?source=mediaeditor_legendary_upgradevip`, opened in `com.miui.voiceassist` (`AiWebActivity`).

So "M10R-VIP" is a Xiaomi AI membership/points quota on the account, checked by the server. **[inferred]** A photo cannot make itself "VIP"; the photo only has to be recognised as M10R. Whether the server also checks account region or device is **not visible in the client**.

## 4. Palette in the Media Editor

**[code]** The pad (Saturation and Tone, plus temperature, tint, grain, sharpen, vignette) is `vz/c0` (portrait) and `vz/l0` (landscape), hosted by `vz/s` (module `/filter/provider`). It edits `b00/g` (`FilterStyleParams`), applied as an `@StyleAdjustEffect;Algos=..;SharpenIntensity=..;ContrastIntensity=..;SaturationIntensity=..;Temperature=..;Tint=..;Grain=..;VignetteIntensity=..` script.

The data comes **from the photo**, not from a device flag:

- The camera embeds a "re-edit" protobuf (`ReEditDataBeanMsg`: images plus an op list) in the image metadata. `o5/a` reads the image metadata (`MiCameraProp`), `sl/b$a.a` extracts it, `tl/b` decodes it, and `ul/c$a.a` maps each `BizOpBeanMsg` by name (`portrait_refocus`, `mark`, `live`, `crop`, `watermark`, `aigc_mark`, `filter`, `adjust`).
- The `filter` op (`BizFilterBeanMsg`) holds `filterId`, `progress`, `renderType`, `colorParam`, `filterFunc`, `lutData`, `token`, `miCamStyle`.
- `vz/d0.buildCameraStyleOperation` turns it into a `CameraStyleFilterItem` (`b00/b`; source `micamera`). `b00/g$a.b` parses `colorParam` for the `@StyleAdjustEffect` values. Render type must be `LUT` or `ADJUSTABLE`.
- `vz/r.onCreate` scans the operation history for a `/filter/provider` op whose item is a `CameraStyleFilterItem`, logs `found camera style!`, and loads the filter list and the Palette flow.

I found **no device-name check** in this path. **[inferred]** Palette appears when (a) the photo still carries the camera's re-edit `filter` op and (b) the editor build contains this code. Both are needed. A photo that has lost its EXIF (messaging apps, some share paths, some export paths) cannot show Palette in any editor.

### Suggested test for other phones

1. Shoot with the camera's Palette (on 17 Ultra, with the camera mod's Palette panel on).
2. Copy the **original file** by cable or file manager (do not share through a messenger).
3. Open it in the 18 Pro Media Editor build, then in the stock editor. If the pad appears only in the newer build, the missing piece is the editor code, not a flag.
4. On a device, check `logcat -s PhotoFilterViewModel` for `found camera style! name=`.

## 5. Not found yet

- Which metadata container holds the re-edit protobuf (the exact marker/tag).
- The exact request fields of `CreateLegendV2TaskParams` and what the server validates.
- Whether any remote config gates the Palette pad. None was seen in the classes read.
- The meaning of the boolean returned by `rv/r.v` (it returns true for `TELEPHOTO_CLOUD`).
- Whether the shipped CI APK is stock or patched; it was not diffed against a stock build.
