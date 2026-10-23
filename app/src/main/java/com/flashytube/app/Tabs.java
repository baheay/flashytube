package com.flashytube.app;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** SnapTube-style bottom tab navigation shared by the main screens. */
public class Tabs {
    public static final int TAB_DOWNLOAD = 0;
    public static final int TAB_LIBRARY = 1;
    public static final int TAB_SETTINGS = 2;

    public static void bind(final Activity act, int selected) {
        if (act.findViewById(R.id.tabBar) == null) return;
        bindTab(act, R.id.tabDownload, R.id.tabDownloadIcon, R.id.tabDownloadLabel,
                selected == TAB_DOWNLOAD, MainActivity.class);
        bindTab(act, R.id.tabLibrary, R.id.tabLibraryIcon, R.id.tabLibraryLabel,
                selected == TAB_LIBRARY, LibraryActivity.class);
        bindTab(act, R.id.tabSettings, R.id.tabSettingsIcon, R.id.tabSettingsLabel,
                selected == TAB_SETTINGS, SettingsActivity.class);
    }

    private static void bindTab(final Activity act, int tabId, int iconId, int labelId,
                                boolean selected, final Class<?> cls) {
        View tab = act.findViewById(tabId);
        if (tab == null) return;
        ImageView icon = tab.findViewById(iconId);
        TextView label = tab.findViewById(labelId);
        int color = selected
                ? ThemeHelper.attrColor(act, android.R.attr.colorPrimary)
                : ThemeHelper.attrColor(act, R.attr.colorOnSurfaceVariant);
        if (icon != null) icon.setColorFilter(color);
        if (label != null) label.setTextColor(color);
        if (selected) return;
        tab.setOnClickListener(v -> {
            Intent i = new Intent(act, cls);
            // Reuse the existing instance instead of stacking a new one.
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            act.startActivity(i);
        });
    }
}
