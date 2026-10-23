package com.ytgrab.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny thumbnail loader: memory LRU + disk cache, no libraries. */
public class ThumbLoader {

    private static final int MEM_MAX = 60;
    private static final Map<String, Bitmap> mem =
            Collections.synchronizedMap(new LinkedHashMap<String, Bitmap>(MEM_MAX, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
                    return size() > MEM_MAX;
                }
            });

    private static final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static Bitmap placeholder;

    public static void load(Context ctx, String url, ImageView iv) {
        if (url == null) {
            iv.setImageBitmap(placeholder(ctx));
            iv.setTag(null);
            return;
        }
        Bitmap b = mem.get(url);
        if (b != null) {
            iv.setTag(url);
            iv.setImageBitmap(b);
            return;
        }
        iv.setTag(url);
        iv.setImageBitmap(placeholder(ctx));
        final Context app = ctx.getApplicationContext();
        pool.execute(() -> {
            Bitmap bmp = fetch(app, url);
            if (bmp == null) return;
            mem.put(url, bmp);
            iv.post(() -> {
                if (url.equals(iv.getTag())) iv.setImageBitmap(bmp);
            });
        });
    }

    private static Bitmap fetch(Context ctx, String url) {
        try {
            File dir = new File(ctx.getCacheDir(), "thumbs");
            dir.mkdirs();
            File f = new File(dir, String.valueOf(url.hashCode()) + ".jpg");
            if (f.exists() && f.length() > 0) {
                Bitmap b = decode(f.getAbsolutePath());
                if (b != null) return b;
            }
            HttpURLConnection con =
                    (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            con.connect();
            if (con.getResponseCode() != 200) return null;
            InputStream in = con.getInputStream();
            File tmp = new File(dir, f.getName() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } finally {
                try { out.close(); } catch (Exception ignored) {}
                try { in.close(); } catch (Exception ignored) {}
                con.disconnect();
            }
            tmp.renameTo(f);
            return decode(f.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decode(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sw = o.outWidth / 240;
            o.inSampleSize = sw < 1 ? 1 : sw;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(path, o);
        } catch (Exception e) {
            return null;
        }
    }

    /** Task thumbnail: YouTube thumb when a videoId is known, otherwise a frame
     *  grabbed from the local file (for videos re-added by the folder scan). */
    public static void loadForTask(Context ctx, DlEngine.Task t, ImageView iv) {
        if (t.videoId != null && !t.videoId.isEmpty()) {
            load(ctx, DlHelper.thumbUrl(t.videoId), iv);
        } else if (t.publishedUri != null && !t.publishedUri.isEmpty()) {
            loadLocal(ctx, t.publishedUri, iv);
        } else {
            load(ctx, null, iv);
        }
    }

    /** Grab a frame from a local video file (content:// or file://) and cache it. */
    public static void loadLocal(Context ctx, String uriString, ImageView iv) {
        final String key = "local:" + uriString;
        Bitmap b = mem.get(key);
        if (b != null) {
            iv.setTag(key);
            iv.setImageBitmap(b);
            return;
        }
        iv.setTag(key);
        iv.setImageBitmap(placeholder(ctx));
        final Context app = ctx.getApplicationContext();
        pool.execute(() -> {
            Bitmap bmp = frame(app, uriString);
            if (bmp == null) return;
            mem.put(key, bmp);
            iv.post(() -> {
                if (key.equals(iv.getTag())) iv.setImageBitmap(bmp);
            });
        });
    }

    private static Bitmap frame(Context ctx, String uriString) {
        android.media.MediaMetadataRetriever r =
                new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, android.net.Uri.parse(uriString));
            Bitmap b = r.getFrameAtTime(1_000_000); // frame at 1s
            if (b == null) b = r.getFrameAtTime(0);
            if (b == null) return null;
            int w = b.getWidth(), h = b.getHeight();
            int tw = 192;
            int th = w > 0 ? (int) (192f * h / w) : 108;
            if (th <= 0) th = 108;
            Bitmap s = Bitmap.createScaledBitmap(b, tw, th, true);
            if (s != b) b.recycle();
            return s;
        } catch (Exception e) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private static synchronized Bitmap placeholder(Context ctx) {
        if (placeholder != null) return placeholder;
        int w = 192, h = 144;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.parseColor("#37474F"));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.parseColor("#B0BEC5"));
        float cx = w / 2f, cy = h / 2f, r = 26f;
        c.drawCircle(cx, cy, r, p);
        p.setColor(Color.parseColor("#37474F"));
        Path tri = new Path();
        tri.moveTo(cx - 8, cy - 12);
        tri.lineTo(cx - 8, cy + 12);
        tri.lineTo(cx + 12, cy);
        tri.close();
        c.drawPath(tri, p);
        placeholder = b;
        return b;
    }
}
