package com.seanming.player.ui.web;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

/**
 * 自定义Web广播入口(对齐影视仓 CustomWebReceiver):
 * 监听 action = android.content.movie.custom.web.Action, 携带 extra "url",
 * 收到后打开 WebViewActivity 加载该地址. 方便从手机/扫码等外部触发网页.
 */
public class CustomWebReceiver extends BroadcastReceiver {

    public static final String ACTION = "android.content.movie.custom.web.Action";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String url = intent.getStringExtra("url");
        if (TextUtils.isEmpty(url)) return;
        Intent i = new Intent(context, WebViewActivity.class);
        i.putExtra("url", url);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(i);
        } catch (Throwable ignored) {}
    }
}
