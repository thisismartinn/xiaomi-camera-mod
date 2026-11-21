package io.martin.hyperpalettemod;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.service.XposedService;

// The module's settings. They are written to the framework's remote preferences, which the hooks in
// the Camera and the Media Editor read, so a change reaches them without a restart of this app.
public class SettingsActivity extends Activity {
    private final List<View> controls = new ArrayList<>();
    private final Runnable onServiceChanged = this::refresh;

    private TextView status;
    private Button scopeButton;
    private Switch padSwitch;
    private Switch widthSwitch;
    private RadioGroup modeGroup;
    private final int[] modeIds = new int[3];
    private EditText filterField;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(32));
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        root.addView(label("HyperPaletteMod", 24, true));
        root.addView(note("Palette for the Media Editor and the Camera. Draft."));

        status = note("");
        status.setPadding(0, dp(12), 0, dp(4));
        root.addView(status);
        scopeButton = new Button(this);
        scopeButton.setText("Add Camera and Media Editor to the scope");
        scopeButton.setAllCaps(false);
        scopeButton.setOnClickListener(v -> requestScope());
        scopeButton.setVisibility(View.GONE);
        root.addView(scopeButton);

        // ---- Media Editor ----
        root.addView(section("Media Editor"));
        padSwitch = switchRow(root, "Open the Palette pad on this phone",
                "The editor opens its Saturation and Tone pad only on the 18 series. This lets it "
                        + "open here too. Applies the next time the Media Editor starts.",
                Settings.EDITOR_PAD);
        widthSwitch = switchRow(root, "Keep the pad's numbers on one line",
                "Stops values such as 20 or -44 from wrapping onto two lines in this phone's font.",
                Settings.EDITOR_WIDTH);

        // ---- Camera ----
        root.addView(section("Camera"));
        root.addView(label("Add re-edit data to saved HEIC photos", 16, false));
        modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.VERTICAL);
        String[] modeLabels = {"Off", "Photos taken with a filter", "Every photo"};
        for (int i = 0; i < modeLabels.length; i++) {
            RadioButton button = new RadioButton(this);
            button.setText(modeLabels[i]);
            button.setId(View.generateViewId());
            modeIds[i] = button.getId();
            modeGroup.addView(button);
            controls.add(button);
        }
        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (loading) {
                return;
            }
            for (int i = 0; i < modeIds.length; i++) {
                if (modeIds[i] == checkedId) {
                    putInt(Settings.CAMERA_MODE, i);
                }
            }
        });
        root.addView(modeGroup);
        root.addView(note("Gives the editor what it needs to open the Palette pad on the photo. This "
                + "phone's camera does not keep the unfiltered picture, so the photo itself is the "
                + "source and the pad starts at 0. Photos roughly double in size. Applies from the "
                + "next photo."));

        root.addView(label("Editor filter id", 16, false));
        filterField = new EditText(this);
        filterField.setInputType(InputType.TYPE_CLASS_NUMBER);
        filterField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        filterField.setSingleLine(true);
        filterField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveFilter();
                filterField.clearFocus();
            }
            return false;
        });
        filterField.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                saveFilter();
            }
        });
        controls.add(filterField);
        root.addView(filterField, new LinearLayout.LayoutParams(dp(120),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(note("The editor filter the added data names (" + Settings.CAMERA_FILTER_DEFAULT
                + " by default), shown selected at 0 % so it does not change the picture."));

        // ---- planned ----
        root.addView(section("Planned"));
        root.addView(note("Save the camera's own filter and Palette values so the editor can re-edit "
                + "them. That needs the unfiltered picture, which is still being investigated; it "
                + "is not available yet."));
        root.addView(note("Open the original photo from the phone (copy it off by cable). Chat apps "
                + "and an iPhone remove the added data."));
    }

    @Override
    protected void onStart() {
        super.onStart();
        App.addListener(onServiceChanged);
        refresh();
    }

    @Override
    protected void onStop() {
        super.onStop();
        App.removeListener(onServiceChanged);
        saveFilter();
    }

    // ---- state ---------------------------------------------------------------------------------

    private void refresh() {
        XposedService service = App.service();
        boolean active = service != null;
        for (View control : controls) {
            control.setEnabled(active);
        }
        if (!active) {
            status.setText("Not active. Enable HyperPaletteMod in LSPosed or Vector, with Camera and "
                    + "Media Editor in its scope, then open this screen again.");
            scopeButton.setVisibility(View.GONE);
            return;
        }
        String text = "Active: " + service.getFrameworkName() + " " + service.getFrameworkVersion()
                + " (API " + service.getApiVersion() + ").";
        List<String> missing = new ArrayList<>();
        try {
            List<String> scope = service.getScope();
            for (String pkg : new String[]{Settings.CAMERA_PACKAGE, Settings.EDITOR_PACKAGE}) {
                if (!scope.contains(pkg)) {
                    missing.add(pkg);
                }
            }
        } catch (RuntimeException e) {
            // the scope cannot be read; leave the button hidden
        }
        if (!missing.isEmpty()) {
            text += " Not in the scope yet: " + String.join(", ", missing) + ".";
        }
        status.setText(text);
        scopeButton.setVisibility(missing.isEmpty() ? View.GONE : View.VISIBLE);

        SharedPreferences prefs = App.prefs();
        if (prefs == null) {
            return;
        }
        loading = true;
        padSwitch.setChecked(prefs.getBoolean(Settings.EDITOR_PAD, Settings.EDITOR_PAD_DEFAULT));
        widthSwitch.setChecked(prefs.getBoolean(Settings.EDITOR_WIDTH, Settings.EDITOR_WIDTH_DEFAULT));
        int mode = prefs.getInt(Settings.CAMERA_MODE, Settings.CAMERA_MODE_DEFAULT);
        modeGroup.check(modeIds[mode < 0 || mode >= modeIds.length ? Settings.MODE_OFF : mode]);
        if (!filterField.hasFocus()) {
            filterField.setText(String.valueOf(
                    prefs.getInt(Settings.CAMERA_FILTER, Settings.CAMERA_FILTER_DEFAULT)));
        }
        loading = false;
    }

    private void requestScope() {
        XposedService service = App.service();
        if (service == null) {
            return;
        }
        try {
            service.requestScope(List.of(Settings.CAMERA_PACKAGE, Settings.EDITOR_PACKAGE),
                    new XposedService.OnScopeEventListener() {
                        @Override
                        public void onScopeRequestFailed(String message) {
                            runOnUiThread(() -> Toast.makeText(SettingsActivity.this,
                                    "Scope request failed: " + message, Toast.LENGTH_LONG).show());
                        }

                        @Override
                        public void onScopeRequestApproved(List<String> approved) {
                            runOnUiThread(SettingsActivity.this::refresh);
                        }
                    });
        } catch (RuntimeException e) {
            Toast.makeText(this, "Scope request failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---- writing -------------------------------------------------------------------------------

    private void putBoolean(String key, boolean value) {
        SharedPreferences prefs = App.prefs();
        if (prefs == null) {
            Toast.makeText(this, "Not active: the setting was not saved", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putBoolean(key, value).apply();
    }

    private void putInt(String key, int value) {
        SharedPreferences prefs = App.prefs();
        if (prefs == null) {
            Toast.makeText(this, "Not active: the setting was not saved", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putInt(key, value).apply();
    }

    private void saveFilter() {
        if (loading || filterField == null || App.prefs() == null) {
            return;
        }
        int id;
        try {
            id = Integer.parseInt(filterField.getText().toString().trim());
        } catch (NumberFormatException e) {
            id = -1;
        }
        if (id < 1 || id > 0xFFFF) {
            id = Settings.CAMERA_FILTER_DEFAULT;
            filterField.setText(String.valueOf(id));
            Toast.makeText(this, "Filter id must be 1 to 65535; reset to " + id, Toast.LENGTH_SHORT)
                    .show();
        }
        putInt(Settings.CAMERA_FILTER, id);
    }

    // ---- small view helpers ----------------------------------------------------------------------

    private Switch switchRow(LinearLayout root, String title, String description, String key) {
        Switch row = new Switch(this);
        row.setText(title);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.setPadding(0, dp(8), 0, dp(2));
        row.setOnCheckedChangeListener((button, checked) -> {
            if (!loading) {
                putBoolean(key, checked);
            }
        });
        controls.add(row);
        root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(note(description));
        return row;
    }

    private TextView section(String title) {
        TextView view = label(title, 14, true);
        view.setAllCaps(true);
        view.setPadding(0, dp(24), 0, dp(4));
        view.setAlpha(0.7f);
        return view;
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        view.setGravity(Gravity.START);
        view.setPadding(0, dp(8), 0, dp(2));
        return view;
    }

    private TextView note(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setAlpha(0.75f);
        view.setPadding(0, dp(2), 0, dp(8));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
