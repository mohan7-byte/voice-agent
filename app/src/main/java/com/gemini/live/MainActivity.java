package com.gemini.live;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity implements GeminiLiveManager.LiveCallback {
    private GeminiLiveManager liveManager;
    private TextView statusText, subText;
    private MaterialButton btnConnect;
    private LinearLayout activeControlsGroup;
    private View orbGlow, orbCore;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        setContentView(R.layout.activity_main);

        // Request Runtime Permissions
        String[] perms = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        };
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(perms, 101);
                break;
            }
        }

        prefs = getSharedPreferences("voice_prefs", MODE_PRIVATE);
        liveManager = new GeminiLiveManager(this, this);

        statusText = findViewById(R.id.statusText);
        subText = findViewById(R.id.subText);
        btnConnect = findViewById(R.id.btnConnect);
        activeControlsGroup = findViewById(R.id.activeControlsGroup);
        orbGlow = findViewById(R.id.orbGlow);
        orbCore = findViewById(R.id.orbCore);

        btnConnect.setOnClickListener(v -> startVoiceSession());
        findViewById(R.id.btnEnd).setOnClickListener(v -> liveManager.stopSession());
        findViewById(R.id.btnSettings).setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
        });

        findViewById(R.id.rootTouchContainer).setOnClickListener(v -> moveTaskToBack(true));
    }

    private void startVoiceSession() {
        String apiKey = prefs.getString("api_key", "");
        if (apiKey.isEmpty()) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        String model = prefs.getString("model", "models/gemini-3.1-flash-live-preview");
        String voice = prefs.getString("voice", "Puck");
        String prompt = prefs.getString("prompt", "");

        liveManager.startSession(apiKey, model, voice, prompt);
    }

    @Override
    public void onStateChange(String state, String status, String sub) {
        statusText.setText(status);
        subText.setText(sub);

        if (state.equals("idle")) {
            btnConnect.setVisibility(View.VISIBLE);
            activeControlsGroup.setVisibility(View.GONE);
            orbGlow.setBackgroundColor(Color.parseColor("#4338CA"));
        } else {
            btnConnect.setVisibility(View.GONE);
            activeControlsGroup.setVisibility(View.VISIBLE);

            if (state.equals("listening")) {
                orbGlow.setBackgroundColor(Color.parseColor("#A855F7"));
            } else if (state.equals("working")) {
                orbGlow.setBackgroundColor(Color.parseColor("#06B6D4"));
            } else if (state.equals("speaking")) {
                orbGlow.setBackgroundColor(Color.parseColor("#FBBF24"));
            }
        }
    }

    @Override
    public void onTaskStep(int step, String action) {
        subText.setText("Step " + step + ": " + action);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (liveManager != null) liveManager.stopSession();
        super.onDestroy();
    }
}
