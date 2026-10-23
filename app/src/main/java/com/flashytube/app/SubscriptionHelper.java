package com.flashytube.app;

import android.content.Context;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches the user's YouTube subscriptions via the authenticated
 * InnerTube browse API (FEsubscriptions), using the WebView login session.
 * Falls back to null when not logged in or on any failure.
 */
public final class SubscriptionHelper {
    private SubscriptionHelper() {}

    public static class Channel {
        public String id;
        public String name;
        public String url;
    }

    private static final String API_KEY =
            "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String BROWSE_URL =
            "https://www.youtube.com/youtubei/v1/browse?key=" + API_KEY
            + "&prettyPrint=false";

    /**
     * Get subscribed channels. Must be called on a background thread.
     * Returns empty list (not null) on failure.
     */
    public static List<Channel> getSubscriptions(Context ctx) {
        List<Channel> out = new ArrayList<>();
        if (!GoogleLogin.isLoggedIn(ctx)) return out;
        try {
            String body = "{\"browseId\":\"FEsubscriptions\","
                    + "\"context\":{\"client\":{\"clientName\":\"WEB\","
                    + "\"clientVersion\":\"2.20241201.00.00\","
                    + "\"hl\":\"en\",\"gl\":\"US\"}}}";
            String json = postJson(BROWSE_URL, body);
            if (json == null || json.isEmpty()) return out;
            JsonObject root = JsonParser.object().from(json);
            // Response: contents -> sectionListRenderer or richGridRenderer
            // containing channelRenderer items.
            collectChannels(root, out);
        } catch (Exception ignored) {}
        // Fallback: scrape subscriptions HTML for channel IDs + names.
        if (out.isEmpty()) {
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (String pageUrl : new String[]{
                    "https://www.youtube.com/feed/channels",
                    "https://www.youtube.com/feed/subscriptions"}) {
                if (out.size() >= 50) break;
                try {
                    String html = getHtml(pageUrl);
                    if (html == null) continue;
                    // channelRenderer with title.
                    java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"channelId\":\"(UC[\\w-]{22})\"[^}]*?"
                                + "\"title\":\\{\"simpleText\":\"([^\"]+)\"")
                        .matcher(html);
                    while (m.find() && out.size() < 50) {
                        String cid = m.group(1);
                        String name = m.group(2);
                        if (seen.add(cid)) {
                            Channel c = new Channel();
                            c.id = cid;
                            c.name = name;
                            c.url = "https://www.youtube.com/channel/" + cid;
                            out.add(c);
                        }
                    }
                    // Plain channelId fallback (no name).
                    if (out.isEmpty()) {
                        java.util.regex.Matcher m2 = java.util.regex.Pattern
                            .compile("\"channelId\":\"(UC[\\w-]{22})\"")
                            .matcher(html);
                        while (m2.find() && out.size() < 50) {
                            String cid = m2.group(1);
                            if (seen.add(cid)) {
                                Channel c = new Channel();
                                c.id = cid;
                                c.name = "";
                                c.url = "https://www.youtube.com/channel/" + cid;
                                out.add(c);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return out;
    }

    private static void collectChannels(JsonObject root, List<Channel> out) {
        deepWalk(root, out, new java.util.HashSet<>());
    }

    /**
     * Walk EVERY object/array node in the browse response, whatever keys
     * YouTube nests them under (the old collector only descended a fixed
     * list of wrapper keys, so layout changes silently yielded zero
     * channels). Collects channelRenderer + gridChannelRenderer entries.
     */
    private static void deepWalk(Object node, List<Channel> out,
                                 java.util.Set<String> seen) {
        if (node instanceof JsonObject) {
            JsonObject o = (JsonObject) node;
            for (String key : new String[]{"channelRenderer",
                    "gridChannelRenderer"}) {
                Object r = o.get(key);
                if (r instanceof JsonObject) {
                    JsonObject cr = (JsonObject) r;
                    String cid = cr.getString("channelId");
                    String name = "";
                    JsonObject t = cr.getObject("title");
                    if (t != null) {
                        name = t.getString("simpleText");
                        if (name == null) {
                            JsonArray runs = t.getArray("runs");
                            if (runs != null && runs.size() > 0
                                    && runs.get(0) instanceof JsonObject) {
                                name = ((JsonObject) runs.get(0))
                                        .getString("text");
                            }
                        }
                    }
                    if (cid != null && !cid.isEmpty() && seen.add(cid)) {
                        Channel c = new Channel();
                        c.id = cid;
                        c.name = name != null ? name : "";
                        c.url = "https://www.youtube.com/channel/" + cid;
                        out.add(c);
                    }
                }
            }
            for (Object v : o.values()) deepWalk(v, out, seen);
        } else if (node instanceof JsonArray) {
            for (Object it : (JsonArray) node) deepWalk(it, out, seen);
        }
    }

    /**
     * Get recent videos from a channel (first page of the Videos tab).
     * Must be called on a background thread.
     */
    public static List<StreamInfoItem> getChannelVideos(String channelUrl,
                                                        int max) {
        List<StreamInfoItem> out = new ArrayList<>();
        try {
            DlHelper.ensureInit();
            ChannelExtractor ex = org.schabi.newpipe.extractor.ServiceList
                    .YouTube.getChannelExtractor(channelUrl);
            ex.fetchPage();
            // Find the Videos tab.
            String videosUrl = null;
            try {
                List<ListLinkHandler> tabs = ex.getTabs();
                if (tabs != null) {
                    for (ListLinkHandler t : tabs) {
                        String u = t.getUrl();
                        if (u != null && u.contains("/videos")) {
                            videosUrl = u;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}

            // Get the videos tab and fetch via ChannelTabExtractor.
            org.schabi.newpipe.extractor.StreamingService service =
                    org.schabi.newpipe.extractor.ServiceList.YouTube;
            ChannelExtractor ce = service.getChannelExtractor(channelUrl);
            ce.fetchPage();
            ListLinkHandler videosTab = null;
            try {
                List<ListLinkHandler> tabs = ce.getTabs();
                if (tabs != null) {
                    for (ListLinkHandler t : tabs) {
                        String u = t.getUrl();
                        if (u != null && u.contains("/videos")) {
                            videosTab = t;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
            if (videosTab == null) return out;
            org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor cte =
                    service.getChannelTabExtractor(videosTab);
            cte.fetchPage();
            // Paginate for variety (not just the latest videos).
            ListExtractor.InfoItemsPage<? extends
                    org.schabi.newpipe.extractor.InfoItem> page =
                    cte.getInitialPage();
            int pagesFetched = 0;
            while (page != null && out.size() < max && pagesFetched < 5) {
                if (page.getItems() != null) {
                    for (org.schabi.newpipe.extractor.InfoItem it
                            : page.getItems()) {
                        if (!(it instanceof StreamInfoItem)) continue;
                        StreamInfoItem si = (StreamInfoItem) it;
                        if (si.getUrl() != null) {
                            out.add(si);
                            if (out.size() >= max) break;
                        }
                    }
                }
                pagesFetched++;
                if (out.size() >= max || !page.hasNextPage()) break;
                try {
                    page = cte.getPage(page.getNextPage());
                } catch (Exception e) { break; }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String getHtml(String urlStr) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(urlStr);
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 "
                    + "(Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            String cookies = GoogleLogin.cookiesForUrl(urlStr);
            if (cookies != null && !cookies.isEmpty()) {
                c.setRequestProperty("Cookie", cookies);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream()
                    : c.getInputStream();
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            // Limit to 500KB.
            int total = 0;
            while ((n = in.read(buf)) != -1 && total < 512000) {
                bos.write(buf, 0, n);
                total += n;
            }
            in.close();
            return bos.toString("UTF-8");
        } catch (Exception ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String postJson(String urlStr, String body) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(urlStr);
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 "
                    + "(Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            c.setRequestProperty("Origin", "https://www.youtube.com");
            c.setRequestProperty("Referer", "https://www.youtube.com/");
            // Auth: cookies + SAPISIDHASH.
            String cookies = GoogleLogin.cookiesForUrl(urlStr);
            if (cookies != null && !cookies.isEmpty()) {
                c.setRequestProperty("Cookie", cookies);
            }
            String auth = GoogleLogin.sapisidHash();
            if (auth != null) {
                c.setRequestProperty("Authorization",
                        "SAPISIDHASH " + auth);
                c.setRequestProperty("X-Goog-AuthUser", "0");
                c.setRequestProperty("X-Origin", "https://www.youtube.com");
            }
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream()
                    : c.getInputStream();
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            return bos.toString("UTF-8");
        } catch (Exception ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
