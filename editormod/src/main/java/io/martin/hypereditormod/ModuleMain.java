package io.martin.hypereditormod;

import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Locale;

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

    private volatile boolean opened;

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
