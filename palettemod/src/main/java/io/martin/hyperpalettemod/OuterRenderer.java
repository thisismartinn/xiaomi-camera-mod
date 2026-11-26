package io.martin.hyperpalettemod;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.Gainmap;
import android.os.Build;
import android.util.Log;

import androidx.heifwriter.HeifWriter;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;

import io.github.libxposed.api.XposedModule;

// Renders the camera's live filter + Palette look onto an unfiltered HEIC/JPEG using the camera's
// own CandySDK, then encodes the filtered Bitmap back to HEIC (via Jetpack HeifWriter) or JPEG
// (via Bitmap.compress, preserving Ultra HDR Gainmap when present).
// The caller then copies the original Exif and color profile onto the rendered image and embeds
// the unfiltered source inside the re-edit protobuf so Gallery shows the filtered photo while
// Media Editor can non-destructively re-edit it.
final class OuterRenderer {
    private static final String TAG = "HyperPaletteMod";
    private static final String CANDY_SDK = "com.xiaomi.milab.filtersdk.CandySDK";
    private static final int CANDY_MODE_BITMAP = 5;   // RGBA Bitmap (10 is YUV HardwareBuffer)
    private static final int OUTER_QUALITY = 98;
    private static final long HEIF_STOP_TIMEOUT_MS = 15_000L;

    private static final int THUMB_MAX_EDGE = 240;

    record Result(byte[] heic, byte[] thumbJpeg, Bitmap uiThumb) {
    }

    private OuterRenderer() {
    }

    static Bitmap filterBitmap(XposedModule module, ClassLoader cameraLoader, Bitmap src,
                               ParamProbe.CaptureStyle style) {
        if (cameraLoader == null || src == null || src.isRecycled()
                || style == null || style.candyParam() == null) {
            return null;
        }
        long t0 = System.currentTimeMillis();
        Bitmap out = null;
        try {
            out = src.copy(Bitmap.Config.ARGB_8888, true);
            if (out == null) {
                return null;
            }
            applyCandy(cameraLoader, out, style.candyParam(), style.lutBitmaps());
            module.log(Log.INFO, TAG, "OuterRenderer thumb: " + out.getWidth() + "x"
                    + out.getHeight() + " in " + (System.currentTimeMillis() - t0) + "ms");
            return out;
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "OuterRenderer thumb failed: " + t);
            if (out != null && !out.isRecycled()) {
                out.recycle();
            }
            return null;
        }
    }

    static Result render(XposedModule module, Application app, ClassLoader cameraLoader,
                         byte[] unfilteredHeic, ParamProbe.CaptureStyle style, String path) {
        if (app == null || cameraLoader == null || style == null || style.candyParam() == null) {
            return null;
        }
        long t0 = System.currentTimeMillis();
        Bitmap bitmap = null;
        File temp = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inMutable = true;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bitmap = BitmapFactory.decodeByteArray(unfilteredHeic, 0, unfilteredHeic.length, opts);
            if (bitmap == null) {
                module.log(Log.WARN, TAG, "OuterRenderer: failed to decode unfiltered HEIC");
                return null;
            }
            ColorSpace cs = bitmap.getColorSpace();
            boolean hasGainmap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && bitmap.hasGainmap();

            applyCandy(cameraLoader, bitmap, style.candyParam(), style.lutBitmaps());
            Bitmap scaled = scaleThumb(bitmap);
            byte[] thumbJpeg = compressThumb(scaled);

            temp = File.createTempFile("hpm_outer_", ".heic", app.getCacheDir());
            try (HeifWriter writer = new HeifWriter.Builder(
                    temp.getAbsolutePath(),
                    bitmap.getWidth(),
                    bitmap.getHeight(),
                    HeifWriter.INPUT_MODE_BITMAP)
                    .setQuality(OUTER_QUALITY)
                    .setRotation(0)
                    .build()) {
                writer.start();
                writer.addBitmap(bitmap);
                writer.stop(HEIF_STOP_TIMEOUT_MS);
            }
            byte[] encoded = Files.readAllBytes(temp.toPath());
            module.log(Log.INFO, TAG, "OuterRenderer [" + path + "/heic]: rendered "
                    + bitmap.getWidth() + "x" + bitmap.getHeight() + " -> " + encoded.length
                    + " bytes (q=" + OUTER_QUALITY + ", cs=" + (cs != null ? cs.getName() : "null")
                    + ", gainmap=" + hasGainmap + ", thumb "
                    + (thumbJpeg != null ? thumbJpeg.length : 0) + " B) in "
                    + (System.currentTimeMillis() - t0) + "ms");
            return new Result(encoded, thumbJpeg, scaled);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "OuterRenderer failed, falling back to unfiltered outer: " + t);
            return null;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            if (temp != null) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    static Result renderJpeg(XposedModule module, ClassLoader cameraLoader,
                             byte[] unfilteredJpeg, ParamProbe.CaptureStyle style, String path) {
        if (cameraLoader == null || style == null || style.candyParam() == null) {
            return null;
        }
        long t0 = System.currentTimeMillis();
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inMutable = true;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bitmap = BitmapFactory.decodeByteArray(unfilteredJpeg, 0, unfilteredJpeg.length, opts);
            if (bitmap == null) {
                module.log(Log.WARN, TAG, "OuterRenderer: failed to decode unfiltered JPEG");
                return null;
            }
            ColorSpace cs = bitmap.getColorSpace();
            Gainmap gainmap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && bitmap.hasGainmap() ? bitmap.getGainmap() : null;

            applyCandy(cameraLoader, bitmap, style.candyParam(), style.lutBitmaps());
            if (gainmap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    && !bitmap.hasGainmap()) {
                bitmap.setGainmap(gainmap);
            }
            Bitmap scaled = scaleThumb(bitmap);
            byte[] thumbJpeg = compressThumb(scaled);

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(unfilteredJpeg.length);
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, OUTER_QUALITY, out)) {
                module.log(Log.WARN, TAG, "OuterRenderer: JPEG compress failed");
                return null;
            }
            byte[] encoded = out.toByteArray();
            module.log(Log.INFO, TAG, "OuterRenderer [" + path + "/jpeg]: rendered "
                    + bitmap.getWidth() + "x" + bitmap.getHeight() + " -> " + encoded.length
                    + " bytes (q=" + OUTER_QUALITY + ", cs=" + (cs != null ? cs.getName() : "null")
                    + ", gainmap=" + (gainmap != null) + ", thumb "
                    + (thumbJpeg != null ? thumbJpeg.length : 0) + " B) in "
                    + (System.currentTimeMillis() - t0) + "ms");
            return new Result(encoded, thumbJpeg, scaled);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "OuterRenderer JPEG failed, falling back to unfiltered outer: " + t);
            return null;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private static Bitmap scaleThumb(Bitmap full) {
        int w = full.getWidth();
        int h = full.getHeight();
        int max = Math.max(w, h);
        int tw = max > THUMB_MAX_EDGE ? Math.max(1, (int) Math.round(w * (THUMB_MAX_EDGE / (double) max))) : w;
        int th = max > THUMB_MAX_EDGE ? Math.max(1, (int) Math.round(h * (THUMB_MAX_EDGE / (double) max))) : h;
        return Bitmap.createScaledBitmap(full, tw, th, true);
    }

    private static byte[] compressThumb(Bitmap scaled) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, out);
        if (out.size() > 10_000) {
            out.reset();
            scaled.compress(Bitmap.CompressFormat.JPEG, 55, out);
        }
        return out.toByteArray();
    }

    private static void applyCandy(ClassLoader loader, Bitmap bitmap, String candyParam,
                                   List<Bitmap> lutBitmaps) throws Exception {
        Class<?> sdkClass = Class.forName(CANDY_SDK, false, loader);
        Object sdk = sdkClass.getDeclaredConstructor(int.class).newInstance(CANDY_MODE_BITMAP);
        try {
            String pipeline = candyParam.startsWith("@") ? candyParam.substring(1) : candyParam;
            sdkClass.getMethod("a", String.class).invoke(sdk, pipeline);
            int[] indices = (int[]) sdkClass.getMethod("b", String.class).invoke(sdk, pipeline);
            if (indices != null && lutBitmaps != null && !lutBitmaps.isEmpty()) {
                Method setLut = sdkClass.getMethod("f", int.class, Bitmap.class);
                int n = Math.min(indices.length, lutBitmaps.size());
                for (int i = 0; i < n; i++) {
                    Bitmap lut = lutBitmaps.get(i);
                    if (lut != null && !lut.isRecycled()) {
                        setLut.invoke(sdk, indices[i], lut);
                    }
                }
            }
            float[] rect = new float[]{0f, 0f, bitmap.getWidth(), bitmap.getHeight()};
            sdkClass.getMethod("c", Object.class, float[].class).invoke(sdk, bitmap, rect);
        } finally {
            sdkClass.getMethod("e").invoke(sdk);
        }
    }
}
