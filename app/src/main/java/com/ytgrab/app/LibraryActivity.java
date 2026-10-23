package com.ytgrab.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Play tab with sub-tabs: Running | Videos | Audio (SnapTube-style library). */
public class LibraryActivity extends Activity {
    private boolean appliedDark;

    private static final int SUB_RUNNING = 0;
    private static final int SUB_VIDEOS = 1;
    private static final int SUB_AUDIO = 2;
    private static final int SUB_SUBS = 3;
    private static final int SUB_FAVORITES = 4;
    private static final int REQ_EXPORT_FAV = 201;
    private static final int REQ_IMPORT_FAV = 202;

    private ListView list;
    private BaseAdapter curAdapter;
    private DlAdapter dlAdapter;
    private FavAdapter favAdapter;
    private MiniBar miniBar;
    private int subTab = SUB_VIDEOS;
    private Button tabRunning, tabVideos, tabAudio, tabSubs, tabFav;
    private String libFilter = "";
    private final DlEngine.Listener listener =
            () -> runOnUiThread(this::refresh);
    private SwipeListView.OnSwipeListener swipeNav;
    private GestureDetector swipeDetector;
    private boolean swipeTracking = false;
    private final int[] swipeXY = new int[2];

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Lang.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ThemeHelper.apply(this);
        appliedDark = ThemeHelper.isDark(this);
        setContentView(R.layout.activity_library);

        Tabs.bind(this, Tabs.TAB_LIBRARY);
        miniBar = MiniBar.bind(this);

        tabRunning = findViewById(R.id.subRunning);
        tabVideos = findViewById(R.id.subVideos);
        tabAudio = findViewById(R.id.subAudio);
        tabSubs = findViewById(R.id.subSubs);
        tabFav = findViewById(R.id.subFav);
        tabRunning.setOnClickListener(v -> selectSub(SUB_RUNNING));
        tabVideos.setOnClickListener(v -> selectSub(SUB_VIDEOS));
        tabAudio.setOnClickListener(v -> selectSub(SUB_AUDIO));
        tabSubs.setOnClickListener(v -> selectSub(SUB_SUBS));
        tabFav.setOnClickListener(v -> selectSub(SUB_FAVORITES));

        // Header icon actions (per sub-tab, shown/hidden in refresh()).
        findViewById(R.id.hdrPauseAll).setOnClickListener(
                v -> DlEngine.get(this).pauseAll());
        findViewById(R.id.hdrResumeAll).setOnClickListener(
                v -> DlEngine.get(this).resumeAll());
        findViewById(R.id.hdrExportFav).setOnClickListener(v -> {
            if (Prefs.favorites(this).isEmpty()) {
                android.widget.Toast.makeText(this, R.string.toast_no_favorites,
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, "flashytube-favorites.json");
            startActivityForResult(i, REQ_EXPORT_FAV);
        });
        findViewById(R.id.hdrImportFav).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            startActivityForResult(i, REQ_IMPORT_FAV);
        });

        list = findViewById(R.id.libList);

        // Long-press a download: multi-select mode (bulk delete).
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (dlAdapter != null && !dlAdapter.isSelMode()
                    && curAdapter.getItem(pos) instanceof DlEngine.Task) {
                dlAdapter.setSelMode(true);
                dlAdapter.toggleSel(((DlEngine.Task) curAdapter.getItem(pos)).id);
                return true;
            }
            return false;
        });

        // Multi-select action bar.
        findViewById(R.id.delSelBtn).setOnClickListener(v -> {
            if (dlAdapter == null || dlAdapter.selCount() == 0) {
                exitSelMode();
                return;
            }
            int n = dlAdapter.selCount();
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.confirm_del_selected, n))
                    .setPositiveButton(R.string.menu_delete, (d, w) -> {
                        for (String tid : dlAdapter.getSelIds()) {
                            DlEngine.get(this).deleteTask(tid);
                        }
                        exitSelMode();
                    })
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show();
        });
        findViewById(R.id.cancelSelBtn).setOnClickListener(v -> exitSelMode());
        findViewById(R.id.pauseSelBtn).setOnClickListener(v -> {
            if (dlAdapter == null) return;
            DlEngine eng = DlEngine.get(this);
            for (String tid : dlAdapter.getSelIds()) eng.pause(tid);
            dlAdapter.notifyDataSetChanged();
            updateSelBar();
        });
        findViewById(R.id.resumeSelBtn).setOnClickListener(v -> {
            if (dlAdapter == null) return;
            DlEngine eng = DlEngine.get(this);
            for (String tid : dlAdapter.getSelIds()) eng.resume(tid);
            dlAdapter.notifyDataSetChanged();
            updateSelBar();
        });

        // Swipe left/right anywhere on the list switches sub-tabs
        // (Running ↔ Videos ↔ Audio ↔ Favorites). SwipeListView observes touches
        // passively, so scrolling and row buttons are unaffected.
        // Sub-tab swipe: captured at the activity level so it works identically
        // whether the list has items or the empty state is showing. Touches
        // starting on the top controls (chips / search) are left alone.
        swipeNav = new SwipeListView.OnSwipeListener() {
            @Override public void onSwipeLeft() {
                if (subTab < SUB_FAVORITES) selectSub(subTab + 1);
            }
            @Override public void onSwipeRight() {
                if (subTab > SUB_RUNNING) selectSub(subTab - 1);
            }
        };
        swipeDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float vx, float vy) {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (Math.abs(dx) < Math.abs(dy) * 1.5f) return false;
                        if (Math.abs(dx) < 120 || Math.abs(vx) < 200) return false;
                        if (dx < 0) swipeNav.onSwipeLeft();
                        else swipeNav.onSwipeRight();
                        return true;
                    }
                });

        if ("favorites".equals(getIntent().getStringExtra("sub"))) selectSub(SUB_FAVORITES);
        else selectSub(defaultSub());

        // Search-as-you-type filter for the current sub-tab.
        EditText libSearch = findViewById(R.id.libSearch);
        libSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                libFilter = s.toString();
                if (dlAdapter != null) dlAdapter.setFilter(libFilter);
                if (favAdapter != null) favAdapter.setFilter(libFilter);
                refresh();
            }
        });
    }

    /** Default sub-tab: Running when something is active, else Videos. */
    private int defaultSub() {
        List<DlEngine.Task> tasks = DlEngine.get(this).getTasks();
        synchronized (tasks) {
            for (DlEngine.Task t : tasks) {
                if (t.state != DlEngine.State.COMPLETED) return SUB_RUNNING;
            }
        }
        return SUB_VIDEOS;
    }

    /**
     * Feed the swipe detector before any child sees the touch, so sub-tab
     * swiping works the same on a full list and on the empty state.
     * Gestures starting on the top controls (chips / search) are ignored
     * so chip scrolling and text selection keep working.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        try {
            if (swipeDetector != null) {
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    swipeTracking = inContentZone(ev);
                }
                if (swipeTracking) {
                    swipeDetector.onTouchEvent(ev);
                    if (action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL) {
                        swipeTracking = false;
                    }
                }
            }
        } catch (Exception ignored) {}
        return super.dispatchTouchEvent(ev);
    }

    /** True when the touch starts inside the list/empty area (below search). */
    private boolean inContentZone(MotionEvent ev) {
        View content = findViewById(R.id.libContent);
        View search = findViewById(R.id.libSearch);
        if (content == null || search == null) return false;
        content.getLocationOnScreen(swipeXY);
        float bottom = swipeXY[1] + content.getHeight();
        search.getLocationOnScreen(swipeXY);
        float top = swipeXY[1] + search.getHeight();
        float y = ev.getRawY();
        return y >= top && y <= bottom;
    }

    private void selectSub(int sub) {
        exitSelMode();
        subTab = sub;
        dlAdapter = null;
        favAdapter = null;
        if (sub == SUB_FAVORITES) {
            favAdapter = new FavAdapter(this);
            favAdapter.setFilter(libFilter);
            curAdapter = favAdapter;
        } else {
            int mode;
            if (sub == SUB_RUNNING) mode = DlAdapter.MODE_ACTIVE;
            else if (sub == SUB_AUDIO) mode = DlAdapter.MODE_COMPLETED_AUDIO;
            else if (sub == SUB_SUBS) mode = DlAdapter.MODE_COMPLETED_SUB;
            else mode = DlAdapter.MODE_COMPLETED_VIDEO;

            dlAdapter = new DlAdapter(this, mode);
            dlAdapter.setFilter(libFilter);
            dlAdapter.setSelListener(count -> updateSelBar());
            curAdapter = dlAdapter;
            if (sub == SUB_VIDEOS) {
                dlAdapter.setTapListener(t -> {
                    ArrayList<String> queue = new ArrayList<>();
                    int idx = 0;
                    List<DlEngine.Task> tasks = DlEngine.get(this).getTasks();
                    synchronized (tasks) {
                        for (DlEngine.Task x : tasks) {
                            if (x.state == DlEngine.State.COMPLETED && "video".equals(x.kind)) {
                                if (x.id.equals(t.id)) idx = queue.size();
                                queue.add(x.id);
                            }
                        }
                    }
                    Intent i = new Intent(this, PlayerActivity.class);
                    i.putExtra("task_id", t.id);
                    i.putStringArrayListExtra("queue", queue);
                    i.putExtra("queue_index", idx);
                    startActivity(i);
                });
            } else if (sub == SUB_AUDIO) {
                dlAdapter.setTapListener(t -> {
                    AudioPlayer.get(this).play(t);
                    if (miniBar != null) miniBar.onResume();
                });
            } else if (sub == SUB_SUBS) {
                // Subtitle files open externally (text viewer), never the player.
                dlAdapter.setTapListener(t -> DlHelper.openCompleted(this, t));
            }
            // SUB_RUNNING: default tap behavior (pause/resume buttons in rows).
        }
        list.setAdapter(curAdapter);
        paintSubTabs();
        refresh();
    }

    private void paintSubTabs() {
        styleLibChip(tabRunning, subTab == SUB_RUNNING);
        styleLibChip(tabVideos, subTab == SUB_VIDEOS);
        styleLibChip(tabAudio, subTab == SUB_AUDIO);
        styleLibChip(tabSubs, subTab == SUB_SUBS);
        styleLibChip(tabFav, subTab == SUB_FAVORITES);
    }

    /** Modern pill chip: red when active, subtle gray when idle. */
    private void styleLibChip(Button b, boolean sel) {
        float d = getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(20 * d);
        if (sel) {
            bg.setColor(0xFFE53935);
            b.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(0x1A000000);
            try {
                b.setTextColor(ThemeHelper.attrColor(this, R.attr.colorOnSurfaceVariant));
            } catch (Exception ignored) {}
        }
        b.setBackground(bg);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedDark != ThemeHelper.isDark(this)) { recreate(); return; }
        DlEngine.get(this).addListener(listener);
        // Pick up files already in the download folders (e.g. after reinstall).
        DlEngine.get(this).scanExistingFiles();
        if (miniBar != null) miniBar.onResume();
        refresh();
    }

    private String lastStorageKey = "";

    /** "N items • X GB" next to the search box: only the current tab's kind. */
    private void updateStorageInfo() {
        final TextView tv = findViewById(R.id.storageInfo);
        final String wantKind = subTab == SUB_AUDIO ? "audio"
                : subTab == SUB_SUBS ? "sub" : "video";
        // Skip when nothing completed/removed since the last compute.
        StringBuilder key = new StringBuilder(wantKind);
        List<DlEngine.Task> tasks0 = DlEngine.get(this).getTasks();
        synchronized (tasks0) {
            for (DlEngine.Task t : tasks0) {
                if (t.state == DlEngine.State.COMPLETED && wantKind.equals(t.kind)) {
                    key.append(t.id);
                }
            }
        }
        if (key.toString().equals(lastStorageKey)) return;
        lastStorageKey = key.toString();
        new Thread(() -> {
            long bytes = 0;
            int n = 0;
            List<DlEngine.Task> tasks1 = DlEngine.get(this).getTasks();
            synchronized (tasks1) {
                for (DlEngine.Task t : tasks1) {
                    if (t.state != DlEngine.State.COMPLETED) continue;
                    if (!wantKind.equals(t.kind)) continue;
                    n++;
                    long s = t.total > 0 ? t.total
                            : DlHelper.publishedSize(this, t.publishedUri);
                    if (s > 0) bytes += s;
                }
            }
            final String txt = getString(R.string.storage_info, n,
                    DlHelper.fmtSize(bytes));
            runOnUiThread(() -> tv.setText(txt));
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        DlEngine.get(this).removeListener(listener);
        if (miniBar != null) miniBar.onPause();
    }

    /** Multi-select action bar for downloads (long-press a row). */
    private void updateSelBar() {
        boolean on = dlAdapter != null && dlAdapter.isSelMode();
        findViewById(R.id.selBar).setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            ((android.widget.Button) findViewById(R.id.delSelBtn))
                    .setText(getString(R.string.del_selected, dlAdapter.selCount()));
            // Pause/Resume only make sense for tasks that can actually change
            // state — hide them when the selection has nothing pausable or
            // resumable (e.g. completed downloads).
            boolean canPause = false, canResume = false;
            java.util.Set<String> ids = dlAdapter.getSelIds();
            List<DlEngine.Task> tasks2 = DlEngine.get(this).getTasks();
            synchronized (tasks2) {
                for (DlEngine.Task t : tasks2) {
                    if (!ids.contains(t.id)) continue;
                    if (t.state == DlEngine.State.QUEUED
                            || t.state == DlEngine.State.DOWNLOADING) canPause = true;
                    if (t.state == DlEngine.State.PAUSED
                            || t.state == DlEngine.State.FAILED) canResume = true;
                }
            }
            findViewById(R.id.pauseSelBtn).setVisibility(
                    canPause ? View.VISIBLE : View.GONE);
            findViewById(R.id.resumeSelBtn).setVisibility(
                    canResume ? View.VISIBLE : View.GONE);
        }
    }

    private void exitSelMode() {
        if (dlAdapter != null) dlAdapter.setSelMode(false);
        View bar = findViewById(R.id.selBar);
        if (bar != null) bar.setVisibility(View.GONE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req == REQ_EXPORT_FAV) {
            writeFavoritesExport(data.getData());
        } else if (req == REQ_IMPORT_FAV) {
            readFavoritesImport(data.getData());
        }
    }

    /** Export favorites as JSON — the user picks where to save it. */
    private void writeFavoritesExport(android.net.Uri uri) {
        new Thread(() -> {
            boolean ok = false;
            try {
                org.json.JSONObject root = new org.json.JSONObject();
                root.put("app", "FlashyTube");
                root.put("version", 1);
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String e : Prefs.favorites(this)) {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("type", Prefs.favType(e));
                    o.put("name", Prefs.favName(e));
                    o.put("url", Prefs.favUrl(e));
                    o.put("thumb", Prefs.favThumb(e));
                    arr.put(o);
                }
                root.put("favorites", arr);
                java.io.OutputStream out =
                        getContentResolver().openOutputStream(uri);
                try {
                    if (out == null) throw new java.io.IOException("no stream");
                    out.write(root.toString(2).getBytes("UTF-8"));
                } finally {
                    try { if (out != null) out.close(); }
                    catch (Exception ignored) {}
                }
                ok = true;
            } catch (Exception ignored) {}
            final boolean fOk = ok;
            runOnUiThread(() -> android.widget.Toast.makeText(this,
                    fOk ? R.string.toast_exported : R.string.toast_export_fail,
                    android.widget.Toast.LENGTH_LONG).show());
        }).start();
    }

    /**
     * Import into favorites. Accepts:
     * - our export: {"app":"FlashyTube","favorites":[{type,name,url,thumb}]}
     * - our old channels export: {"app":"FlashyTube","channels":[{name,url}]}
     * - YouTube Takeout subscriptions (CSV / OPML): channel links -> channel favorites
     */
    private void readFavoritesImport(android.net.Uri uri) {
        new Thread(() -> {
            int added = 0;
            try {
                java.io.InputStream in =
                        getContentResolver().openInputStream(uri);
                if (in == null) return;
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(in, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                try {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                } finally {
                    try { br.close(); } catch (Exception ignored) {}
                }
                String text = sb.toString();
                if (text.trim().startsWith("{")) {
                    org.json.JSONObject root = new org.json.JSONObject(text);
                    if (root.has("favorites")) {
                        org.json.JSONArray arr = root.getJSONArray("favorites");
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject o = arr.getJSONObject(i);
                            String url = o.optString("url", "");
                            if (!url.isEmpty() && Prefs.addFavorite(this,
                                    o.optString("type", "video"),
                                    o.optString("name", ""), url,
                                    o.optString("thumb", ""))) {
                                added++;
                            }
                        }
                    } else if (root.has("channels")) {
                        org.json.JSONArray arr = root.getJSONArray("channels");
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject o = arr.getJSONObject(i);
                            String url = o.optString("url", "");
                            String name = o.optString("name", "");
                            if (name.isEmpty()) name = Prefs.labelFromUrl(url);
                            if (!url.isEmpty() && Prefs.addFavorite(
                                    this, "channel", name, url, "")) {
                                added++;
                            }
                        }
                    }
                } else {
                    // Takeout CSV / OPML: pull channel links out of the text.
                    for (String[] e : extractChannelLinks(text)) {
                        if (Prefs.addFavorite(this, "channel", e[0], e[1], "")) {
                            added++;
                        }
                    }
                }
            } catch (Exception ignored) {}
            final int n = added;
            runOnUiThread(() -> {
                android.widget.Toast.makeText(this,
                        getString(R.string.toast_fav_imported, n),
                        android.widget.Toast.LENGTH_LONG).show();
                if (favAdapter != null) favAdapter.notifyDataSetChanged();
                refresh();
            });
        }).start();
    }

    /** Pulls channel urls (+ best-guess titles) out of a Takeout CSV/OPML dump. */
    private java.util.List<String[]> extractChannelLinks(String text) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "https?://(?:www\\.)?youtube\\.com/(?:@[\\w.\\-]+|channel/[\\w\\-]+"
                        + "|c/[\\w.\\-]+|user/[\\w.\\-]+)");
        for (String ln : text.split("\n")) {
            java.util.regex.Matcher m = p.matcher(ln);
            if (!m.find()) continue;
            String url = m.group();
            String rest = ln.substring(m.end())
                    .replaceAll("^[\\s,\"]+", "").replaceAll("[\\s\",]+$", "");
            String name = rest.isEmpty() ? Prefs.labelFromUrl(url) : rest;
            out.add(new String[]{name, url});
        }
        return out;
    }

    private void refresh() {
        if (curAdapter == null) return;
        curAdapter.notifyDataSetChanged();
        boolean empty = curAdapter.getCount() == 0;
        list.setVisibility(empty ? View.GONE : View.VISIBLE);
        View emptyBox = findViewById(R.id.libEmpty);
        emptyBox.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            TextView emptyTv = findViewById(R.id.libEmptyText);
            emptyTv.setText(subTab == SUB_RUNNING ? R.string.library_empty_running
                    : subTab == SUB_FAVORITES ? R.string.fav_empty
                    : subTab == SUB_SUBS ? R.string.library_empty_subs
                    : R.string.library_empty);
        }
        // Header: per-tab info/actions next to the search box.
        boolean running = subTab == SUB_RUNNING;
        boolean fav = subTab == SUB_FAVORITES;
        findViewById(R.id.storageInfo).setVisibility(
                (!running && !fav) ? View.VISIBLE : View.GONE);
        findViewById(R.id.hdrPauseAll).setVisibility(
                running ? View.VISIBLE : View.GONE);
        findViewById(R.id.hdrResumeAll).setVisibility(
                running ? View.VISIBLE : View.GONE);
        findViewById(R.id.hdrImportFav).setVisibility(
                fav ? View.VISIBLE : View.GONE);
        findViewById(R.id.hdrExportFav).setVisibility(
                fav ? View.VISIBLE : View.GONE);
        if (!running && !fav) updateStorageInfo();
    }
}
