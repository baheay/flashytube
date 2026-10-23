package com.flashytube.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Transparent entry point for Android's Share sheet (YouTube app -> YT Grab). */
public class ShareActivity extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Lang.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ThemeHelper.apply(this);
        String url = null;
        // Opened via a tapped link (ACTION_VIEW): the URL is the intent data.
        android.net.Uri data = getIntent().getData();
        if (data != null) {
            String scheme = data.getScheme();
            if (scheme != null && scheme.startsWith("vnd.youtube")) {
                // vnd.youtube:VIDEO_ID -> canonical watch URL.
                String id = data.getSchemeSpecificPart();
                if (id != null && !id.isEmpty()) {
                    url = "https://www.youtube.com/watch?v=" + id;
                }
            } else {
                url = DlHelper.extractYoutubeUrl(data.toString());
            }
        }
        if (url == null) {
            String text = getIntent().getStringExtra(Intent.EXTRA_TEXT);
            url = DlHelper.extractYoutubeUrl(text);
        }
        if (url == null) {
            Toast.makeText(this, R.string.toast_bad_share, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (DlHelper.isChannel(url) || DlHelper.isPlaylist(url)) {
            // Channel/playlist: open inline in the app (tab bar for channels).
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra("open_url", url);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            finish();
        } else {
            // Single video: floating quality popup over the calling app.
            // fetchAndPick(..., finishAfter=true) finishes this activity itself
            // when the dialog is dismissed or a download is picked, so we must
            // NOT finish() here or the dialog would never appear.
            DlHelper.fetchAndPick(this, url, true);
        }
    }
}
