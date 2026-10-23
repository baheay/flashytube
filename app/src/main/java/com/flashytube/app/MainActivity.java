package com.flashytube.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Home tab: link field + inline YouTube search results on the same screen. */
public class MainActivity extends Activity {
    private boolean appliedDark;

    private MiniBar miniBar;
    private EditText input;
    private ListView searchList;
    private SrAdapter adapter;
    private View loadFooter; // spinner shown at the list bottom while a page loads
    private boolean loadingMore = false;

    private final List<InfoItem> items = new ArrayList<>();
    private boolean showingSuggestions = false;
    private SearchExtractor extractor;
    private Page nextPage;

    // Multi-select (long-press a video) and inline channel/playlist browsing.
    private boolean selectionMode = false;
    private final Set<Integer> selectedPos = new HashSet<>();
    private boolean browseMode = false;
    private final List<InfoItem> savedItems = new ArrayList<>();
    private DlHelper.BrowseSession browseSession;
    /** True when launched straight into a shared channel/playlist (open_url). */
    private boolean launchedFromShare = false;
    private Button browseBackBtn;
    private View browseBar;
    private android.widget.LinearLayout browseTabs;
    private View bottomBar;
    private Button dlSelectedBtn;

    // Search filters: result type (server-side) + duration (client-side).
    private String searchType = "all";
    private int durFilter = 0; // 0 all, 1 short <4m, 2 medium 4-20m, 3 long >20m
    private String lastQuery = "";
    private View filterRow;
    private int chipTextColor;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Lang.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle b) {
        CrashLog.install(this);
        GoogleLogin.initOnUiThread();
        super.onCreate(b);
        ThemeHelper.apply(this);
        appliedDark = ThemeHelper.isDark(this);
        setContentView(R.layout.activity_main);

        // One-time offer: save to the storage root instead of Downloads.
        maybeOfferRootStorage();
        CrashLog.offerShareIfPresent(this);

        // Tapping the app name opens the Telegram channel.
        findViewById(R.id.appTitle).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://t.me/flashytube")));
            } catch (Exception ignored) {}
        });

        input = findViewById(R.id.urlInput);
        // SEARCH: YouTube link (video/playlist/channel/...) -> download flow,
        // plain text -> inline search results.
        // Tapping the field shows the recent-searches dropdown.
        input.setOnFocusChangeListener((v, has) -> {
            if (has) showRecentSearches(); else dismissRecentPopup();
        });
        input.setOnClickListener(v -> showRecentSearches());

        // Live YouTube suggestions while typing (debounced). Tapping one
        // searches it immediately.
        final android.os.Handler suggestHandler = new android.os.Handler();
        final Runnable[] suggestPending = new Runnable[1];
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(
                    CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {}
            @Override public void onTextChanged(
                    CharSequence s, int a, int b, int c) {
                if (suppressSuggest) { suppressSuggest = false; return; }
                if (suggestPending[0] != null) {
                    suggestHandler.removeCallbacks(suggestPending[0]);
                }
                final String q = s.toString().trim();
                if (q.length() < 2 || isUrlLike(q)) {
                    if (q.isEmpty()) showRecentSearches();
                    else dismissRecentPopup();
                    return;
                }
                suggestPending[0] = () -> fetchSuggestions(q);
                suggestHandler.postDelayed(suggestPending[0], 350);
            }
        });

        // Search tucked inside the link field: URL -> quality dialog,
        // otherwise results appear inline below (no separate page).
        Button inlineSearch = findViewById(R.id.inlineSearchBtn);
        inlineSearch.setOnClickListener(v -> doInlineSearch());
        input.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doInlineSearch();
                return true;
            }
            return false;
        });

        // Inline search results.
        searchList = findViewById(R.id.searchList);
        // Loading spinner footer (visible only while a next page loads).
        ProgressBar fpb = new ProgressBar(this);
        int fpad = Math.round(14 * getResources().getDisplayMetrics().density);
        fpb.setPadding(0, fpad, 0, fpad);
        loadFooter = fpb;
        loadFooter.setVisibility(View.GONE);
        searchList.addFooterView(loadFooter, null, false);
        adapter = new SrAdapter(this);
        searchList.setAdapter(adapter);
        // Home suggestions: show on launch before any search.
        android.widget.Button refreshSug =
                findViewById(R.id.refreshSugBtn);
        refreshSug.setOnClickListener(v -> {
            items.clear();
            adapter.notifyDataSetChanged();
            loadHomeSuggestions();
        });
        loadHomeSuggestions();
        // Infinite scroll: reaching the bottom loads the next page automatically.
        searchList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) {}
            @Override public void onScroll(AbsListView v, int first, int vis, int total) {
                if (loadingMore || total <= vis) return;
                if (first + vis >= total - 1) maybeLoadMore();
            }
        });
        searchList.setOnItemClickListener((p, v, pos, id) -> {
            // Footer (loading indicator) taps carry a position past the data.
            if (pos < 0 || pos >= items.size()) return;
            InfoItem it = items.get(pos);
            if (selectionMode) {
                if (it instanceof StreamInfoItem) toggleSelect(pos);
                return;
            }
            if (it instanceof ChannelInfoItem || it instanceof PlaylistInfoItem) {
                openListInline(it);
            } else {
                DlHelper.fetchAndPick(this, it.getUrl(), false);
            }
        });
        searchList.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= items.size()) return true;
            InfoItem it = items.get(pos);
            if (selectionMode) {
                if (it instanceof StreamInfoItem) toggleSelect(pos);
                return true;
            }
            if (it instanceof StreamInfoItem) {
                // Enter multi-select mode to download several videos together.
                selectionMode = true;
                selectedPos.add(pos);
                adapter.notifyDataSetChanged();
                updateSelectionBar();
                return true;
            }
            // Channel/playlist long-press: straight to the download flow.
            DlHelper.fetchAndPick(this, it.getUrl(), false);
            return true;
        });

        browseBackBtn = findViewById(R.id.browseBackBtn);
        browseBackBtn.setOnClickListener(v -> exitBrowseMode());
        browseBar = findViewById(R.id.browseBar);
        browseTabs = findViewById(R.id.browseTabs);
        bottomBar = findViewById(R.id.bottomBar);
        filterRow = findViewById(R.id.filterRow);
        chipTextColor = ((Button) findViewById(R.id.chipAll)).getCurrentTextColor();
        findViewById(R.id.chipAll).setOnClickListener(v -> {
            searchType = "all"; durFilter = 0; updateChips(); doSearch(lastQuery);
        });
        findViewById(R.id.chipVideos).setOnClickListener(v -> {
            searchType = "videos"; updateChips(); doSearch(lastQuery);
        });
        findViewById(R.id.chipChannels).setOnClickListener(v -> {
            searchType = "channels"; durFilter = 0; updateChips(); doSearch(lastQuery);
        });
        findViewById(R.id.chipPlaylists).setOnClickListener(v -> {
            searchType = "playlists"; durFilter = 0; updateChips(); doSearch(lastQuery);
        });
        findViewById(R.id.chipShort).setOnClickListener(v -> {
            searchType = "videos"; durFilter = 1; updateChips(); doSearch(lastQuery);
        });
        findViewById(R.id.chipMedium).setOnClickListener(v -> {
            searchType = "videos"; durFilter = 2; updateChips(); doSearch(lastQuery);
        });
        findViewById(R.id.chipLong).setOnClickListener(v -> {
            searchType = "videos"; durFilter = 3; updateChips(); doSearch(lastQuery);
        });
        updateChips();
        dlSelectedBtn = findViewById(R.id.dlSelectedBtn);
        dlSelectedBtn.setOnClickListener(v -> downloadSelected());
        findViewById(R.id.cancelSelBtn).setOnClickListener(v -> exitSelectionMode());

        // One-time gesture hint above the results.
        findViewById(R.id.gestureHintClose).setOnClickListener(v -> {
            findViewById(R.id.gestureHint).setVisibility(View.GONE);
            Prefs.setHintSeen(this, "search");
        });

        Tabs.bind(this, Tabs.TAB_DOWNLOAD);
        miniBar = MiniBar.bind(this);

        // Opened via Share: show the shared channel/playlist inline in the page.
        String openUrl = getIntent().getStringExtra("open_url");
        if (openUrl != null && !openUrl.isEmpty()) {
            launchedFromShare = true;
            final String u = openUrl;
            searchList.postDelayed(() -> openLinkInline(u), 300);
        }
    }

    /** Search icon / keyboard search: URL -> inline page, else inline results. */
    private void doInlineSearch() {
        dismissRecentPopup();
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        String url = DlHelper.extractYoutubeUrl(q);
        if (url != null) {
            openLinkInline(url);
            return;
        }
        doSearch(q);
    }

    /**
     * Pasted link opens in the page (not a popup): a channel enters browse
     * mode with its tab bar (same as tapping a channel), a playlist shows its
     * videos inline, and a single video shows as a normal result row.
     */
    public void openLinkInline(String url) {
        exitSelectionMode();
        if (DlHelper.isChannel(url) || DlHelper.isPlaylist(url)) {
            DlHelper.browseFirstPage(this, url, browseCb);
            return;
        }
        DlHelper.fetchVideoItem(this, url, new DlHelper.VideoItemCallback() {
            @Override public void onItem(StreamInfoItem item) {
                java.util.List<InfoItem> one = new java.util.ArrayList<>();
                one.add(item);
                DlHelper.BrowseSession vs =
                        new DlHelper.BrowseSession(null, null, item.getName());
                vs.kind = "video";
                browseCb.onFirstPage(vs, one);
            }
            @Override public void onError(String msg) {
                if (msg != null) {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void doSearch(final String q) {
        if (q == null || q.isEmpty()) return;
        Prefs.addRecentSearch(this, q);
        lastQuery = q;
        try {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Exception ignored) {}
        // New search: leave selection and channel-browsing modes.
        exitSelectionMode();
        browseMode = false;
        browseSession = null;
        loadingMore = false;
        if (loadFooter != null) loadFooter.setVisibility(View.GONE);
        savedItems.clear();
        if (browseBar != null) browseBar.setVisibility(View.GONE);
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage(getString(R.string.prog_search));
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            try {
                DlHelper.ensureInit();
                extractor = ServiceList.YouTube.getSearchExtractor(q,
                        java.util.Collections.singletonList(searchType), "");
                extractor.fetchPage();
                InfoItemsPage<InfoItem> page = extractor.getInitialPage();
                final List<InfoItem> res = applyBlacklist(
                        applyDurFilter(new ArrayList<>(page.getItems())));
                final Page np = page.getNextPage();
                runOnUiThread(() -> {
                    pd.dismiss();
                    items.clear();
                    items.addAll(res);
                    nextPage = np;
                    adapter.notifyDataSetChanged();
                    showingSuggestions = false;
                    findViewById(R.id.refreshSugBtn)
                        .setVisibility(android.view.View.GONE);
                    showResults(true);
                    autoFillIfShort();
                    updateBottomBar();
                    if (res.isEmpty()) {
                        Toast.makeText(this, R.string.toast_no_results,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                final String why = DlHelper.friendlyFetchError(this, e);
                runOnUiThread(() -> {
                    pd.dismiss();
                    Toast.makeText(this, getString(R.string.toast_fetch_fail, why),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** Client-side duration filter (YouTube search has no duration param here). */
    private List<InfoItem> applyDurFilter(List<InfoItem> in) {
        if (durFilter == 0) return in;
        List<InfoItem> out = new ArrayList<>();
        for (InfoItem it : in) {
            if (!(it instanceof StreamInfoItem)) continue;
            long d = ((StreamInfoItem) it).getDuration();
            boolean ok = durFilter == 1 ? d > 0 && d < 240
                    : durFilter == 2 ? d >= 240 && d <= 1200
                    : d > 1200;
            if (ok) out.add(it);
        }
        return out;
    }

    /** Drops blacklisted items: by item URL, or (for videos) by uploader URL
     *  so blacklisting a channel hides all its videos. */
    private List<InfoItem> applyBlacklist(List<InfoItem> in) {
        List<InfoItem> out = new ArrayList<>();
        for (InfoItem it : in) {
            String url = it.getUrl();
            if (url != null && Prefs.isBlacklisted(this, url)) continue;
            if (it instanceof StreamInfoItem) {
                String uu = ((StreamInfoItem) it).getUploaderUrl();
                if (uu != null && Prefs.isBlacklisted(this, uu)) continue;
            }
            out.add(it);
        }
        return out;
    }

    private void updateChips() {
        styleChip(R.id.chipAll, "all".equals(searchType) && durFilter == 0);
        styleChip(R.id.chipVideos, "videos".equals(searchType) && durFilter == 0);
        styleChip(R.id.chipChannels, "channels".equals(searchType));
        styleChip(R.id.chipPlaylists, "playlists".equals(searchType));
        styleChip(R.id.chipShort, durFilter == 1);
        styleChip(R.id.chipMedium, durFilter == 2);
        styleChip(R.id.chipLong, durFilter == 3);
    }

    private void styleChip(int id, boolean sel) {
        Button b = findViewById(id);
        float d = getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(20 * d);
        if (sel) {
            bg.setColor(0xFFE53935);
            b.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(0x1A000000);
            b.setTextColor(chipTextColor);
        }
        b.setBackground(bg);
    }

    private void showResults(boolean show) {
        searchList.setVisibility(show ? View.VISIBLE : View.GONE);
        // In channel-browse mode the filter bar is replaced by the channel tabs.
        if (filterRow != null) filterRow.setVisibility(show && !browseMode ? View.VISIBLE : View.GONE);
        if (show && !Prefs.hintSeen(this, "search")) {
            findViewById(R.id.gestureHint).setVisibility(View.VISIBLE);
        }
        if (!show) {
            findViewById(R.id.gestureHint).setVisibility(View.GONE);
            updateBottomBar();
        }
    }

    /** True when another page can be loaded (search results or channel browse). */
    private boolean hasMore() {
        if (browseMode) return browseSession != null && Page.isValid(browseSession.nextPage);
        return Page.isValid(nextPage);
    }

    /** If the loaded items don't fill the screen but more pages exist, keep loading. */
    private void autoFillIfShort() {
        Runnable check = () -> {
            if (loadingMore || !hasMore()) return;
            int last = searchList.getLastVisiblePosition();
            // All positions (incl. the hidden footer) visible => list not scrollable.
            if (last >= 0 && last >= searchList.getCount() - 1) maybeLoadMore();
        };
        searchList.post(check);
        searchList.postDelayed(check, 400);
    }

    /** Infinite scroll entry point: load the next page when the bottom is reached. */
    private void maybeLoadMore() {
        if (loadingMore || !hasMore()) return;
        if (browseMode) loadMoreBrowse();
        else loadMore();
    }

    private void loadMore() {
        if (extractor == null || !Page.isValid(nextPage) || loadingMore) return;
        loadingMore = true;
        loadFooter.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                InfoItemsPage<InfoItem> page = extractor.getPage(nextPage);
                final List<InfoItem> res = applyBlacklist(
                        applyDurFilter(new ArrayList<>(page.getItems())));
                final Page np = page.getNextPage();
                runOnUiThread(() -> {
                    items.addAll(res);
                    nextPage = np;
                    adapter.notifyDataSetChanged();
                    loadingMore = false;
                    loadFooter.setVisibility(View.GONE);
                    updateBottomBar();
                    autoFillIfShort();
                });
            } catch (Exception e) {
                final String why = DlHelper.friendlyFetchError(this, e);
                runOnUiThread(() -> {
                    loadingMore = false;
                    loadFooter.setVisibility(View.GONE);
                    Toast.makeText(this, getString(R.string.toast_fetch_fail, why),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ---------- Multi-select & inline browsing ----------

    private void toggleSelect(int pos) {
        if (selectedPos.contains(pos)) selectedPos.remove(pos);
        else selectedPos.add(pos);
        if (selectedPos.isEmpty()) exitSelectionMode();
        else {
            adapter.notifyDataSetChanged();
            updateSelectionBar();
        }
    }

    private void exitSelectionMode() {
        selectionMode = false;
        selectedPos.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        updateSelectionBar();
    }

    private void updateSelectionBar() {
        if (bottomBar == null) return;
        boolean sel = selectionMode;
        dlSelectedBtn.setVisibility(sel ? View.VISIBLE : View.GONE);
        findViewById(R.id.cancelSelBtn).setVisibility(sel ? View.VISIBLE : View.GONE);
        if (sel && dlSelectedBtn != null) {
            dlSelectedBtn.setText(getString(R.string.btn_dl_selected)
                    + " (" + selectedPos.size() + ")");
        }
        updateBottomBar();
    }

    /** The bottom row shows only if at least one of its buttons is visible. */
    private void updateBottomBar() {
        if (bottomBar == null) return;
        boolean any = dlSelectedBtn.getVisibility() == View.VISIBLE
                || findViewById(R.id.cancelSelBtn).getVisibility() == View.VISIBLE;
        bottomBar.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    private void downloadSelected() {
        List<Integer> order = new ArrayList<>(selectedPos);
        Collections.sort(order);
        List<StreamInfoItem> sel = new ArrayList<>();
        for (int p : order) {
            if (p < 0 || p >= items.size()) continue;
            InfoItem it = items.get(p);
            if (it instanceof StreamInfoItem) sel.add((StreamInfoItem) it);
        }
        exitSelectionMode();
        if (sel.isEmpty()) return;
        // One quality picker for all selected videos (bulk download).
        DlHelper.pickBulkQuality(this, sel, "search");
    }

    /** Tap on a channel/playlist: show its latest videos inline, paged. */
    private void openListInline(InfoItem it) {
        exitSelectionMode();
        final String url = it.getUrl();
        DlHelper.browseFirstPage(this, url, browseCb);
    }

    /** Shared callback for channel/playlist browsing (first page + next pages). */
    private final DlHelper.BrowseCallback browseCb = new DlHelper.BrowseCallback() {
        @Override public void onFirstPage(DlHelper.BrowseSession s,
                                          List<InfoItem> videos) {
            if (videos.isEmpty()) {
                Toast.makeText(MainActivity.this, R.string.toast_no_videos,
                        Toast.LENGTH_LONG).show();
                return;
            }
            savedItems.clear();
            savedItems.addAll(items);
            browseMode = true;
            browseSession = s;
            items.clear();
            items.addAll(videos);
            updateBottomBar();
            // Channel tabs replace the search filter bar.
            if (filterRow != null) filterRow.setVisibility(View.GONE);
            String hic = "video".equals(s.kind) ? "\uD83C\uDFAC "
                    : "playlist".equals(s.kind) ? "\uD83D\uDCCB " : "\uD83D\uDCFA ";
            browseBackBtn.setText("‹ " + hic + s.name);
            buildBrowseTabs();
            browseBar.setVisibility(View.VISIBLE);
            showResults(true); // searchList starts GONE in the layout
            adapter.notifyDataSetChanged();
            searchList.setSelection(0);
            autoFillIfShort();
        }
        @Override public void onNextPage(DlHelper.BrowseSession s,
                                         List<InfoItem> videos) {
            items.addAll(videos);
            adapter.notifyDataSetChanged();
            loadingMore = false;
            loadFooter.setVisibility(View.GONE);
            updateBottomBar();
            autoFillIfShort();
        }
        @Override public void onTabPage(DlHelper.BrowseSession s,
                                        List<InfoItem> videos) {
            loadingMore = false;
            loadFooter.setVisibility(View.GONE);
            if (videos.isEmpty()) {
                Toast.makeText(MainActivity.this, R.string.toast_no_videos,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            browseSession = s;
            items.clear();
            items.addAll(videos);
            buildBrowseTabs();
            showResults(true);
            adapter.notifyDataSetChanged();
            searchList.setSelection(0);
            updateBottomBar();
            autoFillIfShort();
        }
        @Override public void onError(String msg) {
            loadingMore = false;
            loadFooter.setVisibility(View.GONE);
            if (msg != null) {
                String why = DlHelper.friendlyFetchError(MainActivity.this,
                        new Exception(msg));
                Toast.makeText(MainActivity.this,
                        getString(R.string.toast_fetch_fail, why),
                        Toast.LENGTH_LONG).show();
            }
        }
    };

    /** Infinite-scroll next page for an open channel/playlist. */
    private void loadMoreBrowse() {
        if (browseSession == null || !Page.isValid(browseSession.nextPage)
                || loadingMore) return;
        loadingMore = true;
        loadFooter.setVisibility(View.VISIBLE);
        DlHelper.browseNextPage(this, browseSession, browseCb);
    }

    /** Build the channel tab chips next to the channel name (browse mode). */
    private void buildBrowseTabs() {
        browseTabs.removeAllViews();
        if (browseSession == null) return;
        boolean singleVideo = "video".equals(browseSession.kind);
        float d = getResources().getDisplayMetrics().density;
        if (!singleVideo && browseSession.tabs != null) {
            for (int i = 0; i < browseSession.tabs.size(); i++) {
            final int idx = i;
            String url = browseSession.tabs.get(i).getUrl();
            Button b = new Button(this);
            b.setText(DlHelper.tabLabel(this, url));
            b.setTextSize(13);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            int ps = Math.round(14 * d);
            b.setPadding(ps, Math.round(6 * d), ps, Math.round(6 * d));
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Math.round(8 * d));
            b.setLayoutParams(lp);
            styleTabChip(b, idx == browseSession.activeTab);
            b.setOnClickListener(v -> {
                if (idx == browseSession.activeTab || loadingMore) return;
                loadingMore = true;
                DlHelper.browseSwitchTab(this, browseSession, idx, browseCb);
            });
            browseTabs.addView(b);
        }
        }
        if (!singleVideo && browseSession.sourceUrl != null) {
            // Download-all chip at the end of the browse bar.
            Button dl = new Button(this);
            dl.setText("\u2B07 " + getString(R.string.m_download_all));
            dl.setTextSize(13);
            dl.setMinWidth(0);
            dl.setMinimumWidth(0);
            dl.setMinHeight(0);
            dl.setMinimumHeight(0);
            int ps = Math.round(14 * d);
            dl.setPadding(ps, Math.round(6 * d), ps, Math.round(6 * d));
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Math.round(8 * d));
            dl.setLayoutParams(lp);
            styleTabChip(dl, false);
            dl.setOnClickListener(v ->
                    DlHelper.downloadAllDirect(this, browseSession, false));
            browseTabs.addView(dl);
        }
    }

    private void styleTabChip(Button b, boolean sel) {
        float d = getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(20 * d);
        if (sel) {
            bg.setColor(0xFFE53935);
            b.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(0x1A000000);
            b.setTextColor(chipTextColor);
        }
        b.setBackground(bg);
    }

    private void exitBrowseMode() {
        browseMode = false;
        browseSession = null;
        loadingMore = false;
        if (loadFooter != null) loadFooter.setVisibility(View.GONE);
        if (savedItems.isEmpty() && launchedFromShare) {
            // Launched straight into a shared channel/playlist: there is no
            // previous screen inside the app to restore (empty home + dead
            // filter chips) — go back to the app it was shared from instead.
            finish();
            return;
        }
        items.clear();
        items.addAll(savedItems);
        savedItems.clear();
        browseBar.setVisibility(View.GONE);
        updateBottomBar();
        adapter.notifyDataSetChanged();
        showResults(true); // restore the filter bar (browseMode is false now)
    }

    @Override
    public void onBackPressed() {
        if (selectionMode) { exitSelectionMode(); return; }
        if (browseMode) { exitBrowseMode(); return; }
        super.onBackPressed();
    }

    private String thumbFor(InfoItem it) {
        if (it instanceof StreamInfoItem) {
            return DlHelper.thumbUrl(DlHelper.extractVideoId(it.getUrl()));
        }
        try {
            List<Image> thumbs = it.getThumbnails();
            if (thumbs != null && !thumbs.isEmpty()) return thumbs.get(0).getUrl();
        } catch (Exception ignored) {}
        return null;
    }

    private String metaFor(InfoItem it) {
        if (it instanceof StreamInfoItem) {
            StreamInfoItem s = (StreamInfoItem) it;
            String dur = DlHelper.fmtDuration(s.getDuration());
            String up = s.getUploaderName();
            String who = up == null ? "" : up;
            String date = DlHelper.fmtUploadDate(s.getUploadDate());
            if (!date.isEmpty()) who += (who.isEmpty() ? "" : " • ") + "📅 " + date;
            if (!dur.isEmpty() && !who.isEmpty()) return dur + " | " + who;
            if (!dur.isEmpty()) return dur;
            return who;
        }
        if (it instanceof ChannelInfoItem) return "📺 " + getString(R.string.type_channel);
        if (it instanceof PlaylistInfoItem) {
            long n = ((PlaylistInfoItem) it).getStreamCount();
            String base = "📋 " + getString(R.string.type_playlist);
            if (n > 0) base += " \u2022 " + n + " " + getString(R.string.videos_count);
            return base;
        }
        return "";
    }

    /** Load home suggestions: subscribed channels' videos, or favorites. */
    private void loadHomeSuggestions() {
        new Thread(() -> {
            List<InfoItem> suggestions = new ArrayList<>();
            String header = null;
            // Try subscriptions first (needs Google login).
            if (GoogleLogin.isLoggedIn(this)) {
                List<SubscriptionHelper.Channel> subs =
                        SubscriptionHelper.getSubscriptions(this);
                // Shuffle so refresh gives different channels each time.
                java.util.Collections.shuffle(subs);
                // Get videos from 3 random channels (4 videos each).
                int chCount = 0;
                for (SubscriptionHelper.Channel ch : subs) {
                    if (chCount >= 3) break;
                    // Get up to 20 videos (paginated) for real variety.
                    List<StreamInfoItem> vids =
                            SubscriptionHelper.getChannelVideos(ch.url, 20);
                    java.util.Collections.shuffle(vids);
                    for (int i = 0; i < Math.min(4, vids.size()); i++) {
                        suggestions.add(vids.get(i));
                    }
                    chCount++;
                }
                if (!suggestions.isEmpty()) {
                    header = getString(R.string.home_subs);
                }
            }
            // Fallback: favorite channels AND favorite videos.
            if (suggestions.isEmpty()) {
                List<String> favs = new ArrayList<>(Prefs.favorites(this));
                java.util.Collections.shuffle(favs);
                int count = 0;
                for (String f : favs) {
                    if (count >= 12) break;
                    // Favorite format: "type|name|url|thumb"
                    String[] parts = f.split("\\|", 4);
                    if (parts.length >= 3) {
                        String type = parts[0];
                        String name = parts[1];
                        String url = parts[2];
                        if ("channel".equals(type) && url != null) {
                            List<StreamInfoItem> vids =
                                SubscriptionHelper.getChannelVideos(url, 20);
                            java.util.Collections.shuffle(vids);
                            int take = Math.min(4, vids.size());
                            for (int i = 0; i < take; i++) {
                                suggestions.add(vids.get(i));
                            }
                            count += take;
                        } else if ("video".equals(type) && url != null) {
                            // Add the favorite video directly.
                            try {
                                StreamInfoItem si = new StreamInfoItem(
                                    org.schabi.newpipe.extractor.ServiceList
                                        .YouTube.getServiceId(),
                                    url, name,
                                    org.schabi.newpipe.extractor.stream
                                        .StreamType.VIDEO_STREAM);
                                suggestions.add(si);
                                count++;
                            } catch (Exception ignored) {}
                        }
                    }
                }
                if (!suggestions.isEmpty()) {
                    header = getString(R.string.home_favs);
                }
            }
            // Final shuffle: mix everything so it's different every time.
            java.util.Collections.shuffle(suggestions);
            final List<InfoItem> finalSug = suggestions;
            final String finalHeader = header;
            showingSuggestions = !finalSug.isEmpty();
            runOnUiThread(() -> {
                android.widget.Button rb =
                        findViewById(R.id.refreshSugBtn);
                if (!finalSug.isEmpty()) {
                    items.addAll(finalSug);
                    adapter.notifyDataSetChanged();
                    showResults(true);
                    rb.setVisibility(android.view.View.VISIBLE);
                    if (finalHeader != null) {
                        Toast.makeText(this, finalHeader,
                                Toast.LENGTH_SHORT).show();
                    }
                } else {
                    rb.setVisibility(android.view.View.GONE);
                }
            });
        }).start();
    }

    private class SrAdapter extends BaseAdapter {
        private final Context ctx;
        SrAdapter(Context c) { ctx = c; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            InfoItem it = items.get(pos);
            if (cv == null) {
                cv = LayoutInflater.from(ctx).inflate(R.layout.row_search, parent, false);
            }
            ImageView thumb = cv.findViewById(R.id.srThumb);
            TextView name = cv.findViewById(R.id.srName);
            TextView meta = cv.findViewById(R.id.srMeta);
            android.widget.CheckBox check = cv.findViewById(R.id.srCheck);
            TextView heart = cv.findViewById(R.id.srHeart);
            ThumbLoader.load(ctx, thumbFor(it), thumb);
            name.setText(it.getName());
            DlHelper.singleLineExpandable(name);
            String m = metaFor(it);
            meta.setText(m);
            meta.setVisibility(m.isEmpty() ? View.GONE : View.VISIBLE);
            boolean sel = selectionMode && selectedPos.contains(pos);
            check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            check.setChecked(sel);
            if (sel) {
                int pc = ThemeHelper.attrColor((Activity) ctx,
                        android.R.attr.colorPrimary);
                cv.setBackgroundColor((pc & 0x00FFFFFF) | 0x22000000);
            } else {
                cv.setBackgroundColor(0x00000000);
            }
            // Heart on channel rows: tap toggles the channel in Favorites.
            if (it instanceof ChannelInfoItem && it.getUrl() != null) {
                heart.setVisibility(View.VISIBLE);
                final String chUrl = it.getUrl();
                paintHeart(heart, Prefs.isFavorite(ctx, chUrl));
                heart.setOnClickListener(v -> {
                    boolean nowFav = !Prefs.isFavorite(ctx, chUrl);
                    if (nowFav) Prefs.addFavorite(ctx, "channel", it.getName(), chUrl,
                            thumbFor(it));
                    else Prefs.removeFavorite(ctx, chUrl);
                    paintHeart(heart, nowFav);
                    Toast.makeText(ctx, nowFav ? R.string.toast_fav_added
                            : R.string.toast_fav_removed,
                            Toast.LENGTH_SHORT).show();
                });
            } else {
                heart.setVisibility(View.GONE);
                heart.setOnClickListener(null);
            }
            // ⋮ per-result menu: download / favorite / hide / share.
            TextView rowMenu = cv.findViewById(R.id.srMenu);
            rowMenu.setOnClickListener(v -> showResultMenu(v, it));
            return cv;
        }
    }

    /** ⋮ menu for a search result: download, open, share, copy, favorite, hide. */
    private void showResultMenu(View anchor, InfoItem it) {
        final String url = it.getUrl();
        if (url == null) return;
        final boolean isVideo = it instanceof StreamInfoItem;
        final boolean isChannel = it instanceof ChannelInfoItem;
        final String favType = isVideo ? "video" : isChannel ? "channel" : "playlist";
        final boolean fav = Prefs.isFavorite(this, url);

        java.util.List<RichMenu.Item> items = new java.util.ArrayList<>();
        if (isVideo) {
            items.add(new RichMenu.Item("\u2b07", getString(R.string.m_download_video),
                    () -> DlHelper.fetchAndPick(this, url, false)));
            items.add(new RichMenu.Item("\uD83D\uDCF6", getString(R.string.m_download_wifi),
                    () -> DlHelper.fetchAndPickWifiOnly(this, url, false)));
        } else {
            items.add(new RichMenu.Item("\ud83d\udcfa", getString(R.string.m_view_videos),
                    () -> openListInline(it)));
            items.add(new RichMenu.Item("\u2b07", getString(R.string.m_download_all),
                    () -> DlHelper.fetchAndPick(this, url, false)));
            items.add(new RichMenu.Item("\uD83D\uDCF6", getString(R.string.m_download_all_wifi),
                    () -> DlHelper.fetchAndPickWifiOnly(this, url, false)));
        }
        items.add(new RichMenu.Item("\u25b6", getString(R.string.m_open_youtube),
                () -> DlHelper.openInYoutube(this, url)));
        items.add(new RichMenu.Item("\ud83d\udce4", getString(R.string.m_share_link),
                () -> DlHelper.shareUrl(this, url)));
        items.add(new RichMenu.Item("\ud83d\udccb", getString(R.string.m_copy_link),
                () -> DlHelper.copyLink(this, url)));
        items.add(new RichMenu.Item(fav ? "\u2606" : "\u2b50",
                getString(fav ? R.string.m_fav_remove : R.string.m_fav_add),
                () -> {
                    if (Prefs.isFavorite(this, url)) {
                        Prefs.removeFavorite(this, url);
                        Toast.makeText(this, R.string.toast_fav_removed,
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Prefs.addFavorite(this, favType, it.getName(), url,
                                thumbFor(it));
                        Toast.makeText(this, R.string.toast_fav_added,
                                Toast.LENGTH_SHORT).show();
                    }
                    adapter.notifyDataSetChanged();
                }));
        items.add(new RichMenu.Item("\ud83d\udeab", getString(R.string.m_hide),
                () -> {
                    // Videos: hide the whole channel; channels/playlists: hide themselves.
                    String hideUrl = url, hideName = it.getName();
                    if (isVideo) {
                        String uu = ((StreamInfoItem) it).getUploaderUrl();
                        if (uu != null && !uu.isEmpty()) {
                            hideUrl = uu;
                            hideName = ((StreamInfoItem) it).getUploaderName();
                        }
                    }
                    Prefs.addBlacklist(this, hideName, hideUrl);
                    this.items.remove(it);
                    adapter.notifyDataSetChanged();
                    Toast.makeText(this, R.string.toast_blacklisted,
                            Toast.LENGTH_SHORT).show();
                }));

        RichMenu.show(this, anchor, items);
    }

    private void paintHeart(TextView heart, boolean fav) {
        heart.setText(fav ? "♥" : "♡");
        heart.setTextColor(fav
                ? ThemeHelper.attrColor(MainActivity.this, android.R.attr.colorPrimary)
                : ThemeHelper.attrColor(MainActivity.this, R.attr.colorOnSurface));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedDark != ThemeHelper.isDark(this)) { recreate(); return; }
        if (miniBar != null) miniBar.onResume();
    }

    @Override
    protected void onPause() {
        GoogleLogin.flush();
        super.onPause();
        dismissRecentPopup();
        if (miniBar != null) miniBar.onPause();
    }

    // ---- Recent searches dropdown (semi-transparent, below the search field) ----
    private PopupWindow recentPopup;
    private boolean suggestBroken = false; // kill-switch if popups crash
    private boolean suppressSuggest = false;

    private int rdp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void dismissRecentPopup() {
        if (recentPopup != null) {
            try { recentPopup.dismiss(); } catch (Exception ignored) {}
            recentPopup = null;
        }
    }

    private boolean isUrlLike(String q) {
        String l = q.toLowerCase();
        return l.contains("youtube.com") || l.contains("youtu.be")
                || l.startsWith("http");
    }

    /** Live YouTube suggestions for the current query (background thread). */
    private void fetchSuggestions(final String q) {
        new Thread(() -> {
            List<String> out = new ArrayList<>();
            try {
                DlHelper.ensureInit();
                List<String> s = ServiceList.YouTube.getSuggestionExtractor()
                        .suggestionList(q);
                if (s != null) out.addAll(s);
            } catch (Throwable ignored) {}
            final List<String> res = out.size() > 8 ? out.subList(0, 8) : out;
            runOnUiThread(() -> {
                try {
                    if (suggestBroken) return;
                    if (res.isEmpty() || !input.hasFocus()) return;
                    // Stale: the user kept typing after this request started.
                    if (!input.getText().toString().trim().equals(q)) return;
                    showSuggestionPopup(res);
                } catch (Throwable t) {
                    // Popup system broken on this device/ROM: never try again
                    // this session instead of crashing on every keystroke.
                    suggestBroken = true;
                }
            });
        }).start();
    }

    /** Suggestion dropdown styled like the recent-searches popup. */
    private void showSuggestionPopup(final List<String> suggestions) {
        if (suggestBroken) return;
        // Async callback: the activity may be gone (back pressed / rotated)
        // by the time suggestions arrive — showing a popup then crashes
        // with BadTokenException.
        if (isFinishing()) return;
        if (isUrlLike(input.getText().toString().trim())) return;
        dismissRecentPopup();
        final View box = findViewById(R.id.searchBox);
        if (box.getWidth() == 0) return;

        final ListView lv = new ListView(this);
        lv.setDivider(new ColorDrawable(0x33FFFFFF));
        lv.setDividerHeight(1);

        final BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return suggestions.size(); }
            @Override public Object getItem(int p) { return suggestions.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(final int pos, View cv, ViewGroup parent) {
                View v = cv;
                if (v == null) v = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.row_recent, parent, false);
                ((TextView) v.findViewById(R.id.recentText))
                        .setText(suggestions.get(pos));
                v.findViewById(R.id.recentDel).setVisibility(View.GONE);
                return v;
            }
        };
        lv.setAdapter(ad);
        lv.setOnItemClickListener((p, v, pos, id) -> {
            String q = suggestions.get(pos);
            suppressSuggest = true;
            input.setText(q);
            input.setSelection(q.length());
            dismissRecentPopup();
            doInlineSearch();
        });

        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrap.setBackgroundResource(R.drawable.recent_bg);
        int wp = rdp(4);
        wrap.setPadding(wp, wp, wp, wp);
        wrap.addView(lv, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        int h = Math.min(suggestions.size(), 5) * rdp(40) + rdp(8);
        int w = (int) (box.getWidth() * 0.85);
        recentPopup = new PopupWindow(wrap, w, h, false);
        recentPopup.setBackgroundDrawable(new ColorDrawable(0x00000000));
        recentPopup.setOutsideTouchable(true);
        try {
            recentPopup.showAsDropDown(box, (box.getWidth() - w) / 2, rdp(4));
        } catch (Exception e) {
            // Popup failed (BadToken on some ROMs, EMUI quirks, etc.).
            recentPopup = null;
        }
    }

    private void showRecentSearches() {
        dismissRecentPopup();
        final List<String> recents = Prefs.recentSearches(this);
        if (recents.isEmpty()) return;
        final View box = findViewById(R.id.searchBox);

        final ListView lv = new ListView(this);
        lv.setDivider(new ColorDrawable(0x33FFFFFF));
        lv.setDividerHeight(1);

        final BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return recents.size(); }
            @Override public Object getItem(int p) { return recents.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(final int pos, View cv, ViewGroup parent) {
                View v = cv;
                if (v == null) v = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.row_recent, parent, false);
                ((TextView) v.findViewById(R.id.recentText)).setText(recents.get(pos));
                v.findViewById(R.id.recentDel).setOnClickListener(x -> {
                    Prefs.removeRecentSearch(MainActivity.this, recents.get(pos));
                    recents.remove(pos);
                    if (recents.isEmpty()) dismissRecentPopup();
                    else notifyDataSetChanged();
                });
                return v;
            }
        };
        lv.setAdapter(ad);
        lv.setOnItemClickListener((p, v, pos, id) -> {
            String q = recents.get(pos);
            input.setText(q);
            input.setSelection(q.length());
            dismissRecentPopup();
            doInlineSearch();
        });

        TextView clear = new TextView(this);
        clear.setText("\uD83D\uDDD1 " + getString(R.string.clear_recent));
        clear.setTextColor(0xFFFF8A80);
        clear.setTextSize(14);
        int cp = rdp(14);
        clear.setPadding(cp, rdp(10), cp, rdp(10));
        clear.setOnClickListener(x -> {
            Prefs.clearRecentSearches(this);
            dismissRecentPopup();
        });

        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrap.setBackgroundResource(R.drawable.recent_bg);
        int wp = rdp(4);
        wrap.setPadding(wp, wp, wp, wp);
        wrap.addView(lv, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        wrap.addView(clear, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        int h = Math.min(recents.size(), 5) * rdp(40) + rdp(48);
        int w = (int) (box.getWidth() * 0.85);
        recentPopup = new PopupWindow(wrap, w, h, false);
        recentPopup.setBackgroundDrawable(new ColorDrawable(0x00000000));
        recentPopup.setOutsideTouchable(true);
        // Center the narrower dropdown under the search field.
        try {
            recentPopup.showAsDropDown(box, (box.getWidth() - w) / 2, rdp(4));
        } catch (Exception e) {
            // Popup failed (BadToken on some ROMs, EMUI quirks, etc.).
            recentPopup = null;
        }
    }

    /** One-time prompt to enable storage-root downloads (All-files access). */
    private void maybeOfferRootStorage() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 30) return;
            if (DlEngine.useRootStorage(this)) return;
            if (Prefs.rootPromptDismissed(this)) return;
            Prefs.setRootPromptDismissed(this, true);
            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.root_title)
                    .setMessage(R.string.root_msg)
                    .setPositiveButton(R.string.root_enable, (d, w) -> {
                        try {
                            android.content.Intent i = new android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            try {
                                startActivity(new android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            } catch (Exception ignored) {}
                        }
                    })
                    .setNegativeButton(R.string.root_later, null)
                    .show();
        } catch (Exception ignored) {}
    }
}
