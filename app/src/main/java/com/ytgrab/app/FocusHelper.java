package com.ytgrab.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

/**
 * Audio focus: playing any clip pauses other apps' audio, and if another
 * app starts audio, our playback pauses.
 */
public class FocusHelper {

    public interface Listener { void onFocusLoss(); }

    private final AudioManager am;
    private AudioFocusRequest request;
    private final AudioManager.OnAudioFocusChangeListener listener;

    public FocusHelper(Context ctx, Listener l) {
        am = (AudioManager) ctx.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        listener = focusChange -> {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                    || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                try { l.onFocusLoss(); } catch (Exception ignored) {}
            }
            // TRANSIENT_CAN_DUCK: keep playing (system lowers others).
            // GAIN: do nothing (user resumes manually).
        };
    }

    /** Request focus before starting playback. */
    public void request() {
        try {
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                if (request == null) {
                    request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build())
                            .setOnAudioFocusChangeListener(listener)
                            .build();
                }
                am.requestAudioFocus(request);
            } else {
                am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
            }
        } catch (Exception ignored) {}
    }

    /** Give up focus when playback stops. */
    public void abandon() {
        try {
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 26 && request != null) {
                am.abandonAudioFocusRequest(request);
            } else {
                am.abandonAudioFocus(listener);
            }
        } catch (Exception ignored) {}
    }
}
