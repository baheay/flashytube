package com.flashytube.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;

/**
 * App-wide mini audio bar. Included above the bottom tab bar on every
 * tabbed screen. Contains: thumb, seek bar, speed slider popup, play/pause, close.
 */
public class MiniBar {

    private final Activity act;
    private final View bar;
    private final ImageView thumb;
    private final SeekBar seek;
    private final Button speedBtn;
    private final Button playBtn;
    private boolean userSeeking = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioPlayer.Listener listener;

    private MiniBar(Activity act) {
        this.act = act;
        listener = () -> act.runOnUiThread(this::refresh);
        bar = act.findViewById(R.id.miniBar);
        thumb = act.findViewById(R.id.miniThumb);
        seek = act.findViewById(R.id.miniSeek);
        speedBtn = act.findViewById(R.id.miniSpeed);
        playBtn = act.findViewById(R.id.miniPlay);
        Button close = act.findViewById(R.id.miniClose);

        playBtn.setOnClickListener(v -> {
            AudioPlayer.get(act).toggle();
            refreshProgress();
        });
        speedBtn.setOnClickListener(v -> showSpeedPicker());
        close.setOnClickListener(v -> AudioPlayer.get(act).stop());

        // Tapping the icon jumps back to the playing video (or the library
        // for pure audio files).
        thumb.setOnClickListener(v -> AudioPlayer.get(act).openNowPlaying());

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                userSeeking = false;
                AudioPlayer.get(act).seekTo(s.getProgress());
                refreshProgress();
            }
        });
    }

    /** Returns null when the activity layout has no mini bar. */
    public static MiniBar bind(Activity act) {
        if (act.findViewById(R.id.miniBar) == null) return null;
        return new MiniBar(act);
    }

    public void onResume() {
        AudioPlayer.get(act).addListener(listener);
        refresh();
        handler.post(tick);
    }

    public void onPause() {
        AudioPlayer.get(act).removeListener(listener);
        handler.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshProgress();
            handler.postDelayed(this, 1000);
        }
    };

    /** Vertical speed slider popup (same as the video player). */
    private android.widget.PopupWindow speedPopup;

    private void showSpeedPicker() {
        if (speedPopup != null) {
            try { speedPopup.dismiss(); } catch (Exception ignored) {}
            speedPopup = null;
        }
        AudioPlayer ap = AudioPlayer.get(act);
        SpeedSliderView slider = new SpeedSliderView(act,
                AudioPlayer.allSpeeds(), ap.getSpeed(), s -> {
                    ap.setSpeed(s);
                    act.runOnUiThread(this::refresh);
                });
        float d = act.getResources().getDisplayMetrics().density;
        int w = (int) (52 * d), h = (int) (210 * d);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xE61E1E1E);
        bg.setCornerRadius(14 * d);
        slider.setBackground(bg);
        speedPopup = new android.widget.PopupWindow(slider, w, h, true);
        speedPopup.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(0x00000000));
        speedPopup.setOutsideTouchable(true);
        int[] loc = new int[2];
        speedBtn.getLocationOnScreen(loc);
        android.util.DisplayMetrics dm = act.getResources().getDisplayMetrics();
        int x = loc[0] + speedBtn.getWidth() / 2 - w / 2;
        int y = loc[1] - h - (int) (10 * d);
        x = Math.max(0, Math.min(dm.widthPixels - w, x));
        if (y < 0) y = loc[1] + speedBtn.getHeight() + (int) (10 * d);
        speedPopup.showAtLocation(speedBtn,
                android.view.Gravity.NO_GRAVITY, x, y);
    }

    /** Full refresh: visibility + metadata. */
    private void refresh() {
        if (act.isFinishing()) return;
        AudioPlayer ap = AudioPlayer.get(act);
        if (!ap.hasTask()) {
            bar.setVisibility(View.GONE);
            return;
        }
        bar.setVisibility(View.VISIBLE);
        DlEngine.Task t = ap.getTask();
        ThumbLoader.load(act, DlHelper.thumbUrl(t.videoId), thumb);
        speedBtn.setText(ap.speedLabel());
        refreshProgress();
    }

    /** Per-second: seek position + play state only. */
    private void refreshProgress() {
        if (bar.getVisibility() != View.VISIBLE) return;
        AudioPlayer ap = AudioPlayer.get(act);
        if (!ap.hasTask()) return;
        seek.setMax(Math.max(ap.duration(), 1));
        if (!userSeeking) seek.setProgress(ap.position());
        playBtn.setText(ap.isPlaying() ? "⏸" : "▶");
    }
}
