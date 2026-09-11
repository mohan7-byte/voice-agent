package com.gemini.live;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
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
        String endpoint = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + apiKey;
        Request request = new Request.Builder().url(endpoint).build();

        initAudioTrack();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                sendSetupPacket(ws, model, voice, prompt);
                startMicStream(ws);
                updateState("listening", "Listening", "Go ahead");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleMessage(ws, text);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                stopSession();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                stopSession();
            }
        });
    }

    private void sendSetupPacket(WebSocket ws, String model, String voice, String prompt) {
        try {
            JSONObject setup = new JSONObject();
            setup.put("model", model.isEmpty() ? "models/gemini-3.1-flash-live-preview" : model);

            JSONObject generationConfig = new JSONObject();
            JSONArray modalities = new JSONArray();
            modalities.put("AUDIO");
            generationConfig.put("responseModalities", modalities);

            JSONObject voiceConfig = new JSONObject();
            JSONObject prebuiltVoice = new JSONObject();
            prebuiltVoice.put("voiceName", voice.isEmpty() ? "Puck" : voice);
            voiceConfig.put("prebuiltVoiceConfig", prebuiltVoice);
            JSONObject speechConfig = new JSONObject();
            speechConfig.put("voiceConfig", voiceConfig);
            generationConfig.put("speechConfig", speechConfig);
            setup.put("generationConfig", generationConfig);

            if (!prompt.isEmpty()) {
                JSONObject sysInst = new JSONObject();
                JSONArray parts = new JSONArray();
                JSONObject part = new JSONObject();
                part.put("text", prompt);
                parts.put(part);
                sysInst.put("parts", parts);
                setup.put("systemInstruction", sysInst);
            }

            JSONObject root = new JSONObject();
            root.put("setup", setup);
            ws.send(root.toString());
        } catch (Exception ignored) {}
    }

    private void startMicStream(WebSocket ws) {
        int sampleRate = 16000;
        int bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2);
            audioRecord.startRecording();
            isRecording = true;

            new Thread(() -> {
                byte[] buffer = new byte[1024];
                while (isRecording) {
                    if (isSpeaking) {
                        SystemClock.sleep(20);
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
        } catch (Exception ignored) {}
    }

    private void initAudioTrack() {
        int sampleRate = 24000;
        int bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack(android.media.AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2, AudioTrack.MODE_STREAM);
        audioTrack.play();
    }

    private void handleMessage(WebSocket ws, String jsonText) {
        try {
            JSONObject msg = new JSONObject(jsonText);

            if (msg.has("toolCall")) {
                JSONObject toolCall = msg.getJSONObject("toolCall");
                JSONArray calls = toolCall.getJSONArray("functionCalls");
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.getJSONObject(i);
                    executeTool(ws, call.getString("name"), call.optJSONObject("args"), call.optString("id", "1"));
                }
            }

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
