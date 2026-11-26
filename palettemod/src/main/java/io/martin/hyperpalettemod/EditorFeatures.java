package io.martin.hyperpalettemod;

import android.content.res.Resources;
import android.graphics.Bitmap;
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
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

// What this module does inside the Media Editor (com.miui.mediaeditor 2.10.40.7 and 2.10.43.0.6,
// both read):
//
//  1. The Palette pad. Tapping an already selected filter asks the filter view model for the state
//     "param adjust". The request starts with a check that compares Build.DEVICE (ignoring case) with
//     the entries of a static String[] in class gt.a, and returns without doing anything when
//     there is no match. The array holds the codenames "madrid", "venice" and "hongkong" (the 18
//     series); the same check is made at three more places of the Palette UI, always against that one
//     array. When this phone is not in it, its Build.DEVICE is written over the first entry, so every
//     check passes with no per-build method names to find. A phone already in the list is left alone.
//
//  2. The pad's numbers. The Saturation and Tone numbers above the pad are in text views a fixed 16 dp
//     wide (sized for the 18 series' font); with another font a two digit value wraps onto two lines.
//     When the panel is inflated, both views are made as wide as "100" needs in their own font.
//
//  3. Optional Compare-against-saved-look. On non-destructive re-edit photos, Media Editor's Compare
//     button toggles h50.q.setShowOrigin(true), which draws a50.a.a (the embedded unfiltered origin).
//     When enabled, we snapshot the first non-origin rendered bitmap and temporarily swap a50.a.a
//     while setShowOrigin(true) is active, restoring the real unfiltered origin on release.
//
// Each is a setting of this module (see Settings). The pad is decided when the editor starts; the
// numbers and compare mode are read dynamically.
final class EditorFeatures {
    private static final String TAG = "HyperPaletteMod";
    private static final String GATE_CLASS = "gt.a";
    // Entries of the editor's own list; used only to tell the Palette array from the other static
    // String[] fields of the same class (they hold other devices' codenames).
    private static final String[] KNOWN = {"madrid", "venice", "hongkong"};
    private static final int MAX_LENGTH = 8;   // the Palette array has 3 entries; the others 2..36
    private static final String[] VALUE_IDS = {
            "filter_param_panel_saturation_value", "filter_param_panel_tone_value"};
    private static final String WIDEST_VALUE = "100";   // the largest magnitude the pad shows

    private static final class CompareState {
        Object originState;
        Bitmap realOrigin;
        Bitmap savedSnapshot;
        Bitmap pendingBeforeOrigin;
        boolean swapped;
    }

    private final XposedModule module;
    private final Config config;
    private volatile boolean padDone;
    private volatile boolean widthHooked;
    private volatile boolean compareHooked;
    private volatile int[] valueIds;
    private final AtomicBoolean widthFailureLogged = new AtomicBoolean();
    private final AtomicBoolean compareSnapshotLogged = new AtomicBoolean();
    private final WeakHashMap<Object, CompareState> compareStates = new WeakHashMap<>();

    EditorFeatures(XposedModule module, Config config) {
        this.module = module;
        this.config = config;
    }

    void install(String stage, ClassLoader loader) {
        if (!padDone && config.editorPad()) {
            try {
                openPad(stage, loader);
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, stage + ": cannot open the Palette device check: " + t);
            }
        }
        installWidthFix();
        installCompareHook(loader);
    }

    // ---- 1. the pad ----------------------------------------------------------------------------

    private void openPad(String stage, ClassLoader loader) throws Exception {
        String device = Build.DEVICE;
        if (device == null || device.isEmpty()) {
            module.log(Log.WARN, TAG, "Build.DEVICE is empty, nothing to add");
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
                padDone = true;
                module.log(Log.INFO, TAG, device + " is already in the editor's Palette device list");
                return;
            }
        }
        String before = Arrays.toString(palette);
        palette[0] = device;   // the array object is shared by every reader, so all checks see it
        padDone = true;
        module.log(Log.INFO, TAG, stage + ": Palette device list " + before + " -> "
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

    // ---- 2. the numbers ------------------------------------------------------------------------

    private void installWidthFix() {
        if (widthHooked) {
            return;
        }
        try {
            Method inflate = LayoutInflater.class.getDeclaredMethod("inflate", int.class,
                    ViewGroup.class, boolean.class);
            module.hook(inflate).intercept(chain -> {
                Object result = chain.proceed();
                if (result instanceof View view && config.editorWidth()) {
                    try {
                        widenValues(view);
                    } catch (Throwable t) {
                        if (widthFailureLogged.compareAndSet(false, true)) {
                            module.log(Log.WARN, TAG, "cannot widen the Palette values: " + t);
                        }
                    }
                }
                return result;
            });
            widthHooked = true;
            module.log(Log.INFO, TAG, "hooked LayoutInflater.inflate (Palette value width)");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "cannot hook LayoutInflater.inflate: " + t);
        }
    }

    private void widenValues(View root) {
        int[] ids = valueIds;
        if (ids == null) {
            Resources res = root.getResources();
            ids = new int[VALUE_IDS.length];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = res.getIdentifier(VALUE_IDS[i], "id", Settings.EDITOR_PACKAGE);
            }
            valueIds = ids;
        }
        for (int id : ids) {
            if (id != 0 && root.findViewById(id) instanceof TextView value) {
                widen(value);
            }
        }
    }

    private static void widen(TextView value) {
        ViewGroup.LayoutParams params = value.getLayoutParams();
        if (params == null || params.width <= 0) {
            return;   // already wrapping its content, nothing to widen
        }
        int need = (int) Math.ceil(value.getPaint().measureText(WIDEST_VALUE))
                + value.getPaddingStart() + value.getPaddingEnd();
        if (params.width >= need) {
            return;
        }
        params.width = need;
        value.setLayoutParams(params);
        value.setSingleLine(true);
    }

    // ---- 3. compare against saved look ---------------------------------------------------------

    private void installCompareHook(ClassLoader loader) {
        if (compareHooked) {
            return;
        }
        try {
            Class<?> canvasView = Class.forName("h50.q", false, loader);
            Class<?> originStateClass = Class.forName("a50.a", false, loader);
            Field origBitmapField = originStateClass.getDeclaredField("a");
            origBitmapField.setAccessible(true);

            Method setOriginState = canvasView.getDeclaredMethod("setOriginalImageState",
                    originStateClass);
            Method setImageBitmap = canvasView.getDeclaredMethod(
                    "setImageBitmap$photoeditor_base_release", Bitmap.class);
            Method setShowOrigin = canvasView.getDeclaredMethod("setShowOrigin", boolean.class);

            module.hook(setOriginState).intercept(chain -> {
                Object res = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    Object origState = chain.getArg(0);
                    if (self != null) {
                        Bitmap originBmp = origState != null
                                && origBitmapField.get(origState) instanceof Bitmap b ? b : null;
                        synchronized (compareStates) {
                            CompareState cs = compareStates.computeIfAbsent(self,
                                    k -> new CompareState());
                            if (cs.originState != origState) {
                                if (cs.savedSnapshot != null && !cs.savedSnapshot.isRecycled()) {
                                    cs.savedSnapshot.recycle();
                                }
                                cs.originState = origState;
                                cs.realOrigin = originBmp;
                                cs.swapped = false;
                                cs.savedSnapshot = null;
                                if (cs.pendingBeforeOrigin != null
                                        && !cs.pendingBeforeOrigin.isRecycled()
                                        && cs.pendingBeforeOrigin != originBmp) {
                                    cs.savedSnapshot = copyBitmap(cs.pendingBeforeOrigin);
                                }
                                cs.pendingBeforeOrigin = null;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return res;
            });

            module.hook(setImageBitmap).intercept(chain -> {
                Object res = chain.proceed();
                if (!config.editorCompareSaved()) {
                    return res;
                }
                try {
                    Object self = chain.getThisObject();
                    Object arg0 = chain.getArg(0);
                    if (self != null && arg0 instanceof Bitmap bmp && !bmp.isRecycled()) {
                        synchronized (compareStates) {
                            CompareState cs = compareStates.computeIfAbsent(self,
                                    k -> new CompareState());
                            if (cs.originState == null) {
                                cs.pendingBeforeOrigin = bmp;
                            } else if (cs.savedSnapshot == null && bmp != cs.realOrigin) {
                                Bitmap snap = copyBitmap(bmp);
                                if (snap != null) {
                                    cs.savedSnapshot = snap;
                                    if (compareSnapshotLogged.compareAndSet(false, true)) {
                                        module.log(Log.INFO, TAG, "editor compare: captured saved "
                                                + "snapshot " + snap.getWidth() + "x"
                                                + snap.getHeight());
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return res;
            });

            module.hook(setShowOrigin).intercept(chain -> {
                try {
                    Object self = chain.getThisObject();
                    boolean show = Boolean.TRUE.equals(chain.getArg(0));
                    if (self != null) {
                        synchronized (compareStates) {
                            CompareState cs = compareStates.get(self);
                            if (cs != null && cs.originState != null) {
                                if (show && config.editorCompareSaved()
                                        && cs.savedSnapshot != null
                                        && !cs.savedSnapshot.isRecycled()) {
                                    origBitmapField.set(cs.originState, cs.savedSnapshot);
                                    cs.swapped = true;
                                } else if (!show && cs.swapped && cs.realOrigin != null) {
                                    origBitmapField.set(cs.originState, cs.realOrigin);
                                    cs.swapped = false;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });

            compareHooked = true;
            module.log(Log.INFO, TAG, "hooked Media Editor compare view (h50.q)");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "cannot hook Media Editor compare view (h50.q): " + t);
        }
    }

    private static Bitmap copyBitmap(Bitmap src) {
        try {
            Bitmap.Config cfg = src.getConfig();
            return src.copy(cfg != null ? cfg : Bitmap.Config.ARGB_8888, false);
        } catch (Throwable t) {
            return null;
        }
    }
}
