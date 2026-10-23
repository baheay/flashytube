package com.flashytube.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.util.TypedValue;

/**
 * Manual Material theming (no AppCompat): picks AppTheme or AppTheme.Dark
 * according to the user's choice (system / light / dark). Call apply()
 * before setContentView() in every activity.
 */
public class ThemeHelper {

    /** True when the dark theme is currently active on this context. */
    public static boolean isDark(Context c) {
        int mode = Prefs.themeMode(c);
        if (mode == Prefs.THEME_DARK) return true;
        if (mode == Prefs.THEME_LIGHT) return false;
        int night = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void apply(Activity act) {
        act.setTheme(isDark(act) ? R.style.AppTheme_Dark : R.style.AppTheme);
    }

    /** Resolve a theme attribute color (e.g. R.attr.colorSurface) on this context. */
    public static int attrColor(Context c, int attr) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }
}
