package com.seanming.player.ui.search;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 外部搜索广播入口(对齐影视仓 SearchReceiver):
 * 监听 action = android.content.movie.search.Action, 携带搜索词 extra "title",
 * 收到后直接打开"全源秒搜"并按词搜索. 方便从手机/语音助手等外部触发.
 */
public class SearchReceiver extends BroadcastReceiver {

    public static final String ACTION = "android.content.movie.search.Action";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String word = intent.getStringExtra(FastSearchActivity.EXTRA_KEYWORD);
        Intent i = new Intent(context, FastSearchActivity.class);
        if (word != null) i.putExtra(FastSearchActivity.EXTRA_KEYWORD, word);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(i);
        } catch (Throwable ignored) {}
    }
}