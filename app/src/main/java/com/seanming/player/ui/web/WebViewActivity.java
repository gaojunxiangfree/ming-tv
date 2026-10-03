package com.seanming.player.ui.web;

import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.seanming.player.R;

/**
 * 自定义 WebView 页(对齐影视仓 WebViewActivity):
 * 由 CustomWebReceiver 或外部链接打开, 支持遥控器返回键逐级回退.
 */
public class WebViewActivity extends AppCompatActivity {

    private WebView webView;
    private TextView tvTitle;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);

        webView = findViewById(R.id.webView);
        tvTitle = findViewById(R.id.tvTitle);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        String url = getIntent().getStringExtra("url");
        if (url == null || url.isEmpty()) {
            tvTitle.setText("地址为空");
            return;
        }
        tvTitle.setText(url);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String u) {
                super.onPageFinished(view, u);
                String title = view.getTitle();
                tvTitle.setText(title == null || title.isEmpty() ? u : title);
            }
        });
        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
