package com.example.fblite;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

/**
 * Single-Activity, single-WebView "app" that wraps the DESKTOP Facebook
 * site (web.facebook.com), not the mobile site.
 *
 * Why desktop mode: Facebook intentionally disables calling on the mobile
 * web view — it redirects to "install the Messenger app" instead. The
 * desktop layout keeps the call buttons and lets WebRTC calls (including
 * group calls) run inside a plain browser engine, which is what this
 * WebView is. So this app spoofs a desktop Chrome User-Agent and asks the
 * page to lay out at desktop width, then lets Android's WebView (a
 * Chromium engine) handle the actual audio/video call.
 *
 * This is NOT guaranteed by Facebook to keep working — they can change
 * this behavior at any time, same as any browser-based workaround.
 *
 * Zero third-party libraries, same family as the YTLite project.
 */
public class MainActivity extends Activity {

    private static final String HOME_URL = "https://www.facebook.com/messages/t/";

    // Only these hosts are allowed to load as *top-level navigation* inside
    // the WebView. Sub-resources (images, scripts, call media) are not
    // restricted by this — only full-page navigation is.
    private static final String[] ALLOWED_HOSTS = {
            "facebook.com",
            "messenger.com",
            "fb.com",
            "fbcdn.net",
            "accounts.google.com" // in case a linked-login flow is used
    };

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final int REQ_AV_PERMISSIONS = 1;

    // Hides Facebook's desktop-only side rails (left nav + right
    // "complementary" panel) so the conversation column gets the screen
    // width instead of sharing it with panels that don't matter on a
    // phone. Facebook exposes these via ARIA "role" attributes for
    // accessibility, which are far more stable across redesigns than
    // their auto-generated CSS class names — but not guaranteed forever.
    private static final String COMPACT_CSS =
            "[role='navigation']{display:none!important}"
            + "[role='complementary']{display:none!important}"
            + "[role='main']{width:100%!important;max-width:100%!important;"
            + "min-width:0!important;margin:0!important}"
            + "body{min-width:0!important}";

    private WebView webView;
    private ProgressBar progressBar;
    private FrameLayout fullscreenContainer;

    // State kept while a <video> element is in native fullscreen mode
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private int originalOrientation;

    // A pending in-page permission request (camera/mic), waiting on the
    // Android runtime permission dialog to be answered.
    private PermissionRequest pendingWebRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);

        setupWebView();
        requestAvPermissionsIfNeeded();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
        }
    }

    private void requestAvPermissionsIfNeeded() {
        boolean needCamera = checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED;
        boolean needMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED;
        if (needCamera || needMic) {
            requestPermissions(
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    REQ_AV_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_AV_PERMISSIONS || pendingWebRequest == null) return;

        // The page asked for camera/mic (via onPermissionRequest below) and
        // Android's own permission dialog has now been answered. Resolve
        // the page's request accordingly.
        boolean allGranted = true;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
        }
        if (allGranted) {
            pendingWebRequest.grant(pendingWebRequest.getResources());
        } else {
            pendingWebRequest.deny();
            Toast.makeText(this, "Camera/Mic permission needed for calls",
                    Toast.LENGTH_LONG).show();
        }
        pendingWebRequest = null;
    }

    private void setupWebView() {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Desktop layout: allow zoom (the page won't be phone-sized), and
        // let the WebView scale the wide desktop layout down to fit the
        // screen width instead of showing it at 1:1 pixel scale.
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        // Slightly larger text, since the page still renders at desktop
        // proportions even after the CSS trim above.
        settings.setTextZoom(120);

        // Spoof desktop Chrome so Facebook serves the desktop (calling-capable)
        // experience instead of redirecting to "install the app".
        settings.setUserAgentString(DESKTOP_UA);

        // Keep the user logged in between launches.
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                String scheme = uri.getScheme();

                if ("http".equals(scheme) || "https".equals(scheme)) {
                    String host = uri.getHost();
                    if (host != null && isAllowedHost(host)) {
                        return false; // let the WebView load it normally
                    }
                    openExternally(url);
                    return true;
                }

                // intent://, market://, fb-messenger:// ... swallow and stay here.
                openExternally(url);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                view.evaluateJavascript(compactPageJs(), null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (newProgress > 0 && newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }

            // The page asking for camera/mic access (a call starting).
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        boolean haveCamera = checkSelfPermission(Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED;
                        boolean haveMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED;
                        if (haveCamera && haveMic) {
                            request.grant(request.getResources());
                        } else {
                            pendingWebRequest = request;
                            requestAvPermissionsIfNeeded();
                        }
                    }
                });
            }

            // --- Fullscreen <video> handling (rarely used by calls, but
            // harmless to keep for any video content) ---
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                originalOrientation = getRequestedOrientation();

                webView.setVisibility(View.GONE);
                fullscreenContainer.setVisibility(View.VISIBLE);
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                hideSystemUi();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;

                webView.setVisibility(View.VISIBLE);
                fullscreenContainer.setVisibility(View.GONE);
                fullscreenContainer.removeView(customView);
                customView = null;

                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }

                setRequestedOrientation(originalOrientation);
                showSystemUi();
            }
        });
    }

    private String compactPageJs() {
        String css = COMPACT_CSS.replace("\\", "\\\\").replace("'", "\\'");
        return "(function(){function go(){"
                + "var s=document.getElementById('fbl-compact');"
                + "if(!s){s=document.createElement('style');s.id='fbl-compact';"
                + "(document.head||document.documentElement).appendChild(s)}"
                + "s.textContent='" + css + "';}"
                + "go();"
                + "var last=location.href;"
                + "setInterval(function(){if(location.href!==last){last=location.href;go()}},800)"
                + "})();";
    }

    private boolean isAllowedHost(String host) {
        for (String allowed : ALLOWED_HOSTS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private void openExternally(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            // No app / browser can handle it — ignore.
        }
    }

    private void hideSystemUi() {
        //noinspection deprecation
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showSystemUi() {
        //noinspection deprecation
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                webView.getWebChromeClient().onHideCustomView();
                return true;
            }
            if (webView.canGoBack()) {
                webView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }
}
