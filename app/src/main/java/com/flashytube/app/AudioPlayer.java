package com.flashytube.app;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import java.util.ArrayList;
import java.util.List;

/**
 * App-wide singleton audio player. Keeps playing across screens (tabs);
 * the mini audio bar binds to it. Supports playback speed like the video player.
 */
public class AudioPlayer {

    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};

    public interface Listener { void onAudioChange(); }

    private static AudioPlayer inst;

    public static synchronized AudioPlayer get(Context c) {
        if (inst == null) inst = new AudioPlayer(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final List<Listener> listeners = new ArrayList<>();
    private MediaPlayer mp;
    private DlEngine.Task task;
    private float speed = 1f;
    private boolean prepared = false;
    private MediaSession mediaSession;
    private Bitmap thumbBitmap;
    private String thumbForVideoId;
    private FocusHelper focusHelper;

    private AudioPlayer(Context ctx) {
        this.ctx = ctx;
        this.speed = Prefs.playSpeed(ctx);
        // Audio focus: pause when another app starts playing.
        focusHelper = new FocusHelper(ctx, () -> {
            try {
                if (isPlaying()) {
                    mp.pause();
                    updateNotif();
                    notifyChanged();
                }
            } catch (Exception ignored) {}
        });
        handler.post(saver); // periodic resume save while playing
        handler.post(notifTicker); // keep notification progress fresh
    }

    /** Refresh MediaSession progress every second while playing. */
    private final Runnable notifTicker = new Runnable() {
        @Override public void run() {
            try {
                if (task != null && isPlaying()) {
                    // Only push the session state (drives the system seekbar);
                    // rebuilding the notification every second caused flicker.
                    pushPlaybackState();
                }
            } catch (Exception ignored) {}
            handler.postDelayed(this, 1000);
        }
    };

    /** Saves the stop position every 15s while playing (survives process death). */
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable saver = new Runnable() {
        @Override public void run() {
            try {
                if (task != null && task.videoId != null && isPlaying()) {
                    Prefs.setResume(ctx, task.videoId, position());
                }
            } catch (Exception ignored) {}
            handler.postDelayed(this, 15000);
        }
    };

    /** Remember where the current track stopped (when switching/closing). */
    private void saveResume() {
        try {
            if (task != null && task.videoId != null && prepared) {
                Prefs.setResume(ctx, task.videoId, position());
            }
        } catch (Exception ignored) {}
    }

    public void addListener(Listener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        synchronized (listeners) { listeners.remove(l); }
    }

    private void notifyChanged() {
        List<Listener> copy;
        synchronized (listeners) { copy = new ArrayList<>(listeners); }
        for (Listener l : copy) {
            try { l.onAudioChange(); } catch (Exception ignored) {}
        }
    }

    /** Start playing a completed audio task (replaces anything playing). */
    public void play(DlEngine.Task t) {
        play(t, -1, Prefs.playSpeed(ctx));
    }

    /**
     * Start playing a task from a given position (ms). Used by the video
     * player's background handoff. startPosMs < 0 means "resume saved".
     */
    public void play(DlEngine.Task t, int startPosMs, float speed) {
        if (t == null || t.publishedUri == null || t.publishedUri.isEmpty()) return;
        if (focusHelper != null) focusHelper.request(); // stop other apps' audio
        saveResume(); // remember outgoing track's position
        stopInternal();
        task = t;
        prepared = false;
        this.speed = speed;
        try {
            mp = new MediaPlayer();
            mp.setDataSource(ctx, Uri.parse(t.publishedUri));
            final int sp = startPosMs;
            mp.setOnPreparedListener(p -> {
                prepared = true;
                int seekTo = sp;
                if (seekTo < 0) {
                    // Resume where the user left off.
                    int r = Prefs.getResume(ctx, task.videoId);
                    int dur = p.getDuration();
                    if (r > 2000 && dur > 0 && r < dur - 5000) seekTo = r;
                }
                if (seekTo > 0) {
                    // seekTo() is async: start only after it completes,
                    // otherwise playback begins at 0 and the seek is lost.
                    // Some firmware (MIUI) silently drops a pre-start seek,
                    // so verify the landing position and re-seek if needed.
                    final int want = seekTo;
                    final MediaPlayer me = p;
                    try {
                        p.setOnSeekCompleteListener(mp2 -> {
                            mp2.setOnSeekCompleteListener(null);
                            // Set speed AFTER seek (setPlaybackParams can
                            // auto-start from 0 if called before the seek).
                            applySpeed();
                            mp2.start();
                            showNotif();
                            notifyChanged();
                            new android.os.Handler(
                                    android.os.Looper.getMainLooper())
                                    .postDelayed(() -> {
                                try {
                                    if (mp != me || !mp2.isPlaying()) return;
                                    int cur = mp2.getCurrentPosition();
                                    if (Math.abs(cur - want) > 3000) {
                                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                                            mp2.seekTo(want, android.media.MediaPlayer.SEEK_CLOSEST);
                                        } else {
                                            mp2.seekTo(want);
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }, 900);
                        });
                        // SEEK_CLOSEST: precise seek (default seeks to keyframe,
                        // which can be seconds before the target).
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            p.seekTo(seekTo, android.media.MediaPlayer.SEEK_CLOSEST);
                        } else {
                            p.seekTo(seekTo);
                        }
                        return;
                    } catch (Exception ignored) {}
                }
                applySpeed();
                p.start();
                showNotif();
                notifyChanged();
            });
            mp.setOnCompletionListener(p -> {
                // Capture first: stop() nulls the task, and its saveResume()
                // would otherwise overwrite the clear with the end position.
                String vid = task != null ? task.videoId : null;
                stop(); // hides the notification too
                if (vid != null) Prefs.clearResume(ctx, vid); // finished: start over next time
            });
            mp.setOnErrorListener((p, what, extra) -> {
                stop();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            stopInternal();
            task = null;
        }
        notifyChanged();
    }

    public void toggle() {
        if (mp == null || !prepared) return;
        try {
            if (mp.isPlaying()) mp.pause();
            else mp.start();
        } catch (Exception ignored) {}
        updateNotif();
        notifyChanged();
    }

    public void cycleSpeed() {
        int idx = 0;
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - speed) < 0.01f) { idx = i; break; }
        }
        setSpeed(SPEEDS[(idx + 1) % SPEEDS.length]);
    }

    public void setSpeed(float s) {
        speed = s;
        Prefs.setPlaySpeed(ctx, speed);
        applySpeed();
        notifyChanged();
    }

    /** All available speeds (for the picker dialog). */
    public static float[] allSpeeds() { return SPEEDS.clone(); }

    private void applySpeed() {
        try {
            if (mp != null) {
                // setPlaybackParams() resumes a paused player on some
                // Android versions — restore the paused state if it was.
                boolean wasPlaying = mp.isPlaying();
                mp.setPlaybackParams(new PlaybackParams().setSpeed(speed));
                if (!wasPlaying && mp.isPlaying()) mp.pause();
            }
        } catch (Exception ignored) {}
    }

    public void stop() {
        if (focusHelper != null) focusHelper.abandon();
        saveResume();
        stopInternal();
        task = null;
        hideNotif();
        notifyChanged();
    }

    private void stopInternal() {
        prepared = false;
        try {
            if (mp != null) {
                try { mp.stop(); } catch (Exception ignored) {}
                mp.release();
            }
        } catch (Exception ignored) {}
        mp = null;
    }

    public boolean isPlaying() {
        try { return mp != null && prepared && mp.isPlaying(); }
        catch (Exception e) { return false; }
    }

    public boolean hasTask() { return task != null; }

    public DlEngine.Task getTask() { return task; }

    public float getSpeed() { return speed; }

    public String speedLabel() {
        String s = (speed == (int) speed)
                ? String.valueOf((int) speed) : String.valueOf(speed);
        return s + "x";
    }

    public int position() {
        try { return mp != null ? mp.getCurrentPosition() : 0; }
        catch (Exception e) { return 0; }
    }

    public int duration() {
        try { return mp != null && prepared ? mp.getDuration() : 0; }
        catch (Exception e) { return 0; }
    }

    public void seekTo(int ms) {
        try { if (mp != null && prepared) mp.seekTo(ms); }
        catch (Exception ignored) {}
    }

    /** Seek relative (used by notification prev/next). */
    public void seekBy(int deltaMs) {
        try {
            if (mp == null || !prepared) return;
            int pos = mp.getCurrentPosition() + deltaMs;
            if (pos < 0) pos = 0;
            int dur = mp.getDuration();
            if (dur > 0 && pos > dur) pos = dur;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                mp.seekTo(pos, android.media.MediaPlayer.SEEK_CLOSEST);
            } else {
                mp.seekTo(pos);
            }
            updateNotif();
        } catch (Exception ignored) {}
    }

    // ---------- Background notification (plain, no foreground service) ----------

    private static final String NOTIF_CH = "flashytube_audio";
    private static final int NOTIF_ID = 2001;

    private void ensureNotifChannel() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm =
                        (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm.getNotificationChannel(NOTIF_CH) == null) {
                    nm.createNotificationChannel(new NotificationChannel(
                            NOTIF_CH, "تشغيل الصوت",
                            NotificationManager.IMPORTANCE_LOW));
                }
            }
        } catch (Exception ignored) {}
    }

    private PendingIntent notifAction(String action, int code) {
        Intent i = new Intent(ctx, AudioActionReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(ctx, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private MediaSession mediaSession() {
        if (mediaSession == null) {
            try {
                mediaSession = new MediaSession(ctx, "FlashyTubeAudio");
                mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
                mediaSession.setActive(true);
            } catch (Exception ignored) {}
        }
        return mediaSession;
    }

    /** Push the current play state to the MediaSession (lockscreen + seekbar). */
    private void pushPlaybackState() {
        try {
            MediaSession ms = mediaSession();
            if (ms == null) return;
            int st = isPlaying() ? PlaybackState.STATE_PLAYING
                    : PlaybackState.STATE_PAUSED;
            long pos = 0;
            long dur = 0;
            try {
                if (mp != null) {
                    if (prepared) pos = mp.getCurrentPosition();
                    int d = mp.getDuration();
                    if (d > 0) dur = d;
                }
            } catch (Exception ignored) {}
            PlaybackState ps = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY
                            | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_STOP
                            | PlaybackState.ACTION_SEEK_TO)
                    .setState(st, pos, 1.0f)
                    .build();
            ms.setPlaybackState(ps);
            try {
                String title = "FlashyTube";
                if (task != null && task.fileName != null) {
                    title = DlHelper.plainTitle(task.fileName);
                }
                ms.setMetadata(new android.media.MediaMetadata.Builder()
                        .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title)
                        .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, dur)
                        .build());
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    /** Download the video thumbnail in the background for the large icon. */
    private void loadThumbAsync() {
        final String vid;
        try {
            vid = (task != null) ? task.videoId : null;
        } catch (Exception e) { return; }
        if (vid == null || vid.equals(thumbForVideoId)) return;
        thumbForVideoId = vid;
        thumbBitmap = null;
        new Thread(() -> {
            try {
                String url = DlHelper.thumbUrl(vid);
                HttpURLConnection c = (HttpURLConnection)
                        new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                InputStream in = c.getInputStream();
                final Bitmap bmp = BitmapFactory.decodeStream(in);
                try { in.close(); } catch (Exception ignored) {}
                if (bmp != null) {
                    thumbBitmap = bmp;
                    updateNotif();
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private Notification buildNotif() {
        ensureNotifChannel();
        loadThumbAsync();
        pushPlaybackState();
        String title = "FlashyTube";
        try {
            if (task != null && task.fileName != null) {
                title = DlHelper.plainTitle(task.fileName);
            }
        } catch (Exception ignored) {}
        boolean playing = isPlaying();
        PendingIntent content = nowPlayingIntent();
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(ctx, NOTIF_CH);
        } else {
            b = new Notification.Builder(ctx);
        }
        String subtitle = "FlashyTube";
        try {
            if (task != null && task.subfolder != null && !task.subfolder.isEmpty()) {
                // subfolder is often "channel/playlist".
                String sf = task.subfolder;
                int slash = sf.indexOf('/');
                subtitle = slash > 0 ? sf.substring(0, slash) : sf;
            }
        } catch (Exception ignored) {}
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setContentIntent(content)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (thumbBitmap != null) {
            b.setLargeIcon(thumbBitmap);
        }
        // SnapTube-style: prev | play/pause | next | close.
        b.addAction(new Notification.Action.Builder(
                        Icon.createWithResource(ctx,
                                android.R.drawable.ic_media_previous),
                        "السابق",
                        notifAction(AudioActionReceiver.ACTION_PREV, 3)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(ctx, playing
                                ? android.R.drawable.ic_media_pause
                                : android.R.drawable.ic_media_play),
                        playing ? "إيقاف مؤقت" : "تشغيل",
                        notifAction(AudioActionReceiver.ACTION_TOGGLE, 1)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(ctx,
                                android.R.drawable.ic_media_next),
                        "التالي",
                        notifAction(AudioActionReceiver.ACTION_NEXT, 4)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(ctx,
                                android.R.drawable.ic_menu_close_clear_cancel),
                        "إغلاق",
                        notifAction(AudioActionReceiver.ACTION_STOP, 2)).build());
        // SnapTube-style media notification: compact actions + session for
        // lockscreen controls and the system seekbar.
        try {
            MediaSession ms = mediaSession();
            if (ms != null) {
                Notification.MediaStyle style = new Notification.MediaStyle()
                        .setMediaSession(ms.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2);
                b.setStyle(style);
            }
        } catch (Exception ignored) {}
        return b.build();
    }

    /**
     * Notification tap target: a video's audio reopens the video player
     * (it resumes from the background position); pure audio opens the library.
     */
    private PendingIntent nowPlayingIntent() {
        Intent open;
        int code;
        if (task != null && "video".equals(task.kind)) {
            open = new Intent(ctx, PlayerActivity.class);
            open.putExtra("task_id", task.id);
            code = 10;
        } else {
            open = new Intent(ctx, LibraryActivity.class);
            code = 0;
        }
        open.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, code, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Mini-bar icon tap: jump back to whatever is playing. */
    public void openNowPlaying() {
        if (task == null) return;
        try {
            Intent open;
            if ("video".equals(task.kind)) {
                open = new Intent(ctx, PlayerActivity.class);
                open.putExtra("task_id", task.id);
            } else {
                open = new Intent(ctx, LibraryActivity.class);
            }
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(open);
        } catch (Exception ignored) {}
    }

    private NotificationManager notifManager() {
        return (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /** Show (or refresh) the background playback notification. */
    public void showNotif() {
        try {
            notifManager().notify(NOTIF_ID, buildNotif());
        } catch (Exception ignored) {}
    }

    /** Refresh the notification if it is currently shown. */
    public void updateNotif() {
        try {
            if (task != null) showNotif();
        } catch (Exception ignored) {}
    }

    /** Remove the background playback notification. */
    public void hideNotif() {
        try {
            notifManager().cancel(NOTIF_ID);
        } catch (Exception ignored) {}
    }
}
