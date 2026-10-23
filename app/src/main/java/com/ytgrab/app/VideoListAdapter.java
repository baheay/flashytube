package com.ytgrab.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Thumbnail rows for the playlist/channel video picker dialogs. */
public class VideoListAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<StreamInfoItem> videos;
    private boolean multi;
    private final Set<Integer> checked = new HashSet<>();

    public VideoListAdapter(Context ctx, List<StreamInfoItem> videos, boolean multi) {
        this.ctx = ctx;
        this.videos = videos;
        this.multi = multi;
    }

    public boolean isMulti() { return multi; }

    public void setMulti(boolean m) {
        multi = m;
        if (!m) checked.clear();
        notifyDataSetChanged();
    }

    /** Check/uncheck every row (select-all toggle). */
    public void setAllChecked(boolean c) {
        checked.clear();
        if (c) {
            for (int i = 0; i < videos.size(); i++) checked.add(i);
        }
        notifyDataSetChanged();
    }

    public boolean isAllChecked() {
        return !videos.isEmpty() && checked.size() == videos.size();
    }

    /** Positions currently checked (multi-select mode). */
    public Set<Integer> getChecked() { return checked; }

    public void toggle(int pos) {
        if (checked.contains(pos)) checked.remove(pos);
        else checked.add(pos);
        notifyDataSetChanged();
    }

    @Override public int getCount() { return videos.size(); }
    @Override public Object getItem(int p) { return videos.get(p); }
    @Override public long getItemId(int p) { return p; }

    @Override
    public View getView(int pos, View cv, ViewGroup parent) {
        if (cv == null) {
            cv = LayoutInflater.from(ctx).inflate(R.layout.row_videolist, parent, false);
        }
        StreamInfoItem v = videos.get(pos);
        ImageView thumb = cv.findViewById(R.id.vlThumb);
        TextView title = cv.findViewById(R.id.vlTitle);
        TextView meta = cv.findViewById(R.id.vlMeta);
        CheckBox check = cv.findViewById(R.id.vlCheck);

        ThumbLoader.load(ctx,
                DlHelper.thumbUrl(DlHelper.extractVideoId(v.getUrl())), thumb);
        title.setText((pos + 1) + ". " + v.getName());
        String m = DlHelper.fmtDuration(v.getDuration());
        try {
            long views = v.getViewCount();
            if (views > 0) {
                if (!m.isEmpty()) m += " • ";
                m += compactViews(views);
            }
        } catch (Exception ignored) {}
        meta.setText(m);
        meta.setVisibility(m.isEmpty() ? View.GONE : View.VISIBLE);

        check.setVisibility(multi ? View.VISIBLE : View.GONE);
        check.setChecked(checked.contains(pos));
        return cv;
    }

    private static String compactViews(long n) {
        if (n >= 1_000_000_000L) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000L) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000L) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
