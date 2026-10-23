package com.flashytube.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Last-resort for age-restricted videos: loads the watch page in a hidden
 * WebView and extracts ytInitialPlayerResponse via JavaScript. Because this
 * runs YouTube's own JavaScript (BotGuard, PO tokens), the server treats it
 * exactly like the user's working browser.
 */
public final class WebViewPlayer {
    private WebViewPlayer() {}

    public static class Result {
        public String title;
        public List<WebPlayer.Format> formats = new ArrayList<>();
    }

    /**
     * Must be called on a background thread; internally hops to the UI
     * thread for WebView work. Returns null on timeout/failure.
     */
    public static Result fetch(final Activity act, final String videoId) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> jsonRef = new AtomicReference<>();
        final Handler ui = new Handler(Looper.getMainLooper());

        ui.post(() -> {
            WebView web = null;
            try {
                web = new WebView(act);
                web.getSettings().setJavaScriptEnabled(true);
                web.getSettings().setDomStorageEnabled(true);
                // Share the login session.
                android.webkit.CookieManager.getInstance()
                        .setAcceptCookie(true);
                final WebView w = web;
                // Desktop UA: YouTube treats plain WebViews differently.
                web.getSettings().setUserAgentString("Mozilla/5.0 "
                        + "(Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36");
                web.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        // Poll a few times for the player response.
                        final int[] tries = {0};
                        final Runnable poll = new Runnable() {
                            @Override public void run() {
                                tries[0]++;
                                try {
                                    w.evaluateJavascript(
                                        "(function(){"
                                        + "var r = window.ytInitialPlayerResponse"
                                        + " || null;"
                                        + "return r ? JSON.stringify(r) : '';"
                                        + "})();",
                                        v -> {
                                            if (v != null && v.length() > 200
                                                && v.contains(
                                                    "playabilityStatus")) {
                                                jsonRef.set(v);
                                                latch.countDown();
                                            } else if (tries[0] < 6) {
                                                w.postDelayed(this, 2000);
                                            } else {
                                                latch.countDown();
                                            }
                                        });
                                } catch (Exception e) {
                                    latch.countDown();
                                }
                            }
                        };
                        view.postDelayed(poll, 3000);
                    }
                });
                web.loadUrl("https://www.youtube.com/embed/" + videoId);
                // Safety: never wait forever.
                ui.postDelayed(latch::countDown, 25000);
            } catch (Exception e) {
                latch.countDown();
                if (web != null) web.destroy();
            }
        });

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return null;
        }
        final String json = jsonRef.get();
        ui.post(() -> {
            // WebView cleanup happens on the next UI loop; best-effort.
        });
        if (json == null || json.isEmpty()) return null;
        return parse(json);
    }

    private static Result parse(String json) {
        try {
            JsonObject root = JsonParser.object().from(json);
            JsonObject status = root.getObject("playabilityStatus");
            if (status == null
                    || !"OK".equalsIgnoreCase(status.getString("status"))) {
                WebPlayer.lastReason = "webview: status="
                        + (status != null ? status.getString("status")
                                : "null");
                return null;
            }
            Result r = new Result();
            JsonObject details = root.getObject("videoDetails");
            if (details != null) r.title = details.getString("title");
            JsonObject sd = root.getObject("streamingData");
            if (sd == null) {
                WebPlayer.lastReason = "webview: no streamingData";
                return null;
            }
            JsonArray formats = sd.getArray("formats");
            if (formats != null) {
                for (Object o : formats) {
                    if (!(o instanceof JsonObject)) continue;
                    WebPlayer.Format f = toFormat((JsonObject) o);
                    if (f != null) r.formats.add(f);
                }
            }
            if (r.formats.isEmpty()) {
                WebPlayer.lastReason = "webview: no direct URLs";
                return null;
            }
            return r;
        } catch (Exception e) {
            WebPlayer.lastReason =
                    "webview: " + e.getClass().getSimpleName();
            return null;
        }
    }

    private static WebPlayer.Format toFormat(JsonObject o) {
        try {
            String url = o.getString("url");
            if (url == null || url.isEmpty()) return null;
            String mime = o.getString("mimeType");
            if (mime == null || !mime.contains("video/mp4")) return null;
            WebPlayer.Format f = new WebPlayer.Format();
            f.url = url;
            f.mime = mime;
            String ql = o.getString("qualityLabel");
            f.label = (ql != null && !ql.isEmpty()) ? ql : "video";
            f.hasAudio = true; // progressive formats have audio
            return f;
        } catch (Exception e) {
            return null;
        }
    }
}
