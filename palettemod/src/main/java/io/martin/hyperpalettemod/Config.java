package io.martin.hyperpalettemod;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedModule;

// Reads the settings inside a hooked app. They come from the framework's remote preferences, which
// the settings screen writes; each read sees the current value. Without them (no framework support,
// or the screen never opened) the defaults apply.
final class Config {
    private static final String TAG = "HyperPaletteMod";

    private final XposedModule module;
    private volatile SharedPreferences prefs;
    private volatile boolean failed;

    Config(XposedModule module) {
        this.module = module;
    }

    boolean editorPad() {
        return bool(Settings.EDITOR_PAD, Settings.EDITOR_PAD_DEFAULT);
    }

    boolean editorWidth() {
        return bool(Settings.EDITOR_WIDTH, Settings.EDITOR_WIDTH_DEFAULT);
    }

    boolean editorCompareSaved() {
        return bool(Settings.EDITOR_COMPARE_SAVED, Settings.EDITOR_COMPARE_SAVED_DEFAULT);
    }

    int cameraMode() {
        int mode = integer(Settings.CAMERA_MODE, Settings.CAMERA_MODE_DEFAULT);
        return mode < Settings.MODE_OFF || mode > Settings.MODE_ALL ? Settings.MODE_OFF : mode;
    }

    int cameraFilter() {
        int id = integer(Settings.CAMERA_FILTER, Settings.CAMERA_FILTER_DEFAULT);
        return id < 1 || id > 0xFFFF ? Settings.CAMERA_FILTER_DEFAULT : id;
    }

    boolean cameraJavaFilter() {
        return bool(Settings.CAMERA_JAVA_FILTER, Settings.CAMERA_JAVA_FILTER_DEFAULT);
    }

    private boolean bool(String key, boolean fallback) {
        SharedPreferences p = prefs();
        try {
            return p == null ? fallback : p.getBoolean(key, fallback);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private int integer(String key, int fallback) {
        SharedPreferences p = prefs();
        try {
            return p == null ? fallback : p.getInt(key, fallback);
        } catch (RuntimeException e) {
            return fallback;   // a value of another type
        }
    }

    private SharedPreferences prefs() {
        SharedPreferences p = prefs;
        if (p == null && !failed) {
            try {
                p = module.getRemotePreferences(Settings.GROUP);
                prefs = p;
            } catch (Throwable t) {
                failed = true;
                module.log(Log.WARN, TAG, "no remote preferences (" + t + "); using the defaults");
            }
        }
        return p;
    }
}
