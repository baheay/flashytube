package com.flashytube.app;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.List;

/**
 * App-wide options menu: a small modern popup anchored to the ⋮ button
 * that opened it. Every ⋮ menu (search results, downloads, favorites)
 * uses this so they all look and feel the same.
 */
public class RichMenu {

    public static class Item {
        public final String icon;
        public final String label;
        public final boolean danger;
        public final Runnable action;

        public Item(String icon, String label, Runnable action) {
            this(icon, label, false, action);
        }

        public Item(String icon, String label, boolean danger, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.danger = danger;
            this.action = action;
        }
    }

    /** Shows the popup below the anchor (the ⋮ view), aligned to its edge. */
    public static void show(Activity act, View anchor, List<Item> items) {
        ListView lv = new ListView(act);
        lv.setDivider(new ColorDrawable(ThemeHelper.attrColor(act, R.attr.colorDivider)));
        lv.setDividerHeight(1);
        lv.setClipToPadding(false);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int p) { return items.get(p); }
            @Override public long getItemId(int p) { return p; }

            @Override
            public View getView(int p, View cv, ViewGroup parent) {
                Item it = items.get(p);
                if (cv == null) {
                    cv = LayoutInflater.from(act).inflate(
                            R.layout.row_menu_popup, parent, false);
                }
                ((TextView) cv.findViewById(R.id.mIcon)).setText(it.icon);
                TextView label = cv.findViewById(R.id.mLabel);
                label.setText(it.label);
                // Default text color comes from the theme (correct in light
                // and dark mode); only danger items get a fixed red.
                if (it.danger) label.setTextColor(0xFFE53935);
                return cv;
            }
        };
        lv.setAdapter(adapter);

        final float d = act.getResources().getDisplayMetrics().density;
        final PopupWindow pw = new PopupWindow(lv,
                (int) (220 * d), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(
                act.getResources().getDrawable(R.drawable.menu_popup_bg, act.getTheme()));
        pw.setElevation(10 * d);
        pw.setOutsideTouchable(true);
        lv.setOnItemClickListener((p, v, pos, id) -> {
            pw.dismiss();
            items.get(pos).action.run();
        });
        try {
            pw.showAsDropDown(anchor, 0, (int) (4 * d), Gravity.END);
        } catch (Exception e) {
            pw.showAsDropDown(anchor);
        }
    }

    /** Backwards-compatible overload: anchors to the activity's content view. */
    public static void show(Activity act, String title, List<Item> items) {
        show(act, act.findViewById(android.R.id.content), items);
    }
}
