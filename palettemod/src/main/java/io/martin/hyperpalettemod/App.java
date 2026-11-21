package io.martin.hyperpalettemod;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

// Connects this module's app to the Xposed framework (LSPosed, Vector, ...) so the settings screen can
// write the settings the hooked apps read. The connection exists only while the module is enabled.
public class App extends Application {
    private static volatile XposedService service;
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService bound) {
                service = bound;
                changed();
            }

            @Override
            public void onServiceDied(XposedService died) {
                service = null;
                changed();
            }
        });
    }

    static XposedService service() {
        return service;
    }

    // The settings, or null while the module is not connected to the framework.
    static SharedPreferences prefs() {
        XposedService bound = service;
        if (bound == null) {
            return null;
        }
        try {
            return bound.getRemotePreferences(Settings.GROUP);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    private static void changed() {
        MAIN.post(() -> {
            for (Runnable listener : LISTENERS) {
                listener.run();
            }
        });
    }
}
