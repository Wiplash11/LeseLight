package de.leselicht.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    private static final int REQ_CAMERA = 2;

    private WebView web;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private String pendingShare;
    private boolean pageLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "LeselichtNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoaded = true;
                deliverShare();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                if (params.isCaptureEnabled()) {
                    openCamera();
                } else {
                    openFilePicker(params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                }
                return true;
            }
        });

        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { ttsEvent(id, "start", ""); }
                    @Override public void onDone(String id) { ttsEvent(id, "end", ""); }
                    @Override public void onError(String id) { ttsEvent(id, "error", "synthesis-failed"); }
                    @Override public void onError(String id, int code) { ttsEvent(id, "error", "synthesis-failed-" + code); }
                    @Override public void onStop(String id, boolean interrupted) { }
                });
            }
            js("window.__leselichtVoices && window.__leselichtVoices()");
        });

        handleIntent(getIntent());
        web.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && !text.trim().isEmpty()) {
                pendingShare = text;
                deliverShare();
            }
        }
    }

    private void deliverShare() {
        if (!pageLoaded || pendingShare == null) return;
        String t = JSONObject.quote(pendingShare);
        pendingShare = null;
        js("setTimeout(function(){ window.__leselichtImport && window.__leselichtImport('Geteilter Text', " + t + "); }, 400)");
    }

    private void openFilePicker(boolean multiple) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/pdf", "text/plain", "text/markdown", "text/html",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "image/*"});
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(Intent.createChooser(i, "Datei wählen"), REQ_FILE);
        } catch (ActivityNotFoundException e) {
            finishChooser(null);
        }
    }

    private void openCamera() {
        try {
            File dir = new File(getCacheDir(), "photos");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "Foto_" + System.currentTimeMillis() + ".jpg");
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, "Keine Kamera-App gefunden", Toast.LENGTH_LONG).show();
            finishChooser(null);
        }
    }

    private void finishChooser(Uri[] result) {
        if (fileCallback != null) fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAMERA) {
            finishChooser(resultCode == RESULT_OK && cameraUri != null ? new Uri[]{cameraUri} : null);
        } else if (requestCode == REQ_FILE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null && clip.getItemCount() > 0) {
                    result = new Uri[clip.getItemCount()];
                    for (int k = 0; k < clip.getItemCount(); k++) result[k] = clip.getItemAt(k).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            finishChooser(result);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private void js(String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private void ttsEvent(String id, String ev, String err) {
        js("window.__leselichtTts && window.__leselichtTts(" + JSONObject.quote(id) + "," + JSONObject.quote(ev) + "," + JSONObject.quote(err) + ")");
    }

    private static String voiceLabel(Voice v) {
        String lang = v.getLocale().getDisplayLanguage(Locale.GERMAN);
        String country = v.getLocale().getDisplayCountry(Locale.GERMAN);
        StringBuilder b = new StringBuilder(lang);
        if (!country.isEmpty()) b.append(" (").append(country).append(")");
        b.append(" · ").append(v.getName());
        if (v.getQuality() >= Voice.QUALITY_VERY_HIGH) b.append(" · Premium");
        else if (v.getQuality() >= Voice.QUALITY_HIGH) b.append(" · Enhanced");
        if (v.isNetworkConnectionRequired()) b.append(" · Online");
        return b.toString();
    }

    private class Bridge {

        @JavascriptInterface
        public String getVoices() {
            JSONArray arr = new JSONArray();
            if (!ttsReady) return arr.toString();
            try {
                Set<Voice> vs = tts.getVoices();
                if (vs == null) return arr.toString();
                for (Voice v : vs) {
                    Set<String> feats = v.getFeatures();
                    if (feats != null && feats.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                    JSONObject o = new JSONObject();
                    o.put("name", v.getName());
                    o.put("label", voiceLabel(v));
                    o.put("lang", v.getLocale().toLanguageTag());
                    o.put("network", v.isNetworkConnectionRequired());
                    o.put("quality", v.getQuality());
                    arr.put(o);
                }
            } catch (Exception ignored) { }
            return arr.toString();
        }

        @JavascriptInterface
        public boolean ready() { return ttsReady; }

        @JavascriptInterface
        public void speak(String id, String text, String voiceName, String lang, float rate, float pitch) {
            if (!ttsReady) { ttsEvent(id, "error", "not-ready"); return; }
            try {
                boolean set = false;
                if (voiceName != null && !voiceName.isEmpty()) {
                    Set<Voice> vs = tts.getVoices();
                    if (vs != null) for (Voice v : vs) {
                        if (v.getName().equals(voiceName)) { tts.setVoice(v); set = true; break; }
                    }
                }
                if (!set && lang != null && !lang.isEmpty()) tts.setLanguage(Locale.forLanguageTag(lang));
                tts.setSpeechRate(rate);
                tts.setPitch(pitch);
                Bundle params = new Bundle();
                int r = tts.speak(text, TextToSpeech.QUEUE_ADD, params, id);
                if (r != TextToSpeech.SUCCESS) ttsEvent(id, "error", "synthesis-failed");
            } catch (Exception e) {
                ttsEvent(id, "error", "synthesis-failed");
            }
        }

        @JavascriptInterface
        public void stop() {
            if (ttsReady) tts.stop();
        }

        @JavascriptInterface
        public void openTtsSettings() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent("com.android.settings.TTS_SETTINGS");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    try {
                        Intent i = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                        startActivity(i);
                    } catch (Exception ignored) {
                        Toast.makeText(MainActivity.this, "Öffne Einstellungen › Sprache › Sprachausgabe", Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public String saveText(String filename, String content) {
            try {
                String name = filename.replaceAll("[\\\\/:*?\"<>|]+", " ").trim();
                if (name.isEmpty()) name = "Leselicht.txt";
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, name.endsWith(".md") ? "text/markdown" : "text/plain");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Leselicht");
                ContentResolver cr = getContentResolver();
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return "";
                try (OutputStream os = cr.openOutputStream(uri)) {
                    os.write(content.getBytes(StandardCharsets.UTF_8));
                }
                final String shown = name;
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Gespeichert in Downloads/Leselicht/" + shown, Toast.LENGTH_LONG).show());
                return "Download/Leselicht/" + name;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void post(String id, String url, String headersJson, String body) {
            new Thread(() -> {
                int status = 0;
                String text;
                HttpURLConnection c = null;
                try {
                    if (!url.startsWith("https://api.anthropic.com/")) throw new IllegalArgumentException("blocked");
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(300000);
                    JSONObject h = new JSONObject(headersJson);
                    Iterator<String> keys = h.keys();
                    while (keys.hasNext()) { String k = keys.next(); c.setRequestProperty(k, h.getString(k)); }
                    try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
                    status = c.getResponseCode();
                    InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    if (is != null) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                        is.close();
                    }
                    text = bos.toString("UTF-8");
                } catch (Exception e) {
                    text = String.valueOf(e.getMessage());
                } finally {
                    if (c != null) c.disconnect();
                }
                js("window.__leselichtHttp && window.__leselichtHttp(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(text) + ")");
            }).start();
        }
    }
}
