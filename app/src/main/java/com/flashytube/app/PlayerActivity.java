package com.flashytube.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.util.ArrayList;

/** SnapTube-style in-app video player: big controls, speed, prev/next,
 *  fullscreen, and an "All videos" list below.
 *  Leaving the player (back/home) hands the audio to the background
 *  audio player automatically — unless disabled in Settings. */
public class PlayerActivity extends Activity {
    private boolean appliedDark;

    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};

    private View videoBox;
    private View playerUi;
    private VideoView videoView;
    private ImageView thumbIv;
    private View loadingSpinner;
    private MediaPlayer videoMp;
    private TextView titleTv;
    private TextView subTv;
    private PlayerGestureOverlay gestureOsd;
    private android.media.AudioManager audioMan;
    private java.util.List<SubtitleParser.Cue> subCues;
    private boolean subsVisible = true;
    private boolean locked = false;

    private boolean zoomFill = false; // true = fill screen (crop), false = fit
    // Streaming mode (watch directly without downloading).
    private String streamUrl = null;
    private String streamTitle = null;
    private String streamQuality = null;
    private boolean isStream = false;

    /** Launch the player to stream a URL directly (no download). */
    public static void watchStream(Activity act, String url,
                                   String title, String videoId) {
        android.content.Intent i = new android.content.Intent(act,
                PlayerActivity.class);
        i.putExtra("stream_url", url);
        i.putExtra("stream_title", title);
        i.putExtra("stream_vid", videoId);
        act.startActivity(i);
    }
    private ListView queueList;
    // Unified overlay controls (float over the video, portrait + fullscreen).
    private View overlay;
    private FocusHelper focusHelper;
    private SeekBar fsSeek;
    private TextView fsCurTv, fsTotalTv;
    private android.widget.ImageButton fsPlayBtn;
    private android.widget.ImageButton fsPrevBtn, fsNextBtn;
    private android.widget.Button fsSpeedTopBtn;
    private android.widget.TextView fsQualityTv;
    private boolean fsUserSeeking = false;
    private static final long FS_HIDE_MS = 3000;

    private ArrayList<String> queue;
    private int queueIndex = -1;
    private DlEngine.Task task;
    private float speed = 1f;
    private int durationMs = 0;
    private boolean prepared = false;
    private boolean fullscreen = false;
    /** True when leaving for an external player: don't hand audio to the service. */
    private boolean suppressHandoff = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void attachBaseContext(Context n) {
        super.attachBaseContext(Lang.wrap(n));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ThemeHelper.apply(this);
        appliedDark = ThemeHelper.isDark(this);
        setContentView(R.layout.activity_player);

        videoBox = findViewById(R.id.videoBox);
        playerUi = findViewById(R.id.playerUi);
        videoView = findViewById(R.id.playerVideo);
        thumbIv = findViewById(R.id.playerThumb);
        loadingSpinner = findViewById(R.id.playerLoading);
        titleTv = findViewById(R.id.playerTitle);
        DlHelper.singleLineExpandable(titleTv);
        queueList = findViewById(R.id.queueList);
        subTv = findViewById(R.id.playerSub);
        gestureOsd = findViewById(R.id.gestureOsd);
        audioMan = (android.media.AudioManager)
                getSystemService(Context.AUDIO_SERVICE);
        subTv = findViewById(R.id.playerSub);
        gestureOsd = findViewById(R.id.gestureOsd);
        audioMan = (android.media.AudioManager)
                getSystemService(Context.AUDIO_SERVICE);
        // Unified overlay controls (inside videoBox).
        overlay = findViewById(R.id.playerOverlay);
        fsSeek = findViewById(R.id.fsSeek);
        fsCurTv = findViewById(R.id.fsCur);
        fsTotalTv = findViewById(R.id.fsTotal);
        fsPlayBtn = findViewById(R.id.fsPlay);
        fsPrevBtn = findViewById(R.id.fsPrev);
        fsNextBtn = findViewById(R.id.fsNext);
        findViewById(R.id.fsBack).setOnClickListener(v -> finish());
        findViewById(R.id.fsPip).setOnClickListener(v -> enterPip());
        findViewById(R.id.fsMenu).setOnClickListener(v -> showPlayerMenu(v));
        fsSpeedTopBtn = findViewById(R.id.fsSpeedTop);
        fsQualityTv = findViewById(R.id.fsQuality);
        fsSpeedTopBtn.setOnClickListener(v -> showSpeedPicker(v));
        updateSpeedLabel();

        setVideoAspect();

        speed = Prefs.playSpeed(this);
        updateSpeedLabel();

        queue = getIntent().getStringArrayListExtra("queue");
        queueIndex = getIntent().getIntExtra("queue_index", -1);
        String taskId = getIntent().getStringExtra("task_id");

        final boolean[] suppressCenterClick = new boolean[1];
        final android.os.Handler clickHandler = new android.os.Handler(
                android.os.Looper.getMainLooper());
        // Audio focus: pause video when another app starts audio.
        focusHelper = new FocusHelper(this, () -> {
            try {
                if (prepared && videoView.isPlaying()) {
                    videoView.pause();
                    setPlayIcon(false);
                }
            } catch (Exception ignored) {}
        });
        // Unified overlay actions.
        // Center buttons: delay click to let middle-third double-tap win.
        findViewById(R.id.fsPrev).setOnClickListener(v -> clickHandler.postDelayed(() -> {
            if (suppressCenterClick[0]) { suppressCenterClick[0] = false; return; }
            step(-1);
        }, 280));
        findViewById(R.id.fsNext).setOnClickListener(v -> clickHandler.postDelayed(() -> {
            if (suppressCenterClick[0]) { suppressCenterClick[0] = false; return; }
            step(1);
        }, 280));
        fsPlayBtn.setOnClickListener(v -> clickHandler.postDelayed(() -> {
            if (suppressCenterClick[0]) { suppressCenterClick[0] = false; return; }
            toggle();
        }, 280));
        findViewById(R.id.fsExit).setOnClickListener(v -> setFullscreen(!fullscreen));

        fsSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar s) {
                fsUserSeeking = true;
                handler.removeCallbacks(hideFsControls); // don't hide mid-seek
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                fsUserSeeking = false;
                seekTo(s.getProgress());
                pokeFsControls();
            }
        });

        // Any touch on the fullscreen bar resets the auto-hide timer.
        overlay.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) pokeFsControls();
            return false;
        });
        // Tap: fullscreen ? toggle controls : play/pause.
        // Double-tap left/right: seek -10s/+10s (YouTube style).
        // Swipe: left side vertical = brightness, right side = volume,
        //        horizontal = seek (SnapTube style).
        final GestureDetector gd = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (locked) { showUnlockHint(); return true; }
                toggleOverlay();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (locked) return true;
                float x = e.getX();
                int w = videoBox.getWidth();
                if (w > 0 && x < w / 3f) {
                    seekBy(-10000); // left third: -10s
                } else if (w > 0 && x > w * 2f / 3f) {
                    seekBy(10000); // right third: +10s
                } else {
                    // Middle third: entirely play/pause (suppress button clicks).
                    suppressCenterClick[0] = true;
                    toggle();
                }
                return true;
            }
            @Override public boolean onDown(MotionEvent e) { return true; }
        });
        final float[] swipeStart = new float[2];
        final int[] swipeMode = new int[1]; // 0=none, 1=vol, 2=bright, 3=seek, 4=bg-audio
        final int[] swipeBase = new int[1];
        videoBox.setOnTouchListener((v, e) -> {
            if (locked) { gd.onTouchEvent(e); return true; }
            // GestureDetector needs the full event stream for taps.
            gd.onTouchEvent(e);
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                swipeStart[0] = e.getX();
                swipeStart[1] = e.getY();
                swipeMode[0] = 0;
            } else if (action == MotionEvent.ACTION_MOVE && prepared) {
                float dx = e.getX() - swipeStart[0];
                float dy = e.getY() - swipeStart[1];
                int w = videoBox.getWidth();
                int h = videoBox.getHeight();
                if (swipeMode[0] == 0 && w > 0 && h > 0) {
                    // Swipe down in middle third -> background audio (like back).
                    float sx = swipeStart[0];
                    boolean middle = sx > w / 3f && sx < w * 2f / 3f;
                    float adx = Math.abs(dx);
                    float ady = Math.abs(dy);
                    if (middle && dy > h * 0.15f && dy > adx * 1.5f) {
                        swipeMode[0] = 4;
                    } else if (ady > h * 0.06f && ady > adx * 1.8f) {
                        // Clear vertical dominance: volume/brightness.
                        boolean left = e.getX() < w / 2f;
                        swipeMode[0] = left ? 2 : 1;
                        if (swipeMode[0] == 1) {
                            swipeBase[0] = audioMan.getStreamVolume(
                                android.media.AudioManager.STREAM_MUSIC);
                        } else {
                            swipeBase[0] = (int) (getBrightness() * 100);
                        }
                    } else if (adx > w * 0.06f && adx > ady * 1.8f) {
                        // Clear horizontal dominance: seek. Diagonal swipes
                        // stay unlocked so they don't cause accidental seeks.
                        swipeMode[0] = 3;
                        swipeBase[0] = currentPos();
                    }
                    // Otherwise: direction unclear, keep waiting (no accidental mode).
                }
                if (swipeMode[0] == 1) {
                    int max = audioMan.getStreamMaxVolume(
                        android.media.AudioManager.STREAM_MUSIC);
                    int delta = (int) (-dy / h * max * 1.5f);
                    int vol = Math.max(0, Math.min(max,
                            swipeBase[0] + delta));
                    audioMan.setStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC, vol, 0);
                    gestureOsd.show("Volume",
                            vol + " / " + max, (float) vol / max);
                } else if (swipeMode[0] == 2) {
                    int delta = (int) (-dy / h * 100 * 1.5f);
                    int bright = Math.max(1, Math.min(100,
                            swipeBase[0] + delta));
                    setBrightness(bright / 100f);
                    gestureOsd.show("Brightness",
                            bright + "%", bright / 100f);
                } else if (swipeMode[0] == 3 && durationMs > 0) {
                    int delta = (int) (dx / w * durationMs * 0.5f);
                    // Dead zone: ignore tiny movements (< 2s) to avoid jitter seeks.
                    if (Math.abs(delta) < 2000) {
                        gestureOsd.hideNow();
                    } else {
                        int target = Math.max(0, Math.min(durationMs,
                                swipeBase[0] + delta));
                        seekTo(target);
                            gestureOsd.show("Seek",
                                DlHelper.fmtDuration(target / 1000),
                                (float) target / durationMs);
                    }
                }
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                if (swipeMode[0] == 4 && action == MotionEvent.ACTION_UP) {
                    float dy = e.getY() - swipeStart[1];
                    int h = videoBox.getHeight();
                    if (dy > h * 0.25f) {
                        // Swipe down -> play as background audio (like back).
                        onBackPressed();
                        return true;
                    }
                }
                swipeMode[0] = 0;
            }
            return true;
        });

        // Let the gesture detector see touches on center buttons (for double-tap).
        android.view.View.OnTouchListener gdFeed = (v, e) -> {
            gd.onTouchEvent(e);
            return false; // let the button still handle clicks
        };
        fsPlayBtn.setOnTouchListener(gdFeed);
        fsPrevBtn.setOnTouchListener(gdFeed);
        fsNextBtn.setOnTouchListener(gdFeed);

        streamUrl = getIntent().getStringExtra("stream_url");
        if (streamUrl != null && !streamUrl.isEmpty()) {
            isStream = true;
            streamTitle = getIntent().getStringExtra("stream_title");
            String svid = getIntent().getStringExtra("stream_vid");
            if (!loadStream(streamUrl, streamTitle, svid)) {
                Toast.makeText(this, R.string.player_error,
                        Toast.LENGTH_LONG).show();
                finish();
            }
        } else if (!loadTask(taskId)) {
            Toast.makeText(this, R.string.player_error, Toast.LENGTH_LONG).show();
            finish();
        }
        // Show the overlay initially (auto-hides after a few seconds).
        pokeFsControls();
    }

    /** 16:9 video box based on current width; portrait panel sits below it. */
    private void setVideoAspect() {
        videoBox.post(() -> {
            if (fullscreen) return;
            int w = videoBox.getWidth();
            if (w <= 0) return;
            int h = w * 9 / 16;
            ViewGroup.LayoutParams lp = videoBox.getLayoutParams();
            lp.height = h;
            videoBox.setLayoutParams(lp);
            // playerUi is a sibling in the FrameLayout root: push it below the video.
            ViewGroup.LayoutParams ulp = playerUi.getLayoutParams();
            if (ulp instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) ulp).topMargin = h;
                playerUi.setLayoutParams(ulp);
            }
        });
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!fullscreen) setVideoAspect(); // re-fit 16:9 after rotation
    }

    /**
     * Load a direct stream URL (watch without downloading).
     * Uses the same player UI: gestures, speed, subtitles (if cached).
     */
    private boolean loadStream(String url, String title, String videoId) {
        releasePlayer();
        task = null;
        isStream = true;
        prepared = false;
        titleTv.setText(title != null ? title : "");
        if (videoId != null) {
            ThumbLoader.load(this, DlHelper.thumbUrl(videoId), thumbIv);
        }
        thumbIv.setVisibility(View.VISIBLE);
        loadingSpinner.setVisibility(View.VISIBLE);
        videoView.setVisibility(View.VISIBLE);
        fsSeek.setProgress(0);
        fsCurTv.setText("00:00");
        fsTotalTv.setText("00:00");
        refreshSuggestions();
        try {
            videoView.setVideoURI(Uri.parse(url));
            final String sVid = videoId;
            videoView.setOnPreparedListener(mp -> {
                try {
                    int vh = mp.getVideoHeight();
                    if (vh >= 1080) streamQuality = "1080P";
                    else if (vh >= 720) streamQuality = "720P";
                    else if (vh >= 480) streamQuality = "480P";
                    else if (vh >= 360) streamQuality = "360P";
                    else if (vh >= 240) streamQuality = "240P";
                    else if (vh > 0) streamQuality = "144P";
                    updateQualityLabel();
                } catch (Exception ignored) {}
                videoMp = mp;
                prepared = true;
                durationMs = videoView.getDuration();
                thumbIv.setVisibility(View.GONE);
                loadingSpinner.setVisibility(View.GONE);
                onReady();
                setPlayIcon(true);
                // Resume where the user left off.
                int resume = sVid != null ? Prefs.getResume(this, sVid) : 0;
                if (resume > 2000 && durationMs > 0
                        && resume < durationMs - 5000) {
                    videoView.seekTo(resume);
                }
                videoView.start();
            });
            videoView.setOnCompletionListener(mp -> {
                setPlayIcon(false);
            });
            videoView.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, R.string.player_error,
                        Toast.LENGTH_LONG).show();
                finish();
                return true;
            });
        } catch (Exception e) {
            return false;
        }
        // No queue for streams; hide queue UI.
        findViewById(R.id.queueHeader).setVisibility(View.GONE);
        queueList.setVisibility(View.GONE);
        fsPrevBtn.setVisibility(View.GONE);
        fsNextBtn.setVisibility(View.GONE);
        return true;
    }

    /**
     * Loads (or reloads) a task into the player. Returns false when invalid.
     * If the background service was playing this task, its position is kept
     * and the service is stopped (video takes over — mutual exclusion).
     */
    private boolean loadTask(String taskId) {
        releasePlayer();
        task = taskId == null ? null : DlEngine.get(this).findTask(taskId);
        if (task == null || task.publishedUri == null || task.publishedUri.isEmpty()) {
            return false;
        }
        prepared = false;
        titleTv.setText(DlHelper.displayName(task.fileName));
        updateQualityLabel();
        ThumbLoader.load(this, DlHelper.thumbUrl(task.videoId), thumbIv);
        // Poster thumbnail + spinner until the first frame is ready.
        thumbIv.setVisibility(View.VISIBLE);
        loadingSpinner.setVisibility(View.VISIBLE);
        // Taking over from background audio: resume from its position.
        int bgPos = 0;
        try {
            AudioPlayer ap = AudioPlayer.get(this);
            if (ap.hasTask() && ap.getTask() != null
                    && task.id.equals(ap.getTask().id)) {
                bgPos = ap.position();
            }
        } catch (Exception ignored) {}
        // Mutual exclusion: video playback takes over from any background
        // or mini-bar audio.
        try { AudioPlayer.get(this).stop(); } catch (Exception ignored) {}
        videoView.setVisibility(View.VISIBLE);
        fsSeek.setProgress(0);
        fsCurTv.setText("00:00");
        fsTotalTv.setText("00:00");
        final int resumeFromBg = bgPos;
        try {
            videoView.setVideoURI(Uri.parse(task.publishedUri));
            videoView.setOnPreparedListener(mp -> {
                videoMp = mp;
                prepared = true;
                durationMs = videoView.getDuration();
                thumbIv.setVisibility(View.GONE);
                loadingSpinner.setVisibility(View.GONE);
                onReady();
                // Resume where the user left off (background audio position
                // wins when taking over from the service).
                int resume = resumeFromBg > 0
                        ? resumeFromBg : Prefs.getResume(this, task.videoId);
                setPlayIcon(true);
                final int wantSeek = (resume > 2000 && durationMs > 0
                        && resume < durationMs - 5000) ? resume : 0;
                if (wantSeek > 0) {
                    // seekTo() is async: start only after it completes,
                    // otherwise the video begins at 0 and the seek is lost
                    // on some firmware (MIUI). Verify and re-seek if needed.
                    final android.media.MediaPlayer me = mp;
                    try {
                        mp.setOnSeekCompleteListener(mp2 -> {
                            mp2.setOnSeekCompleteListener(null);
                            try { videoView.start(); } catch (Exception ignored) {}
                            new android.os.Handler(
                                    android.os.Looper.getMainLooper())
                                    .postDelayed(() -> {
                                try {
                                    if (videoMp != me) return;
                                    int cur = videoView.getCurrentPosition();
                                    if (Math.abs(cur - wantSeek) > 3000) {
                                        videoView.seekTo(wantSeek);
                                    }
                                } catch (Exception ignored) {}
                            }, 900);
                        });
                        videoView.seekTo(wantSeek);
                    } catch (Exception ignored) {
                        try { videoView.start(); } catch (Exception ignored2) {}
                    }
                } else {
                    videoView.start();
                }
            });
            videoView.setOnCompletionListener(mp -> {
                setPlayIcon(false);
                // Capture first: step() -> saveResume() would otherwise
                // overwrite the clear with the finished video's end position.
                String vid = task != null ? task.videoId : null;
                step(1); // auto-advance to next video in the list
                if (vid != null) Prefs.clearResume(this, vid); // finished: start over next time
            });
            videoView.setOnErrorListener((mp, what, extra) -> {
                fallbackToExternal();
                return true;
            });
        } catch (Exception e) {
            fallbackToExternal();
            return false;
        }
        refreshQueue();
        refreshSuggestions();
        loadSubtitles();
        return true;
    }

    /** Load downloaded subtitles (if any) for the current video. */
    private void loadSubtitles() {
        subCues = null;
        subTv.setVisibility(View.GONE);
        try {
            java.io.File f = DlEngine.get(this).findSubtitleFile(task);
            if (f != null) {
                subCues = SubtitleParser.parse(f);
            }
        } catch (Exception ignored) {}
    }

    /** Update the subtitle overlay for the current position. */
    private void updateSubtitles(int posMs) {
        if (!subsVisible) return;
        if (subCues == null || subCues.isEmpty()) return;
        String txt = SubtitleParser.textAt(subCues, posMs);
        if (txt != null && !txt.isEmpty()) {
            subTv.setText(txt);
            if (subTv.getVisibility() != View.VISIBLE) {
                subTv.setVisibility(View.VISIBLE);
            }
        } else {
            if (subTv.getVisibility() != View.GONE) {
                subTv.setVisibility(View.GONE);
            }
        }
    }

    private void onReady() {
        applyZoom(); // re-apply after rotation/mode change
        fsTotalTv.setText(DlHelper.fmtDuration(durationMs / 1000));
        fsSeek.setMax(Math.max(durationMs, 1));
        fsTotalTv.setText(DlHelper.fmtDuration(durationMs / 1000));
        fsSeek.setMax(Math.max(durationMs, 1));
        applySpeed();
        handler.post(progressTick);
    }

    /** In-app playback failed: open with an external app instead of dying silently. */
    private void fallbackToExternal() {
        suppressHandoff = true; // don't hand broken playback to the service
        loadingSpinner.setVisibility(View.GONE);
        thumbIv.setVisibility(View.GONE);
        try {
            Toast.makeText(this, R.string.player_fallback, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
        try {
            DlHelper.openCompleted(this, task);
        } catch (Exception ignored) {}
        finish();
    }

    private void step(int delta) {
        if (queue == null || queueIndex < 0) return;
        int next = queueIndex + delta;
        if (next < 0 || next >= queue.size()) return;
        saveResume(); // remember where we left the current video
        queueIndex = next;
        loadTask(queue.get(next));
    }

    /**
     * Fullscreen: rotate to landscape, video fills the whole screen, and the
     * controls float OVER the video (translucent bar) taking no extra space.
     * The bar auto-hides after a few seconds; tap the video to bring it back.
     */
    private void setFullscreen(boolean on) {
        fullscreen = on;
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            // Immersive: hide status + navigation bars until the user swipes.
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            ViewGroup.LayoutParams vlp = videoBox.getLayoutParams();
            vlp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            videoBox.setLayoutParams(vlp);
            playerUi.setVisibility(View.GONE);
        } else {
            handler.removeCallbacks(hideFsControls);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            playerUi.setVisibility(View.VISIBLE);
            setVideoAspect(); // restores 16:9 strip + panel position
        }
        // Overlay is inside videoBox: show it in both modes.
        pokeFsControls();
        updateQualityLabel();
    }

    /** Auto-hide the overlay controls after a few idle seconds. */
    private final Runnable hideFsControls = () -> {
        overlay.setVisibility(View.GONE);
    };

    /** Show the overlay (stays visible until the user taps to hide). */
    private void pokeFsControls() {
        handler.removeCallbacks(hideFsControls);
        overlay.setVisibility(View.VISIBLE);
    }

    /** Tap the video: toggle the overlay controls. */
    private void toggleOverlay() {
        if (overlay.getVisibility() == View.VISIBLE) {
            handler.removeCallbacks(hideFsControls);
            overlay.setVisibility(View.GONE);
        } else {
            pokeFsControls();
        }
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) { setFullscreen(false); return; }
        // onPause hands the audio to the background service (when enabled),
        // so back simply leaves the screen while playback continues.
        super.onBackPressed();
    }

    /** "All videos" list under the player. */
    private void refreshQueue() {
        boolean hasQueue = queue != null && queue.size() > 1;
        findViewById(R.id.queueHeader).setVisibility(hasQueue ? View.VISIBLE : View.GONE);
        queueList.setVisibility(hasQueue ? View.VISIBLE : View.GONE);
        fsPrevBtn.setVisibility(hasQueue ? View.VISIBLE : View.GONE);
        fsNextBtn.setVisibility(hasQueue ? View.VISIBLE : View.GONE);
        if (!hasQueue) return;
        QueueAdapter qa = new QueueAdapter();
        queueList.setAdapter(qa);
        queueList.setOnItemClickListener((p, v, pos, id) -> {
            queueIndex = pos;
            loadTask(queue.get(pos));
        });
        if (queueIndex >= 0) queueList.setSelection(queueIndex);
    }

    /** Suggestions below the player: downloads for downloaded videos,
     *  favorites for direct streams (SnapTube-style "up next"). */
    private void refreshSuggestions() {
        boolean hasQueue = queue != null && queue.size() > 1;
        if (hasQueue) return; // queue takes precedence
        try {
            java.util.List<Object> sug = new java.util.ArrayList<>();
            String header = null;
            if (isStream) {
                // From favorites (video type).
                java.util.List<String> favs = Prefs.favorites(this);
                for (String f : favs) {
                    String[] parts = f.split("\\|", -1);
                    if (parts.length >= 4 && "video".equals(parts[0])) {
                        sug.add(parts); // [type, name, url, thumb]
                        if (sug.size() >= 10) break;
                    }
                }
                header = "Suggestions";
            } else if (task != null) {
                // From other downloaded videos.
                java.util.List<DlEngine.Task> tasks = DlEngine.get(this).getTasks();
                for (DlEngine.Task t : tasks) {
                    if ("video".equals(t.kind) && t.state == DlEngine.State.COMPLETED
                            && !t.id.equals(task.id)) {
                        sug.add(t);
                        if (sug.size() >= 10) break;
                    }
                }
                header = getString(R.string.player_all_videos);
            }
            if (sug.isEmpty()) {
                findViewById(R.id.queueHeader).setVisibility(View.GONE);
                queueList.setVisibility(View.GONE);
                return;
            }
            android.widget.TextView hdr = findViewById(R.id.queueHeader);
            hdr.setText(header != null ? header : "");
            hdr.setVisibility(View.VISIBLE);
            queueList.setVisibility(View.VISIBLE);
            final java.util.List<Object> items = sug;
            queueList.setAdapter(new android.widget.BaseAdapter() {
                @Override public int getCount() { return items.size(); }
                @Override public Object getItem(int p) { return items.get(p); }
                @Override public long getItemId(int p) { return p; }
                @Override
                public View getView(int pos, View cv, ViewGroup parent) {
                    if (cv == null) {
                        cv = LayoutInflater.from(PlayerActivity.this)
                                .inflate(R.layout.row_queue, parent, false);
                    }
                    Object o = items.get(pos);
                    ImageView thumb = cv.findViewById(R.id.qThumb);
                    TextView name = cv.findViewById(R.id.qName);
                    TextView meta = cv.findViewById(R.id.qMeta);
                    View now = cv.findViewById(R.id.qNow);
                    now.setVisibility(View.GONE);
                    if (o instanceof DlEngine.Task) {
                        DlEngine.Task t = (DlEngine.Task) o;
                        ThumbLoader.load(PlayerActivity.this,
                                DlHelper.thumbUrl(t.videoId), thumb);
                        name.setText(DlHelper.displayName(t.fileName));
                        String dur = DlHelper.fmtDuration(t.durationSec);
                        String size = t.total > 0 ? DlHelper.fmtSize(t.total) : "";
                        meta.setText(dur + (size.isEmpty() ? "" : " | " + size));
                    } else {
                        String[] parts = (String[]) o;
                        ThumbLoader.load(PlayerActivity.this, parts[3], thumb);
                        name.setText(parts[1]);
                        meta.setText("");
                    }
                    DlHelper.singleLineExpandable(name);
                    return cv;
                }
            });
            queueList.setOnItemClickListener((par, v, pos, id) -> {
                Object o = items.get(pos);
                if (o instanceof DlEngine.Task) {
                    loadTask(((DlEngine.Task) o).id);
                } else {
                    String[] parts = (String[]) o;
                    playFavoriteStream(parts[2], parts[1]);
                }
            });
        } catch (Exception ignored) {}
    }

    /** Play a favorite video URL as a direct stream. */
    private void playFavoriteStream(String pageUrl, String title) {
        try {
            android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
            pd.setMessage("Loading...");
            pd.setCancelable(false);
            pd.show();
            new Thread(() -> {
                try {
                    DlHelper.Resolved r = DlHelper.resolveStream(pageUrl,
                            DlHelper.Q_VIDEO_720);
                    String streamUrl = null;
                    String vid = null;
                    if (r != null && r.url != null) {
                        streamUrl = r.url;
                        vid = DlHelper.extractVideoId(pageUrl);
                    }
                    final String fUrl = streamUrl;
                    final String fVid = vid;
                    runOnUiThread(() -> {
                        try { pd.dismiss(); } catch (Exception ignored) {}
                        if (fUrl != null) {
                            loadStream(fUrl, title, fVid);
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        try { pd.dismiss(); } catch (Exception ignored) {}
                    });
                }
            }).start();
        } catch (Exception ignored) {}
    }

    private class QueueAdapter extends BaseAdapter {
        @Override public int getCount() { return queue.size(); }
        @Override public Object getItem(int p) { return queue.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            if (cv == null) {
                cv = LayoutInflater.from(PlayerActivity.this)
                        .inflate(R.layout.row_queue, parent, false);
            }
            DlEngine.Task t = DlEngine.get(PlayerActivity.this).findTask(queue.get(pos));
            ImageView thumb = cv.findViewById(R.id.qThumb);
            TextView name = cv.findViewById(R.id.qName);
            TextView meta = cv.findViewById(R.id.qMeta);
            View now = cv.findViewById(R.id.qNow);
            if (t != null) {
                ThumbLoader.load(PlayerActivity.this,
                        DlHelper.thumbUrl(t.videoId), thumb);
                name.setText(DlHelper.displayName(t.fileName));
                DlHelper.singleLineExpandable(name);
                String dur = DlHelper.fmtDuration(t.durationSec);
                String size = t.total > 0 ? DlHelper.fmtSize(t.total) : "";
                meta.setText(dur + (size.isEmpty() ? "" : " | " + size));
            }
            now.setVisibility(pos == queueIndex ? View.VISIBLE : View.GONE);
            return cv;
        }
    }

    private int currentPos() {
        try { return videoView.getCurrentPosition(); }
        catch (Exception ignored) { return 0; }
    }

    private void toggle() {
        if (!prepared) return;
        try {
            if (videoView.isPlaying()) {
                videoView.pause();
                setPlayIcon(false);
            } else {
                if (focusHelper != null) focusHelper.request();
                videoView.start();
                setPlayIcon(true);
            }
        } catch (Exception ignored) {}
    }

    private void seekTo(int ms) {
        if (!prepared) return;
        try { videoView.seekTo(ms); } catch (Exception ignored) {}
    }

    /** SnapTube-style double-tap seek: jump by ms with OSD feedback. */
    private void seekBy(int ms) {
        if (!prepared) return;
        try {
            int pos = videoView.getCurrentPosition();
            int dur = videoView.getDuration();
            int target = pos + ms;
            if (target < 0) target = 0;
            if (dur > 0 && target > dur) target = dur;
            videoView.seekTo(target);
            // OSD feedback.
            String label = (ms < 0 ? "-" : "+") + (Math.abs(ms) / 1000) + "s";
            if (gestureOsd != null) {
                gestureOsd.show("", label, -1);
            }
        } catch (Exception ignored) {}
    }

    /** Enter Picture-in-Picture mode (SnapTube-style). */
    private void enterPip() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                // Hide controls in PiP (they freeze over the tiny window).
                overlay.setVisibility(View.GONE);
                android.app.PictureInPictureParams params =
                        new android.app.PictureInPictureParams.Builder()
                                .setAspectRatio(new android.util.Rational(16, 9))
                                .build();
                enterPictureInPictureMode(params);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip,
            android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        try {
            if (inPip) {
                // PiP: video only, no controls.
                overlay.setVisibility(View.GONE);
                if (playerUi != null) playerUi.setVisibility(View.GONE);
            } else {
                // Back from PiP: restore the layout.
                if (playerUi != null) playerUi.setVisibility(View.VISIBLE);
                setFullscreen(fullscreen); // re-apply videoBox dimensions
                pokeFsControls();
            }
        } catch (Exception ignored) {}
    }

    /** The ⋮ menu: speed, lock, zoom, subtitles. */
    private void showPlayerMenu(View anchor) {
        try {
            android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
            pm.getMenu().add(0, 1, 0, "Speed: " + speed + "x");
            pm.getMenu().add(0, 2, 0, locked ? "Unlock screen" : "Lock screen");
            pm.getMenu().add(0, 3, 0, "Zoom: " + (zoomFill ? "fill" : "fit"));
            pm.getMenu().add(0, 4, 0, "Subtitles: " + (subsVisible ? "on" : "off"));
            pm.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == 1) showSpeedPicker(anchor);
                else if (id == 2) toggleLock();
                else if (id == 3) toggleZoom();
                else if (id == 4) toggleSubs();
                return true;
            });
            pm.show();
        } catch (Exception ignored) {}
    }

    /** Thin vertical speed slider popping out of the tapped speed button. */
    private PopupWindow speedPopup;

    private void dismissSpeedPopup() {
        if (speedPopup != null) {
            try { speedPopup.dismiss(); } catch (Exception ignored) {}
            speedPopup = null;
        }
    }

    private void showSpeedPicker(View anchor) {
        dismissSpeedPopup();
        SpeedSliderView slider = new SpeedSliderView(this, SPEEDS, speed, this::setSpeed);
        float d = getResources().getDisplayMetrics().density;
        int w = (int) (52 * d), h = (int) (210 * d);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE61E1E1E);
        bg.setCornerRadius(14 * d);
        slider.setBackground(bg);
        speedPopup = new PopupWindow(slider, w, h, true);
        speedPopup.setBackgroundDrawable(new ColorDrawable(0x00000000));
        speedPopup.setOutsideTouchable(true);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int x = loc[0] + anchor.getWidth() / 2 - w / 2;
        int y = loc[1] - h - (int) (10 * d);
        x = Math.max(0, Math.min(dm.widthPixels - w, x));
        if (y < 0) y = loc[1] + anchor.getHeight() + (int) (10 * d);
        speedPopup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
    }

    private void setSpeed(float s) {
        speed = s;
        Prefs.setPlaySpeed(this, speed);
        applySpeed();
    }

    private void applySpeed() {
        try {
            if (videoMp != null) {
                // setPlaybackParams() resumes a paused player on some
                // Android versions — restore the paused state if it was.
                boolean wasPlaying = videoMp.isPlaying();
                videoMp.setPlaybackParams(
                        new PlaybackParams().setSpeed(speed));
                if (!wasPlaying && videoMp.isPlaying()) videoMp.pause();
            }
        } catch (Exception ignored) {}
        updateSpeedLabel();
    }

    private void updateSpeedLabel() {
        String s = (speed == (int) speed)
                ? String.valueOf((int) speed)
                : String.valueOf(speed);
        if (fsSpeedTopBtn != null) fsSpeedTopBtn.setText(s + "x");
    }

    /** Show the video quality (SnapTube-style, fullscreen only). */
    private void updateQualityLabel() {
        if (fsQualityTv == null) return;
        String q = "";
        try {
            if (task != null) {
                int qual = task.quality;
                if (qual == DlHelper.Q_VIDEO_1080) q = "1080P";
                else if (qual == DlHelper.Q_VIDEO_720) q = "720P";
                else if (qual == DlHelper.Q_VIDEO_480) q = "480P";
                else if (qual == DlHelper.Q_VIDEO_360) q = "360P";
                else if (qual == DlHelper.Q_VIDEO_240) q = "240P";
                else if (qual == DlHelper.Q_VIDEO_144) q = "144P";
            } else if (streamQuality != null && !streamQuality.isEmpty()) {
                q = streamQuality.toUpperCase();
            }
        } catch (Exception ignored) {}
        // SnapTube shows quality only in fullscreen.
        if (fullscreen && q != null && !q.isEmpty()) {
            fsQualityTv.setText(q);
            fsQualityTv.setVisibility(View.VISIBLE);
        } else {
            fsQualityTv.setVisibility(View.GONE);
        }
    }

    /** Keep both play buttons (portrait + fullscreen overlay) in sync. */
    private void setPlayIcon(boolean playing) {
        fsPlayBtn.setImageResource(playing
                ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    /** Current window brightness 0..1. */
    private float getBrightness() {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            float b = lp.screenBrightness;
            return b < 0 ? 0.5f : b;
        } catch (Exception ignored) { return 0.5f; }
    }

    private void setBrightness(float b) {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = Math.max(0.01f, Math.min(1f, b));
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
    }

    /** Toggle the touch lock (SnapTube style). */
    private void toggleLock() {
        locked = !locked;
        if (locked) {
            if (fullscreen) overlay.setVisibility(View.GONE);
            gestureOsd.show("Screen locked", "tap to unlock", 1f);
        } else {
            gestureOsd.show("Screen unlocked", "", 0f);
            if (fullscreen) pokeFsControls();
        }
    }

    private void showUnlockHint() {
        gestureOsd.show("Locked", "tap the lock button", 1f);
    }

    /** Toggle between fit (black bars) and fill (crop to fill screen). */
    private void toggleZoom() {
        zoomFill = !zoomFill;
        applyZoom();
        gestureOsd.show(zoomFill ? "Fill screen" : "Fit screen",
                zoomFill ? "cropped" : "full", zoomFill ? 1f : 0f);
    }

    /** Toggle subtitle visibility. */
    private void toggleSubs() {
        subsVisible = !subsVisible;
        try {
            android.widget.TextView subTv = findViewById(R.id.playerSub);
            if (subTv != null) subTv.setVisibility(subsVisible ? View.VISIBLE : View.GONE);
        } catch (Exception ignored) {}
    }

    /** Apply the zoom mode by resizing the VideoView (centerCrop effect). */
    private void applyZoom() {
        if (videoMp == null || !prepared) return;
        try {
            int vw = videoMp.getVideoWidth();
            int vh = videoMp.getVideoHeight();
            int cw = videoBox.getWidth();
            int ch = videoBox.getHeight();
            if (vw <= 0 || vh <= 0 || cw <= 0 || ch <= 0) return;
            ViewGroup.LayoutParams lp = videoView.getLayoutParams();
            if (zoomFill) {
                // Scale to fill the container, cropping the overflow.
                float scale = Math.max((float) cw / vw, (float) ch / vh);
                lp.width = (int) (vw * scale);
                lp.height = (int) (vh * scale);
            } else {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            }
            videoView.setLayoutParams(lp);
        } catch (Exception ignored) {}
    }

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            int pos = currentPos();
            String t = DlHelper.fmtDuration(pos / 1000);
            if (!fsUserSeeking && durationMs > 0) fsSeek.setProgress(pos);
            fsCurTv.setText(t);
            if (!fsUserSeeking && durationMs > 0) fsSeek.setProgress(pos);
            fsCurTv.setText(t);
            updateSubtitles(pos);
            handler.postDelayed(this, 500);
        }
    };

    private void releasePlayer() {
        handler.removeCallbacks(progressTick);
        prepared = false;
        try { videoView.stopPlayback(); } catch (Exception ignored) {}
        videoMp = null;
    }

    /** Save the stop position so reopening this video resumes here. */
    private void saveResume() {
        try {
            if (task != null && task.videoId != null && prepared) {
                Prefs.setResume(this, task.videoId, currentPos());
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
        dismissSpeedPopup();
        boolean wasPlaying = false;
        int pos = 0;
        try {
            if (prepared) {
                pos = videoView.getCurrentPosition();
                if (videoView.isPlaying()) {
                    wasPlaying = true;
                    videoView.pause();
                    setPlayIcon(false);
                }
            }
        } catch (Exception ignored) {}
        // Hand over to the background audio player ONLY if the video was
        // actually playing. If the user paused it, stay paused everywhere.
        // This is the same player behind the mini audio bar, which is proven
        // to keep playing in the background on this device.
        boolean bgPref = Prefs.backgroundPlayback(this);
        if (!suppressHandoff && prepared && wasPlaying && bgPref) {
            try {
                if (task != null) {
                    AudioPlayer.get(this).play(task, pos, speed);
                } else if (isStream && streamUrl != null) {
                    // Stream: hand to background audio via synthetic task.
                    DlEngine.Task st = new DlEngine.Task();
                    st.id = "stream-" + System.currentTimeMillis();
                    st.publishedUri = streamUrl;
                    st.fileName = streamTitle != null ? streamTitle : "stream";
                    st.videoId = getIntent().getStringExtra("stream_vid");
                    AudioPlayer.get(this).play(st, pos, speed);
                }
            } catch (Exception ignored) {}
        }
        saveResume();
        // Save stream position too.
        if (isStream) {
            try {
                String sVid = getIntent().getStringExtra("stream_vid");
                if (sVid != null) Prefs.setResume(this, sVid, pos);
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        if (focusHelper != null) focusHelper.abandon();
        dismissSpeedPopup();
        saveResume();
        super.onDestroy();
        releasePlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedDark != ThemeHelper.isDark(this)) { recreate(); return; }
        // Came back while background audio is playing this task/stream:
        // take playback back into the video, from the audio position.
        try {
            AudioPlayer ap = AudioPlayer.get(this);
            boolean mine = task != null && prepared && ap.hasTask()
                    && ap.getTask() != null && task.id.equals(ap.getTask().id);
            // Stream takeback: match by URL.
            if (!mine && isStream && prepared && ap.hasTask()
                    && ap.getTask() != null && streamUrl != null
                    && streamUrl.equals(ap.getTask().publishedUri)) {
                mine = true;
            }
            if (mine) {
                final int wantSeek = ap.position();
                ap.stop();
                setPlayIcon(true);
                try {
                    if (wantSeek > 2000 && videoMp != null) {
                        videoMp.setOnSeekCompleteListener(mp2 -> {
                            mp2.setOnSeekCompleteListener(null);
                            try { videoView.start(); } catch (Exception ignored) {}
                        });
                        videoView.seekTo(wantSeek);
                    } else {
                        videoView.start();
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }
}
