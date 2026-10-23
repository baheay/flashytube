package com.ytgrab.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;


/** Settings: save folders (system folder picker), language. */
public class SettingsActivity extends Activity {
    private boolean appliedDark;

    private static final int REQ_VIDEO = 101;
    private static final int REQ_AUDIO = 102;

    private TextView videoFolderTv;
    private TextView audioFolderTv;
    private LinearLayout blacklistBox;
    private Runnable refreshGoogleRow;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Lang.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ThemeHelper.apply(this);
        appliedDark = ThemeHelper.isDark(this);
        setContentView(R.layout.activity_settings);
        Tabs.bind(this, Tabs.TAB_SETTINGS);

        buildSettingsRows();
        refreshLabels();

        // Show the installed version (helps debugging which build is on device).
        try {
            TextView versionTv = findViewById(R.id.versionTv);
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            String vn = pi.versionName != null ? pi.versionName : "?";
            long vc = android.os.Build.VERSION.SDK_INT >= 28
                    ? pi.getLongVersionCode() : pi.versionCode;
            versionTv.setText("v" + vn + " (" + vc + ")");
        } catch (Exception ignored) {}

        bindSection(R.id.secHeadDownloads, R.id.cardDownloads,
                R.id.secChevDownloads, "downloads");
        bindSection(R.id.secHeadAppearance, R.id.cardAppearance,
                R.id.secChevAppearance, "appearance");
        bindSection(R.id.secHeadPlayback, R.id.cardPlayback,
                R.id.secChevPlayback, "playback");
        bindSection(R.id.secHeadTelegram, R.id.cardTelegram,
                R.id.secChevTelegram, "telegram");
        bindSection(R.id.secHeadBlacklist, R.id.cardBlacklist,
                R.id.secChevBlacklist, "blacklist");

        blacklistBox = findViewById(R.id.blacklistBox);
        refreshBlacklist();
    }

    /** Collapsible section: header tap toggles the card; state persists. */
    private void bindSection(int headId, int cardId, int chevId, String key) {
        View head = findViewById(headId);
        View card = findViewById(cardId);
        TextView chev = findViewById(chevId);
        applySection(card, chev, Prefs.sectionOpen(this, key, true));
        head.setOnClickListener(v -> {
            boolean open = card.getVisibility() != View.VISIBLE;
            Prefs.setSectionOpen(this, key, open);
            applySection(card, chev, open);
        });
    }

    private void applySection(View card, TextView chev, boolean open) {
        card.setVisibility(open ? View.VISIBLE : View.GONE);
        chev.setText(open ? "\u25BC" : "\u25B2");
    }

    // ---------- Organized settings rows ----------

    private int sdp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** One icon row inside a card, with an inset divider above it when needed. */
    private View addSettingRow(LinearLayout card, String icon, String title,
                               String value, boolean chevron, View.OnClickListener l) {
        if (card.getChildCount() > 0) {
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.HORIZONTAL);
            int p = sdp(14);
            wrap.setPadding(p, 0, p, 0);
            View div = new View(this);
            try {
                div.setBackgroundColor(ThemeHelper.attrColor(this, R.attr.colorDivider));
            } catch (Exception ignored) {}
            wrap.addView(div, new LinearLayout.LayoutParams(0, 1, 1f));
            card.addView(wrap);
        }
        View row = getLayoutInflater().inflate(R.layout.row_setting, card, false);
        ((TextView) row.findViewById(R.id.rowIcon)).setText(icon);
        ((TextView) row.findViewById(R.id.rowTitle)).setText(title);
        TextView vv = row.findViewById(R.id.rowValue);
        if (value == null) {
            vv.setVisibility(View.GONE);
        } else {
            vv.setText(value);
            try {
                vv.setTextColor(ThemeHelper.attrColor(this, R.attr.colorOnSurfaceVariant));
            } catch (Exception ignored) {}
        }
        row.findViewById(R.id.rowChevron)
                .setVisibility(chevron ? View.VISIBLE : View.GONE);
        if (l != null) row.setOnClickListener(l);
        else { row.setClickable(false); row.setFocusable(false); }
        card.addView(row);
        return row;
    }

    private void buildSettingsRows() {
        // Downloads card.
        LinearLayout cardDownloads = findViewById(R.id.cardDownloads);
        View videoDirRow = addSettingRow(cardDownloads, "📁",
                getString(R.string.set_video_dir), "", true,
                v -> pickFolder(REQ_VIDEO));
        videoFolderTv = videoDirRow.findViewById(R.id.rowValue);
        View audioDirRow = addSettingRow(cardDownloads, "🎵",
                getString(R.string.set_audio_dir), "", true,
                v -> pickFolder(REQ_AUDIO));
        audioFolderTv = audioDirRow.findViewById(R.id.rowValue);
        // Storage-root toggle (All-files access): save outside Downloads.
        View rootRow = addSettingRow(cardDownloads, "\uD83D\uDDC0\uFE0F",
                getString(R.string.root_title), "", true, null);
        TextView rootTv = rootRow.findViewById(R.id.rowValue);
        Runnable refreshRoot = () -> rootTv.setText(DlEngine.useRootStorage(this)
                ? getString(R.string.root_on) : getString(R.string.root_off));
        refreshRoot.run();
        rootRow.setOnClickListener(v -> {
            if (DlEngine.useRootStorage(this)) {
                Toast.makeText(this, R.string.root_on, Toast.LENGTH_SHORT).show();
                return;
            }
            if (android.os.Build.VERSION.SDK_INT < 30) {
                Toast.makeText(this, R.string.root_need_30, Toast.LENGTH_LONG).show();
                return;
            }
            try {
                Intent i = new Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(
                            android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {}
            }
        });
        View parallelRow = addSettingRow(cardDownloads, "📥",
                getString(R.string.set_parallel), "", true, null);
        TextView parallelTv = parallelRow.findViewById(R.id.rowValue);
        Runnable refreshParallel = () -> parallelTv.setText(
                getString(R.string.fmt_parallel, Prefs.maxParallel(this)));
        refreshParallel.run();
        parallelRow.setOnClickListener(v -> {
            int cur = Prefs.maxParallel(this);
            String[] labels = {"1", "2", "3", "4", "5"};
            new AlertDialog.Builder(this)
                    .setTitle(R.string.set_parallel)
                    .setSingleChoiceItems(labels, cur - 1, (d, which) -> {
                        Prefs.setMaxParallel(this, which + 1);
                        refreshParallel.run();
                        d.dismiss();
                    })
                    .show();
        });

        // Google account (optional): unlocks age-restricted videos.
        View gRow = addSettingRow(cardDownloads, "\uD83D\uDC64",
                getString(R.string.google_account), "", true, null);
        TextView gTv = gRow.findViewById(R.id.rowValue);
        refreshGoogleRow = () -> gTv.setText(getString(
                GoogleLogin.isLoggedIn(this)
                        ? R.string.google_logged_in : R.string.google_logged_out));
        refreshGoogleRow.run();
        gRow.setOnClickListener(v -> {
            if (GoogleLogin.isLoggedIn(this)) {
                // Logged in: show useful options, not just logout.
                String[] opts = {
                        getString(R.string.google_my_subs),
                        getString(R.string.google_logout),
                };
                new AlertDialog.Builder(this)
                        .setTitle(R.string.google_choose)
                        .setItems(opts, (d, which) -> {
                            if (which == 0) {
                                showMySubscriptions();
                            } else {
                                GoogleLogin.logout(this, () ->
                                    runOnUiThread(() -> {
                                        refreshGoogleRow.run();
                                        Toast.makeText(this,
                                                R.string.google_logout_done,
                                                Toast.LENGTH_SHORT).show();
                                    }));
                            }
                            d.dismiss();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            } else {
                startActivity(new Intent(this, LoginActivity.class));
            }
        });

        // Appearance & language card.
        LinearLayout cardAppearance = findViewById(R.id.cardAppearance);
        View themeRow = addSettingRow(cardAppearance, "🎨",
                getString(R.string.set_theme), "", true, null);
        TextView themeTv = themeRow.findViewById(R.id.rowValue);
        Runnable refreshTheme = () -> themeTv.setText(
                getString(R.string.fmt_theme, themeLabel(Prefs.themeMode(this))));
        refreshTheme.run();
        themeRow.setOnClickListener(v -> {
            int cur = Prefs.themeMode(this);
            String[] labels = {
                    getString(R.string.theme_system),
                    getString(R.string.theme_light),
                    getString(R.string.theme_dark)};
            new AlertDialog.Builder(this)
                    .setTitle(R.string.set_theme)
                    .setSingleChoiceItems(labels, cur, (d, which) -> {
                        Prefs.setThemeMode(this, which);
                        refreshTheme.run();
                        d.dismiss();
                        recreate(); // apply theme immediately
                    })
                    .show();
        });
        addSettingRow(cardAppearance, "🌐", getString(R.string.set_lang),
                "ar".equals(Prefs.lang(this)) ? "العربية" : "English", true, v -> {
                    Prefs.toggleLang(this);
                    Intent i = getPackageManager()
                            .getLaunchIntentForPackage(getPackageName());
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } else {
                        recreate();
                    }
                });

        // Playback card.
        LinearLayout cardPlayback = findViewById(R.id.cardPlayback);
        View bgRow = addSettingRow(cardPlayback, "🔊",
                getString(R.string.set_background), "", true, null);
        TextView bgTv = bgRow.findViewById(R.id.rowValue);
        Runnable refreshBg = () -> bgTv.setText(getString(
                Prefs.backgroundPlayback(this)
                        ? R.string.background_on : R.string.background_off));
        refreshBg.run();
        bgRow.setOnClickListener(v -> {
            Prefs.setBackgroundPlayback(this, !Prefs.backgroundPlayback(this));
            refreshBg.run();
        });

        // Telegram card.
        LinearLayout cardTelegram = findViewById(R.id.cardTelegram);
        addSettingRow(cardTelegram, "✈", getString(R.string.set_telegram),
                "t.me/flashytube", true, v -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://t.me/flashytube")));
                    } catch (Exception ignored) {}
                });
    }

    // ---------- My channels ----------

    private String themeLabel(int mode) {
        switch (mode) {
            case Prefs.THEME_LIGHT: return getString(R.string.theme_light);
            case Prefs.THEME_DARK: return getString(R.string.theme_dark);
            default: return getString(R.string.theme_system);
        }
    }

    private void refreshBlacklist() {
        blacklistBox.removeAllViews();
        List<String> list = Prefs.blacklist(this);
        if (list.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(R.string.blacklist_empty);
            t.setTextSize(12);
            blacklistBox.addView(t);
            return;
        }
        LayoutInflater inf = LayoutInflater.from(this);
        for (String entry : list) {
            View row = inf.inflate(R.layout.row_mychannel, blacklistBox, false);
            TextView name = row.findViewById(R.id.chName);
            name.setText(Prefs.blName(entry));
            row.findViewById(R.id.chDel).setOnClickListener(v -> {
                Prefs.removeBlacklist(this, Prefs.blUrl(entry));
                refreshBlacklist();
            });
            blacklistBox.addView(row);
        }
    }

    private void pickFolder(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req != REQ_VIDEO && req != REQ_AUDIO) return;
        Uri uri = data.getData();
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_folder_fail, Toast.LENGTH_LONG).show();
            return;
        }
        String old = req == REQ_VIDEO ? Prefs.videoTree(this) : Prefs.audioTree(this);
        if (old != null && !old.isEmpty() && !old.equals(uri.toString())) {
            try {
                getContentResolver().releasePersistableUriPermission(
                        Uri.parse(old), flags);
            } catch (Exception ignored) {}
        }
        if (req == REQ_VIDEO) Prefs.setVideoTree(this, uri.toString());
        else Prefs.setAudioTree(this, uri.toString());
        refreshLabels();
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
    }

    private void refreshLabels() {
        videoFolderTv.setText(folderLabel(Prefs.videoTree(this), Prefs.videoDir(this)));
        audioFolderTv.setText(folderLabel(Prefs.audioTree(this), Prefs.audioDir(this)));
    }

    private String folderLabel(String tree, String legacyDir) {
        if (tree != null && !tree.isEmpty()) return friendlyName(tree);
        return getString(R.string.folder_default, legacyDir);
    }

    private String friendlyName(String treeUriStr) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUriStr));
            int i = docId.indexOf(':');
            String p = i >= 0 ? docId.substring(i + 1) : docId;
            return p.isEmpty() ? docId : p;
        } catch (Exception e) {
            return treeUriStr;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedDark != ThemeHelper.isDark(this)) { recreate(); return; }
        refreshBlacklist();
        // LoginActivity may have signed the user in while we were away.

        if (refreshGoogleRow != null) refreshGoogleRow.run();
    }
    /**
     * Subscriptions, natively: fetch the real channel list via
     * SubscriptionHelper (InnerTube). Tapping a channel opens it inside
     * the app; the import button adds them all to favorites. The old
     * WebView page is kept only as a fallback (its taps are bridged too).
     */
    private void showMySubscriptions() {
        android.app.ProgressDialog prog = new android.app.ProgressDialog(this);
        prog.setMessage(getString(R.string.google_subs_loading));
        prog.setCancelable(true);
        prog.show();
        new Thread(() -> {
            java.util.List<SubscriptionHelper.Channel> subs =
                    SubscriptionHelper.getSubscriptions(this);
            runOnUiThread(() -> {
                try { prog.dismiss(); } catch (Exception ignored) {}
                if (subs == null || subs.isEmpty()) {
                    showSubscriptionsWebView();
                    return;
                }
                String[] names = new String[subs.size()];
                for (int i = 0; i < subs.size(); i++) {
                    String n = subs.get(i).name;
                    names[i] = (n == null || n.isEmpty())
                            ? subs.get(i).id : n;
                }
                new android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.google_my_subs)
                        .setItems(names, (d, which) -> {
                            android.content.Intent in =
                                    new android.content.Intent(
                                            this, MainActivity.class);
                            in.putExtra("open_url", subs.get(which).url);
                            startActivity(in);
                        })
                        .setPositiveButton(R.string.google_import_favs,
                                (d, w) -> {
                            int added = 0;
                            for (SubscriptionHelper.Channel ch : subs) {
                                String n = (ch.name == null
                                        || ch.name.isEmpty())
                                        ? Prefs.labelFromUrl(ch.url)
                                        : ch.name;
                                if (Prefs.addFavorite(this, "channel", n,
                                        ch.url, "")) {
                                    added++;
                                }
                            }
                            android.widget.Toast.makeText(this,
                                    getString(R.string.toast_fav_imported,
                                            added),
                                    android.widget.Toast.LENGTH_LONG).show();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }).start();
    }

    /** Fallback: the subscriptions web page, with a JS click bridge. */
    private void showSubscriptionsWebView() {
        // Visible WebView dialog: YouTube renders the user's own
        // subscriptions page (same login session). m.youtube is a SPA, so
        // shouldOverrideUrlLoading never sees channel taps; a JS bridge
        // catches clicks on channel links and opens them in the app.
        android.webkit.WebView wv = new android.webkit.WebView(this);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.getSettings().setDomStorageEnabled(true);

        android.app.AlertDialog dlg =
                new android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.google_my_subs)
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();

        wv.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void openChannel(String url) {
                runOnUiThread(() -> {
                    try { dlg.dismiss(); } catch (Exception ignored) {}
                    android.content.Intent in =
                            new android.content.Intent(
                                    SettingsActivity.this,
                                    MainActivity.class);
                    in.putExtra("open_url", url);
                    startActivity(in);
                });
            }
        }, "FTBridge");

        wv.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    android.webkit.WebView view,
                    android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Channel/user links -> open in app.
                if (url.contains("/channel/")
                        || url.contains("/user/")
                        || url.contains("/@")) {
                    try { dlg.dismiss(); } catch (Exception ignored) {}
                    android.content.Intent in =
                            new android.content.Intent(
                                    SettingsActivity.this,
                                    MainActivity.class);
                    in.putExtra("open_url", url);
                    startActivity(in);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(android.webkit.WebView view,
                                       String url) {
                view.evaluateJavascript(
                        "document.addEventListener('click',function(e){"
                        + "var a=e.target.closest('a[href]');if(!a)return;"
                        + "var h=a.getAttribute('href')||'';"
                        + "if(/\\/(channel\\/|@|user\\/|c\\/)/.test(h)){"
                        + "e.preventDefault();"
                        + "FTBridge.openChannel("
                        + "new URL(h,location.href).href);}},true);",
                        null);
            }
        });

        // Wrap in a container with padding.
        android.widget.FrameLayout holder =
                new android.widget.FrameLayout(this);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        holder.setPadding(pad, pad, pad, pad);
        holder.addView(wv,
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        (int) (420 * getResources().getDisplayMetrics().density)));
        dlg.setView(holder);
        dlg.show();
        wv.loadUrl("https://m.youtube.com/feed/channels");
    }

}
