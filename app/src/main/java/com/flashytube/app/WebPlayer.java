package com.flashytube.app;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Fallback for age-restricted videos: queries YouTube's InnerTube player
 * with the WEB client (exactly like a desktop browser), which honors the
 * logged-in session for age-gated content where the extractor's
 * ANDROID/iOS clients do not.
 */
public final class WebPlayer {
    private WebPlayer() {}

    /** InnerTube WEB client key (public, same one youtube.com uses). */
    private static final String WEB_KEY =
            "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?key=" + WEB_KEY
                    + "&prettyPrint=false";

    public static class Format {
        public String url;
        public String label;   // e.g. "720p"
        public String mime;    // e.g. "video/mp4"
        public boolean hasAudio;
    }

    public static class Result {
        public String title;
        public List<Format> formats = new ArrayList<>();
    }

    /** Cached visitorData (anonymous, safe to reuse for the session). */
    private static String cachedVisitorData = null;

    /** Last failure reason, for the age dialog diagnostic. */
    public static String lastReason = "";

    /**
     * visitorData for the X-Goog-Visitor-Id header, scraped from the
     * YouTube homepage. Required by the WEB InnerTube client.
     */
    private static synchronized String visitorData() {
        if (cachedVisitorData != null) return cachedVisitorData;
        try {
            HttpURLConnection con = (HttpURLConnection)
                    new URL("https://www.youtube.com/").openConnection();
            con.setConnectTimeout(20000);
            con.setReadTimeout(20000);
            con.setRequestProperty("User-Agent", "Mozilla/5.0 "
                    + "(Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            con.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            InputStream in = con.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            // visitorData appears early in the page; 300KB is plenty.
            while ((n = in.read(buf)) != -1 && total < 300 * 1024) {
                bos.write(buf, 0, n);
                total += n;
            }
            in.close();
            String html = bos.toString("UTF-8");
            String key = "\"visitorData\":\"";
            int i = html.indexOf(key);
            if (i >= 0) {
                int j = html.indexOf("\"", i + key.length());
                if (j > i) {
                    cachedVisitorData =
                            html.substring(i + key.length(), j);
                }
            }
        } catch (Exception e) {
            lastReason = "visitorData: " + e.getClass().getSimpleName();
        }
        if (cachedVisitorData == null) {
            lastReason = "visitorData: not found";
        }
        return cachedVisitorData;
    }

    /**
     * Returns progressive (audio+video) formats with direct URLs,
     * or null when the video is still blocked / request failed.
     */
    public static Result fetch(String videoId) {
        Result r = fetchWithClient(videoId, "WEB", "2.20241201.00.00", "1",
                false);
        if (r != null) return r;
        // TVHTML5 is lenient for age-gated content.
        return fetchWithClient(videoId, "TVHTML5", "7.20241201.00.00", "7",
                true);
    }

    private static Result fetchWithClient(String videoId, String clientName,
                                          String clientVersion,
                                          String clientId, boolean tvEmbed) {
        try {
            String vd0 = visitorData();
            if (vd0 == null) {
                lastReason = clientName + ": no visitorData";
            }
            String body = "{\"videoId\":\"" + videoId + "\","
                    + "\"contentCheckOk\":true,\"racyCheckOk\":true,"
                    + "\"context\":{\"client\":{\"clientName\":\""
                    + clientName + "\",\"clientVersion\":\""
                    + clientVersion + "\","
                    + "\"hl\":\"en\",\"gl\":\"US\","
                    + "\"visitorData\":\"" + (vd0 != null ? vd0 : "")
                    + "\"}},"
                    + "\"playbackContext\":{\"contentPlaybackContext\":{"
                    + "\"html5Preference\":\"HTML5_PREF_WANTS\","
                    + "\"signatureTimestamp\":19369}}}\"";

            HttpURLConnection con =
                    (HttpURLConnection) new URL(PLAYER_URL).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(25000);
            con.setReadTimeout(25000);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Accept", "*/*");
            con.setRequestProperty("User-Agent", "Mozilla/5.0 "
                    + "(Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            con.setRequestProperty("Origin", "https://www.youtube.com");
            con.setRequestProperty("Referer", tvEmbed
                    ? "https://www.youtube.com/embed/" + videoId
                    : "https://www.youtube.com/watch?v=" + videoId);
            con.setRequestProperty("X-YouTube-Client-Name", clientId);
            con.setRequestProperty("X-YouTube-Client-Version", clientVersion);
            String vd = visitorData();
            if (vd != null && !vd.isEmpty()) {
                con.setRequestProperty("X-Goog-Visitor-Id", vd);
            }

            // Auth: cookies + SAPISIDHASH (same as SimpleDownloader).
            String ck = GoogleLogin.youtubeCookies();
            if (ck != null && !ck.isEmpty()) {
                con.setRequestProperty("Cookie", ck);
            }
            String hash = GoogleLogin.sapisidHash();
            if (hash != null && !hash.isEmpty()) {
                con.setRequestProperty("Authorization",
                        "SAPISIDHASH " + hash);
                con.setRequestProperty("X-Goog-AuthUser", "0");
                con.setRequestProperty("X-Origin",
                        "https://www.youtube.com");
            }

            byte[] data = body.getBytes("UTF-8");
            try (OutputStream os = con.getOutputStream()) {
                os.write(data);
            }

            int code = con.getResponseCode();
            InputStream in = code >= 400
                    ? con.getErrorStream() : con.getInputStream();
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            String json = bos.toString("UTF-8");

            JsonObject root = JsonParser.object().from(json);
            JsonObject status = root.getObject("playabilityStatus");
            String st = status != null ? status.getString("status") : "null";
            if (!"OK".equalsIgnoreCase(st)) {
                lastReason = clientName + ": status=" + st;
                return null;
            }
            Result r = new Result();
            JsonObject details = root.getObject("videoDetails");
            if (details != null) r.title = details.getString("title");

            JsonObject sd = root.getObject("streamingData");
            if (sd == null) return null;
            // Progressive first (has audio+video in one URL).
            JsonArray formats = sd.getArray("formats");
            if (formats != null) {
                for (Object o : formats) {
                    if (!(o instanceof JsonObject)) continue;
                    Format f = toFormat((JsonObject) o, true);
                    if (f != null) r.formats.add(f);
                }
            }
            // Then adaptive video+audio (muxable) with direct URLs.
            JsonArray adaptive = sd.getArray("adaptiveFormats");
            if (adaptive != null) {
                for (Object o : adaptive) {
                    if (!(o instanceof JsonObject)) continue;
                    Format f = toFormat((JsonObject) o, false);
                    if (f != null) r.formats.add(f);
                }
            }
            if (r.formats.isEmpty()) {
                lastReason = clientName + ": no direct URLs";
                return null;
            }
            return r;
        } catch (Exception e) {
            lastReason = clientName + ": " + e.getClass().getSimpleName();
            return null;
        }
    }

    /** Only formats with a direct (non-ciphered) URL. */
    private static Format toFormat(JsonObject o, boolean progressive) {
        try {
            String url = o.getString("url");
            // Skip ciphered URLs (would need signature deciphering).
            if (url == null || url.isEmpty()) return null;
            String mime = o.getString("mimeType");
            if (mime == null) mime = "";
            // Keep it simple: mp4 video only.
            if (!mime.contains("video/mp4")) return null;
            Format f = new Format();
            f.url = url;
            f.mime = mime;
            String ql = o.getString("qualityLabel");
            f.label = (ql != null && !ql.isEmpty()) ? ql : "video";
            f.hasAudio = progressive || o.getInt("audioChannels", 0) > 0;
            // For adaptive, we need audio track info; keep video-only ones
            // out unless progressive (they'd need muxing we can't do here).
            if (!progressive && !f.hasAudio
                    && mime.contains("video/")) {
                // video-only DASH: usable only with a mux audio track;
                // DlHelper can mux, so keep it but mark clearly.
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }
}
