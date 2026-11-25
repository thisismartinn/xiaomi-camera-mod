package io.martin.hyperpalettemod;

import android.app.Application;
import android.content.ContentResolver;
import android.media.Image;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.FileObserver;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
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

    private final XposedModule module;
    private final Config config;
    private final ParamProbe probe;
    private volatile boolean hooked;
    private final AtomicInteger seen = new AtomicInteger();
    private final AtomicBoolean logged = new AtomicBoolean();
    private volatile FileObserver watcher;   // kept: an unreferenced FileObserver is collected
    private volatile ScheduledExecutorService pool;

    CameraFeatures(XposedModule module, Config config) {
        this.module = module;
        this.config = config;
        this.probe = new ParamProbe(module);
    }

    void install(String stage, ClassLoader loader) {
        if (hooked) {
            return;
        }
        installWatcher();
        probe.install(loader);
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
        int cameraFilter = ReEditInjector.cameraFilterId(file);
        if (mode == Settings.MODE_FILTERED
                && (cameraFilter < 0 || cameraFilter == ReEditInjector.FILTER_STANDARD)) {
            if (seen.get() <= 8) {
                module.log(Log.INFO, TAG, "re-edit data skipped on the " + path + ": no filter in "
                        + "this photo (camera filter id " + cameraFilter + ")");
            }
            return null;
        }
        int learned = probe.editorFilterId(cameraFilter);
        int filter = learned > 0 ? learned
                : (cameraFilter == ReEditInjector.CAMERA_CHROME ? ReEditInjector.EDITOR_CHROME
                : config.cameraFilter());
        boolean nativeStyle = learned > 0 || cameraFilter == ReEditInjector.CAMERA_CHROME;
        try {
            byte[] injected = ReEditInjector.inject(file, filter, 0, nativeStyle);
            if (injected != null && logged.compareAndSet(false, true)) {
                module.log(Log.INFO, TAG, "added re-edit data on the " + path + " (camera filter "
                        + cameraFilter + ", editor filter " + filter + ", native=" + nativeStyle
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
                        executor.schedule(() -> addToFile(file), 2, TimeUnit.SECONDS);
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

    // A finished file, two seconds after its last event: wait until its size is steady, add the
    // data, write it back (the original bytes go back if that fails) and let the media scanner
    // refresh the file's size.
    private void addToFile(File file) {
        try {
            int mode = config.cameraMode();
            if (mode == Settings.MODE_OFF || !file.isFile()) {
                return;
            }
            long length = file.length();
            Thread.sleep(500);
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
