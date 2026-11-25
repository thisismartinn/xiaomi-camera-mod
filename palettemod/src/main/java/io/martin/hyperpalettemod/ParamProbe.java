package io.martin.hyperpalettemod;

import android.graphics.Bitmap;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

// Step 1 of capturing the camera's real filter and Palette values: read only, changes nothing.
// The camera builds a "candy" script for the renderer in MIVIRenderTag$TagForCandy.getCandyParams
// (the same kind of "@CubeLutEffect;...@@StyleAdjustEffect;..." text the 18 Pro stores in the
// photo). This logs that text and the tag's own fields whenever the text changes, at most
// MAX_LINES times per process, so one filtered shot shows what values the camera really uses.
final class ParamProbe {
    private static final String TAG = "HyperPaletteMod";
    private static final String CANDY = "com.xiaomi.camera.mivi.filter.MIVIRenderTag$TagForCandy";
    private static final int MAX_LINES = 40;

    record CaptureStyle(int cameraFilterId, int editorFilterId, int degree,
                        String candyParam, String styleAdjust, List<Bitmap> lutBitmaps) {
    }

    private final XposedModule module;
    private final Config config;
    private final AtomicInteger lines = new AtomicInteger();
    private final AtomicInteger filterProcLines = new AtomicInteger();
    private final AtomicBoolean halTagBypassLogged = new AtomicBoolean();
    private final ConcurrentHashMap<Integer, CaptureStyle> styleMap = new ConcurrentHashMap<>();
    private volatile ClassLoader cameraLoader;
    private volatile String last;

    ParamProbe(XposedModule module, Config config) {
        this.module = module;
        this.config = config;
    }

    ClassLoader cameraLoader() {
        return cameraLoader;
    }

    // Returns the latest CaptureStyle learned from MIVIRenderTag for a given camera filterId,
    // or null if not seen yet.
    CaptureStyle styleFor(int cameraFilterId) {
        return styleMap.get(cameraFilterId);
    }

    int editorFilterId(int cameraFilterId) {
        CaptureStyle s = styleMap.get(cameraFilterId);
        return s != null ? s.editorFilterId() : -1;
    }

    void install(ClassLoader loader) {
        this.cameraLoader = loader;
        installTag(loader);
        installFilterProcessorProbe(loader);
        installJavaFilterRouting(loader);
        try {
            Class<?> candy = Class.forName(CANDY, false, loader);
            Method target = null;
            for (Method m : candy.getDeclaredMethods()) {
                if (m.getName().equals("getCandyParams") && m.getReturnType() == String.class
                        && m.getParameterCount() == 2) {
                    target = m;
                }
            }
            if (target == null) {
                throw new NoSuchMethodException("getCandyParams in " + candy);
            }
            Field outer = candy.getDeclaredField("this$0");
            outer.setAccessible(true);
            module.hook(target).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    report(chain.getThisObject(), outer, chain.getArg(1), result);
                } catch (Throwable t) {
                    // diagnostics must never disturb the camera
                }
                return result;
            });
            module.log(Log.INFO, TAG, "probing the camera's candy parameters (read only)");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "cannot probe the candy parameters: " + t);
        }
    }

    // Also hook the tag's own initParams(): it runs once per render setup and fills mCandyParams, so
    // it shows the values even if the per-part method above is not the one that fires.
    private void installTag(ClassLoader loader) {
        try {
            Class<?> tag = Class.forName("com.xiaomi.camera.mivi.filter.MIVIRenderTag", false, loader);
            Method init = tag.getDeclaredMethod("initParams");
            init.setAccessible(true);
            module.hook(init).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    dumpTag(chain.getThisObject());
                } catch (Throwable t) {
                    // diagnostics must never disturb the camera
                }
                return result;
            });
            module.log(Log.INFO, TAG, "probing MIVIRenderTag.initParams (read only)");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "cannot probe MIVIRenderTag.initParams: " + t);
        }
    }

    // Read-only probe on FilterProcessor (di.g.a in Camera 6.7.000500.0) to confirm whether the
    // unfiltered full-res android.media.Image passes through Java before the filter is applied.
    private void installFilterProcessorProbe(ClassLoader loader) {
        try {
            Class<?> fp = Class.forName("di.g", false, loader);
            Method target = null;
            for (Method m : fp.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("a") && p.length == 5 && p[1] == Image.class
                        && p[2] == int.class && p[3] == boolean.class) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                throw new NoSuchMethodException("di.g.a(di.r, Image, int, boolean, di.j)");
            }
            module.hook(target).intercept(chain -> {
                try {
                    if (filterProcLines.incrementAndGet() <= 16) {
                        Object imgObj = chain.getArg(1);
                        String imgDesc = imgObj instanceof Image img
                                ? img.getWidth() + "x" + img.getHeight() + " fmt=" + img.getFormat()
                                : String.valueOf(imgObj);
                        module.log(Log.INFO, TAG, "FilterProcessor di.g.a fired: image=" + imgDesc
                                + " resultType=" + chain.getArg(2)
                                + " swapUv=" + chain.getArg(3));
                    }
                } catch (Throwable t) {
                    // diagnostics must never disturb the camera
                }
                return chain.proceed();
            });
            module.log(Log.INFO, TAG, "probing FilterProcessor di.g.a (read only)");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "cannot probe FilterProcessor di.g.a: " + t);
        }
    }

    // Experimental opt-in: skips sending applyMIVIRenderType (n9.z0.L1) to the HAL so the saved
    // HEIC is unfiltered, allowing ReEditInjector to embed the real unfiltered image + live Palette.
    private void installJavaFilterRouting(ClassLoader loader) {
        if (!config.cameraJavaFilter()) {
            return;
        }
        try {
            Class<?> z0 = Class.forName("n9.z0", false, loader);
            Method l1 = z0.getDeclaredMethod("L1", CaptureRequest.Builder.class);
            module.hook(l1).intercept(chain -> {
                if (config.cameraJavaFilter()) {
                    if (halTagBypassLogged.compareAndSet(false, true)) {
                        module.log(Log.INFO, TAG, "n9.z0.L1 skipped (unfiltered HAL HEIC mode)");
                    }
                    return null;
                }
                return chain.proceed();
            });
            module.log(Log.INFO, TAG, "hooked n9.z0.L1 (unfiltered HAL HEIC mode, experimental)");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "cannot hook n9.z0.L1: " + t);
        }
    }

    private void dumpTag(Object tag) throws Exception {
        int camId = -1;
        int genId = -1;
        int degree = 100;
        String candyParam = null;
        String styleAdjust = null;
        List<Bitmap> luts = null;
        StringBuilder sb = new StringBuilder();
        for (Field f : tag.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Class<?> t = f.getType();
            if (t.isPrimitive() || t == String.class || t.isEnum()
                    || java.util.List.class.isAssignableFrom(t)) {
                f.setAccessible(true);
                Object v = f.get(tag);
                if ("mFilterId".equals(f.getName()) && v instanceof Integer i) {
                    camId = i;
                } else if ("mGeneralFilterId".equals(f.getName()) && v instanceof Integer i) {
                    genId = i;
                } else if ("mFilterDegree".equals(f.getName()) && v instanceof Integer i) {
                    degree = i;
                } else if ("mCandyParams".equals(f.getName()) && v instanceof java.util.List<?> list) {
                    for (Object o : list) {
                        if (o instanceof String s && !s.isEmpty()) {
                            if (candyParam == null) {
                                candyParam = s;
                            }
                            String extracted = extractStyleAdjust(s);
                            if (extracted != null && styleAdjust == null) {
                                styleAdjust = extracted;
                            }
                        }
                    }
                } else if ("mLutBitmaps".equals(f.getName()) && v instanceof java.util.List<?> list
                        && !list.isEmpty()) {
                    List<Bitmap> copies = new ArrayList<>(list.size());
                    for (Object o : list) {
                        if (o instanceof Bitmap b && !b.isRecycled()) {
                            Bitmap copy = b.copy(Bitmap.Config.ARGB_8888, false);
                            if (copy != null) {
                                copies.add(copy);
                            }
                        }
                    }
                    if (!copies.isEmpty()) {
                        luts = copies;
                    }
                }
                if (v instanceof java.util.List<?> list) {
                    sb.append(f.getName()).append("=[");
                    for (Object o : list) {
                        sb.append(o instanceof android.graphics.Bitmap ? "bitmap" : o).append(" | ");
                    }
                    sb.append("] ");
                } else {
                    sb.append(f.getName()).append('=').append(v).append(' ');
                }
            }
        }
        if (camId > 0 && genId > 0) {
            int mappedGenId = remapEditorFilterId(genId);
            CaptureStyle prev = styleMap.get(camId);
            List<Bitmap> keepLuts = luts != null ? luts : (prev != null ? prev.lutBitmaps() : null);
            styleMap.put(camId, new CaptureStyle(camId, mappedGenId, degree, candyParam,
                    styleAdjust, keepLuts));
        }
        if (lines.get() >= MAX_LINES) {
            return;
        }
        String text = sb.toString().trim();
        if (text.equals(last)) {
            return;
        }
        last = text;
        lines.incrementAndGet();
        module.log(Log.INFO, TAG, "initParams tag: " + text);
    }

    // 17 Ultra Media Editor uses 14x/15x IDs for Leica filters in the Leica UI, while
    // MIVIRenderTag.mGeneralFilterId reports the legacy 2..7 IDs for VIV, NAT, HC, and BLU.
    private static int remapEditorFilterId(int genId) {
        return switch (genId) {
            case 2 -> 140;   // VIV
            case 3 -> 141;   // NAT
            case 5 -> 149;   // HC
            case 7 -> 151;   // BLU
            default -> genId;
        };
    }

    private static String extractStyleAdjust(String candy) {
        int start = candy.indexOf("@StyleAdjustEffect;");
        if (start < 0) {
            return null;
        }
        int next = candy.indexOf('@', start + 1);
        return next > start ? candy.substring(start, next) : candy.substring(start);
    }

    private void report(Object self, Field outer, Object arg, Object result) throws Exception {
        String text = String.valueOf(result);
        if (text.equals(last) || lines.get() >= MAX_LINES) {
            return;
        }
        last = text;
        lines.incrementAndGet();
        Object tag = outer.get(self);
        StringBuilder sb = new StringBuilder();
        for (Field f : tag.getClass().getDeclaredFields()) {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod)) {
                continue;
            }
            Class<?> t = f.getType();
            if (t.isPrimitive() || t == String.class || t.isEnum()) {
                f.setAccessible(true);
                sb.append(f.getName()).append('=').append(f.get(tag)).append(' ');
            }
        }
        module.log(Log.INFO, TAG, "candy(arg=" + arg + ") = " + text);
        module.log(Log.INFO, TAG, "tag: " + sb.toString().trim());
    }
}
