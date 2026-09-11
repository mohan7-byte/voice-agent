package com.gemini.live;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

public class GeminiLiveManager {
    public interface LiveCallback {
        void onStateChange(String state, String status, String sub);
        void onTaskStep(int step, String action);
    }

    private Context context;
    private LiveCallback callback;
    private WebSocket webSocket;
    private OkHttpClient client;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean isRecording = false;
    private boolean isSpeaking = false;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    public GeminiLiveManager(Context ctx, LiveCallback cb) {
        this.context = ctx;
        this.callback = cb;
        this.client = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();
    }

    public void startSession(String apiKey, String model, String voice, String prompt) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            updateState("idle", "API Key Missing", "Configure in Settings");
            return;
        }

        updateState("idle", "Connecting...", "Reaching Google...");
        initAudioTrack();

        String endpoint = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + apiKey.trim();
        Request request = new Request.Builder().url(endpoint).build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                sendSetupPacket(ws, model, voice, prompt);
                startMicStream(ws);
                updateState("listening", "Listening", "Go ahead, speak");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleMessage(ws, text);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                updateState("idle", "Closed (" + code + ")", reason != null ? reason : "Ready");
                stopSession();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                String errorMsg = t != null ? t.getMessage() : "Network error";
                updateState("idle", "Connection Failed", errorMsg);
                stopSession();
            }
        });
    }

    private void sendSetupPacket(WebSocket ws, String model, String voice, String prompt) {
        try {
            JSONObject setup = new JSONObject();
            String cleanModel = (model == null || model.trim().isEmpty()) ? "models/gemini-3.1-flash-live-preview" : model.trim();
            if (!cleanModel.startsWith("models/")) cleanModel = "models/" + cleanModel;
            setup.put("model", cleanModel);

            JSONObject generationConfig = new JSONObject();
            JSONArray modalities = new JSONArray();
            modalities.put("AUDIO");
            generationConfig.put("responseModalities", modalities);

            JSONObject voiceConfig = new JSONObject();
            JSONObject prebuiltVoice = new JSONObject();
            prebuiltVoice.put("voiceName", (voice == null || voice.trim().isEmpty()) ? "Puck" : voice.trim());
            voiceConfig.put("prebuiltVoiceConfig", prebuiltVoice);
            JSONObject speechConfig = new JSONObject();
            speechConfig.put("voiceConfig", voiceConfig);
            generationConfig.put("speechConfig", speechConfig);
            setup.put("generationConfig", generationConfig);

            if (prompt != null && !prompt.trim().isEmpty()) {
                JSONObject sysInst = new JSONObject();
                JSONArray parts = new JSONArray();
                JSONObject part = new JSONObject();
                part.put("text", prompt.trim());
                parts.put(part);
                sysInst.put("parts", parts);
                setup.put("systemInstruction", sysInst);
            }

            JSONObject root = new JSONObject();
            root.put("setup", setup);
            ws.send(root.toString());
        } catch (Exception e) {
            updateState("idle", "Setup Error", e.getMessage());
        }
    }

    private void startMicStream(WebSocket ws) {
        int sampleRate = 16000;
        int minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int recordBufSize = Math.max(minBuf, 4096);

        try {
            // Using AudioSource.MIC for 100% reliable hardware capture on Samsung
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recordBufSize);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                updateState("idle", "Mic Blocked", "AudioRecord init failed");
                return;
            }

            audioRecord.startRecording();
            isRecording = true;

            new Thread(() -> {
                // 2048 bytes = 1024 samples = 64ms sweet spot
                byte[] buffer = new byte[2048];
                while (isRecording) {
                    if (isSpeaking) {
                        SystemClock.sleep(25);
                        continue;
                    }
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0 && ws != null) {
                        String b64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP);
                        try {
                            JSONObject frame = new JSONObject();
                            JSONObject realtime = new JSONObject();
                            JSONObject media = new JSONObject();
                            media.put("mimeType", "audio/pcm;rate=16000");
                            media.put("data", b64);
                            realtime.put("audio", media);
                            frame.put("realtimeInput", realtime);
                            ws.send(frame.toString());
                        } catch (Exception ignored) {}
                    }
                }
            }).start();
        } catch (Exception e) {
            updateState("idle", "Mic Error", e.getMessage());
        }
    }

    private void initAudioTrack() {
        int sampleRate = 24000;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = (minBuf > 0) ? minBuf * 2 : 8192;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            } else {
                audioTrack = new AudioTrack(android.media.AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);
            }
            audioTrack.play();
        } catch (Exception e) {
            updateState("idle", "Speaker Error", e.getMessage());
        }
    }

    private void handleMessage(WebSocket ws, String jsonText) {
        try {
            JSONObject msg = new JSONObject(jsonText);

            // Handshake Confirmation
            if (msg.has("setupComplete")) {
                updateState("listening", "Listening", "Ready to talk");
            }

            // Error handling
            if (msg.has("error")) {
                JSONObject err = msg.getJSONObject("error");
                String errMsg = err.optString("message", "API Error");
                updateState("idle", "Gemini Error", errMsg);
                stopSession();
                return;
            }

            // Tool Calls
            if (msg.has("toolCall")) {
                JSONObject toolCall = msg.getJSONObject("toolCall");
                JSONArray calls = toolCall.getJSONArray("functionCalls");
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.getJSONObject(i);
                    executeTool(ws, call.getString("name"), call.optJSONObject("args"), call.optString("id", "1"));
                }
            }

            // Streaming Audio Output
            if (msg.has("serverContent")) {
                JSONObject sc = msg.getJSONObject("serverContent");
                if (sc.optBoolean("interrupted", false)) {
                    if (audioTrack != null) audioTrack.flush();
                    isSpeaking = false;
                    updateState("listening", "Listening", "Go ahead");
                    return;
                }
                if (sc.has("modelTurn")) {
                    JSONArray parts = sc.getJSONObject("modelTurn").getJSONArray("parts");
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.getJSONObject(i);
                        if (part.has("inlineData")) {
                            isSpeaking = true;
                            updateState("speaking", "Speaking", "Jarvis...");
                            byte[] pcm = Base64.decode(part.getJSONObject("inlineData").getString("data"), Base64.DEFAULT);
                            if (audioTrack != null) audioTrack.write(pcm, 0, pcm.length);
                        }
                    }
                }
                if (sc.optBoolean("turnComplete", false)) {
                    mainHandler.postDelayed(() -> {
                        isSpeaking = false;
                        updateState("listening", "Listening", "Go ahead");
                    }, 350);
                }
            }
        } catch (Exception ignored) {}
    }

    private void executeTool(WebSocket ws, String name, JSONObject args, String callId) {
        updateState("working", "Working...", name);
        String result = "Completed";

        try {
            if (VolumeTriggerService.instance != null) {
                if (name.equals("tap_coordinates")) {
                    int x = args.getInt("x");
                    int y = args.getInt("y");
                    boolean ok = VolumeTriggerService.instance.tapCoordinates(x, y);
                    result = ok ? "Tapped coordinates" : "Failed to tap";
                } else if (name.equals("long_press")) {
                    int x = args.getInt("x");
                    int y = args.getInt("y");
                    boolean ok = VolumeTriggerService.instance.longPress(x, y);
                    result = ok ? "Long-pressed" : "Failed";
                } else if (name.equals("type_text")) {
                    boolean ok = VolumeTriggerService.instance.typeText(args.getString("text"));
                    result = ok ? "Typed text" : "Could not find focused input";
                } else if (name.equals("scroll")) {
                    boolean ok = VolumeTriggerService.instance.scroll(args.optString("direction", "down"));
                    result = ok ? "Scrolled" : "Failed";
                } else if (name.equals("read_screen_text")) {
                    result = VolumeTriggerService.instance.readScreenText();
                }
            }
        } catch (Exception e) {
            result = "Error: " + e.getMessage();
        }

        try {
            JSONObject toolResp = new JSONObject();
            JSONObject tr = new JSONObject();
            JSONArray fr = new JSONArray();
            JSONObject resp = new JSONObject();
            resp.put("id", callId);
            JSONObject out = new JSONObject();
            out.put("result", result);
            resp.put("response", new JSONObject().put("output", out));
            fr.put(resp);
            tr.put("functionResponses", fr);
            toolResp.put("toolResponse", tr);
            ws.send(toolResp.toString());
        } catch (Exception ignored) {}
    }

    private void updateState(String state, String status, String sub) {
        mainHandler.post(() -> {
            if (callback != null) callback.onStateChange(state, status, sub);
        });
    }

    public void stopSession() {
        isRecording = false;
        isSpeaking = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        if (audioTrack != null) {
            try { audioTrack.stop(); audioTrack.release(); } catch (Exception ignored) {}
            audioTrack = null;
        }
        if (webSocket != null) {
            webSocket.close(1000, "User ended");
            webSocket = null;
        }
        updateState("idle", "Voice", "Ready");
    }
}
