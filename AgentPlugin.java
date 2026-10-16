package com.roohu.phoneagent;

import android.Manifest;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.speech.tts.Voice;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.speech.tts.Voice;
import com.getcapacitor.JSArray;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONObject;

@CapacitorPlugin(name = "Agent", permissions = {@Permission(strings = {Manifest.permission.RECORD_AUDIO}, alias = "mic"), @Permission(strings = {Manifest.permission.READ_CONTACTS}, alias = "contacts")})
public class AgentPlugin extends Plugin {
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer sr;

    private AgentAccessibilityService svc() { return AgentAccessibilityService.instance; }

    @PluginMethod
    public void isEnabled(PluginCall call) {
        JSObject r = new JSObject();
        r.put("enabled", svc() != null);
        call.resolve(r);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    @PluginMethod
    public void run(PluginCall call) {
        AgentAccessibilityService s = svc();
        if (s == null) { call.reject("Service is off"); return; }
        s.setHeal(call.getString("goal", ""), call.getString("base", ""), call.getString("key", ""), call.getString("model", ""));
        if (!s.runPlan(call.getString("plan", "[]"))) { call.reject("Agent is busy or the plan is bad"); return; }
        call.resolve();
    }

    @PluginMethod
    public void runAgent(PluginCall call) {
        AgentAccessibilityService s = svc();
        if (s == null) { call.reject("Service is off"); return; }
        if (!s.runAgent(call.getString("goal", ""), call.getString("base", ""), call.getString("key", ""), call.getString("model", ""))) {
            call.reject("Busy"); return;
        }
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        AgentAccessibilityService s = svc();
        JSObject r = new JSObject();
        r.put("status", s == null ? "{}" : s.getStatusJson());
        call.resolve(r);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        AgentAccessibilityService s = svc();
        if (s != null) s.stopNow();
        call.resolve();
    }

    @PluginMethod
    public void botStart(PluginCall call) {
        AgentAccessibilityService s = svc();
        if (s == null) { call.reject("Service is off"); return; }
        s.startBot(call.getString("token", ""), call.getString("code", ""), call.getString("base", ""), call.getString("key", ""), call.getString("model", ""));
        call.resolve();
    }

    @PluginMethod
    public void botStop(PluginCall call) {
        AgentAccessibilityService s = svc();
        if (s != null) s.stopBot();
        call.resolve();
    }

    private MediaPlayer mp;
    private boolean ttsInit = false;
    private Runnable pending;

    private SharedPreferences vp() { return getContext().getSharedPreferences("roohu", 0); }

    private void withTts(final Runnable r) {
        if (tts != null && ttsReady) { r.run(); return; }
        pending = r;
        if (!ttsInit) {
            ttsInit = true;
            getActivity().runOnUiThread(() -> tts = new TextToSpeech(getContext(), status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    applyVoice();
                    Runnable x = pending;
                    pending = null;
                    if (x != null) x.run();
                }
            }));
        }
    }

    private void applyVoice() {
        try {
            SharedPreferences p = vp();
            String name = p.getString("vname", "");
            if (!name.isEmpty()) for (Voice v : tts.getVoices()) if (v.getName().equals(name)) { tts.setVoice(v); break; }
            tts.setPitch(p.getFloat("vpitch", 1.0f));
            tts.setSpeechRate(p.getFloat("vrate", 1.0f));
        } catch (Exception e) { }
    }

    private void local(final String t) {
        withTts(() -> tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "r"));
    }

    private String cloudSpeak(String url, String hName, String hVal, String body) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty(hName, hVal);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "audio/mpeg");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            OutputStream o = c.getOutputStream();
            o.write(body.getBytes("UTF-8"));
            o.close();
            int code = c.getResponseCode();
            if (code >= 400) {
                String m = "";
                try {
                    InputStream es = c.getErrorStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] eb = new byte[2048];
                    int en;
                    while (es != null && (en = es.read(eb)) > 0) bo.write(eb, 0, en);
                    m = bo.toString("UTF-8");
                } catch (Exception e) { }
                return "HTTP " + code + (m.isEmpty() ? "" : " " + m.substring(0, Math.min(220, m.length())));
            }
            File f = new File(getContext().getCacheDir(), "v.mp3");
            FileOutputStream fo = new FileOutputStream(f);
            InputStream is = c.getInputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = is.read(b)) > 0) fo.write(b, 0, n);
            fo.close();
            if (mp != null) { try { mp.release(); } catch (Exception e) { } }
            mp = new MediaPlayer();
            mp.setDataSource(f.getPath());
            mp.prepare();
            mp.start();
            return null;
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    @PluginMethod
    public void speak(final PluginCall call) {
        final String t = call.getString("text", "");
        new Thread(() -> {
            SharedPreferences p = vp();
            String prov = p.getString("vprov", "phone");
            String err = null;
            boolean cloud = false;
            try {
                if (prov.equals("fish") && !p.getString("fkey", "").isEmpty()) {
                    cloud = true;
                    JSONObject b = new JSONObject().put("text", t).put("format", "mp3");
                    if (!p.getString("fvoice", "").isEmpty()) b.put("reference_id", p.getString("fvoice", ""));
                    err = cloudSpeak("https://api.fish.audio/v1/tts", "Authorization", "Bearer " + p.getString("fkey", ""), b.toString());
                } else if (prov.equals("orfish") && !p.getString("okey", "").isEmpty()) {
                    cloud = true;
                    err = cloudSpeak("https://openrouter.ai/api/v1/audio/speech", "Authorization", "Bearer " + p.getString("okey", ""),
                            new JSONObject().put("model", "fish-audio/s2.1-pro-free").put("input", t).put("response_format", "mp3").toString());
                } else if (prov.equals("eleven") && !p.getString("ekey", "").isEmpty() && !p.getString("evoice", "").isEmpty()) {
                    cloud = true;
                    err = cloudSpeak("https://api.elevenlabs.io/v1/text-to-speech/" + p.getString("evoice", ""), "xi-api-key", p.getString("ekey", ""), new JSONObject().put("text", t).toString());
                }
            } catch (Exception e) {
                err = String.valueOf(e.getMessage());
            }
            if (!cloud || err != null) local(t);
            JSObject r = new JSObject();
            r.put("ok", err == null);
            r.put("err", err == null ? "" : err);
            call.resolve(r);
        }).start();
    }

    @PluginMethod
    public void askContacts(PluginCall call) {
        if (getPermissionState("contacts") == PermissionState.GRANTED) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
        } else {
            requestPermissionForAlias("contacts", call, "contactsDone");
        }
    }

    @PermissionCallback
    private void contactsDone(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", getPermissionState("contacts") == PermissionState.GRANTED);
        call.resolve(r);
    }

    @PluginMethod
    public void shutUp(PluginCall call) {
        if (tts != null) tts.stop();
        try { if (mp != null) mp.stop(); } catch (Exception e) { }
        call.resolve();
    }

    @PluginMethod
    public void voices(final PluginCall call) {
        withTts(() -> {
            JSArray arr = new JSArray();
            try {
                String lang = Locale.getDefault().getLanguage();
                for (Voice v : tts.getVoices()) {
                    if (!v.getLocale().getLanguage().equals(lang)) continue;
                    JSObject o = new JSObject();
                    o.put("name", v.getName());
                    o.put("label", v.getLocale().getDisplayCountry() + " " + v.getName().replaceAll(".*-x-", "") + (v.isNetworkConnectionRequired() ? " (online)" : ""));
                    o.put("q", v.getQuality());
                    arr.put(o);
                }
            } catch (Exception e) { }
            JSObject r = new JSObject();
            r.put("voices", arr);
            call.resolve(r);
        });
    }

    @PluginMethod
    public void setVoice(PluginCall call) {
        vp().edit().putString("vname", call.getString("name", "")).putFloat("vpitch", call.getFloat("pitch", 1.0f))
                .putFloat("vrate", call.getFloat("rate", 1.0f)).putString("vprov", call.getString("prov", "phone"))
                .putString("ekey", call.getString("ekey", "")).putString("evoice", call.getString("evoice", ""))
                .putString("okey", call.getString("okey", "")).putString("fkey", call.getString("fkey", "")).putString("fvoice", call.getString("fvoice", "")).apply();
        if (tts != null && ttsReady) applyVoice();
        call.resolve();
    }

    @PluginMethod
    public void setAuto(PluginCall call) {
        vp().edit().putInt("auto", call.getInt("level", 1)).apply();
        call.resolve();
    }

    @PluginMethod
    public void sound(PluginCall call) {
        String k = call.getString("k", "send");
        int[] f = k.equals("start") ? new int[]{523, 659, 784} : k.equals("done") ? new int[]{784, 1047} : k.equals("err") ? new int[]{330, 247} : new int[]{880};
        play(f);
        call.resolve();
    }

    private void play(final int[] f) {
        new Thread(() -> {
            try {
                int rate = 22050;
                int n = rate * 120 / 1000;
                short[] buf = new short[n * f.length];
                for (int k = 0; k < f.length; k++) {
                    for (int i = 0; i < n; i++) {
                        double env = Math.min(1.0, Math.min(i / 400.0, (n - i) / 600.0));
                        buf[k * n + i] = (short) (Math.sin(2 * Math.PI * f[k] * i / rate) * env * 7000);
                    }
                }
                AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, buf.length * 2, AudioTrack.MODE_STATIC);
                t.write(buf, 0, buf.length);
                t.play();
                Thread.sleep(120L * f.length + 250);
                t.release();
            } catch (Exception e) { }
        }).start();
    }

    @PluginMethod
    public void listen(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            requestPermissionForAlias("mic", call, "micDone");
        } else {
            startListen(call);
        }
    }

    @PermissionCallback
    private void micDone(PluginCall call) {
        if (getPermissionState("mic") == PermissionState.GRANTED) startListen(call);
        else call.reject("Microphone permission was denied");
    }

    private void startListen(final PluginCall call) {
        getActivity().runOnUiThread(() -> {
            if (!SpeechRecognizer.isRecognitionAvailable(getContext())) {
                call.reject("Speech recognition is not available on this phone");
                return;
            }
            if (tts != null) tts.stop();
            if (sr != null) sr.destroy();
            sr = SpeechRecognizer.createSpeechRecognizer(getContext());
            sr.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) { }
                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float v) { }
                public void onBufferReceived(byte[] b) { }
                public void onEndOfSpeech() { }
                public void onPartialResults(Bundle b) { }
                public void onEvent(int t, Bundle b) { }
                public void onError(int e) { call.reject("I could not hear you (code " + e + ")"); }
                public void onResults(Bundle b) {
                    ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    JSObject r = new JSObject();
                    r.put("text", l != null && !l.isEmpty() ? l.get(0) : "");
                    call.resolve(r);
                }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            sr.startListening(i);
        });
    }
}
