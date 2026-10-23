package com.flashytube.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Handles the background-audio notification buttons (play/pause, stop).
 * Works without any service: it just drives the AudioPlayer singleton.
 */
public class AudioActionReceiver extends BroadcastReceiver {

    public static final String ACTION_TOGGLE = "com.flashytube.app.audio.TOGGLE";
    public static final String ACTION_STOP = "com.flashytube.app.audio.STOP";
    public static final String ACTION_PREV = "com.flashytube.app.audio.PREV";
    public static final String ACTION_NEXT = "com.flashytube.app.audio.NEXT";

    @Override
    public void onReceive(Context c, Intent i) {
        try {
            String a = i != null ? i.getAction() : null;
            AudioPlayer ap = AudioPlayer.get(c);
            if (ACTION_TOGGLE.equals(a)) {
                ap.toggle();
            } else if (ACTION_STOP.equals(a)) {
                ap.stop();
            } else if (ACTION_PREV.equals(a)) {
                ap.seekBy(-10000);
            } else if (ACTION_NEXT.equals(a)) {
                ap.seekBy(10000);
            }
        } catch (Exception ignored) {}
    }
}
