package io.martin.hypereditormod;

import android.content.res.Resources;
import android.os.Build;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

// Lets the Xiaomi Media Editor open its Palette (Saturation / Tone) pad on a phone that is not in
// the editor's built-in device list.
//
// What the editor does (com.miui.mediaeditor 2.10.40.7 and 2.10.43.0.6, both read):
//  - Tapping an already selected filter asks the filter view model for the state "param adjust".
//    The request starts with a check that compares Build.DEVICE (ignoring case) with the entries of
//    a static String[] in class gt.a, and returns without doing anything when there is no match.
//    The array holds the codenames "madrid", "venice" and "hongkong" (the 18 series). The same
//    check is made at three more places of the Palette UI (the filter item select handlers and the
//    list builder), always against that one array.
//  - The 17 Ultra (nezha) is not in the array, so the pad never opens there, although the editor
//    shows which filter a photo used.
//
// What this module does: when the editor process starts, it finds that array and, unless this
// phone's Build.DEVICE is already in it, writes Build.DEVICE over its first entry. Every check then
// passes, on every place that reads the array, with no per-build method names to find. A phone that
// is already in the list is left alone.
//
// Second fix: the Saturation and Tone numbers above the pad are in text views a fixed 16 dp wide
// (sized for the 18 series' font). With another font a two digit value such as "20" no longer fits
// and wraps onto two lines. When the panel is inflated, both value views are made as wide as
// "100" needs in the view's own font (never narrower than the editor's width).
//
// This does not give a photo Palette data it does not have: the pad shows values from the photo's
// re-edit data (the "edit" item of the HEIC), which the 18 Pro camera writes and the 17 Ultra camera
// does not.
//
// Setprop switch: hypereditor.palette_gate=0 turns the module off (read at every editor start).
public class ModuleMain extends XposedModule {
    private static final String TAG = "HyperEditorMod";
    private static final String EDITOR_PACKAGE = "com.miui.mediaeditor";
    private static final String GATE_CLASS = "gt.a";
    private static final String DISABLE_KEY = "hypereditor.palette_gate";
    // Entries the editor's own list is made of; used only to tell the Palette array from the
    // other static String[] fields of the same class (they hold other devices' codenames).
    private static final String[] KNOWN = {"madrid", "venice", "hongkong"};
    private static final int MAX_LENGTH = 8;   // the Palette array has 3 entries; the others 2..36

    // Palette pad: the two number views above the pad
    private static final String[] VALUE_IDS = {
            "filter_param_panel_saturation_value", "filter_param_panel_tone_value"};
    private static final String WIDEST_VALUE = "100";   // the largest magnitude the pad shows

    private volatile boolean opened;
    private volatile boolean panelHooked;
    private volatile int[] valueIds;
    private final AtomicBoolean panelFailureLogged = new AtomicBoolean();

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        install("onPackageLoaded", param.getPackageName(), param.getDefaultClassLoader());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        install("onPackageReady", param.getPackageName(), param.getClassLoader());
    }

    private void install(String stage, String pkg, ClassLoader loader) {
        if (!EDITOR_PACKAGE.equals(pkg) || loader == null || opened) {
            return;
        }
        if ("0".equals(systemProperty(DISABLE_KEY, "1"))) {
            log(Log.INFO, TAG, DISABLE_KEY + "=0, not changing the editor");
            return;
        }
        try {
            openGate(stage, loader);
        } catch (Throwable t) {
            log(Log.WARN, TAG, stage + ": cannot open the Palette device check: " + t);
        }
        installPanelFix();
    }

    // ---- Palette pad number width ------------------------------------------------------------

    private void installPanelFix() {
        if (panelHooked) {
            return;
        }
        try {
            Method inflate = LayoutInflater.class.getDeclaredMethod("inflate", int.class,
                    ViewGroup.class, boolean.class);
            hook(inflate).intercept(chain -> {
                Object result = chain.proceed();
                if (result instanceof View view) {
                    try {
                        widenValues(view);
                    } catch (Throwable t) {
                        if (panelFailureLogged.compareAndSet(false, true)) {
                            log(Log.WARN, TAG, "cannot widen the Palette values: " + t);
                        }
                    }
                }
                return result;
            });
            panelHooked = true;
            log(Log.INFO, TAG, "hooked LayoutInflater.inflate (Palette value width)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook LayoutInflater.inflate: " + t);
        }
    }

    private void widenValues(View root) {
        int[] ids = valueIds;
        if (ids == null) {
            Resources res = root.getResources();
            ids = new int[VALUE_IDS.length];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = res.getIdentifier(VALUE_IDS[i], "id", EDITOR_PACKAGE);
            }
            valueIds = ids;
        }
        for (int id : ids) {
            if (id != 0 && root.findViewById(id) instanceof TextView value) {
                widen(value);
            }
        }
    }

    private void widen(TextView value) {
        ViewGroup.LayoutParams params = value.getLayoutParams();
        if (params == null || params.width <= 0) {
            return;   // already wrapping its content, nothing to widen
        }
        int need = (int) Math.ceil(value.getPaint().measureText(WIDEST_VALUE))
                + value.getPaddingStart() + value.getPaddingEnd();
        if (params.width >= need) {
            return;
        }
        int was = params.width;
        params.width = need;
        value.setLayoutParams(params);
        value.setSingleLine(true);
        if (panelFailureLogged.get() || Log.isLoggable(TAG, Log.DEBUG)) {
            log(Log.DEBUG, TAG, "Palette value width " + was + " px -> " + need + " px");
        }
    }

    private void openGate(String stage, ClassLoader loader) throws Exception {
        String device = Build.DEVICE;
        if (device == null || device.isEmpty()) {
            log(Log.WARN, TAG, "Build.DEVICE is empty, nothing to add");
            return;
        }
        Class<?> holder = Class.forName(GATE_CLASS, false, loader);
        String[] palette = null;
        for (Field f : holder.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String[].class) {
                continue;
            }
            f.setAccessible(true);
            String[] values = (String[]) f.get(null);   // runs the class's static initialiser
            log(Log.DEBUG, TAG, GATE_CLASS + "." + f.getName() + " = " + Arrays.toString(values));
            if (values != null && values.length > 0 && values.length <= MAX_LENGTH
                    && containsKnown(values)) {
                if (palette != null) {
                    throw new IllegalStateException("more than one device array in " + GATE_CLASS);
                }
                palette = values;
            }
        }
        if (palette == null) {
            throw new NoSuchFieldException("no device array with the 18 series codenames in "
                    + GATE_CLASS);
        }
        for (String entry : palette) {
            if (device.equalsIgnoreCase(entry)) {
                opened = true;
                log(Log.INFO, TAG, device + " is already in the editor's Palette device list");
                return;
            }
        }
        String before = Arrays.toString(palette);
        palette[0] = device;   // the array object is shared by every reader, so all checks see it
        opened = true;
        log(Log.INFO, TAG, stage + ": Palette device list " + before + " -> "
                + Arrays.toString(palette) + " (this phone: " + device + ")");
    }

    private static boolean containsKnown(String[] values) {
        for (String v : values) {
            if (v == null) {
                continue;
            }
            String lower = v.toLowerCase(Locale.ROOT);
            for (String known : KNOWN) {
                if (known.equals(lower)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String systemProperty(String key, String fallback) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return (String) props.getMethod("get", String.class, String.class)
                    .invoke(null, key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }
}
