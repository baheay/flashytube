package com.flashytube.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal WebVTT parser for in-player subtitle rendering. */
public final class SubtitleParser {
    private SubtitleParser() {}

    public static class Cue {
        public long startMs;
        public long endMs;
        public String text;
    }

    private static final Pattern TS = Pattern.compile(
        "(\\d{2}):(\\d{2}):(\\d{2})[.,](\\d{3})\\s*-->\\s*"
        + "(\\d{2}):(\\d{2}):(\\d{2})[.,](\\d{3})");

    public static List<Cue> parse(File f) {
        List<Cue> cues = new ArrayList<>();
        if (f == null || !f.exists()) return cues;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), "UTF-8"))) {
            String line;
            Cue cur = null;
            StringBuilder txt = new StringBuilder();
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.equals("WEBVTT")
                        || line.startsWith("NOTE")) {
                    if (cur != null && txt.length() > 0) {
                        cur.text = clean(txt.toString().trim());
                        cues.add(cur);
                        cur = null;
                        txt.setLength(0);
                    }
                    continue;
                }
                Matcher m = TS.matcher(line);
                if (m.find()) {
                    if (cur != null && txt.length() > 0) {
                        cur.text = clean(txt.toString().trim());
                        cues.add(cur);
                        txt.setLength(0);
                    }
                    cur = new Cue();
                    cur.startMs = toMs(m.group(1), m.group(2),
                            m.group(3), m.group(4));
                    cur.endMs = toMs(m.group(5), m.group(6),
                            m.group(7), m.group(8));
                } else if (cur != null) {
                    if (txt.length() > 0) txt.append('\n');
                    txt.append(line);
                }
            }
            if (cur != null && txt.length() > 0) {
                cur.text = clean(txt.toString().trim());
                cues.add(cur);
            }
        } catch (Exception ignored) {}
        return cues;
    }

    private static long toMs(String h, String m, String s, String ms) {
        return Long.parseLong(h) * 3600000L
                + Long.parseLong(m) * 60000L
                + Long.parseLong(s) * 1000L
                + Long.parseLong(ms);
    }

    /** Strip VTT tags like <c>, <i>, timestamps. */
    private static String clean(String t) {
        return t.replaceAll("<[^>]*>", "").trim();
    }

    /** Find the cue active at positionMs, or null. */
    public static String textAt(List<Cue> cues, long positionMs) {
        if (cues == null) return null;
        for (Cue c : cues) {
            if (positionMs >= c.startMs && positionMs <= c.endMs) {
                return c.text;
            }
        }
        return null;
    }
}
