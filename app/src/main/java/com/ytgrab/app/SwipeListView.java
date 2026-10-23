package com.ytgrab.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.ListView;

/**
 * ListView that reports horizontal flings (for sub-tab swiping) without
 * consuming any touch events — scrolling, row taps and buttons keep
 * working exactly as before. A plain OnTouchListener on a ListView only
 * fires for touches on empty areas, because rows consume their own
 * touches before the listener is consulted.
 */
public class SwipeListView extends ListView {

    public interface OnSwipeListener {
        void onSwipeLeft();
        void onSwipeRight();
    }

    private GestureDetector detector;

    public SwipeListView(Context c) {
        super(c);
    }

    public SwipeListView(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setOnSwipeListener(OnSwipeListener l) {
        if (l == null) {
            detector = null;
            return;
        }
        detector = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float vx, float vy) {
                        return handleFling(e1, e2, vx, vy, l);
                    }
                });
    }

    /**
     * Passive swipe detection for any view — e.g. the container behind an
     * empty list (when the list itself is GONE there is nothing to swipe on).
     * Never consumes the touch; clicks and scrolling keep working.
     */
    public static void bindSwipe(android.content.Context ctx, android.view.View v,
                                 OnSwipeListener l) {
        GestureDetector det = new GestureDetector(ctx,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float vx, float vy) {
                        return handleFling(e1, e2, vx, vy, l);
                    }
                });
        v.setOnTouchListener((view, ev) -> {
            try { det.onTouchEvent(ev); } catch (Exception ignored) {}
            return false;
        });
    }

    private static boolean handleFling(MotionEvent e1, MotionEvent e2,
                                       float vx, float vy, OnSwipeListener l) {
        if (e1 == null || e2 == null) return false;
        float dx = e2.getX() - e1.getX();
        float dy = e2.getY() - e1.getY();
        // Horizontal fling only (ignore vertical scrolls).
        if (Math.abs(dx) < Math.abs(dy) * 1.5f) return false;
        if (Math.abs(dx) < 120 || Math.abs(vx) < 200) return false;
        if (dx < 0) l.onSwipeLeft();
        else l.onSwipeRight();
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // Passive: observe every touch (rows included), never consume.
        if (detector != null) {
            try {
                detector.onTouchEvent(ev);
            } catch (Exception ignored) {}
        }
        return super.dispatchTouchEvent(ev);
    }
}
