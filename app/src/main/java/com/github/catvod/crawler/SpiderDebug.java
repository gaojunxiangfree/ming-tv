package com.github.catvod.crawler;

import android.util.Log;

/**
 * TVBox/CatVod 爬虫调试日志类(对齐影视仓 com.github.catvod.crawler.SpiderDebug)。
 *
 * 为什么必须存在: 接口里的 wex 加密爬虫, 真实实现被加密在 jar 的 .guard 中,
 * 由 native 在运行时解密后加载. 解密出来的代码会直接引用 SpiderDebug,
 * 本类缺失时抛 NoClassDefFoundError, 会把爬虫内部流程打断(例如 playerContent 解析失败).
 *
 * 方法签名与影视仓保持一致, 保证解密代码能正常解析到。
 */
public class SpiderDebug {

    private static final String TAG = "SpiderLog";

    /** 影视仓同名方法: 返回空串(用于错误码文案) */
    public static String ec(int i) {
        return "";
    }

    public static void log(String str) {
        try {
            Log.d(TAG, str);
        } catch (Throwable ignored) {}
    }

    public static void log(Throwable th) {
        try {
            Log.d(TAG, String.valueOf(th == null ? null : th.getMessage()), th);
        } catch (Throwable ignored) {}
    }
}
