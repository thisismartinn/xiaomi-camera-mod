package io.martin.hyperpalettemod;

// The setting names and their defaults, shared by the settings screen (which writes them) and the
// hooks inside the Camera and the Media Editor (which read them).
final class Settings {
    static final String GROUP = "settings";

    static final String CAMERA_PACKAGE = "com.android.camera";
    static final String EDITOR_PACKAGE = "com.miui.mediaeditor";

    // Media Editor
    static final String EDITOR_PAD = "editor_pad";               // open the Palette pad on this phone
    static final boolean EDITOR_PAD_DEFAULT = true;
    static final String EDITOR_WIDTH = "editor_width";           // keep the pad's numbers on one line
    static final boolean EDITOR_WIDTH_DEFAULT = true;

    // Camera
    static final String CAMERA_MODE = "camera_mode";             // re-edit data in saved HEIC photos
    static final int MODE_OFF = 0;
    static final int MODE_FILTERED = 1;
    static final int MODE_ALL = 2;
    static final int CAMERA_MODE_DEFAULT = MODE_FILTERED;
    static final String CAMERA_FILTER = "camera_filter_id";      // editor filter the data names
    static final int CAMERA_FILTER_DEFAULT = 58;

    private Settings() {
    }
}
