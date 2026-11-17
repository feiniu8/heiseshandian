package com.heiseidian.lightning;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private WebView web;
    private FrameLayout embedRoot;
    private WebView embedWeb;
    private TextView embedTitle;
    private ProgressBar embedProgress;
    private boolean embedOpen;
    static MainActivity inst;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        inst = this;
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null || !HOST.equals(uri.getHost())) return null;
                String path = uri.getPath();
                if (path == null || path.equals("/")) path = "/index.html";
                if (path.startsWith("/")) path = path.substring(1);
                try {
                    InputStream in = getAssets().open(path);
                    String mime = "application/octet-stream";
                    if (path.endsWith(".html")) mime = "text/html";
                    else if (path.endsWith(".js")) mime = "application/javascript";
                    else if (path.endsWith(".css")) mime = "text/css";
                    else if (path.endsWith(".png")) mime = "image/png";
                    else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) mime = "image/jpeg";
                    else if (path.endsWith(".svg")) mime = "image/svg+xml";
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Access-Control-Allow-Origin", "*");
                    headers.put("Cache-Control", "no-cache");
                    return new WebResourceResponse(mime, "utf-8", 200, "OK", headers, in);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return false;
                Uri uri = Uri.parse(url);
                if (HOST.equals(uri.getHost())) return false;
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    openInAppUrl(url);
                    return true;
                }
                return false;
            }
        });

        setContentView(web);
        web.addJavascriptInterface(new Bridge(this, web), "Native");
        web.loadUrl("https://" + HOST + "/index.html");

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 71);
            } catch (Exception ignored) {
            }
        }
    }

    /* ---------------- in-app browser ---------------- */

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    void openInAppUrl(final String url) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (url == null) return;
                if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                    openExternalUrl(url);
                    return;
                }
                if (embedRoot == null) buildEmbed();
                embedOpen = true;
                if (embedProgress != null) embedProgress.setVisibility(View.VISIBLE);
                if (embedTitle != null) embedTitle.setText(hostOf(url));
                if (embedWeb != null) embedWeb.loadUrl(url);
            }
        });
    }

    private String hostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? url : h;
        } catch (Exception e) {
            return url;
        }
    }

    private void buildEmbed() {
        FrameLayout content = (FrameLayout) findViewById(android.R.id.content);
        if (content == null) return;

        embedRoot = new FrameLayout(this);
        embedRoot.setBackgroundColor(Color.BLACK);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Color.rgb(11, 13, 18));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int) dp(6), 0, (int) dp(6), 0);

        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextColor(Color.WHITE);
        back.setTextSize(14);
        back.setPadding((int) dp(8), (int) dp(12), (int) dp(8), (int) dp(12));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                embedGoBack();
            }
        });

        embedTitle = new TextView(this);
        embedTitle.setTextColor(Color.rgb(210, 216, 228));
        embedTitle.setTextSize(13);
        embedTitle.setSingleLine(true);
        embedTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        embedTitle.setLayoutParams(tp);
        embedTitle.setPadding((int) dp(4), 0, (int) dp(4), 0);

        TextView openOut = new TextView(this);
        openOut.setText("↗");
        openOut.setTextColor(Color.rgb(255, 214, 10));
        openOut.setTextSize(16);
        openOut.setPadding((int) dp(10), (int) dp(10), (int) dp(10), (int) dp(10));
        openOut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (embedWeb != null) openExternalUrl(embedWeb.getUrl());
            }
        });

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(16);
        close.setPadding((int) dp(10), (int) dp(10), (int) dp(10), (int) dp(10));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeEmbedded();
            }
        });

        bar.addView(back);
        bar.addView(embedTitle);
        bar.addView(openOut);
        bar.addView(close);

        embedProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        embedProgress.setIndeterminate(true);
        embedProgress.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(2)));

        column.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(48)));
        column.addView(embedProgress);

        embedWeb = new WebView(this);
        embedWeb.setBackgroundColor(Color.BLACK);
        WebSettings es = embedWeb.getSettings();
        es.setJavaScriptEnabled(true);
        es.setDomStorageEnabled(true);
        es.setAllowFileAccess(false);
        es.setCacheMode(WebSettings.LOAD_DEFAULT);
        embedWeb.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (embedProgress != null) embedProgress.setVisibility(View.GONE);
                if (embedTitle != null) embedTitle.setText(hostOf(url));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return false;
                if (url.startsWith("http://") || url.startsWith("https://")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        column.addView(embedWeb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        embedRoot.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(embedRoot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void embedGoBack() {
        if (embedWeb != null && embedWeb.canGoBack()) embedWeb.goBack();
        else closeEmbedded();
    }

    void closeEmbedded() {
        if (embedRoot == null) return;
        FrameLayout content = (FrameLayout) findViewById(android.R.id.content);
        if (content != null) content.removeView(embedRoot);
        if (embedWeb != null) {
            try {
                embedWeb.destroy();
            } catch (Exception ignored) {
            }
        }
        embedWeb = null;
        embedRoot = null;
        embedProgress = null;
        embedTitle = null;
        embedOpen = false;
    }

    void openExternalUrl(String url) {
        if (url == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        if (embedOpen) {
            embedGoBack();
            return;
        }
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.App && App.back && App.back()) || false", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!"true".equals(value)) finish();
            }
        });
    }

    void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }
}
