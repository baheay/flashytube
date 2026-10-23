package com.ytgrab.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Captures uncaught crashes to a file and shows it on next launch. */
public final class CrashLog {
    private CrashLog() {}

    private static File logFile(Context ctx) {
        File d = ctx.getExternalFilesDir(null);
        if (d == null) d = ctx.getFilesDir();
        return new File(d, "crash.log");
    }

    /** Install as early as possible (MainActivity.onCreate). */
    public static void install(final Context appCtx) {
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("=== FlashyTube crash ===");
                pw.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        Locale.US).format(new Date()));
                pw.println("Device: " + android.os.Build.MANUFACTURER + " "
                        + android.os.Build.MODEL + " (SDK "
                        + android.os.Build.VERSION.SDK_INT + ")");
                pw.println("Thread: " + t.getName());
                e.printStackTrace(pw);
                pw.flush();
                FileWriter fw = new FileWriter(logFile(appCtx), false);
                fw.write(sw.toString());
                fw.close();
            } catch (Exception ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
            else android.os.Process.killProcess(android.os.Process.myPid());
        });
    }

    /** If a crash log exists from a previous run, show it with a copy button. */
    public static void offerShareIfPresent(final Activity act) {
        final File f = logFile(act);
        if (!f.exists() || f.length() == 0) return;
        String text;
        try {
            text = new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
        } catch (Exception e) { return; }
        if (text.length() > 4000) text = text.substring(0, 4000) + "\n...";

        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextIsSelectable(true);
        int p = (int) (16 * act.getResources().getDisplayMetrics().density);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(act);
        sv.addView(tv);

        new AlertDialog.Builder(act)
                .setTitle(act.getString(R.string.crash_title))
                .setMessage(act.getString(R.string.crash_msg))
                .setView(sv)
                .setPositiveButton(act.getString(R.string.crash_copy),
                        (d, w) -> {
                            ClipboardManager cm = (ClipboardManager)
                                    act.getSystemService(
                                            Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText(
                                    "crash", tv.getText().toString()));
                            Toast.makeText(act, R.string.crash_copied,
                                    Toast.LENGTH_SHORT).show();
                            f.delete();
                        })
                .setNegativeButton(act.getString(R.string.crash_delete),
                        (d, w) -> { f.delete(); })
                .setOnCancelListener(d -> { f.delete(); })
                .show();
    }
}
