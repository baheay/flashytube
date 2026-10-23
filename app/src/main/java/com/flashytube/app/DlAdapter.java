package com.flashytube.app;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** List adapter for the in-app download manager (thumbnail + duration + size). */
public class DlAdapter extends BaseAdapter {

    public static final int MODE_ALL = 0;
    public static final int MODE_ACTIVE = 1;          // not completed (download tab)
    public static final int MODE_COMPLETED_VIDEO = 2; // library: videos
    public static final int MODE_COMPLETED_AUDIO = 3; // library: audios
    public static final int MODE_COMPLETED_SUB = 4;   // library: subtitles

    /** Custom tap handling (e.g. library audio -> mini bar instead of player). */
    public interface TapListener { void onTap(DlEngine.Task t); }

    /** Fired when the multi-select set changes (to refresh the action bar). */
    public interface SelListener { void onSelChanged(int count); }

    private final Context ctx;
    private final int mode;
    private TapListener tapListener;
    private SelListener selListener;
    private String filter = "";

    /** Multi-select state (long-press a row to enter). */
    private boolean selMode;
    private final java.util.Set<String> selIds = new java.util.HashSet<>();

    public boolean isSelMode() { return selMode; }
    public int selCount() { return selIds.size(); }
    public java.util.Set<String> getSelIds() { return new java.util.HashSet<>(selIds); }

    public void setSelListener(SelListener l) { selListener = l; }

    public void setSelMode(boolean on) {
        selMode = on;
        if (!on) selIds.clear();
        notifyDataSetChanged();
        if (selListener != null) selListener.onSelChanged(selIds.size());
    }

    public void toggleSel(String id) {
        if (selIds.contains(id)) selIds.remove(id);
        else selIds.add(id);
        notifyDataSetChanged();
        if (selListener != null) selListener.onSelChanged(selIds.size());
    }

    /** Filter rows by name (search box in the library). */
    public void setFilter(String q) {
        filter = q == null ? "" : q.trim();
        notifyDataSetChanged();
    }

    public DlAdapter(Context ctx) { this(ctx, MODE_ALL); }

    public DlAdapter(Context ctx, int mode) {
        this.ctx = ctx;
        this.mode = mode;
    }

    public void setTapListener(TapListener l) { tapListener = l; }

    private List<DlEngine.Task> list() {
        List<DlEngine.Task> all = DlEngine.get(ctx).getTasks();
        List<DlEngine.Task> out = new ArrayList<>();
        synchronized (all) {
            for (DlEngine.Task t : all) {
                if (mode == MODE_ALL) { out.add(t); continue; }
                boolean completed = t.state == DlEngine.State.COMPLETED;
                if (mode == MODE_ACTIVE && !completed) out.add(t);
                else if (mode == MODE_COMPLETED_VIDEO && completed
                        && "video".equals(t.kind)) out.add(t);
                else if (mode == MODE_COMPLETED_AUDIO && completed
                        && "audio".equals(t.kind)) out.add(t);
                else if (mode == MODE_COMPLETED_SUB && completed
                        && "sub".equals(t.kind)) out.add(t);
            }
        }
        if (!filter.isEmpty()) {
            List<DlEngine.Task> f = new ArrayList<>();
            for (DlEngine.Task t : out) {
                if (DlHelper.displayName(t.fileName).contains(filter)) f.add(t);
            }
            return f;
        }
        return out;
    }

    @Override public int getCount() { return list().size(); }
    @Override public Object getItem(int p) { return list().get(p); }
    @Override public long getItemId(int p) { return p; }

    @Override
    public View getView(int pos, View cv, ViewGroup parent) {
        final DlEngine.Task t = (DlEngine.Task) getItem(pos);
        if (cv == null) {
            cv = LayoutInflater.from(ctx).inflate(R.layout.row_download, parent, false);
        }
        ImageView thumb = cv.findViewById(R.id.dlThumb);
        TextView name = cv.findViewById(R.id.dlName);
        TextView meta = cv.findViewById(R.id.dlMeta);
        ProgressBar bar = cv.findViewById(R.id.dlBar);
        TextView status = cv.findViewById(R.id.dlStatus);
        Button menuBtn = cv.findViewById(R.id.dlMenu);

        ThumbLoader.loadForTask(ctx, t, thumb);
        name.setText(DlHelper.displayName(t.fileName));
        // In selection mode the name tap toggles the checkbox; otherwise
        // the name expands/collapses on tap (its own listener would
        // otherwise swallow the row tap and break selection).
        if (selMode) {
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setOnClickListener(v -> toggleSel(t.id));
        } else {
            DlHelper.singleLineExpandable(name);
        }

        boolean active = t.state != DlEngine.State.COMPLETED;
        String dur = DlHelper.fmtDuration(t.durationSec);
        // Size lives in the status line for active tasks (live downloaded/total),
        // so the meta line shows it only for completed ones — no duplication.
        String size = (!active && t.total > 0) ? DlHelper.fmtSize(t.total) : "";
        String metaText = dur + (size.isEmpty() ? "" : " | " + size);
        meta.setText(metaText);
        meta.setVisibility(metaText.isEmpty() ? View.GONE : View.VISIBLE);

        int pct = t.total > 0 ? (int) (t.done * 100 / t.total) : 0;

        // Live progress: "12.5 / 71.3 MB" next to the status.
        String prog = "";
        if (t.total > 0) {
            prog = " • " + DlHelper.fmtSize(t.done) + " / " + DlHelper.fmtSize(t.total);
        } else if (t.done > 0) {
            prog = " • " + DlHelper.fmtSize(t.done);
        }

        switch (t.state) {
            case DOWNLOADING:
                bar.setVisibility(View.VISIBLE);
                bar.setIndeterminate(t.total <= 0);
                bar.setProgress(pct);
                status.setText(ctx.getString(R.string.dl_st_downloading, pct) + prog);
                break;
            case QUEUED:
                bar.setVisibility(View.VISIBLE);
                bar.setIndeterminate(true);
                if (t.wifiOnly && !DlEngine.isWifiConnected(ctx)) {
                    status.setText("\u23f3 " + ctx.getString(R.string.wifi_wait));
                } else {
                    status.setText(ctx.getString(R.string.dl_queued));
                }
                break;
            case PAUSED:
                bar.setVisibility(View.VISIBLE);
                bar.setIndeterminate(false);
                bar.setProgress(pct);
                status.setText(ctx.getString(R.string.dl_st_paused) + prog);
                break;
            case FAILED:
                bar.setVisibility(View.VISIBLE);
                bar.setIndeterminate(false);
                bar.setProgress(pct);
                status.setText(ctx.getString(R.string.dl_failed,
                        t.error.isEmpty() ? ctx.getString(R.string.dl_failed_short) : t.error));
                break;
            case COMPLETED:
                bar.setVisibility(View.GONE);
                status.setText(ctx.getString(R.string.dl_st_done));
                break;
        }

        // ⋮ menu with actions matching the task state.
        menuBtn.setOnClickListener(v -> showRowMenu(menuBtn, t));

        // Multi-select checkbox.
        android.widget.CheckBox selCheck = cv.findViewById(R.id.dlCheck);
        selCheck.setVisibility(selMode ? View.VISIBLE : View.GONE);
        selCheck.setChecked(selIds.contains(t.id));

        // Whole-row tap: in selection mode it toggles the checkbox,
        // otherwise it opens the task (bigger tap target than text-only).
        cv.setOnClickListener(v -> {
            if (selMode) toggleSel(t.id);
            else openTask(t);
        });

        return cv;
    }

    /** ⋮ menu: select + actions matching the task state (pause/resume/retry/cancel/delete/play). */
    private void showRowMenu(View anchor, DlEngine.Task t) {
        DlEngine eng = DlEngine.get(ctx);
        java.util.List<RichMenu.Item> items = new java.util.ArrayList<>();
        // Guaranteed entry into multi-select mode (long-press can be
        // flaky while downloads are actively updating the list).
        items.add(new RichMenu.Item("\u2611", ctx.getString(R.string.m_select),
                () -> { setSelMode(true); toggleSel(t.id); }));
        switch (t.state) {
            case DOWNLOADING:
            case QUEUED:
                items.add(new RichMenu.Item("\u23f8", ctx.getString(R.string.m_pause),
                        () -> { eng.pause(t.id); notifyDataSetChanged(); }));
        items.add(new RichMenu.Item("\uD83D\uDCF6",
                ctx.getString(t.wifiOnly ? R.string.m_run_on_data : R.string.m_wait_for_wifi),
                () -> { eng.setTaskWifiOnly(t.id, !t.wifiOnly); notifyDataSetChanged(); }));
                items.add(new RichMenu.Item("\u2716", ctx.getString(R.string.m_cancel_dl),
                        () -> { eng.cancel(t.id); notifyDataSetChanged(); }));
                break;
            case PAUSED:
                items.add(new RichMenu.Item("\u25b6", ctx.getString(R.string.m_resume),
                        () -> { eng.resume(t.id); notifyDataSetChanged(); }));
        items.add(new RichMenu.Item("\uD83D\uDCF6",
                ctx.getString(t.wifiOnly ? R.string.m_run_on_data : R.string.m_wait_for_wifi),
                () -> { eng.setTaskWifiOnly(t.id, !t.wifiOnly); notifyDataSetChanged(); }));
                items.add(new RichMenu.Item("\u2716", ctx.getString(R.string.m_cancel_dl),
                        () -> { eng.cancel(t.id); notifyDataSetChanged(); }));
                break;
            case FAILED:
                items.add(new RichMenu.Item("\ud83d\udd01", ctx.getString(R.string.m_retry),
                        () -> { eng.resume(t.id); notifyDataSetChanged(); }));
        items.add(new RichMenu.Item("\uD83D\uDCF6",
                ctx.getString(t.wifiOnly ? R.string.m_run_on_data : R.string.m_wait_for_wifi),
                () -> { eng.setTaskWifiOnly(t.id, !t.wifiOnly); notifyDataSetChanged(); }));
                items.add(new RichMenu.Item("\ud83d\uddd1", ctx.getString(R.string.m_delete), true,
                        () -> confirmDelete(t)));
                break;
            case COMPLETED:
                items.add(new RichMenu.Item("\u25b6", ctx.getString(R.string.m_play),
                        () -> openTask(t)));
                items.add(new RichMenu.Item("\ud83d\uddd1", ctx.getString(R.string.m_delete), true,
                        () -> confirmDelete(t)));
                break;
        }
        RichMenu.show((android.app.Activity) ctx, anchor, items);
    }

    /** Confirm, then delete the task AND its file from storage. */
    private void confirmDelete(DlEngine.Task t) {
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.menu_delete)
                .setMessage(ctx.getString(R.string.confirm_delete,
                        DlHelper.displayName(t.fileName)))
                .setPositiveButton(R.string.menu_delete, (d, w) -> {
                    DlEngine.get(ctx).deleteTask(t.id);
                    notifyDataSetChanged();
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    /** Open a completed task the same way tapping the row does. */
    private void openTask(DlEngine.Task t) {
        if (tapListener != null) {
            tapListener.onTap(t);
        } else if (ctx instanceof android.app.Activity) {
            DlHelper.playTask((android.app.Activity) ctx, t);
        }
    }
}
