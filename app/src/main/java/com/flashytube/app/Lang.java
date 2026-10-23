package com.flashytube.app;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** Runtime locale switch (Arabic <-> English). */
public class Lang {

    public static Context wrap(Context c) {
        Locale locale = new Locale(Prefs.lang(c));
        Locale.setDefault(locale);
        Configuration cfg = new Configuration(c.getResources().getConfiguration());
        cfg.setLocale(locale);
        return c.createConfigurationContext(cfg);
    }
}
