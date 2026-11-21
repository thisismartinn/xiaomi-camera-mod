package io.martin.hyperpalettemod;

import io.github.libxposed.api.XposedModule;

// One module for the two apps Palette lives in:
//  - the Media Editor (com.miui.mediaeditor): see EditorFeatures;
//  - the Camera (com.android.camera): see CameraFeatures.
// The settings screen of this module's own app (SettingsActivity) controls both.
public class ModuleMain extends XposedModule {
    private final Config config = new Config(this);
    private final EditorFeatures editor = new EditorFeatures(this, config);
    private final CameraFeatures camera = new CameraFeatures(this, config);

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        handle("onPackageLoaded", param.getPackageName(), param.getDefaultClassLoader());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        handle("onPackageReady", param.getPackageName(), param.getClassLoader());
    }

    private void handle(String stage, String pkg, ClassLoader loader) {
        if (loader == null) {
            return;
        }
        if (Settings.EDITOR_PACKAGE.equals(pkg)) {
            editor.install(stage, loader);
        } else if (Settings.CAMERA_PACKAGE.equals(pkg)) {
            camera.install(stage, loader);
        }
    }
}
