package com.flashytube.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Clean gesture OSD: title + value + progress bar. No emoji icons.
 */
public class PlayerGestureOverlay extends View {
    private String title = "";
    private String value = "";
    private float progress = 0f; // 0..1
    private boolean showing = false;
    private final Paint bgPaint = new Paint();
    private final Paint barBgPaint = new Paint();
    private final Paint barPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final RectF rect = new RectF();

    public PlayerGestureOverlay(Context c) { super(c); init(); }
    public PlayerGestureOverlay(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        bgPaint.setColor(0xCC000000);
        barBgPaint.setColor(0x66FFFFFF);
        barPaint.setColor(0xFFC81E1E);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setAntiAlias(true);
        setVisibility(GONE);
    }

    /** Show the OSD: title (e.g. "Volume"), value (e.g. "8/15"), progress 0..1. */
    public void show(String title, String value, float progress) {
        this.title = title != null ? title : "";
        this.value = value != null ? value : "";
        this.progress = Math.max(0f, Math.min(1f, progress));
        this.showing = true;
        setVisibility(VISIBLE);
        invalidate();
        removeCallbacks(hideRun);
        postDelayed(hideRun, 800);
    }

    private final Runnable hideRun = () -> {
        showing = false;
        setVisibility(GONE);
    };

    public void hideNow() {
        removeCallbacks(hideRun);
        showing = false;
        setVisibility(GONE);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!showing) return;
        float w = getWidth(), h = getHeight();
        float bw = Math.min(w * 0.44f, 420);
        float bh = 150;
        float l = (w - bw) / 2, t = (h - bh) / 2;
        rect.set(l, t, l + bw, t + bh);
        c.drawRoundRect(rect, 20, 20, bgPaint);
        // Title.
        textPaint.setTextSize(28);
        textPaint.setFakeBoldText(false);
        c.drawText(title, w / 2, t + 52, textPaint);
        // Value.
        textPaint.setTextSize(34);
        textPaint.setFakeBoldText(true);
        c.drawText(value, w / 2, t + 96, textPaint);
        textPaint.setFakeBoldText(false);
        // Progress bar.
        float bl = l + 36, br = l + bw - 36, by = t + 114, bh2 = 8;
        rect.set(bl, by, br, by + bh2);
        c.drawRoundRect(rect, 4, 4, barBgPaint);
        rect.set(bl, by, bl + (br - bl) * progress, by + bh2);
        c.drawRoundRect(rect, 4, 4, barPaint);
    }
}
