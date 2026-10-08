package com.aether.signal;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(true);
        // FIX v3.24: ES-module import (core.js/data.js/charts.js) via file:// diblokir
        // WebView tanpa flag ini -> app.js gagal total -> semua tab mati.
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // Jangan blank: tampilkan pesan jujur agar bisa didiagnosis
                String html = "<html><body style='background:#070b11;color:#e6edf3;font-family:sans-serif;padding:24px'>"
                    + "<h3>Gagal memuat AetherSignalBot</h3><p>" + description + "</p>"
                    + "<p>" + failingUrl + "</p></body></html>";
                view.loadData(html, "text/html", "utf-8");
            }
        });
        web.loadUrl("file:///android_asset/www/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
