package com.ytgrab.app;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Optional Google sign-in inside a WebView (same pattern downloaders use):
 * the user signs in on YouTube's own page; we only keep the resulting
 * session cookies on this device so age-restricted videos can play/download.
 */
public class LoginActivity extends Activity {

    private WebView web;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Lang.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ThemeHelper.apply(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView warn = new TextView(this);
        warn.setText(R.string.google_login_warn);
        int p = Math.round(14 * getResources().getDisplayMetrics().density);
        warn.setPadding(p, p, p, p);
        root.addView(warn);

        web = new WebView(this);
        web.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(web);

        setContentView(root);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            // accounts.google.com -> youtube.com is cross-site; allow it.
            cm.setAcceptThirdPartyCookies(web, true);
        }

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Detect the session as soon as YouTube sets it.
                if (GoogleLogin.isLoggedIn(LoginActivity.this)) {
                    Toast.makeText(LoginActivity.this,
                            R.string.google_login_done, Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                }
            }
        });
        web.loadUrl(GoogleLogin.LOGIN_URL);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        GoogleLogin.flush();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
