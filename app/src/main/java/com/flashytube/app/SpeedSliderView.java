package com.flashytube.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Thin vertical speed slider: drag or tap to snap between the speed steps.
 * Shared by the video player and the audio mini-bar.
 */
public class SpeedSliderView extends View {

    public interface Listener { void onSpeed(float s); }

    private final float[] speeds;
    private final Listener listener;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private int index;
    private boolean dragging;

    public SpeedSliderView(Context c, float[] speeds, float currentSpeed, Listener l) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        this.speeds = speeds;
        listener = l;
        index = 2; // 1x
        for (int i = 0; i < speeds.length; i++) {
            if (Math.abs(speeds[i] - currentSpeed) < 0.01f) { index = i; break; }
        }
        trackPaint.setColor(0x44FFFFFF);
        fillPaint.setColor(0xFFE53935);
        thumbPaint.setColor(0xFFFFFFFF);
        dotPaint.setColor(0x88FFFFFF);
    }

    private float top() { return 20 * density; }
    private float bottom() { return getHeight() - 20 * density; }
    private float yFor(int i) {
        return bottom() - (bottom() - top()) * i / (speeds.length - 1);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f;
        float tw = 3 * density; // thin track
        c.drawRoundRect(cx - tw / 2, top(), cx + tw / 2, bottom(),
                tw / 2, tw / 2, trackPaint);
        float y = yFor(index);
        c.drawRoundRect(cx - tw / 2, y, cx + tw / 2, bottom(),
                tw / 2, tw / 2, fillPaint);
        for (int i = 0; i < speeds.length; i++) {
            if (i == index) continue;
            c.drawCircle(cx, yFor(i), 2 * density, dotPaint);
        }
        c.drawCircle(cx, y, 8 * density, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    float y = Math.max(top(), Math.min(bottom(), e.getY()));
                    int ni = Math.round((bottom() - y) / (bottom() - top())
                            * (speeds.length - 1));
                    ni = Math.max(0, Math.min(speeds.length - 1, ni));
                    if (ni != index) {
                        index = ni;
                        listener.onSpeed(speeds[index]);
                        invalidate();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
        }
        return super.onTouchEvent(e);
    }
}
