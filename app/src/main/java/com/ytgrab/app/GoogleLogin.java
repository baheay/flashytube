package com.ytgrab.app;

import android.content.Context;
import android.webkit.CookieManager;

/**
 * Optional Google sign-in for age-restricted videos.
 * Single source of truth is the WebView CookieManager: LoginActivity captures
 * the YouTube session cookies there, SimpleDownloader attaches them to
 * YouTube requests, and logout wipes them. Nothing is sent to our servers
 * (there are none) — cookies never leave the device.
 */
public class GoogleLogin {

    /** Page the login WebView opens; the user taps "Sign in" there. */
    public static final String LOGIN_URL = "https://m.youtube.com/signin";
    /** SnapTube-style: single host for cookie ops. */
    private static final String YT_HOST = "https://youtube.com";

    /** Presence of this cookie means a YouTube session is active. */
    private static final String SESSION_COOKIE = "LOGIN_INFO";

    private static CookieManager cm() {
        return CookieManager.getInstance();
    }

    /** Must run once on the UI thread before any background use. */
    public static void initOnUiThread() {
        try { cm(); } catch (Exception ignored) {}
    }

    /** Persist cookies to disk now (call on pause — fixes logout-on-close). */
    public static void flush() {
        try { cm().flush(); } catch (Exception ignored) {}
    }

    /** Raw "name=value; ..." cookie string for youtube.com, or null. */
    /**
     * Get cookies for a specific URL (preferred over merged youtubeCookies).
     * CookieManager already handles domain matching correctly — merging
     * cookies from different domains causes conflicts (e.g., different
     * SAPISID values for www vs m).
     */
    /**
     * SnapTube-style: get cookies from the single YT host and strip
     * the mobile PREF param (app=m) so YouTube treats us as desktop.
     */
    public static String ytCookies() {
        try {
            String c = cm().getCookie(YT_HOST);
            if (c == null || c.isEmpty()) return null;
            // Strip app=m from PREF (SnapTube removeYoutubePrefParam).
            StringBuilder out = new StringBuilder();
            for (String kv : c.split(";")) {
                kv = kv.trim();
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                String name = kv.substring(0, eq).trim();
                String val = kv.substring(eq + 1).trim();
                if (name.equals("PREF")) {
                    val = val.replace("app=m&", "").replace("&app=m", "")
                             .replace("app=m", "");
                }
                if (out.length() > 0) out.append("; ");
                out.append(name).append("=").append(val);
            }
            return out.length() == 0 ? null : out.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static String cookiesForUrl(String url) {
        try {
            if (url == null || url.isEmpty()) return null;
            String c = cm().getCookie(url);
            return (c == null || c.isEmpty()) ? null : c;
        } catch (Exception e) {
            return null;
        }
    }

    public static String youtubeCookies() {
        try {
            StringBuilder out = new StringBuilder();
            // YouTube sets session cookies on several domains; combine them.
            String[] urls = {
                "https://www.youtube.com",
                "https://m.youtube.com",
                "https://accounts.google.com",
            };
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String u : urls) {
                String c = cm().getCookie(u);
                if (c == null || c.isEmpty()) continue;
                for (String kv : c.split(";")) {
                    kv = kv.trim();
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    String name = kv.substring(0, eq).trim();
                    if (seen.add(name)) {
                        if (out.length() > 0) out.append("; ");
                        out.append(kv);
                    }
                }
            }
            return out.length() == 0 ? null : out.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * SAPISIDHASH for InnerTube: "timestamp_hexsha1". YouTube's player API
     * often ignores bare cookies without this Authorization header.
     */
    public static String sapisidHash() {
        try {
            // Prefer www.youtube.com cookies (API target); fallback to merged.
            String cookies = cookiesForUrl("https://www.youtube.com");
            if (cookies == null) cookies = youtubeCookies();
            if (cookies == null) return null;
            String sapisid = null;
            for (String kv : cookies.split(";")) {
                kv = kv.trim();
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                String n = kv.substring(0, eq).trim();
                if (n.equals("SAPISID") || n.equals("__Secure-3PAPISID")) {
                    sapisid = kv.substring(eq + 1).trim();
                    break;
                }
            }
            if (sapisid == null || sapisid.isEmpty()) return null;
            long ts = System.currentTimeMillis() / 1000L;
            String origin = "https://www.youtube.com";
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(
                    (ts + " " + sapisid + " " + origin).getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return ts + "_" + hex.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isLoggedIn(Context c) {
        String ck = youtubeCookies();
        return ck != null && ck.contains(SESSION_COOKIE);
    }

    /** Wipe the YouTube session cookies (runs async; callback on UI thread). */
    public static void logout(Context c, Runnable done) {
        try {
            cm().removeAllCookies(v -> {
                try { cm().flush(); } catch (Exception ignored) {}
                if (done != null) done.run();
            });
        } catch (Exception e) {
            if (done != null) done.run();
        }
    }

    /**
     * One-line diagnostic for age-gate failures (no secret values).
     * Example: "auth: cookies=12 SAPISID=yes login=true"
     */
    public static String authDiagnostic() {
        try {
            String c = youtubeCookies();
            int n = 0;
            boolean hasSapisid = false;
            if (c != null) {
                for (String kv : c.split(";")) {
                    kv = kv.trim();
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    n++;
                    String nm = kv.substring(0, eq).trim();
                    if (nm.equals("SAPISID")
                            || nm.equals("__Secure-3PAPISID")) {
                        hasSapisid = true;
                    }
                }
            }
            boolean logged = c != null && c.contains(SESSION_COOKIE);
            return "auth: cookies=" + n
                    + " SAPISID=" + (hasSapisid ? "yes" : "no")
                    + " login=" + logged;
        } catch (Exception e) {
            return "auth: error";
        }
    }

    /**
     * Check if the saved session is actually valid by fetching YouTube
     * and looking for login indicators. Cookie presence alone is not
     * enough — sessions expire.
     * Must be called on a background thread.
     */
    public static boolean isSessionValid() {
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL u = new java.net.URL("https://www.youtube.com/");
            c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; Redmi Note 9 Pro) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36");
            String cookies = cookiesForUrl("https://www.youtube.com/");
            if (cookies != null) {
                c.setRequestProperty("Cookie", cookies);
            }
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) return false;
            java.io.InputStream in = c.getInputStream();
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n, total = 0;
            // Read first 300KB — enough for the login indicators.
            while ((n = in.read(buf)) != -1 && total < 300 * 1024) {
                sb.append(new String(buf, 0, n, "UTF-8"));
                total += n;
                String partial = sb.toString();
                if (partial.contains("\"loggedIn\":true")) return true;
            }
            String html = sb.toString();
            // Logged-out pages show a sign-in button.
            if (html.contains("\"loggedIn\":false")) return false;
            return html.contains("\"loggedIn\":true");
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    /** True for hosts whose requests should carry the YouTube session. */
    public static boolean appliesTo(String host) {
        if (host == null) return false;
        host = host.toLowerCase();
        return host.endsWith("youtube.com")
                || host.endsWith("googlevideo.com")
                || host.endsWith("youtubei.googleapis.com")
                || host.endsWith("ytimg.com");
    }
}
