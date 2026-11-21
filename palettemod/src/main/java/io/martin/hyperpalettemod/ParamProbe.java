package io.martin.hyperpalettemod;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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

    private final XposedModule module;
    private final AtomicInteger lines = new AtomicInteger();
    private volatile String last;

    ParamProbe(XposedModule module) {
        this.module = module;
    }

    void install(ClassLoader loader) {
        installTag(loader);
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

    private void dumpTag(Object tag) throws Exception {
        if (lines.get() >= MAX_LINES) {
            return;
        }
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
        String text = sb.toString().trim();
        if (text.equals(last)) {
            return;
        }
        last = text;
        lines.incrementAndGet();
        module.log(Log.INFO, TAG, "initParams tag: " + text);
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
