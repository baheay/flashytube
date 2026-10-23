package com.flashytube.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriPermission;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.NewPipe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-app download manager with pause/resume support.
 * Files download to private .part files (HTTP Range resume); on completion they are
 * published to the public Downloads folder (separate video/audio folders from settings).
 * High qualities (1080p+) are downloaded as video-only + audio and muxed on-device.
 */
public class DlEngine {

    public enum State { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

    public static class Task {
        public String id;
        public String url;            // direct file URL (null until resolved for bulk)
        public String muxAudioUrl;    // set for mux tasks (video-only url in `url`)
        public String videoPageUrl;   // set for bulk tasks: resolve stream first
        public int quality;           // DlHelper.Q_* for bulk tasks
        public String kind = "video"; // video | audio | sub
        public String fileName;
        public String publishedUri = "";
        public String videoId;        // YouTube video id -> thumbnail
        public long durationSec = 0;  // video duration in seconds
        public volatile long total = -1;
        public volatile long done = 0;
        public volatile State state = State.QUEUED;
        public String error = "";
        public int muxHeight = 0;        // mux tasks: ladder height (for 403 recovery)
        public boolean muxWebm = false;  // mux tasks: webm container (for 403 recovery)
        public transient boolean refreshed403 = false; // 403 recovery already attempted
        public transient boolean autoPaused = false; // paused by Wi-Fi-only guard
        public String subfolder;         // optional subfolder (playlist/channel name)
        public boolean wifiOnly = false;     // download on Wi-Fi only
        public int subLang = 0; // subtitles: 0=none, 1=ar, 2=en, 3=both
        public transient List<org.schabi.newpipe.extractor.stream.SubtitlesStream> pendingSubs; // resolved subtitle streams
    }

    public interface Listener { void onChanged(); }

    private static DlEngine inst;

    public static synchronized DlEngine get(Context c) {
        Context wc = Lang.wrap(c.getApplicationContext());
        if (inst == null) {
            inst = new DlEngine(wc);
        } else {
            inst.ctx = wc; // refresh locale
        }
        return inst;
    }

    private static final String UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private static final int MAX_ATTEMPTS = 5;
    // Parallel (segmented) downloading: multiple connections beat YouTube's
    // per-connection throttling (this is why SnapTube is fast on one link).
    private static final int PARALLEL_CHUNKS = 8;
    private static final long MIN_PARALLEL_BYTES = 1 * 1024 * 1024; // >=1MB -> split

    private Context ctx;
    private ExecutorService pool;
    private int poolSize = 0;
    private final List<Task> tasks = Collections.synchronizedList(new ArrayList<Task>());
    private final List<Listener> listeners = new ArrayList<Listener>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastNotify = 0;
    private long lastSave = 0;

    private DlEngine(Context ctx) {
        this.ctx = ctx;
        workDir().mkdirs();
        load();
        watchNetwork();
    }

    // ---------- Wi-Fi only downloads ----------

    /** True when the active network is Wi-Fi (needs ACCESS_NETWORK_STATE). */
    public static boolean isWifiConnected(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null
                    && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    /** False when this task is Wi-Fi-only and we are not on Wi-Fi. */
    public boolean downloadsAllowedNow(Task t) {
        return !t.wifiOnly || isWifiConnected(ctx);
    }

    /**
     * Per-task Wi-Fi toggle from the download's own menu.
     * wifiOnly=true ("wait for Wi-Fi"): the task only runs on Wi-Fi from now
     * on; if it is active right now without Wi-Fi it pauses and waits.
     * wifiOnly=false ("run on data"): clear the flag and (re)start the task
     * immediately on the current network.
     */
    public void setTaskWifiOnly(String id, boolean wifiOnly) {
        Task target = null;
        synchronized (tasks) {
            for (Task t : tasks) {
                if (t.id.equals(id)) { target = t; break; }
            }
        }
        if (target == null) return;
        final Task t = target;
        synchronized (tasks) {
            t.wifiOnly = wifiOnly;
            if (wifiOnly) {
                if (!downloadsAllowedNow(t)
                        && (t.state == State.DOWNLOADING || t.state == State.QUEUED)) {
                    t.state = State.PAUSED;
                    t.autoPaused = true;
                }
            } else {
                t.autoPaused = false;
                if (t.state == State.QUEUED || t.state == State.PAUSED
                        || t.state == State.FAILED) {
                    t.error = null;
                    t.state = State.QUEUED;
                }
            }
        }
        save();
        notifyChanged();
        if (!wifiOnly && t.state == State.QUEUED) {
            pool().execute(() -> runTask(t));
        }
    }

    /**
     * Watches connectivity: when Wi-Fi drops (and Wi-Fi-only is on) active
     * downloads auto-pause; when Wi-Fi returns they auto-resume. User-paused
     * tasks are never touched.
     */
    private void watchNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            cm.registerDefaultNetworkCallback(
                    new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (isWifiConnected(ctx)) {
                        resumeAutoPaused();
                    }
                }

                @Override
                public void onLost(Network network) {
                    if (!isWifiConnected(ctx)) {
                        pauseForWifi();
                    }
                }
            });
        } catch (Exception ignored) {}
    }

    /** Pause active Wi-Fi-only tasks because Wi-Fi went away. */
    private void pauseForWifi() {
        boolean changed = false;
        synchronized (tasks) {
            for (Task t : tasks) {
                if (!t.wifiOnly) continue;
                if (t.state == State.DOWNLOADING || t.state == State.QUEUED) {
                    t.state = State.PAUSED;
                    t.autoPaused = true;
                    changed = true;
                }
            }
        }
        if (changed) { save(); notifyChanged(); }
    }

    /** Resume tasks that were auto-paused by the Wi-Fi guard. */
    private void resumeAutoPaused() {
        boolean changed = false;
        synchronized (tasks) {
            for (Task t : tasks) {
                if (t.autoPaused && t.state == State.PAUSED) {
                    t.autoPaused = false;
                    t.state = State.QUEUED;
                    t.error = "";
                    changed = true;
                    pool().execute(() -> runTask(t));
                } else if (t.state == State.QUEUED && downloadsAllowedNow(t)) {
                    // Wi-Fi-only task enqueued while offline: it never ran, so
                    // it was never auto-paused — without this kick it would sit
                    // in QUEUED forever even though the network allows it now.
                    // (runTask() no-ops on non-QUEUED tasks, so this is safe.)
                    changed = true;
                    pool().execute(() -> runTask(t));
                }
            }
        }
        if (changed) { save(); notifyChanged(); }
    }

    private long lastWifiToast = 0;

    /** Throttled "waiting for Wi-Fi" toast (called from worker threads). */
    private void toastWifiWait() {
        long now = System.currentTimeMillis();
        if (now - lastWifiToast < 4000) return;
        lastWifiToast = now;
        main.post(() -> {
            try {
                Toast.makeText(ctx, R.string.toast_wifi_wait,
                        Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        });
    }

    /** Thread pool sized by the user's parallel-download setting (1-5). */
    private synchronized ExecutorService pool() {
        int want = Prefs.maxParallel(ctx);
        if (pool == null || poolSize != want) {
            if (pool != null) pool.shutdown();
            poolSize = want;
            pool = Executors.newFixedThreadPool(want);
        }
        return pool;
    }

    // ---------- public API ----------

    public Task findTask(String id) {
        synchronized (tasks) {
            for (Task t : tasks) if (t.id.equals(id)) return t;
        }
        return null;
    }

    public List<Task> getTasks() { return tasks; }

    public void addListener(Listener l) { synchronized (listeners) { listeners.add(l); } }
    public void removeListener(Listener l) { synchronized (listeners) { listeners.remove(l); } }

    public Task enqueue(String fileUrl, String fileName, String kind,
                        String videoId, long durationSec) {
        return enqueue(fileUrl, fileName, kind, videoId, durationSec, false);
    }

    public Task enqueue(String fileUrl, String fileName, String kind,
                        String videoId, long durationSec, boolean wifiOnly) {
        Task t = newTask();
        t.url = fileUrl;
        t.fileName = fileName;
        t.kind = kind;
        t.videoId = videoId;
        t.durationSec = durationSec;
        t.wifiOnly = wifiOnly;
        addAndStart(t);
        return t;
    }

    /** Mux task: downloads video-only + audio, then merges them (MP4 for AVC, WebM for VP9). */
    public Task enqueueMux(String videoUrl, String audioUrl, String fileName,
                           String videoId, long durationSec) {
        return enqueueMux(videoUrl, audioUrl, fileName, videoId, durationSec, 0, false);
    }

    /** Mux task with ladder info (height/container) for HTTP 403 recovery. */
    public Task enqueueMux(String videoUrl, String audioUrl, String fileName,
                           String videoId, long durationSec,
                           int muxHeight, boolean muxWebm) {
        return enqueueMux(videoUrl, audioUrl, fileName, videoId, durationSec,
                muxHeight, muxWebm, false);
    }

    public Task enqueueMux(String videoUrl, String audioUrl, String fileName,
                           String videoId, long durationSec,
                           int muxHeight, boolean muxWebm, boolean wifiOnly) {
        Task t = newTask();
        t.url = videoUrl;
        t.muxAudioUrl = audioUrl;
        t.fileName = fileName;
        t.kind = "video";
        t.videoId = videoId;
        t.durationSec = durationSec;
        t.muxHeight = muxHeight;
        t.muxWebm = muxWebm;
        t.wifiOnly = wifiOnly;
        addAndStart(t);
        return t;
    }

    /** Bulk task: resolves the actual stream URL from the video page when the worker starts. */
    public Task enqueueVideo(String pageUrl, String baseName, int quality, String kind,
                             long durationSec, String subfolder) {
        return enqueueVideo(pageUrl, baseName, quality, kind, durationSec, subfolder, false);
    }

    public Task enqueueVideo(String pageUrl, String baseName, int quality, String kind,
                             long durationSec, String subfolder, boolean wifiOnly) {
        Task t = newTask();
        t.videoPageUrl = pageUrl;
        t.quality = quality;
        t.fileName = baseName;
        t.kind = kind;
        t.videoId = DlHelper.extractVideoId(pageUrl);
        t.durationSec = durationSec;
        t.subfolder = subfolder;
        t.wifiOnly = wifiOnly;
        addAndStart(t);
        return t;
    }

    /** One item of a bulk ("download all") enqueue. */
    public static class BulkVideo {
        public String pageUrl;
        public String baseName;
        public int quality;
        public String kind;
        public long durationSec;
        public String subfolder;
        public boolean wifiOnly;
        public int subLang;
    }

    /**
     * Bulk enqueue for "download all" / multi-select: builds every task, then
     * a SINGLE save() + notify for the whole batch. The old per-item loop
     * wrote downloads.json to disk N times on the UI thread and froze the app
     * (ANR) on big channels.
     */
    public void enqueueBulkVideos(List<BulkVideo> videos) {
        List<Task> batch = new ArrayList<>(videos.size());
        for (BulkVideo v : videos) {
            Task t = newTask();
            t.videoPageUrl = v.pageUrl;
            t.quality = v.quality;
            t.fileName = v.baseName;
            t.kind = v.kind;
            t.videoId = DlHelper.extractVideoId(v.pageUrl);
            t.durationSec = v.durationSec;
            t.subfolder = v.subfolder;
            t.wifiOnly = v.wifiOnly;
            t.subLang = v.subLang;
            batch.add(t);
        }
        synchronized (tasks) {
            for (int i = batch.size() - 1; i >= 0; i--) tasks.add(0, batch.get(i));
        }
        save();
        notifyChanged();
        boolean anyWaiting = false;
        for (Task t : batch) {
            if (!downloadsAllowedNow(t)) anyWaiting = true;
            else pool().execute(() -> runTask(t));
        }
        if (anyWaiting) toastWifiWait();
    }

    public void pause(String id) {
        Task t = find(id);
        if (t != null && (t.state == State.DOWNLOADING || t.state == State.QUEUED)) {
            t.state = State.PAUSED;
            save();
            notifyChanged();
        }
    }

    public void resume(String id) {
        Task t = find(id);
        if (t != null && (t.state == State.PAUSED || t.state == State.FAILED)) {
            t.state = State.QUEUED;
            t.error = "";
            t.autoPaused = false; // user took over
            save();
            notifyChanged();
            if (!downloadsAllowedNow(t)) toastWifiWait();
            pool().execute(() -> runTask(t));
        }
    }

    public void pauseAll() {
        boolean changed = false;
        synchronized (tasks) {
            for (Task t : tasks) {
                if (t.state == State.DOWNLOADING || t.state == State.QUEUED) {
                    t.state = State.PAUSED;
                    changed = true;
                }
            }
        }
        if (changed) { save(); notifyChanged(); }
    }

    // ---------- Library scan: re-import files already on disk ----------

    /**
     * Scans the download folders and re-adds files that exist on disk but
     * are missing from the task list (e.g. after reinstalling the app).
     * Runs on a worker thread; idempotent, safe to call often.
     */
    public void scanExistingFiles() {
        new Thread(() -> {
            try {
                boolean changed = false;
                changed |= scanFolder(Prefs.videoDir(ctx), Prefs.videoTree(ctx), "video");
                changed |= scanFolder(Prefs.audioDir(ctx), Prefs.audioTree(ctx), "audio");
                // Legacy pre-rebrand folders: keep old files visible even
                // though new downloads now go to FlashyTube/.
                changed |= scanFolder("YT Grab/Video", "", "video");
                changed |= scanFolder("YT Grab/Audio", "", "audio");
                if (changed) { save(); notifyChanged(); }
                pruneMissingFiles();
            } catch (Exception ignored) {}
        }).start();
    }

    private boolean scanFolder(String dirName, String treeUriStr, String kind) {
        try {
            if (treeUriStr != null && !treeUriStr.isEmpty()) {
                return scanTree(treeUriStr, kind);
            }
            if (useRootStorage(ctx)) {
                // New root location + old Downloads location (migration).
                boolean c = scanRoot(dirName, kind);
                if (Build.VERSION.SDK_INT >= 29) c |= scanMediaStore(dirName, kind);
                else c |= scanLegacy(dirName, kind);
                return c;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                return scanMediaStore(dirName, kind);
            }
            return scanLegacy(dirName, kind);
        } catch (Exception e) {
            return false;
        }
    }

    /** Default branch: files published through MediaStore. */
    private boolean scanMediaStore(String dirName, String kind) {
        boolean changed = false;
        ContentResolver cr = ctx.getContentResolver();
        // LIKE (not =): also catches playlist/channel subfolders.
        String relPath = Environment.DIRECTORY_DOWNLOADS + "/" + dirName + "/%";
        String[] proj = {
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE };
        try (android.database.Cursor c = cr.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj,
                MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
                new String[]{ relPath }, null)) {
            if (c == null) return false;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID);
            int nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
            int sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE);
            while (c.moveToNext()) {
                String name = c.getString(nameCol);
                long id = c.getLong(idCol);
                long size = c.getLong(sizeCol);
                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                if (adoptFile(name, uri.toString(), size, kind)) changed = true;
            }
        } catch (Exception ignored) {}
        return changed;
    }

    /** User picked a custom folder (Storage Access Framework tree). */
    private boolean scanTree(String treeUriStr, String kind) {
        boolean changed = false;
        try {
            Uri treeUri = Uri.parse(treeUriStr);
            changed = scanTreeDir(treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri), kind);
        } catch (Exception ignored) {}
        return changed;
    }

    /** Recursive: also catches playlist/channel subfolders. */
    private boolean scanTreeDir(Uri treeUri, String parentDocId, String kind) {
        boolean changed = false;
        try {
            ContentResolver cr = ctx.getContentResolver();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, parentDocId);
            String[] proj = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE };
            try (android.database.Cursor c = cr.query(children, proj, null, null, null)) {
                if (c == null) return false;
                int idCol = c.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameCol = c.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeCol = c.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_MIME_TYPE);
                int sizeCol = c.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_SIZE);
                while (c.moveToNext()) {
                    String name = c.getString(nameCol);
                    String docId = c.getString(idCol);
                    String mime = c.getString(mimeCol);
                    long size = c.getLong(sizeCol);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        if (scanTreeDir(treeUri, docId, kind)) changed = true;
                        continue;
                    }
                    Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(
                            treeUri, docId);
                    if (adoptFile(name, fileUri.toString(), size, kind)) changed = true;
                }
            }
        } catch (Exception ignored) {}
        return changed;
    }

    /** Pre-29 fallback: plain files under Downloads/<dir>. */
    private boolean scanLegacy(String dirName, String kind) {
        boolean changed = false;
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), dirName);
            changed = scanLegacyDir(dir, kind);
        } catch (Exception ignored) {}
        return changed;
    }

    /** Recursive: also catches playlist/channel subfolders. */
    private boolean scanLegacyDir(File dir, String kind) {
        boolean changed = false;
        try {
            File[] files = dir.listFiles();
            if (files == null) return false;
            for (File f : files) {
                if (f.isDirectory()) {
                    if (scanLegacyDir(f, kind)) changed = true;
                    continue;
                }
                if (adoptFile(f.getName(), Uri.fromFile(f).toString(),
                        f.length(), kind)) changed = true;
            }
        } catch (Exception ignored) {}
        return changed;
    }

    /** Add a completed task for a file already on disk, unless listed. */
    private boolean adoptFile(String name, String uri, long size, String kind) {
        if (name == null || !isMediaFile(name)) return false;
        synchronized (tasks) {
            for (Task t : tasks) {
                if (t.fileName != null && t.fileName.equalsIgnoreCase(name)) {
                    return false;
                }
            }
            Task t = new Task();
            t.id = "file-" + Math.abs((kind + "|" + name).hashCode());
            t.kind = kind;
            t.fileName = name;
            t.publishedUri = uri == null ? "" : uri;
            t.videoId = ""; // unknown: no thumbnail, title parsed from filename
            t.state = State.COMPLETED;
            t.total = size;
            t.done = size;
            tasks.add(t);
            return true;
        }
    }

    private static boolean isMediaFile(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        return n.endsWith(".mp4") || n.endsWith(".m4a") || n.endsWith(".mp3")
                || n.endsWith(".webm") || n.endsWith(".mkv")
                || n.endsWith(".aac") || n.endsWith(".opus") || n.endsWith(".ogg");
    }

    public void resumeAll() {
        boolean changed = false;
        synchronized (tasks) {
            for (Task t : tasks) {
                if (t.state == State.PAUSED || t.state == State.FAILED) {
                    t.state = State.QUEUED;
                    t.error = "";
                    t.autoPaused = false; // user took over
                    changed = true;
                    pool().execute(() -> runTask(t));
                }
            }
        }
        if (changed) {
            save();
            notifyChanged();
            boolean anyWaiting = false;
            synchronized (tasks) {
                for (Task t : tasks) {
                    if (t.wifiOnly && (t.state == State.QUEUED)
                            && !isWifiConnected(ctx)) { anyWaiting = true; break; }
                }
            }
            if (anyWaiting) toastWifiWait();
        }
    }

    public void cancel(String id) {
        Task t = find(id);
        if (t == null) return;
        t.state = State.PAUSED; // signals a running worker to stop
        partFile(t).delete();
        new File(workDir(), t.id + ".vpart").delete();
        new File(workDir(), t.id + ".apart").delete();
        new File(workDir(), t.id + ".mux.tmp").delete();
        tasks.remove(t);
        save();
        notifyChanged();
    }

    /** Delete a task AND its finished file from storage. */
    public void deleteTask(String id) {
        Task t = find(id);
        if (t == null) return;
        deletePublishedFile(t);
        cancel(id); // removes the task + any part files
    }

    /** Delete the finished file of a task, whatever the storage path was. */
    private void deletePublishedFile(Task t) {
        try {
            String pub = t.publishedUri;
            if (pub != null && !pub.isEmpty()) {
                Uri u = Uri.parse(pub);
                String scheme = u.getScheme();
                if ("content".equals(scheme)) {
                    if (isDocumentUri(u)) {
                        // SAF tree file: ContentResolver.delete() does not work
                        // on document URIs; DocumentsContract does.
                        try {
                            DocumentsContract.deleteDocument(
                                    ctx.getContentResolver(), u);
                        } catch (Exception ignored) {}
                    } else {
                        try {
                            ctx.getContentResolver().delete(u, null, null);
                        } catch (Exception ignored) {}
                    }
                } else if ("file".equals(scheme) && u.getPath() != null) {
                    new File(u.getPath()).delete();
                }
            } else {
                // Legacy pre-29 publish left publishedUri empty: rebuild path.
                File f = legacyPublishedFile(t);
                if (f != null) f.delete();
            }
        } catch (Exception ignored) {}
    }

    private static boolean isDocumentUri(Uri u) {
        String s = u.toString();
        return s.contains("/document/") || s.contains("/tree/")
                || "com.android.externalstorage.documents".equals(u.getAuthority());
    }

    /** Where the legacy (pre-29) publish path put the finished file. */
    private File legacyPublishedFile(Task t) {
        try {
            String dirName = "audio".equals(t.kind)
                    ? Prefs.audioDir(ctx) : Prefs.videoDir(ctx);
            File rdir = rootDir(dirName);
            if (t.subfolder != null && !t.subfolder.isEmpty()) {
                rdir = new File(rdir, t.subfolder);
            }
            File atRoot = new File(rdir, t.fileName);
            if (useRootStorage(ctx) && atRoot.exists()) return atRoot;
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), dirName);
            if (t.subfolder != null && !t.subfolder.isEmpty()) {
                dir = new File(dir, t.subfolder);
            }
            return new File(dir, t.fileName);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Drop COMPLETED tasks whose file no longer exists on the device
     * (deleted from a file manager). Conservative: an entry is removed only
     * when we are sure the file is gone. Runs on a worker thread.
     */
    public void pruneMissingFiles() {
        new Thread(() -> {
            try {
                boolean changed = false;
                synchronized (tasks) {
                    java.util.Iterator<Task> it = tasks.iterator();
                    while (it.hasNext()) {
                        Task t = it.next();
                        if (t.state != State.COMPLETED) continue;
                        if (!publishedExists(t)) { it.remove(); changed = true; }
                    }
                }
                if (changed) { save(); notifyChanged(); }
            } catch (Exception ignored) {}
        }).start();
    }

    /** True when the task's published file still exists (or we cannot tell). */
    private boolean publishedExists(Task t) {
        try {
            String pub = t.publishedUri;
            if (pub != null && !pub.isEmpty()) {
                Uri u = Uri.parse(pub);
                String scheme = u.getScheme();
                if ("content".equals(scheme)) {
                    String[] proj = isDocumentUri(u)
                            ? new String[]{
                                    DocumentsContract.Document.COLUMN_DOCUMENT_ID }
                            : new String[]{ MediaStore.Downloads._ID };
                    try (android.database.Cursor c = ctx.getContentResolver()
                            .query(u, proj, null, null, null)) {
                        // A null cursor (or empty one) means the target is gone.
                        return c != null && c.moveToFirst();
                    } catch (SecurityException se) {
                        return true; // grant lost etc: keep the entry
                    } catch (Exception e) {
                        return false;
                    }
                }
                if ("file".equals(scheme) && u.getPath() != null) {
                    return new File(u.getPath()).exists();
                }
                return true; // unknown scheme: do not prune blindly
            }
            File f = legacyPublishedFile(t);
            return f == null || f.exists();
        } catch (Exception e) {
            return true; // on doubt, keep the entry
        }
    }

    // ---------- worker ----------

    private Task newTask() {
        Task t = new Task();
        t.id = UUID.randomUUID().toString();
        return t;
    }

    private void addAndStart(Task t) {
        tasks.add(0, t);
        save();
        notifyChanged();
        if (!downloadsAllowedNow(t)) toastWifiWait();
        pool().execute(() -> runTask(t));
    }

    private Task find(String id) {
        synchronized (tasks) {
            for (Task t : tasks) if (t.id.equals(id)) return t;
        }
        return null;
    }

    private void runTask(Task t) {
        if (t.state != State.QUEUED) return;
        if (!downloadsAllowedNow(t)) return; // stays QUEUED until Wi-Fi returns
        t.state = State.DOWNLOADING;
        t.error = "";
        notifyChanged();
        try {
            if (NewPipe.getDownloader() == null) NewPipe.init(new SimpleDownloader());
            if (t.muxAudioUrl != null) {
                runMuxTask(t);
            } else if (t.videoPageUrl != null) {
                DlHelper.Resolved r = DlHelper.resolveStream(t.videoPageUrl, t.quality);
                if (r == null) {
                    throw new Exception(ctx.getString(R.string.err_no_stream));
                }
                t.url = r.url;
                if (t.subLang != 0) t.pendingSubs = r.subtitles;
                // Append the container suffix only once: a retried/ resumed
                // task re-runs this resolve branch, and blind appending
                // produced "video.mp4.mp4".
                if (r.suffix != null && !r.suffix.isEmpty()
                        && !t.fileName.endsWith(r.suffix)) {
                    t.fileName = t.fileName + r.suffix;
                }
                save();
                if (r.muxAudioUrl != null) {
                    t.muxAudioUrl = r.muxAudioUrl;
                    save();
                    runMuxTask(t);
                } else {
                    downloadSegment(t, r.url, partFile(t), 0, 0);
                }
            } else {
                downloadSegment(t, t.url, partFile(t), 0, 0);
            }
        } catch (Exception e) {
            if (t.state == State.DOWNLOADING && t.wifiOnly
                    && !isWifiConnected(ctx)) {
                // Wi-Fi dropped mid-download: wait for it, don't fail.
                t.state = State.PAUSED;
                t.autoPaused = true;
                save();
                notifyChanged();
                return;
            }
            boolean handled = false;
            if (t.state == State.DOWNLOADING) {
                handled = try403Recovery(t, e);
            }
            if (!handled && t.state == State.DOWNLOADING) {
                t.state = State.FAILED;
                t.error = e.getMessage() == null
                        ? ctx.getString(R.string.err_unknown) : e.getMessage();
            }
        }
        if (t.state == State.DOWNLOADING) {
            try {
                if (t.muxAudioUrl != null) {
                    publishFile(t, new File(workDir(), t.id + ".mux.tmp"));
                } else {
                    publishFile(t, partFile(t));
                }
                t.state = State.COMPLETED;
                if (t.subLang != 0) downloadTaskSubtitles(t);
            } catch (Exception e) {
                t.state = State.FAILED;
                t.error = friendlyPublishError(e);
            }
        }
        save();
        notifyChanged();
    }

    /** HTTP 403 recovery: YouTube stream URLs are ephemeral (expire /
     *  IP-bound on mobile networks). On the first 403 we re-resolve fresh
     *  stream URLs and retry the download once. Returns true when the retry
     *  was attempted (its outcome stands). */
    private boolean try403Recovery(Task t, Exception e) {
        if (t.refreshed403) return false;
        String msg = e.getMessage();
        if (msg == null || !msg.contains("403")) return false;
        t.refreshed403 = true;

        boolean isMux = t.muxAudioUrl != null;
        String newVideo = null, newAudio = null;
        try {
            if (t.videoPageUrl != null && !t.videoPageUrl.isEmpty()) {
                // bulk task: re-resolve everything fresh
                DlHelper.Resolved r = DlHelper.resolveStream(t.videoPageUrl, t.quality);
                if (r == null) return false;
                newVideo = r.url;
                newAudio = r.muxAudioUrl;
            } else if (isMux && t.muxHeight > 0
                    && t.videoId != null && !t.videoId.isEmpty()) {
                // dialog mux task: re-resolve the same ladder rung
                DlHelper.MuxStreams ms =
                        DlHelper.resolveMuxStreams(t.videoId, t.muxHeight, t.muxWebm);
                if (ms == null) return false;
                newVideo = ms.videoUrl;
                newAudio = ms.audioUrl;
            } else {
                return false;
            }
        } catch (Exception ex) {
            return false;
        }
        if (newVideo == null) return false;
        t.url = newVideo;
        if (isMux && newAudio != null) {
            t.muxAudioUrl = newAudio;
            File vp = new File(workDir(), t.id + ".vpart");
            File ap = new File(workDir(), t.id + ".apart");
            vp.delete();
            ap.delete();
            deleteChunks(vp);
            deleteChunks(ap);
            new File(workDir(), t.id + ".mux.tmp").delete();
        } else {
            File pf = partFile(t);
            pf.delete();
            deleteChunks(pf);
        }
        t.done = 0;
        t.total = -1;
        t.error = "";
        save();
        notifyChanged();
        try {
            if (NewPipe.getDownloader() == null) NewPipe.init(new SimpleDownloader());
            if (isMux && t.muxAudioUrl != null) {
                runMuxTask(t);
            } else {
                downloadSegment(t, t.url, partFile(t), 0, 0);
            }
        } catch (Exception retryErr) {
            if (t.state == State.DOWNLOADING) {
                t.state = State.FAILED;
                t.error = retryErr.getMessage() == null
                        ? ctx.getString(R.string.err_unknown) : retryErr.getMessage();
            }
        }
        return true;
    }

    private void runMuxTask(Task t) throws Exception {
        File vp = new File(workDir(), t.id + ".vpart");
        File ap = new File(workDir(), t.id + ".apart");
        // Resume: a fully-downloaded part must not be fetched again. Its
        // chunks were merged into the part file and deleted, so the segment
        // downloader would otherwise start that part from zero (e.g. pausing
        // during the audio phase re-downloaded the whole video).
        if (!isPartComplete(t.url, vp)) {
            downloadSegment(t, t.url, vp, 0, 0);
            if (t.state != State.DOWNLOADING) return;
        }
        long vDone = vp.length();
        long vTotal = t.total > 0 ? t.total : vDone;
        if (!isPartComplete(t.muxAudioUrl, ap)) {
            downloadSegment(t, t.muxAudioUrl, ap, vDone, vTotal);
            if (t.state != State.DOWNLOADING) return;
        }
        File muxed = new File(workDir(), t.id + ".mux.tmp");
        if (muxed.exists()) muxed.delete();
        muxParts(vp, ap, muxed);
        vp.delete();
        ap.delete();
        deleteChunks(vp);
        deleteChunks(ap);
    }

    /** True when the part file already holds the full remote content. */
    private boolean isPartComplete(String fileUrl, File part) {
        try {
            if (part == null || !part.exists() || part.length() <= 0) return false;
            long total = DlHelper.probeSize(fileUrl);
            return total > 0 && part.length() >= total;
        } catch (Exception e) {
            return false;
        }
    }

    /** Downloads one file segment. Files >=1MB are split into parallel
     *  chunks (separate connections), which defeats per-connection throttling
     *  and saturates the link — the same technique SnapTube-style apps use. */
    private void downloadSegment(Task t, String fileUrl, File part,
                                 long baseDone, long baseTotal) throws Exception {
        long total = DlHelper.probeSize(fileUrl);
        if (total >= MIN_PARALLEL_BYTES) {
            try {
                downloadParallel(t, fileUrl, part, total, baseDone, baseTotal);
                return;
            } catch (NoRangeException e) {
                // server ignored Range: fall back to single connection
                deleteChunks(part);
            }
        }
        downloadSingle(t, fileUrl, part, baseDone, baseTotal);
    }

    /** Marker: the server doesn't honor Range requests (no parallel possible). */
    private static class NoRangeException extends Exception {}

    private File chunkFile(File part, int i) {
        return new File(part.getParent(), part.getName() + ".c" + i);
    }

    private void deleteChunks(File part) {
        File dir = part.getParentFile();
        if (dir == null) return;
        String prefix = part.getName() + ".c";
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().startsWith(prefix)) f.delete();
        }
    }

    private void downloadParallel(Task t, String fileUrl, File part, long total,
                                   long baseDone, long baseTotal) throws Exception {
        int n = (int) Math.min(PARALLEL_CHUNKS,
                Math.max(2, total / (2 * 1024 * 1024)));
        long[] cStart = new long[n];
        long[] cEnd = new long[n];
        for (int i = 0; i < n; i++) {
            cStart[i] = (total * i) / n;
            cEnd[i] = (total * (i + 1)) / n - 1;
        }
        // resume: count bytes already in chunk files
        AtomicLong doneBytes = new AtomicLong(0);
        for (int i = 0; i < n; i++) {
            File cf = chunkFile(part, i);
            if (cf.exists()) doneBytes.addAndGet(cf.length());
        }
        t.total = baseTotal + total;
        t.done = baseDone + doneBytes.get();
        notifyChanged();

        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                downloadChunk(t, fileUrl, chunkFile(part, idx),
                        cStart[idx], cEnd[idx], doneBytes, baseDone);
                return null;
            }));
        }
        pool.shutdown();
        Exception firstErr = null;
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException ee) {
                if (firstErr == null) {
                    Throwable c = ee.getCause();
                    firstErr = c instanceof Exception ? (Exception) c
                            : new Exception(c);
                }
                pool.shutdownNow();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
                if (firstErr == null) firstErr = ie;
            }
        }
        if (t.state != State.DOWNLOADING) return; // paused/cancelled: keep chunks
        if (firstErr != null) {
            if (firstErr instanceof NoRangeException) throw firstErr;
            throw firstErr;
        }
        // all chunks complete -> merge into the part file
        mergeChunks(part, n);
        t.done = baseDone + part.length();
        notifyChanged();
    }

    private void downloadChunk(Task t, String fileUrl, File chunkFile,
                               long chunkStart, long chunkEnd,
                               AtomicLong doneBytes, long baseDone) throws Exception {
        long chunkLen = chunkEnd - chunkStart + 1;
        long have = chunkFile.exists() ? chunkFile.length() : 0;
        if (have >= chunkLen) return; // chunk already complete
        long start = chunkStart + have;
        Exception lastErr = null;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (t.state != State.DOWNLOADING) return;
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(fileUrl).openConnection();
                con.setConnectTimeout(25000);
                con.setReadTimeout(25000);
                con.setRequestProperty("User-Agent", UA);
                con.setRequestProperty("Range", "bytes=" + start + "-" + chunkEnd);
                con.connect();
                int code = con.getResponseCode();
                if (code == 416) return; // chunk complete
                boolean fresh = false;
                if (code == 206) {
                    // server honored our range; append
                } else if (code == 200) {
                    if (start != chunkStart) throw new NoRangeException();
                    // server ignored Range on a fresh chunk: restart bounded
                    if (chunkFile.exists()) chunkFile.delete();
                    have = 0;
                    start = chunkStart;
                    fresh = true;
                } else {
                    throw new Exception("HTTP " + code);
                }
                InputStream in = con.getInputStream();
                OutputStream out = new FileOutputStream(chunkFile, !fresh && have > 0);
                byte[] buf = new byte[65536];
                int r;
                try {
                    while ((r = in.read(buf)) != -1) {
                        if (t.state != State.DOWNLOADING) return;
                        long remaining = chunkLen - have;
                        if (remaining <= 0) break;
                        int w = (int) Math.min(r, remaining);
                        out.write(buf, 0, w);
                        have += w;
                        doneBytes.addAndGet(w);
                        t.done = baseDone + doneBytes.get();
                        throttle();
                        if (have >= chunkLen) break;
                    }
                } finally {
                    try { out.close(); } catch (Exception ignored) {}
                    try { in.close(); } catch (Exception ignored) {}
                }
                if (have >= chunkLen) return; // chunk done
                lastErr = new Exception(ctx.getString(R.string.err_conn));
                start = chunkStart + chunkFile.length();
                have = chunkFile.length();
                sleepQuiet(2000);
            } catch (NoRangeException nre) {
                throw nre;
            } catch (Exception e) {
                if (t.state != State.DOWNLOADING) return;
                lastErr = e;
                have = chunkFile.exists() ? chunkFile.length() : 0;
                start = chunkStart + have;
                sleepQuiet(2000);
            } finally {
                if (con != null) con.disconnect();
            }
        }
        throw lastErr != null ? lastErr : new Exception(ctx.getString(R.string.err_dl_fail));
    }

    private void mergeChunks(File part, int n) throws Exception {
        File tmp = new File(part.getParent(), part.getName() + ".merge");
        if (tmp.exists()) tmp.delete();
        OutputStream out = new FileOutputStream(tmp);
        byte[] buf = new byte[65536];
        try {
            for (int i = 0; i < n; i++) {
                File cf = chunkFile(part, i);
                InputStream in = new FileInputStream(cf);
                try {
                    int r;
                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                } finally {
                    try { in.close(); } catch (Exception ignored) {}
                }
            }
        } finally {
            try { out.close(); } catch (Exception ignored) {}
        }
        if (part.exists()) part.delete();
        if (!tmp.renameTo(part)) {
            throw new Exception(ctx.getString(R.string.err_save));
        }
        deleteChunks(part);
    }

    private void downloadSingle(Task t, String fileUrl, File part,
                                 long baseDone, long baseTotal) throws Exception {
        long start = part.exists() ? part.length() : 0;
        t.done = baseDone + start;
        Exception lastErr = null;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (t.state != State.DOWNLOADING) return;
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(fileUrl).openConnection();
                con.setConnectTimeout(25000);
                con.setReadTimeout(25000);
                con.setRequestProperty("User-Agent", UA);
                if (start > 0) con.setRequestProperty("Range", "bytes=" + start + "-");
                con.connect();
                int code = con.getResponseCode();
                boolean append = false;
                if (code == 206) {
                    append = true;
                    long segTotal = parseTotal(con.getHeaderField("Content-Range"));
                    if (segTotal > 0) t.total = baseTotal + segTotal;
                } else if (code == 200) {
                    if (start > 0) { start = 0; t.done = baseDone; }
                    long segTotal = parseLong(con.getHeaderField("Content-Length"));
                    if (segTotal > 0) t.total = baseTotal + segTotal;
                } else if (code == 416) {
                    return; // already fully downloaded
                } else {
                    throw new Exception("HTTP " + code);
                }
                notifyChanged();
                InputStream in = con.getInputStream();
                OutputStream out = new FileOutputStream(part, append);
                byte[] buf = new byte[65536];
                int n;
                long segDone = start;
                t.done = baseDone + segDone;
                try {
                    while ((n = in.read(buf)) != -1) {
                        if (t.state != State.DOWNLOADING) return;
                        out.write(buf, 0, n);
                        segDone += n;
                        t.done = baseDone + segDone;
                        throttle();
                    }
                } finally {
                    try { out.close(); } catch (Exception ignored) {}
                    try { in.close(); } catch (Exception ignored) {}
                }
                long len = part.length();
                t.done = baseDone + len;
                long segTotal = t.total - baseTotal;
                if (segTotal > 0 && len < segTotal) {
                    start = len;
                    lastErr = new Exception(ctx.getString(R.string.err_conn));
                    sleepQuiet(2000);
                    continue;
                }
                return; // success
            } catch (Exception e) {
                if (t.state != State.DOWNLOADING) return;
                lastErr = e;
                start = part.exists() ? part.length() : 0;
                t.done = baseDone + start;
                sleepQuiet(2000);
            } finally {
                if (con != null) con.disconnect();
            }
        }
        throw lastErr != null ? lastErr : new Exception(ctx.getString(R.string.err_dl_fail));
    }

    // ---------- muxing (high qualities) ----------

    private void muxParts(File videoPart, File audioPart, File out) throws Exception {
        MediaExtractor vEx = new MediaExtractor();
        MediaExtractor aEx = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            vEx.setDataSource(videoPart.getAbsolutePath());
            aEx.setDataSource(audioPart.getAbsolutePath());
            int vIdx = selectTrack(vEx, "video/");
            int aIdx = selectTrack(aEx, "audio/");
            if (vIdx < 0 || aIdx < 0) {
                throw new Exception(ctx.getString(R.string.err_mux));
            }
            // VP9 video -> WebM container; AVC -> MP4 container.
            // AV1 is not muxable by MediaMuxer -> fail fast with a clear error.
            String vMime = vEx.getTrackFormat(vIdx).getString(MediaFormat.KEY_MIME);
            String vl = vMime == null ? "" : vMime.toLowerCase();
            if (vl.contains("av01") || vl.contains("av1")) {
                throw new Exception(ctx.getString(R.string.err_mux));
            }
            boolean vp = vl.contains("vp");
            muxer = new MediaMuxer(out.getAbsolutePath(),
                    vp ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                       : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vTrack = muxer.addTrack(vEx.getTrackFormat(vIdx));
            int aTrack = muxer.addTrack(aEx.getTrackFormat(aIdx));
            muxer.start();
            writeTrack(muxer, vEx, vIdx, vTrack);
            writeTrack(muxer, aEx, aIdx, aTrack);
            muxer.stop();
        } finally {
            if (muxer != null) try { muxer.release(); } catch (Exception ignored) {}
            try { vEx.release(); } catch (Exception ignored) {}
            try { aEx.release(); } catch (Exception ignored) {}
        }
    }

    private int selectTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private void writeTrack(MediaMuxer muxer, MediaExtractor ex,
                            int trackIdx, int muxTrack) {
        ex.selectTrack(trackIdx);
        ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            info.offset = 0;
            info.size = ex.readSampleData(buf, 0);
            if (info.size < 0) break;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = ex.getSampleFlags();
            muxer.writeSampleData(muxTrack, buf, info);
            ex.advance();
        }
        ex.unselectTrack(trackIdx);
    }

    /**
     * True when the app may write directly to the storage root
     * (user granted "All files access" on Android 11+). Then downloads
     * default to /<dir>/ in the main path instead of inside Downloads/.
     */
    public static boolean useRootStorage(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return Environment.isExternalStorageManager();
            }
        } catch (Exception ignored) {}
        return false;
    }

    private File rootDir(String dirName) {
        return new File(Environment.getExternalStorageDirectory(), dirName);
    }

    /** Direct publish to the storage root (All-files access granted). */
    private void publishViaRoot(Task t, File src) throws Exception {
        String dirName = targetDir(t);
        File dir = rootDir(dirName);
        if (t.subfolder != null && !t.subfolder.isEmpty()) {
            dir = new File(dir, t.subfolder); // playlist/channel folder
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception(ctx.getString(R.string.err_save));
        }
        File dest = unique(new File(dir, t.fileName));
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dest);
            copy(in, out);
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
        t.publishedUri = Uri.fromFile(dest).toString();
    }

    /** Recursive File scan of the storage-root folder. */
    private boolean scanRoot(String dirName, String kind) {
        boolean changed = false;
        try {
            changed = scanRootDir(rootDir(dirName), kind);
        } catch (Exception ignored) {}
        return changed;
    }

    private boolean scanRootDir(File dir, String kind) {
        boolean changed = false;
        try {
            File[] files = dir.listFiles();
            if (files == null) return false;
            for (File f : files) {
                if (f.isDirectory()) {
                    if (scanRootDir(f, kind)) changed = true;
                    continue;
                }
                if (adoptFile(f.getName(), Uri.fromFile(f).toString(),
                        f.length(), kind)) changed = true;
            }
        } catch (Exception ignored) {}
        return changed;
    }

    // ---------- publishing ----------

    private String targetDir(Task t) {
        return "audio".equals(t.kind) ? Prefs.audioDir(ctx) : Prefs.videoDir(ctx);
    }

    /**
     * Best-effort AR+EN subtitles for a finished bulk task. Each goes into a
     * "subtitles" folder inside the video's own subfolder:
     * <channel>/<playlist>/subtitles/<name>.ar.vtt ...
     * Never fails the video task.
     */
    private void downloadTaskSubtitles(Task t) {
        try {
            List<org.schabi.newpipe.extractor.stream.SubtitlesStream> subs =
                    t.pendingSubs;
            t.pendingSubs = null;
            if ((subs == null || subs.isEmpty()) && t.videoPageUrl != null
                    && !t.videoPageUrl.isEmpty()) {
                // Task resumed after restart: re-fetch (URLs are ephemeral).
                subs = DlHelper.fetchArEnSubtitles(t.videoPageUrl);
            }
            if (subs == null || subs.isEmpty()) return;
            String base = t.fileName == null ? "video" : t.fileName;
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            String subfolder = (t.subfolder != null && !t.subfolder.isEmpty()
                    ? t.subfolder + "/" : "") + "subtitles";
            for (org.schabi.newpipe.extractor.stream.SubtitlesStream st : subs) {
                try {
                    String tag = String.valueOf(st.getLanguageTag())
                            .toLowerCase(java.util.Locale.US);
                    boolean isAr = tag.startsWith("ar");
                    // Respect the user's language choice.
                    if (isAr && (t.subLang & 1) == 0) continue;
                    if (!isAr && (t.subLang & 2) == 0) continue;
                    String lang = isAr ? "ar" : "en";
                    String ext = st.getExtension();
                    if (ext == null || ext.isEmpty()) ext = "vtt";
                    String fname = base + "." + lang + "." + ext;
                    File tmp = new File(workDir(),
                            t.id + ".sub." + lang + ".tmp");
                    downloadUrlToFile(st.getContent(), tmp);
                    Task subTask = new Task();
                    subTask.kind = "video"; // published next to videos
                    subTask.fileName = fname;
                    subTask.subfolder = subfolder;
                    publishFile(subTask, tmp);
                    try { tmp.delete(); } catch (Exception ignored) {}
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /**
     * Locate a downloaded subtitle file for a video task.
     * Prefers Arabic, falls back to English. Returns null if none found.
     */
    public java.io.File findSubtitleFile(Task t) {
        if (t == null || t.fileName == null) return null;
        try {
            String base = t.fileName;
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            // 1) Next to the published file (file:// URIs: root/legacy).
            if (t.publishedUri != null
                    && t.publishedUri.startsWith("file://")) {
                java.io.File vf = new java.io.File(
                        android.net.Uri.parse(t.publishedUri).getPath());
                java.io.File subDir = new java.io.File(
                        vf.getParentFile(), "subtitles");
                java.io.File f = new java.io.File(subDir, base + ".ar.vtt");
                if (f.exists()) return f;
                f = new java.io.File(subDir, base + ".en.vtt");
                if (f.exists()) return f;
            }
            // 2) Reconstruct from prefs (root storage layout).
            if (ctx != null) {
                String dirName = com.flashytube.app.Prefs.videoDir(ctx);
                String sub = (t.subfolder != null && !t.subfolder.isEmpty()
                        ? t.subfolder + "/" : "") + "subtitles";
                java.io.File root = new java.io.File(
                        android.os.Environment.getExternalStorageDirectory(),
                        dirName + "/" + sub);
                java.io.File f = new java.io.File(root, base + ".ar.vtt");
                if (f.exists()) return f;
                f = new java.io.File(root, base + ".en.vtt");
                if (f.exists()) return f;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Minimal GET download for small files (subtitles). */
    private void downloadUrlToFile(String url, File dest) throws Exception {
        HttpURLConnection con = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(20000);
            con.setReadTimeout(20000);
            con.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
            int code = con.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            in = con.getInputStream();
            out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            if (con != null) try { con.disconnect(); } catch (Exception ignored) {}
        }
    }

    private void publishFile(Task t, File src) throws Exception {
        if (useRootStorage(ctx)) {
            publishViaRoot(t, src);
            return;
        }
        String tree = "audio".equals(t.kind) ? Prefs.audioTree(ctx) : Prefs.videoTree(ctx);
        if (tree != null && !tree.isEmpty()) {
            if (hasTreeWriteAccess(tree)) {
                publishViaTree(t, src, tree);
                return;
            }
            // The saved folder URI outlived its permission grant (uninstall /
            // data-clear / backup-restore revokes grants but the URI string may
            // survive). Forget it so we don't fail forever, fall back to the
            // default Downloads folder, and tell the user to re-pick.
            if ("audio".equals(t.kind)) Prefs.setAudioTree(ctx, "");
            else Prefs.setVideoTree(ctx, "");
            toastTreeLost();
        }
        String dirName = targetDir(t);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, t.fileName);
            cv.put(MediaStore.Downloads.MIME_TYPE, DlHelper.guessMime(t.fileName));
            String relPath = Environment.DIRECTORY_DOWNLOADS + "/" + dirName;
            if (t.subfolder != null && !t.subfolder.isEmpty()) {
                relPath += "/" + t.subfolder; // playlist/channel folder
            }
            cv.put(MediaStore.Downloads.RELATIVE_PATH, relPath);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception(ctx.getString(R.string.err_save));
            // Streams are opened INSIDE the try: if openOutputStream throws,
            // an already-opened input stream must not leak its FD.
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new FileInputStream(src);
                out = cr.openOutputStream(uri);
                copy(in, out);
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                try { if (out != null) out.close(); } catch (Exception ignored) {}
            }
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, cv, null, null);
            t.publishedUri = uri.toString();
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), dirName);
            if (t.subfolder != null && !t.subfolder.isEmpty()) {
                dir = new File(dir, t.subfolder); // playlist/channel folder
            }
            if (!dir.exists()) dir.mkdirs();
            File dest = unique(new File(dir, t.fileName));
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new FileInputStream(src);
                out = new FileOutputStream(dest);
                copy(in, out);
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                try { if (out != null) out.close(); } catch (Exception ignored) {}
            }
            t.publishedUri = Uri.fromFile(dest).toString();
        }
        src.delete();
    }

    /** Do we still hold the persistable SAF grant for this tree? Grants die on
     *  uninstall / data-clear / backup-restore while the saved URI string may
     *  survive — writing then throws SecurityException (Permission Denial). */
    private boolean hasTreeWriteAccess(String treeUriStr) {
        try {
            Uri treeUri = Uri.parse(treeUriStr);
            for (UriPermission p : ctx.getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(treeUri) && p.isWritePermission()) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void toastTreeLost() {
        try {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(ctx, R.string.toast_tree_lost,
                            Toast.LENGTH_LONG).show());
        } catch (Exception ignored) {}
    }

    /** Translates storage permission failures into plain Arabic. */
    private String friendlyPublishError(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof SecurityException || m.contains("Permission Denial")
                || m.contains("MANAGE_DOCUMENTS") || m.contains("grantUriPermission")) {
            return ctx.getString(R.string.err_tree_permission);
        }
        return m.isEmpty() || "null".equals(m)
                ? ctx.getString(R.string.err_save) : m;
    }

    /** Write the finished file into a user-picked folder (Storage Access Framework). */
    private void publishViaTree(Task t, File src, String treeUriStr) throws Exception {
        Uri treeUri = Uri.parse(treeUriStr);
        ContentResolver cr = ctx.getContentResolver();
        String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId);
        String parentId = treeDocId;
        if (t.subfolder != null && !t.subfolder.isEmpty()) {
            // Nested subfolders ("channel/playlist/subtitles"): create level by level.
            for (String part : t.subfolder.split("/")) {
                if (part == null || part.isEmpty()) continue;
                dirUri = findOrCreateDir(cr, treeUri, dirUri, part);
            }
            parentId = DocumentsContract.getDocumentId(dirUri);
        }
        String mime = DlHelper.guessMime(t.fileName);
        if (mime == null) mime = "application/octet-stream";
        String name = uniqueDocName(cr, treeUri, parentId, t.fileName);
        Uri fileUri = DocumentsContract.createDocument(cr, dirUri, mime, name);
        if (fileUri == null) throw new Exception(ctx.getString(R.string.err_save));
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = cr.openOutputStream(fileUri);
            if (out == null) throw new Exception(ctx.getString(R.string.err_save));
            copy(in, out);
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
        t.publishedUri = fileUri.toString();
        src.delete();
    }

    /** Find a child directory by name under parent, or create it. */
    private Uri findOrCreateDir(ContentResolver cr, Uri treeUri,
                                Uri parentUri, String name) throws Exception {
        String parentId = DocumentsContract.getDocumentId(parentUri);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        android.database.Cursor c = null;
        try {
            c = cr.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                 DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                 DocumentsContract.Document.COLUMN_MIME_TYPE},
                    null, null, null);
            if (c != null) {
                int idIdx = c.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameIdx = c.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeIdx = c.getColumnIndex(
                        DocumentsContract.Document.COLUMN_MIME_TYPE);
                while (c.moveToNext()) {
                    if (name.equals(c.getString(nameIdx))
                            && DocumentsContract.Document.MIME_TYPE_DIR
                                    .equals(c.getString(mimeIdx))) {
                        return DocumentsContract.buildDocumentUriUsingTree(
                                treeUri, c.getString(idIdx));
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) {}
        }
        Uri created = DocumentsContract.createDocument(cr, parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) throw new Exception(ctx.getString(R.string.err_save));
        return created;
    }

    private String uniqueDocName(ContentResolver cr, Uri treeUri,
                                 String parentId, String name) {
        java.util.Set<String> existing = new java.util.HashSet<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        android.database.Cursor c = null;
        try {
            c = cr.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                while (c.moveToNext()) existing.add(c.getString(idx));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) {}
        }
        if (!existing.contains(name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 1;
        String cand;
        do {
            cand = base + " (" + i + ")" + ext;
            i++;
        } while (existing.contains(cand));
        return cand;
    }

    // ---------- helpers ----------

    private File workDir() { return ctx.getExternalFilesDir("downloads"); }

    private File partFile(Task t) { return new File(workDir(), t.id + ".part"); }

    private File unique(File f) {
        if (!f.exists()) return f;
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 1;
        File c;
        do { c = new File(f.getParent(), base + " (" + i + ")" + ext); i++; }
        while (c.exists());
        return c;
    }

    private void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.flush();
    }

    private long parseLong(String v) {
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return -1; }
    }

    private long parseTotal(String contentRange) {
        // "bytes 100-199/1000" -> 1000
        try {
            int slash = contentRange.lastIndexOf('/');
            if (slash >= 0) return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (Exception ignored) {}
        return -1;
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void throttle() {
        long now = System.currentTimeMillis();
        if (now - lastNotify > 500) { lastNotify = now; notifyChanged(); }
        if (now - lastSave > 15000) { lastSave = now; save(); }
    }

    private void notifyChanged() {
        main.post(() -> {
            synchronized (listeners) {
                for (Listener l : listeners) {
                    try { l.onChanged(); } catch (Exception ignored) {}
                }
            }
        });
    }

    // ---------- persistence ----------

    private File stateFile() { return new File(ctx.getFilesDir(), "downloads.json"); }

    private synchronized void save() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (tasks) {
                for (Task t : tasks) {
                    JSONObject o = new JSONObject();
                    o.put("id", t.id);
                    o.put("url", t.url == null ? "" : t.url);
                    o.put("muxAudioUrl", t.muxAudioUrl == null ? "" : t.muxAudioUrl);
                    o.put("videoPageUrl", t.videoPageUrl == null ? "" : t.videoPageUrl);
                    o.put("quality", t.quality);
                    o.put("kind", t.kind == null ? "video" : t.kind);
                    o.put("fileName", t.fileName);
                    o.put("videoId", t.videoId == null ? "" : t.videoId);
                    o.put("durationSec", t.durationSec);
                    o.put("publishedUri", t.publishedUri == null ? "" : t.publishedUri);
                    o.put("total", t.total);
                    o.put("done", t.done);
                    o.put("state", t.state.name());
                    o.put("error", t.error == null ? "" : t.error);
                    o.put("muxHeight", t.muxHeight);
                    o.put("muxWebm", t.muxWebm);
                    o.put("subfolder", t.subfolder == null ? "" : t.subfolder);
                    o.put("wifiOnly", t.wifiOnly);
                    o.put("subLang", t.subLang);
                    arr.put(o);
                }
            }
            FileOutputStream fos = new FileOutputStream(stateFile());
            try {
                fos.write(arr.toString().getBytes("UTF-8"));
            } finally { try { fos.close(); } catch (Exception ignored) {} }
        } catch (Exception ignored) {}
    }

    private void load() {
        File f = stateFile();
        if (!f.exists()) return;
        try {
            FileInputStream fis = new FileInputStream(f);
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = fis.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
            } finally { try { fis.close(); } catch (Exception ignored) {} }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Task t = new Task();
                t.id = o.optString("id", UUID.randomUUID().toString());
                String u = o.optString("url", "");
                t.url = u.isEmpty() ? null : u;
                String m = o.optString("muxAudioUrl", "");
                t.muxAudioUrl = m.isEmpty() ? null : m;
                String p = o.optString("videoPageUrl", "");
                t.videoPageUrl = p.isEmpty() ? null : p;
                t.quality = o.optInt("quality", 0);
                t.kind = o.optString("kind", "video");
                t.fileName = o.optString("fileName", "file");
                String vid = o.optString("videoId", "");
                t.videoId = vid.isEmpty() ? null : vid;
                t.durationSec = o.optLong("durationSec", 0);
                t.publishedUri = o.optString("publishedUri", "");
                t.total = o.optLong("total", -1);
                t.error = o.optString("error", "");
                t.muxHeight = o.optInt("muxHeight", 0);
                t.muxWebm = o.optBoolean("muxWebm", false);
                String sf = o.optString("subfolder", "");
                t.subfolder = sf.isEmpty() ? null : sf;
                t.wifiOnly = o.optBoolean("wifiOnly", false);
                t.subLang = o.optInt("subLang", 0);
                File part = partFile(t);
                t.done = part.exists() ? part.length() : 0;
                String st = o.optString("state", "PAUSED");
                if ("COMPLETED".equals(st)) {
                    t.state = State.COMPLETED;
                    t.done = t.total;
                } else {
                    t.state = State.PAUSED; // interrupted -> user resumes manually
                }
                tasks.add(t);
            }
        } catch (Exception ignored) {}
    }
}
