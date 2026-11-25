# HyperFilmFilters

An LSPosed/Vector module (libxposed API 101-102) that brings back the film filters from old Xiaomi Camera to the filter panel of the new Xiaomi Camera (v6), on HyperOS.

The Palette and Parameters panel, the Original colour style and the Leica Essential CMOS style are in a separate module, [HyperCameraMod](../cameramod/README.md).

## What it does

Exposes all twelve film filters from old Xiaomi Camera (Camera 5.3) in both the standard UI filter panel and the Leica UI ("Looks" panel):

| Film code | Ordinal | Stock 6.7/6.8 display name (when present) |
| --------- | ------- | ----------------------------------------- |
| 600F | 79 | 600F |
| BF70 | 80 | BF70 |
| R600 | 81 | R600 |
| P100F | 82 | P100F |
| F-50 | 83 | Reversal |
| KC64 | 84 | Orchid (Hanjiao) |
| V250 | 85 | Besson |
| H400 | 86 | Freehand |
| KP160 | 87 | Monsoon |
| FC400 | 88 | Kyoto |
| C-50D | 89 | Old Alley |
| KG200 | 90 | Slack |

Existing film entries in the camera's list are reused and renamed in place rather than duplicated; missing ones are added. Every film filter is labelled by its film code (`600F`, `BF70`, `R600`, `P100F`, `F-50`, `KC64`, `V250`, `H400`, `KP160`, `FC400`, `C-50D`, `KG200`) in both the standard UI and the Leica Looks strip.

## Camera versions

The module does not check the camera's version number. It looks at the camera's filter code and works with any build whose code has the layout it knows (a "profile"). The camera is obfuscated, so class names change between builds; the module tries each known profile in turn and uses the first that fits.

| Profile | Camera build | From | Status |
| ------- | ------------ | ---- | ------ |
| 6.7 | 6.7.000280.0 | HyperOS 4 (Xiaomi 17 Ultra) | Tested: standard UI and Leica UI. |
| 6.7.000500 | 6.7.000500.0 | HyperOS 4 | Supported: standard UI and Leica UI. |
| 6.8.002230 | 6.8.002230.0 | HyperOS 4 | Added from static layout. |
| 6.4 | 6.4.000430.0 | HyperOS 3 (Xiaomi 14 Ultra / 14 Pro) | Tested: standard UI filter panel (4 legacy films). |

Other builds, such as one from another HyperOS release, may work if their filter code is unchanged, but only the profiles above are known. Camera 6.4 keeps its 4-film set (`R600`, `P100F`, `F-50`, `KC64`) because its filter enum predates the full 79–90 range.

### When the camera's filter code is different

If the module can't find the code it expects, it leaves the camera unchanged (nothing is hooked, so the camera behaves as stock) and tells you:

- A toast appears in Camera a few seconds after it starts, once for each camera version, for example "HyperFilmFilters (camera 6.9.000100.0): The camera's filter code is different from the layouts this module knows, so no film filters were added."
- The same is written to the log (see Logs).

Smaller problems get their own message: the filter panel hook failing ("Some film filters may not show in the filter panel"), or, on a phone that uses the Leica UI, the Looks list hook failing ("The Leica Looks list is different on this camera version").

To get support for a new camera build, open an issue with the camera version and the `HyperFilmFilters` log lines.

## How it works

1. **Add and deduplicate the items.** The camera builds its photo filter list in one method. The module hooks it, matches any film entries the camera already lists (by ordinal, film-code or artistic string resource, strip key, or cloud id) to reuse and rename them without duplicates, and appends any remaining films out of all twelve. Resource ids come from the camera's resource classes, or are looked up by name when a build has no such class.
2. **Film code names.** Both reused and newly added items are assigned a synthetic string resource id (`0x7d000000 | ordinal`). Hooks on `Resources.getString`, `Resources.getText`, `Resources.getResourceEntryName` and `Resources.getResourceName` return the film code (`600F`..`KG200`), overriding later artistic names.
3. **Skip the cloud merge.** The filter panel merges the local list with the cloud filter list and drops any local filter the cloud list does not name. The module makes the panel's merge check return `false`, and rebuilds the filter component from the local list whenever the cloud merge leaves fewer than 12 films.
4. **Leica UI and Looks tag labels.** The Leica UI builds its own "Looks" list from a separate table (`c()` and `d()`). The module appends/reuses all twelve filters there too and assigns each a synthetic name-icon resource id (`0x7e000000 | ordinal`). Hooks on `Resources.getDrawable` and `ImageView.setImageResource` render two-line `FLM` + film-code `VectorDrawable` tags using the bundled Leica `lg1056_regular.otf` typeface (with camera-asset and `sans-serif-condensed` fallbacks), auto-condensing 5-character codes and adding the native translucent outline.
5. **Order.** The camera's filter strip adds every filter it does not have yet at position 1, one after the other, so the films would come first and in reverse. After the strip has reloaded (and once when the camera opens) the module moves the films to the end and leaves everything else where it is, so an order you set in Manage filters is kept. Films that are new to the strip get the default order (`600F` through `KG200`, slots 12–23); films already in the strip keep the order the strip has. The standard UI's strip is built from categories 2, 5 and 10, which the module hooks as well.

Profile 6.4 works the same way in outline, on different code: the camera keeps its filter lists in `EffectController`, one list per category, and a component class (`f0.M` or `f0.L`) builds the filter panel's items from the list, or from the list merged with cloud filter data. The module appends the four legacy films to the lists for categories 10 and 2, then checks the component's items and, if the merge left the films out, builds them again from the list alone. The Leica UI, the filter strip's order and the Leica-style film labels are not covered by this profile.

| Target (profile 6.7) | Purpose |
| -------------------- | ------- |
| `Ii.i0.d()` | Photo filter list (standard UI): reuses/appends all 12 film filters. |
| `com.android.camera.fragment.m0.ut()` | Filter panel: returns `false` so the list is not merged with the cloud list. |
| `E9.h.c()` / `E9.h.d()` | Leica "Looks" lists: reuses/appends all 12 film filters with `FLM` tag labels. |
| `com.android.camera.features.mode.capture.e` (the strip's reload and set-up methods) | Filter strip: moves the films to the end, keeps the rest. |
| `com.xiaomi.camera.effect.EffectController.q` | Filter lists for categories 2, 5 and 10: reuses/adds the films. |
| `w2.P.c(int, Map)` | Filter component: rebuilds from the local list when cloud merge drops films. |

| Target (profile 6.4) | Purpose |
| -------------------- | ------- |
| `com.android.camera.effect.EffectController.n(int)` | Filter lists by category: appends R600, P100F, F-50 and KC64 to categories 10 and 2. |
| `f0.M` / `f0.L` `mapToCloudItems(int, Map)` | Filter component: builds it again from the list when the cloud merge dropped the films. |

## Use

1. Install the module and enable it in LSPosed or Vector. Set its scope to **Camera** only.
2. Force-stop Camera and open it again.
3. For the standard UI on a phone that uses the Leica UI, turn the Leica theme off. This resets on reboot:

   ```bash
   su -c "setprop camera.debug.theme_customize.disabled true"
   ```

   Set it back to `false` and force-stop Camera to return to the Leica UI.

If the camera misbehaves, disable the module and force-stop Camera to restore the stock behaviour. Nothing on the system partition is changed.

## Logs

The module logs to logcat under the tag `HyperFilmFilters`, not to the manager's module log:

```bash
su -c "logcat -d | grep HyperFilmFilters"
```

Useful lines:

- `camera layout 6.7, hooked ...`: a profile fit and the hooks are installed.
- `filter list: N items, added [...]`: the items were added.
- `does not match` or `cannot hook ...`: the camera's filter code differs from that profile.
- `the camera's filter code is not one this module knows`: no profile fit, so the camera was left unchanged.

## Debug output

The module logs to logcat under the tag `HyperFilmFilters`. Besides the normal lines it writes a few `debug:` lines: the camera and Android versions, the device, and the layout (profile) it chose at start, and the filter keys of each filter list and of the filter component (once per distinct list). They show which filters the camera had and what the module added, so a log is enough to see why a filter is missing.

```bash
su -c "logcat -d | grep HyperFilmFilters"
```

`su -c "setprop camera.feature.filters_debug 0"` turns the `debug:` lines off (it resets on reboot).

## Build

A Gradle project. It needs the Android SDK (compileSdk 37, build-tools 37.0.0); put `sdk.dir=...` in `local.properties`, then:

```bash
./gradlew :app:assembleRelease
```

The release build is signed with the debug key.

## Disclaimer

An unofficial community module, not affiliated with Xiaomi. It hooks internal camera code and is provided as is, for personal use. Use it at your own risk.
