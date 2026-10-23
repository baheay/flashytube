package com.ytgrab.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** App preferences: save folders, language. */
public class Prefs {

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("ytgrab", Context.MODE_PRIVATE);
    }

    public static String videoDir(Context c) {
        return sp(c).getString("video_dir", "FlashyTube/Video");
    }

    public static String audioDir(Context c) {
        return sp(c).getString("audio_dir", "FlashyTube/Audio");
    }

    public static void setDirs(Context c, String video, String audio) {
        sp(c).edit().putString("video_dir", video).putString("audio_dir", audio).apply();
    }

    public static String lang(Context c) {
        return sp(c).getString("lang", "ar");
    }

    public static void toggleLang(Context c) {
        sp(c).edit().putString("lang", "ar".equals(lang(c)) ? "en" : "ar").apply();
    }

    public static int maxParallel(Context c) {
        return Math.max(1, Math.min(5, sp(c).getInt("max_parallel", 3)));
    }

    public static void setMaxParallel(Context c, int n) {
        sp(c).edit().putInt("max_parallel", Math.max(1, Math.min(5, n))).apply();
    }

    public static String videoTree(Context c) {
        return sp(c).getString("video_tree", "");
    }

    public static void setVideoTree(Context c, String uri) {
        sp(c).edit().putString("video_tree", uri == null ? "" : uri).apply();
    }

    public static String audioTree(Context c) {
        return sp(c).getString("audio_tree", "");
    }

    public static void setAudioTree(Context c, String uri) {
        sp(c).edit().putString("audio_tree", uri == null ? "" : uri).apply();
    }

    public static float playSpeed(Context c) {
        return sp(c).getFloat("play_speed", 1f);
    }

    public static void setPlaySpeed(Context c, float s) {
        sp(c).edit().putFloat("play_speed", s).apply();
    }

    // ---- Theme (manual Material light/dark) ----
    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    public static int themeMode(Context c) {
        return sp(c).getInt("theme_mode", THEME_SYSTEM);
    }

    public static void setThemeMode(Context c, int mode) {
        sp(c).edit().putInt("theme_mode", mode).apply();
    }

    // ---- Background playback (hand audio to the service on leave) ----
    public static boolean backgroundPlayback(Context c) {
        return sp(c).getBoolean("background_playback", true);
    }

    public static boolean rootPromptDismissed(Context c) {
        return sp(c).getBoolean("root_prompt_dismissed", false);
    }

    public static void setRootPromptDismissed(Context c, boolean v) {
        sp(c).edit().putBoolean("root_prompt_dismissed", v).apply();
    }

    public static void setBackgroundPlayback(Context c, boolean on) {
        sp(c).edit().putBoolean("background_playback", on).apply();
    }

    // ---- Resume position (per videoId, ms) ----
    public static void setResume(Context c, String videoId, int ms) {
        if (videoId == null) return;
        sp(c).edit().putInt("resume_" + videoId, Math.max(ms, 0)).apply();
    }

    public static int getResume(Context c, String videoId) {
        if (videoId == null) return 0;
        return sp(c).getInt("resume_" + videoId, 0);
    }

    public static void clearResume(Context c, String videoId) {
        if (videoId == null) return;
        sp(c).edit().remove("resume_" + videoId).apply();
    }

    // ---- Collapsible settings sections (persisted open/closed state) ----

    public static boolean sectionOpen(Context c, String key, boolean def) {
        return sp(c).getBoolean("sec_" + key, def);
    }

    public static void setSectionOpen(Context c, String key, boolean open) {
        sp(c).edit().putBoolean("sec_" + key, open).apply();
    }

    // ---- Channel URL helpers (used by the favorites Takeout import) ----

    public static String urlPart(String entry) {
        if (entry == null) return "";
        int i = entry.lastIndexOf('|');
        return i >= 0 ? entry.substring(i + 1).trim() : entry.trim();
    }

    public static String labelFromUrl(String url) {
        if (url == null) return "";
        int i = url.indexOf("/@");
        if (i >= 0) return url.substring(i + 1);
        i = url.lastIndexOf('/');
        if (i >= 0 && i + 1 < url.length()) return url.substring(i + 1);
        return url;
    }

    /** Normalizes + validates a channel link; null if invalid. */
    public static String normChannelUrl(String url) {
        if (url == null) return null;
        url = url.trim();
        if (url.isEmpty()) return null;
        if (url.contains("|")) url = urlPart(url);
        if (!url.contains("youtube.com") && !url.contains("youtu.be")) return null;
        // strip tracking params
        int q = url.indexOf('?');
        if (q > 0) url = url.substring(0, q);
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url.isEmpty() ? null : url;
    }

    // ---- one-time gesture hints ----
    public static boolean hintSeen(Context c, String key) {
        return sp(c).getBoolean("hint_" + key, false);
    }
    public static void setHintSeen(Context c, String key) {
        sp(c).edit().putBoolean("hint_" + key, true).apply();
    }

    // ---- Recent searches (plain-text queries only, most recent first) ----
    public static List<String> recentSearches(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("recent_searches", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {}
        return out;
    }

    public static void addRecentSearch(Context c, String q) {
        if (q == null) return;
        q = q.trim();
        if (q.isEmpty()) return;
        List<String> l = recentSearches(c);
        l.remove(q);
        l.add(0, q);
        while (l.size() > 12) l.remove(l.size() - 1);
        sp(c).edit().putString("recent_searches", new JSONArray(l).toString()).apply();
    }

    public static void clearRecentSearches(Context c) {
        sp(c).edit().remove("recent_searches").apply();
    }

    public static void removeRecentSearch(Context c, String q) {
        List<String> l = recentSearches(c);
        l.remove(q);
        sp(c).edit().putString("recent_searches", new JSONArray(l).toString()).apply();
    }

    // ---- Favorites ("type|name|url"; type = video|channel|playlist) ----
    public static List<String> favorites(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("favorites", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {}
        return out;
    }
    private static String[] favParts(String e) {
        if (e == null) return new String[0];
        return e.split("\\|", -1);
    }
    public static String favType(String e) {
        String[] p = favParts(e);
        return p.length > 0 && !p[0].isEmpty() ? p[0] : "video";
    }
    public static String favName(String e) {
        String[] p = favParts(e);
        if (p.length > 1) return p[1];
        return p.length > 0 ? p[0] : "";
    }
    public static String favUrl(String e) {
        String[] p = favParts(e);
        return p.length > 2 ? p[2] : "";
    }
    /** 4th field (may be empty for entries saved before v2.9): channel/playlist avatar. */
    public static String favThumb(String e) {
        String[] p = favParts(e);
        return p.length > 3 ? p[3] : "";
    }
    /** Fill in a missing thumbnail for an existing favorite (lazy backfill). */
    public static void setFavThumb(Context c, String url, String thumb) {
        if (url == null || thumb == null || thumb.isEmpty()) return;
        List<String> l = favorites(c);
        boolean changed = false;
        for (int i = 0; i < l.size(); i++) {
            String e = l.get(i);
            if (url.equalsIgnoreCase(favUrl(e)) && favThumb(e).isEmpty()) {
                l.set(i, favType(e) + "|" + favName(e) + "|" + favUrl(e)
                        + "|" + thumb.trim());
                changed = true;
            }
        }
        if (changed) {
            sp(c).edit().putString("favorites", new JSONArray(l).toString()).apply();
        }
    }
    public static boolean isFavorite(Context c, String url) {
        if (url == null) return false;
        for (String e : favorites(c)) {
            if (url.equalsIgnoreCase(favUrl(e))) return true;
        }
        return false;
    }
    /** Returns false if already present. */
    public static boolean addFavorite(Context c, String type, String name, String url) {
        return addFavorite(c, type, name, url, "");
    }
    public static boolean addFavorite(Context c, String type, String name,
                                      String url, String thumb) {
        if (url == null || url.trim().isEmpty()) return false;
        url = url.trim();
        if (isFavorite(c, url)) return false;
        List<String> l = favorites(c);
        l.add(0, (type == null ? "video" : type) + "|"
                + (name == null ? "" : name.replace("|", " ").trim()) + "|" + url
                + "|" + (thumb == null ? "" : thumb.trim()));
        sp(c).edit().putString("favorites", new JSONArray(l).toString()).apply();
        return true;
    }
    public static void removeFavorite(Context c, String url) {
        if (url == null) return;
        List<String> keep = new ArrayList<>();
        for (String e : favorites(c)) {
            if (!url.equalsIgnoreCase(favUrl(e))) keep.add(e);
        }
        sp(c).edit().putString("favorites", new JSONArray(keep).toString()).apply();
    }

    // ---- Blacklist ("name|url"): hidden from search results ----
    public static List<String> blacklist(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("blacklist", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {}
        return out;
    }
    public static String blName(String e) {
        if (e == null) return "";
        int b = e.lastIndexOf('|');
        return b > 0 ? e.substring(0, b) : e;
    }
    public static String blUrl(String e) {
        if (e == null) return "";
        int b = e.lastIndexOf('|');
        return b >= 0 ? e.substring(b + 1) : e;
    }
    public static boolean isBlacklisted(Context c, String url) {
        if (url == null) return false;
        for (String e : blacklist(c)) {
            if (url.equalsIgnoreCase(blUrl(e))) return true;
        }
        return false;
    }
    public static boolean addBlacklist(Context c, String name, String url) {
        if (url == null || url.trim().isEmpty()) return false;
        url = url.trim();
        if (isBlacklisted(c, url)) return false;
        List<String> l = blacklist(c);
        l.add(0, (name == null ? "" : name.replace("|", " ").trim()) + "|" + url);
        sp(c).edit().putString("blacklist", new JSONArray(l).toString()).apply();
        return true;
    }
    public static void removeBlacklist(Context c, String url) {
        if (url == null) return;
        List<String> keep = new ArrayList<>();
        for (String e : blacklist(c)) {
            if (!url.equalsIgnoreCase(blUrl(e))) keep.add(e);
        }
        sp(c).edit().putString("blacklist", new JSONArray(keep).toString()).apply();
    }
}
