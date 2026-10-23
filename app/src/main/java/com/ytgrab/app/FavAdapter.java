package com.ytgrab.app;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Favorites sub-tab: videos/channels/playlists saved from search results. */
public class FavAdapter extends BaseAdapter {

    private final Activity act;
    private String filter = "";

    public FavAdapter(Activity act) { this.act = act; }

    public void setFilter(String q) {
        filter = q == null ? "" : q.trim();
        notifyDataSetChanged();
    }

    private List<String> list() {
        List<String> all = Prefs.favorites(act);
        if (filter.isEmpty()) return all;
        List<String> out = new ArrayList<>();
        for (String e : all) {
            if (Prefs.favName(e).contains(filter)) out.add(e);
        }
        return out;
    }

    @Override public int getCount() { return list().size(); }
    @Override public Object getItem(int p) { return list().get(p); }
    @Override public long getItemId(int p) { return p; }

    @Override
    public View getView(int pos, View cv, ViewGroup parent) {
        final String e = (String) getItem(pos);
        if (cv == null) {
            cv = LayoutInflater.from(act).inflate(R.layout.row_favorite, parent, false);
        }
        ImageView thumb = cv.findViewById(R.id.srThumb);
        TextView name = cv.findViewById(R.id.srName);
        TextView meta = cv.findViewById(R.id.srMeta);
        cv.findViewById(R.id.srCheck).setVisibility(View.GONE);
        cv.findViewById(R.id.srHeart).setVisibility(View.GONE);
        TextView menu = cv.findViewById(R.id.srMenu);

        final String type = Prefs.favType(e);
        final String furl = Prefs.favUrl(e);
        name.setText(Prefs.favName(e));
        DlHelper.singleLineExpandable(name);
        String typeLabel = "channel".equals(type) ? act.getString(R.string.type_channel)
                : "playlist".equals(type) ? act.getString(R.string.type_playlist)
                : act.getString(R.string.type_video);
        meta.setText(typeLabel);
        meta.setVisibility(View.VISIBLE);

        if ("video".equals(type)) {
            String vid = DlHelper.extractVideoId(furl);
            if (vid != null) ThumbLoader.load(act, DlHelper.thumbUrl(vid), thumb);
            else thumb.setImageDrawable(null);
        } else {
            // Channel/playlist avatar, saved when the favorite was added.
            String th = Prefs.favThumb(e);
            if (th != null && !th.isEmpty()) {
                ThumbLoader.load(act, th, thumb);
            } else {
                thumb.setImageDrawable(null);
                backfillThumb(type, furl);
            }
        }

        cv.setOnClickListener(v -> DlHelper.fetchAndPick(act, furl, false));
        menu.setOnClickListener(v -> showFavMenu(v, e, type, furl));
        return cv;
    }

    /** Rich per-item menu: download, open in YouTube, share, copy link, remove. */
    private void showFavMenu(View anchor, String entry, String type, String url) {
        final String name = Prefs.favName(entry);
        java.util.List<RichMenu.Item> items = new java.util.ArrayList<>();
        items.add(new RichMenu.Item("\u2b07", act.getString(R.string.m_download_video),
                () -> DlHelper.fetchAndPick(act, url, false)));
        if ("video".equals(type)) {
            items.add(new RichMenu.Item("\uD83D\uDCF6",
                    act.getString(R.string.m_download_wifi),
                    () -> DlHelper.fetchAndPickWifiOnly(act, url, false)));
        }
        items.add(new RichMenu.Item("\u25b6", act.getString(R.string.m_open_youtube),
                () -> DlHelper.openInYoutube(act, url)));
        items.add(new RichMenu.Item("\ud83d\udce4", act.getString(R.string.m_share_link),
                () -> DlHelper.shareUrl(act, url)));
        items.add(new RichMenu.Item("\ud83d\udccb", act.getString(R.string.m_copy_link),
                () -> DlHelper.copyLink(act, url)));
        items.add(new RichMenu.Item("\u2606", act.getString(R.string.m_fav_remove), true,
                () -> {
                    Prefs.removeFavorite(act, url);
                    notifyDataSetChanged();
                    Toast.makeText(act, R.string.toast_fav_removed,
                            Toast.LENGTH_SHORT).show();
                }));
        RichMenu.show(act, anchor, items);
    }

    /** One-time background fetch of a channel/playlist avatar for favorites
     *  saved before v2.9 (no thumb stored). Persists into Prefs on success. */
    private static final java.util.Set<String> thumbFetching =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private void backfillThumb(String type, String url) {
        if (url == null || url.isEmpty() || !thumbFetching.add(url)) return;
        new Thread(() -> {
            String thumb = null;
            try {
                org.schabi.newpipe.extractor.StreamingService svc =
                        org.schabi.newpipe.extractor.NewPipe.getService("YouTube");
                java.util.List<org.schabi.newpipe.extractor.Image> thumbs;
                if ("playlist".equals(type)) {
                    org.schabi.newpipe.extractor.playlist.PlaylistExtractor pe =
                            svc.getPlaylistExtractor(url);
                    pe.fetchPage();
                    thumbs = pe.getThumbnails();
                } else {
                    org.schabi.newpipe.extractor.channel.ChannelExtractor ce =
                            svc.getChannelExtractor(url);
                    ce.fetchPage();
                    thumbs = ce.getAvatars();
                }
                if (thumbs != null && !thumbs.isEmpty())
                    thumb = thumbs.get(0).getUrl();
            } catch (Exception ignored) {}
            thumbFetching.remove(url);
            if (thumb != null && !thumb.isEmpty()) {
                Prefs.setFavThumb(act, url, thumb);
                act.runOnUiThread(this::notifyDataSetChanged);
            }
        }).start();
    }
}
