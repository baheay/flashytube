package com.flashytube.app;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

/** Minimal HTTP downloader for NewPipe Extractor (HttpURLConnection-based). */
public class SimpleDownloader extends Downloader {
    private static final String UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        HttpURLConnection con = (HttpURLConnection) new URL(request.url()).openConnection();
        con.setRequestMethod(request.httpMethod());
        con.setConnectTimeout(25000);
        con.setReadTimeout(25000);
        con.setInstanceFollowRedirects(true);

        boolean hasUA = false;
        Map<String, List<String>> headers = request.headers();
        if (headers != null) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if (e.getKey().equalsIgnoreCase("User-Agent")) hasUA = true;
                for (String v : e.getValue()) con.addRequestProperty(e.getKey(), v);
            }
        }
        if (!hasUA) con.setRequestProperty("User-Agent", UA);

        // Optional Google session: attach the YouTube cookies captured by
        // LoginActivity so age-restricted videos resolve for signed-in users.
        try {
            String url = request.url();
            String host = new URL(url).getHost();
            if (GoogleLogin.appliesTo(host)) {
                String ck = GoogleLogin.cookiesForUrl(url);
                if (ck != null && !ck.isEmpty()) {
                    String existing = con.getRequestProperty("Cookie");
                    con.setRequestProperty("Cookie",
                            existing == null || existing.isEmpty()
                                    ? ck : existing + "; " + ck);
                }
                // InnerTube player API: bare cookies are often ignored
                // without the SAPISIDHASH Authorization header.
                // NOTE: do NOT set X-YouTube-Client-* here — the extractor
                // uses the ANDROID client and mismatched headers break it.
                if (url.contains("youtubei/v1/")) {
                    String hash = GoogleLogin.sapisidHash();
                    if (hash != null && !hash.isEmpty()) {
                        con.setRequestProperty("Authorization",
                                "SAPISIDHASH " + hash);
                        con.setRequestProperty("X-Goog-AuthUser", "0");
                        con.setRequestProperty("X-Origin",
                                "https://www.youtube.com");
                    }
                }
            }
        } catch (Exception ignored) {}

        byte[] data = request.dataToSend();
        if (data != null && data.length > 0) {
            con.setDoOutput(true);
            try (OutputStream os = con.getOutputStream()) { os.write(data); }
        }

        int code = con.getResponseCode();
        InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
        String body = "";
        try {
            if (in != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                body = bos.toString("UTF-8");
            }
        } finally {
            // Always release the stream and the connection, even on read
            // errors — otherwise sockets pile up (HTTP leak).
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            con.disconnect();
        }
        return new Response(code, con.getResponseMessage(), con.getHeaderFields(), body,
            con.getURL().toString());
    }
}
