package com.gemini.live;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.net.Uri;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.io.File;
import java.io.FileWriter;

public class SettingsActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "voice_prefs";
    private EditText apiKeyEditText, instructionsEditText, playbooksEditor;
    private MaterialSwitch autoDisconnectSwitch, autoConnectSwitch, speakerEchoGuardSwitch;
    private Spinner modelSpinner, voiceSpinner;
    private SharedPreferences preferences;

    private final String[] modelOptions = {
        "models/gemini-3.1-flash-live-preview",
        "models/gemini-2.5-flash-native-audio-preview",
        "models/gemini-2.0-flash-exp"
    };

    private final String[] voiceOptions = { "Puck", "Aoede", "Charon", "Fenrir", "Kore" };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        apiKeyEditText = findViewById(R.id.apiKeyEditText);
        instructionsEditText = findViewById(R.id.instructionsEditText);
        playbooksEditor = findViewById(R.id.playbooksEditor);
        autoDisconnectSwitch = findViewById(R.id.autoDisconnectSwitch);
        autoConnectSwitch = findViewById(R.id.autoConnectSwitch);
        speakerEchoGuardSwitch = findViewById(R.id.speakerEchoGuardSwitch);
        modelSpinner = findViewById(R.id.modelSpinner);
        voiceSpinner = findViewById(R.id.voiceSpinner);

        setupApiKeyField();
        setupSpinners();
        setupVolumeCard();
        setupButtons();
        loadPrefs();
    }

    private void setupApiKeyField() {
        apiKeyEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyEditText.setTransformationMethod(PasswordTransformationMethod.getInstance());
    }

    private void setupSpinners() {
        ArrayAdapter<String> mAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modelOptions);
        ArrayAdapter<String> vAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, voiceOptions);
        modelSpinner.setAdapter(mAdapter);
        voiceSpinner.setAdapter(vAdapter);
    }

    private void setupVolumeCard() {
        findViewById(R.id.volumeSetupCard).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
    }

    private void setupButtons() {
        findViewById(R.id.saveButton).setOnClickListener(v -> savePrefs());
        findViewById(R.id.saveRulesButton).setOnClickListener(v -> saveRules());
        findViewById(R.id.openFilesButton).setOnClickListener(v -> openInMyFiles());
    }

    private void loadPrefs() {
        apiKeyEditText.setText(preferences.getString("api_key", ""));
        instructionsEditText.setText(preferences.getString("prompt", "You are Voice (Jarvis), an ultra-fast autonomous Android agent with physical device control and experiential memory."));
        playbooksEditor.setText(preferences.getString("playbooks", "{\n  \"com.samsung.android.app.notes\": {\n    \"actions\": { \"add\": {\"x\": 875, \"y\": 935} }\n  }\n}"));
        autoDisconnectSwitch.setChecked(preferences.getBoolean("auto_disconnect", true));
        autoConnectSwitch.setChecked(preferences.getBoolean("auto_connect", false));
        speakerEchoGuardSwitch.setChecked(preferences.getBoolean("echo_guard", true));
    }

    private void savePrefs() {
        preferences.edit()
            .putString("api_key", apiKeyEditText.getText().toString().trim())
            .putString("prompt", instructionsEditText.getText().toString())
            .putString("playbooks", playbooksEditor.getText().toString())
            .putBoolean("auto_disconnect", autoDisconnectSwitch.isChecked())
            .putBoolean("auto_connect", autoConnectSwitch.isChecked())
            .putBoolean("echo_guard", speakerEchoGuardSwitch.isChecked())
            .putString("model", modelSpinner.getSelectedItem().toString())
            .putString("voice", voiceSpinner.getSelectedItem().toString())
            .apply();

        saveRulesToFile(playbooksEditor.getText().toString());
        Toast.makeText(this, "Saved Configuration", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void saveRules() {
        String rules = playbooksEditor.getText().toString();
        preferences.edit().putString("playbooks", rules).apply();
        saveRulesToFile(rules);
        Toast.makeText(this, "Rules Saved & Synced", Toast.LENGTH_SHORT).show();
    }

    private void saveRulesToFile(String json) {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Voice");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "rules.json");
            FileWriter fw = new FileWriter(file, false);
            fw.write(json);
            fw.flush();
            fw.close();
        } catch (Exception ignored) {}
    }

    private void openInMyFiles() {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Voice");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "rules.json");
            if (!file.exists()) saveRulesToFile(playbooksEditor.getText().toString());

            StrictMode.VmPolicy.Builder builder = new StrictMode.VmPolicy.Builder();
            StrictMode.setVmPolicy(builder.build());

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(file), "application/json");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(intent, "Open with..."));
        } catch (Exception e) {
            Toast.makeText(this, "Error opening file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
