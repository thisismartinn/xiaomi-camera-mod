package io.martin.hyperpalettemod;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;

// What this module does inside the Camera (com.android.camera 6.7.000500.0, read, not run).
//
// The Media Editor opens its Palette pad only for a photo that carries re-edit data: an "edit" item
// and a micamedit XMP marker in the HEIC. The 18 Pro camera writes them; the 17 Ultra camera does
// not. This adds them (see ReEditInjector) to each HEIC the camera saves, right after the file is
// written, with a neutral Palette: the 17 Ultra camera does not hand over the unfiltered picture, so
// the photo itself is the source and the pad starts at 0.
//
// Two ways in, because which save path a given photo takes was only read from the code: the two HEIC
// save methods are hooked, and a watcher on DCIM/Camera picks up any finished HEIC the hooks missed.
// A photo that already has the data is skipped, so both can run on the same file.
//
// Settings: the mode (off, photos taken with a filter, every HEIC) and the editor filter the data
// names. Both are read on every save.
final class CameraFeatures {
    private static final String TAG = "HyperPaletteMod";
    private static final String CAMERA_DIR = "DCIM/Camera";

    // The two places the camera writes a HEIC: the normal save (returns the MediaStore uri of the
    // finished file) and the parallel save (returns the file's bytes, or nothing when it wrote
    // through a file descriptor itself).
    private record SaveProfile(String name, String heifClass, String heifMethod,
                               String parallelClass, String parallelMethod) {
    }

    private static final SaveProfile[] SAVE_PROFILES = {
            // Camera 6.7: 6.7.000500.0
            new SaveProfile("6.7", "n7.f", "c", "W8.e", "e"),
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final XposedModule module;
    private final Config config;
    private final ParamProbe probe;
    private volatile boolean hooked;
    private final AtomicInteger seen = new AtomicInteger();
    private final AtomicBoolean logged = new AtomicBoolean();
    private final AtomicInteger thumbLogs = new AtomicInteger();
    private volatile FileObserver watcher;   // kept: an unreferenced FileObserver is collected
    private volatile ScheduledExecutorService pool;
    private volatile WeakReference<ImageView> thumbViewRef = new WeakReference<>(null);
    private volatile Bitmap lastUiThumb;
    private volatile long lastUiThumbMs;
    private volatile long lastInPlaceThumbMs;

    CameraFeatures(XposedModule module, Config config) {
        this.module = module;
        this.config = config;
        this.probe = new ParamProbe(module, config);
    }

    void install(String stage, ClassLoader loader) {
        if (hooked) {
            return;
        }
        installWatcher();
        probe.install(loader);
        installWriterHook(loader);
        installThumbnailHook(loader);
        for (SaveProfile profile : SAVE_PROFILES) {
            int hooks = 0;
            try {
                Class<?> heif = Class.forName(profile.heifClass(), false, loader);
                Method write = null;
                for (Method m : heif.getDeclaredMethods()) {
                    if (m.getName().equals(profile.heifMethod()) && m.getReturnType() == Uri.class
                            && m.getParameterCount() > 0
                            && m.getParameterTypes()[0] == Image.class) {
                        write = m;
                    }
                }
                if (write == null) {
                    throw new NoSuchMethodException("HEIC writer in " + heif);
                }
                module.hook(write).intercept(chain -> {
                    Object result = chain.proceed();
                    if (seen.incrementAndGet() <= 8) {
                        module.log(Log.INFO, TAG, "normal HEIC save finished: " + result + ", mode="
                                + config.cameraMode());
                    }
                    if (result instanceof Uri uri && config.cameraMode() != Settings.MODE_OFF) {
                        try {
                            addToUri(uri);
                        } catch (Throwable t) {
                            module.log(Log.WARN, TAG, "re-edit data not added to " + uri + ": " + t);
                        }
                    }
                    return result;
                });
                hooks++;
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "cannot hook the normal HEIC save (layout "
                        + profile.name() + "): " + t);
            }
            try {
                Class<?> parallel = Class.forName(profile.parallelClass(), false, loader);
                Method write = null;
                for (Method m : parallel.getDeclaredMethods()) {
                    Class<?>[] types = m.getParameterTypes();
                    if (m.getName().equals(profile.parallelMethod())
                            && m.getReturnType() == byte[].class && types.length == 6
                            && types[2] == byte[].class) {
                        write = m;
                    }
                }
                if (write == null) {
                    throw new NoSuchMethodException("parallel HEIC writer in " + parallel);
                }
                module.hook(write).intercept(chain -> {
                    Object result = chain.proceed();
                    int mode = config.cameraMode();
                    if (seen.incrementAndGet() <= 8) {
                        module.log(Log.INFO, TAG, "parallel HEIC save finished: "
                                + (result instanceof byte[] b ? b.length + " bytes" : result)
                                + ", mode=" + mode);
                    }
                    if (mode != Settings.MODE_OFF && result instanceof byte[] bytes
                            && bytes.length > 0) {
                        try {
                            byte[] injected = addToBytes(bytes, mode, "parallel save");
                            if (injected != null) {
                                return injected;
                            }
                        } catch (Throwable t) {
                            module.log(Log.WARN, TAG, "re-edit data not added (parallel save): " + t);
                        }
                    }
                    return result;
                });
                hooks++;
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "cannot hook the parallel HEIC save (layout "
                        + profile.name() + "): " + t);
            }
            if (hooks > 0) {
                hooked = true;
                module.log(Log.INFO, TAG, "hooked " + hooks + " HEIC save path(s) (layout "
                        + profile.name() + "; mode " + config.cameraMode() + ")");
                return;
            }
        }
    }

    // Intercepts TaskDataStorageWriter.writeTo(OutputStream) (v7.j.b in Camera 6.7.000500.0) so
    // the filtered + re-edit HEIC is written to disk on the very first save—before Camera or
    // Gallery ever reads or caches an unfiltered thumbnail or full-screen preview.
    private void installWriterHook(ClassLoader loader) {
        try {
            Class<?> writer = Class.forName("v7.j", false, loader);
            Method writeTo = writer.getDeclaredMethod("b", OutputStream.class);
            module.hook(writeTo).intercept(chain -> {
                int mode = config.cameraMode();
                Object arg0 = chain.getArg(0);
                if (mode == Settings.MODE_OFF || !(arg0 instanceof OutputStream realOut)) {
                    return chain.proceed();
                }
                ByteArrayOutputStream buf = new ByteArrayOutputStream(4 * 1024 * 1024);
                Object result = chain.proceed(new Object[]{buf});
                byte[] orig = buf.toByteArray();
                byte[] outBytes = orig;
                if (orig.length > 0) {
                    try {
                        byte[] injected = addToBytes(orig, mode, "writer v7.j.b");
                        if (injected != null) {
                            outBytes = injected;
                        }
                    } catch (Throwable t) {
                        module.log(Log.WARN, TAG, "writer hook failed, saving original: " + t);
                    }
                }
                realOut.write(outBytes);
                return result;
            });
            module.log(Log.INFO, TAG, "hooked TaskDataStorageWriter (v7.j.b)");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "cannot hook TaskDataStorageWriter (v7.j.b): " + t);
        }
        try {
            Class<?> storage = Class.forName("n7.M", false, loader);
            Method openOut = storage.getDeclaredMethod("x", String.class, Context.class, Uri.class);
            module.hook(openOut).intercept(chain -> {
                Object result = chain.proceed();
                int mode = config.cameraMode();
                Object pathObj = chain.getArg(0);
                if (mode != Settings.MODE_OFF && result instanceof OutputStream realOut
                        && pathObj instanceof String path
                        && path.toLowerCase(Locale.ROOT).endsWith(".heic")) {
                    return new BufferedHeicOutputStream(realOut, mode, path);
                }
                return result;
            });
            module.log(Log.INFO, TAG, "hooked Storage.openOutputStream (n7.M.x)");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "cannot hook Storage.openOutputStream (n7.M.x): " + t);
        }
        try {
            Class<?> storage = Class.forName("n7.M", false, loader);
            Method writeBuf = storage.getDeclaredMethod("B", String.class, ByteBuffer.class);
            module.hook(writeBuf).intercept(chain -> {
                int mode = config.cameraMode();
                Object pathObj = chain.getArg(0);
                Object bufObj = chain.getArg(1);
                if (mode != Settings.MODE_OFF && pathObj instanceof String path
                        && path.toLowerCase(Locale.ROOT).endsWith(".heic")
                        && bufObj instanceof ByteBuffer bb) {
                    ByteBuffer dup = bb.duplicate();
                    byte[] orig = new byte[dup.remaining()];
                    dup.get(orig);
                    try {
                        byte[] injected = addToBytes(orig, mode, "Storage.B (" + path + ")");
                        if (injected != null) {
                            return chain.proceed(new Object[]{path, ByteBuffer.wrap(injected)});
                        }
                    } catch (Throwable t) {
                        module.log(Log.WARN, TAG, "Storage.B hook failed, saving original: " + t);
                    }
                }
                return chain.proceed();
            });
            module.log(Log.INFO, TAG, "hooked Storage.writeByteBuffer (n7.M.B)");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "cannot hook Storage.writeByteBuffer (n7.M.B): " + t);
        }
        try {
            Class<?> storage = Class.forName("n7.M", false, loader);
            Method saveJ = null;
            for (Method m : storage.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("J") && p.length == 9 && p[3] == ByteBuffer.class) {
                    saveJ = m;
                    break;
                }
            }
            if (saveJ == null) {
                throw new NoSuchMethodException("n7.M.J(..., ByteBuffer, ...)");
            }
            module.hook(saveJ).intercept(chain -> {
                int mode = config.cameraMode();
                Object bufObj = chain.getArg(3);
                if (mode != Settings.MODE_OFF && bufObj instanceof ByteBuffer bb) {
                    ByteBuffer dup = bb.duplicate();
                    byte[] orig = new byte[dup.remaining()];
                    dup.get(orig);
                    if (ReEditInjector.isHeic(orig)) {
                        try {
                            byte[] injected = addToBytes(orig, mode, "Storage.J");
                            if (injected != null) {
                                Object[] args = new Object[9];
                                for (int i = 0; i < 9; i++) {
                                    args[i] = chain.getArg(i);
                                }
                                args[3] = ByteBuffer.wrap(injected);
                                return chain.proceed(args);
                            }
                        } catch (Throwable t) {
                            module.log(Log.WARN, TAG, "Storage.J hook failed, saving original: "
                                    + t);
                        }
                    }
                }
                return chain.proceed();
            });
            module.log(Log.INFO, TAG, "hooked Storage.J (n7.M.J ByteBuffer save)");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "cannot hook Storage.J (n7.M.J): " + t);
        }
    }

    // Hooks FragmentBottomAction.updateThumbnail (A4.v1.v0 in Camera 6.7.000500.0) so Camera's
    // round thumbnail circle shows our CandySDK-rendered filtered thumbnail right after shutter.
    private void installThumbnailHook(ClassLoader loader) {
        try {
            Class<?> fragClass = Class.forName("A4.v1", false, loader);
            Method updateThumb = null;
            for (Method m : fragClass.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("v0") && p.length == 4
                        && p[1] == boolean.class && p[2] == int.class && p[3] == boolean.class) {
                    updateThumb = m;
                    break;
                }
            }
            if (updateThumb == null) {
                throw new NoSuchMethodException("A4.v1.v0(F1.i4, boolean, int, boolean)");
            }
            Field imageField = fragClass.getDeclaredField("j");
            imageField.setAccessible(true);
            Field bmpField = updateThumb.getParameterTypes()[0].getDeclaredField("b");
            bmpField.setAccessible(true);
            module.hook(updateThumb).intercept(chain -> {
                ImageView iv = null;
                try {
                    Object self = chain.getThisObject();
                    if (self != null && imageField.get(self) instanceof ImageView v) {
                        iv = v;
                        thumbViewRef = new WeakReference<>(v);
                    }
                } catch (Throwable ignored) {
                }
                Object thumb = chain.getArg(0);
                boolean transition = Boolean.TRUE.equals(chain.getArg(1));
                boolean filteredInPlace = false;
                boolean swapped = false;
                boolean hadStyle = false;
                if (thumb != null && config.cameraMode() != Settings.MODE_OFF
                        && config.cameraJavaFilter()) {
                    ParamProbe.CaptureStyle style = probe.consumeThumbStyle(5000);
                    hadStyle = style != null && style.candyParam() != null;
                    if (hadStyle) {
                        try {
                            Object bObj = bmpField.get(thumb);
                            if (bObj instanceof Bitmap src && !src.isRecycled()) {
                                Bitmap filtered = OuterRenderer.filterBitmap(module,
                                        probe.cameraLoader(), src, style);
                                if (filtered != null) {
                                    bmpField.set(thumb, filtered);
                                    filteredInPlace = true;
                                    lastUiThumb = filtered;
                                    lastUiThumbMs = System.currentTimeMillis();
                                    lastInPlaceThumbMs = lastUiThumbMs;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    } else {
                        Bitmap override = lastUiThumb;
                        boolean fresh = override != null && !override.isRecycled()
                                && (System.currentTimeMillis() - lastUiThumbMs) < 1500;
                        if (fresh) {
                            try {
                                bmpField.set(thumb, override);
                                swapped = true;
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                if (thumbLogs.incrementAndGet() <= 12) {
                    module.log(Log.INFO, TAG, "thumb v0: thumb=" + (thumb != null)
                            + ", transition=" + transition
                            + ", hadStyle=" + hadStyle
                            + ", filteredInPlace=" + filteredInPlace
                            + ", swapped=" + swapped + ", iv=" + (iv != null));
                }
                return chain.proceed();
            });
            module.log(Log.INFO, TAG, "hooked FragmentBottomAction.updateThumbnail (A4.v1.v0)");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "cannot hook FragmentBottomAction.updateThumbnail (A4.v1.v0): "
                    + t);
        }
    }

    private void pushUiThumb(Bitmap uiThumb) {
        if (uiThumb == null || uiThumb.isRecycled()) {
            return;
        }
        long now = System.currentTimeMillis();
        lastUiThumb = uiThumb;
        lastUiThumbMs = now;
        if (now - lastInPlaceThumbMs < 3000) {
            return;
        }
        MAIN.post(() -> {
            ImageView iv = thumbViewRef.get();
            if (iv != null && !uiThumb.isRecycled()) {
                iv.setImageBitmap(uiThumb);
                iv.setVisibility(View.VISIBLE);
            }
        });
    }

    private final class BufferedHeicOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final int mode;
        private final String path;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(4 * 1024 * 1024);
        private boolean closed;

        BufferedHeicOutputStream(OutputStream delegate, int mode, String path) {
            this.delegate = delegate;
            this.mode = mode;
            this.path = path;
        }

        @Override
        public void write(int b) {
            buffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                byte[] orig = buffer.toByteArray();
                byte[] outBytes = orig;
                if (orig.length > 0) {
                    try {
                        byte[] injected = addToBytes(orig, mode, "Storage.x (" + path + ")");
                        if (injected != null) {
                            outBytes = injected;
                        }
                    } catch (Throwable t) {
                        module.log(Log.WARN, TAG, "Storage.x hook failed, saving original: " + t);
                    }
                }
                delegate.write(outBytes);
                delegate.flush();
            } finally {
                delegate.close();
            }
        }
    }

    // The finished file is read back through its MediaStore uri and, when data was added, written
    // over itself. If the write fails the original bytes are written back.
    private void addToUri(Uri uri) throws IOException {
        Application app = currentApplication();
        if (app == null) {
            return;
        }
        ContentResolver resolver = app.getContentResolver();
        byte[] file;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return;
            }
            file = in.readAllBytes();
        }
        byte[] injected = addToBytes(file, config.cameraMode(), "normal save");
        if (injected == null) {
            return;
        }
        try (OutputStream out = resolver.openOutputStream(uri, "wt")) {
            if (out == null) {
                return;
            }
            out.write(injected);
        } catch (IOException | RuntimeException e) {
            module.log(Log.ERROR, TAG, "writing the photo failed, putting the original back: " + e);
            try (OutputStream out = resolver.openOutputStream(uri, "wt")) {
                if (out != null) {
                    out.write(file);
                }
            }
            throw new IOException("write failed", e);
        }
    }

    // The photo with the data added, or null when it is not to be changed.
    private byte[] addToBytes(byte[] file, int mode, String path) {
        if (!ReEditInjector.isHeic(file)) {
            return null;
        }
        if (ReEditInjector.hasEdit(file)) {
            if (seen.get() <= 8) {
                module.log(Log.INFO, TAG, "re-edit data already in the photo (" + path + ")");
            }
            return null;
        }
        int cameraFilter = ReEditInjector.cameraFilterId(file);
        if (mode == Settings.MODE_FILTERED
                && (cameraFilter < 0 || cameraFilter == ReEditInjector.FILTER_STANDARD)) {
            lastUiThumb = null;
            lastUiThumbMs = 0;
            if (seen.get() <= 8) {
                module.log(Log.INFO, TAG, "re-edit data skipped on the " + path + ": no filter in "
                        + "this photo (camera filter id " + cameraFilter + ")");
            }
            return null;
        }
        ParamProbe.CaptureStyle style = probe.styleFor(cameraFilter);
        int learned = style != null ? style.editorFilterId() : -1;
        int filter = learned > 0 ? learned
                : (cameraFilter == ReEditInjector.CAMERA_CHROME ? ReEditInjector.EDITOR_CHROME
                : config.cameraFilter());
        boolean nativeStyle = learned > 0 || cameraFilter == ReEditInjector.CAMERA_CHROME;
        boolean useLive = config.cameraJavaFilter() && style != null
                && (style.styleAdjust() != null || style.candyParam() != null);
        int progress = useLive ? style.degree() : 0;
        String styleAdjust = useLive ? style.styleAdjust() : null;
        try {
            byte[] outer = file;
            byte[] extraExif = null;
            boolean outerRendered = false;
            if (useLive && style.candyParam() != null) {
                OuterRenderer.Result rendered = OuterRenderer.render(module, currentApplication(),
                        probe.cameraLoader(), file, style, path);
                if (rendered != null) {
                    outer = rendered.heic();
                    extraExif = ReEditInjector.stripExifThumbnail(
                            ReEditInjector.extractExif(file), rendered.thumbJpeg());
                    pushUiThumb(rendered.uiThumb());
                    outerRendered = true;
                }
            }
            byte[] injected = ReEditInjector.inject(outer, file, extraExif, filter, progress,
                    nativeStyle, styleAdjust);
            if (injected != null && logged.compareAndSet(false, true)) {
                module.log(Log.INFO, TAG, "added re-edit data on the " + path + " (camera filter "
                        + cameraFilter + ", editor filter " + filter + ", native=" + nativeStyle
                        + ", outerRendered=" + outerRendered
                        + ", liveStyle=" + (styleAdjust != null ? styleAdjust : "neutral")
                        + ", +" + (injected.length - file.length)
                        + " bytes); logging the first photo only");
            }
            if (injected == null && seen.get() <= 8) {
                module.log(Log.INFO, TAG, "re-edit data already in the photo (" + path + ")");
            }
            return injected;
        } catch (IOException e) {
            module.log(Log.WARN, TAG, "photo left as it was (" + path + "): " + e.getMessage());
            return null;
        }
    }

    // ---- the watcher: any finished HEIC in DCIM/Camera ------------------------------------------

    private synchronized void installWatcher() {
        if (watcher != null) {
            return;
        }
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), CAMERA_DIR);
            if (!dir.isDirectory()) {
                module.log(Log.WARN, TAG, "no " + dir + ", the DCIM/Camera watcher is off");
                return;
            }
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "HyperPaletteMod-reedit");
                t.setDaemon(true);
                return t;
            });
            @SuppressWarnings("deprecation")
            FileObserver observer = new FileObserver(dir.getPath(),
                    FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE) {
                @Override
                public void onEvent(int event, String name) {
                    if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".heic")) {
                        File file = new File(dir, name);
                        executor.schedule(() -> addToFile(file), 2500, TimeUnit.MILLISECONDS);
                    }
                }
            };
            observer.startWatching();
            pool = executor;
            watcher = observer;
            module.log(Log.INFO, TAG, "watching " + dir + " for finished HEIC photos");
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "cannot watch DCIM/Camera: " + t);
        }
    }

    // A finished file, shortly after its last event: wait until its size is steady, add the
    // data, write it back (the original bytes go back if that fails) and let the media scanner
    // refresh the file's size and thumbnail.
    private void addToFile(File file) {
        try {
            int mode = config.cameraMode();
            if (mode == Settings.MODE_OFF || !file.isFile()) {
                return;
            }
            long length = file.length();
            Thread.sleep(150);
            if (file.length() != length || length < 1024) {
                return;   // still being written; its next event comes back here
            }
            byte[] bytes = Files.readAllBytes(file.toPath());
            byte[] injected = addToBytes(bytes, mode, "file watcher (" + file.getName() + ")");
            if (injected == null) {
                return;
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(injected);
                out.getFD().sync();
            } catch (IOException | RuntimeException e) {
                module.log(Log.ERROR, TAG, "writing " + file + " failed, putting the original back: "
                        + e);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(bytes);
                    out.getFD().sync();
                }
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            file.setLastModified(System.currentTimeMillis());
            Application app = currentApplication();
            if (app != null) {
                MediaScannerConnection.scanFile(app, new String[]{file.getPath()}, null, null);
            }
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "re-edit data not added to " + file + ": " + t);
        }
    }

    private static Application currentApplication() {
        try {
            return (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
