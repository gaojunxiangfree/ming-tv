package com.seanming.player;

import android.app.Activity;
import android.app.Application;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;

import com.seanming.player.api.ApiConfig;
import com.seanming.player.server.LocalProxyServer;
import com.seanming.player.spider.SpiderManager;
import com.seanming.player.ui.RotatablePage;
import com.seanming.player.util.PrefUtils;
import com.seanming.player.util.ScreenUtil;

import java.util.Locale;

/** 茗影院 Application */
public class App extends Application {

    private static App instance;

    public static App get() { return instance; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        installCrashGuard();
        installDirectionGuard();
        logDeviceTier();
        PrefUtils.init(this);
        SpiderManager.init(this);
        ApiConfig.init(this);
        // 常驻本地代理服务(影视仓 9978 端口): 承载爬虫 /proxy 中转, 缺它部分源取不到数据
        LocalProxyServer.get().start(this);
    }

    /**
     * 打印设备适配档位: 排查"字号过大/被锁竖屏"这类显示问题时先看这一行,
     * 能直接确认命中的是手机 / 平板 / 电视哪一档、以及实际生效的字号。
     */
    private void logDeviceTier() {
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        int tier = ScreenUtil.deviceTier(this);
        String name = tier == ScreenUtil.TIER_PHONE ? "手机"
                : tier == ScreenUtil.TIER_TABLET ? "平板" : "电视/投影";
        Log.i("App", String.format(Locale.US,
                "屏幕档位=%s(%d) 锁横屏=%s 最小宽度=%ddp 字号 sp=%.0f/%.0f/%.0f",
                name, tier, ScreenUtil.isLargeScreen(this),
                getResources().getConfiguration().smallestScreenWidthDp,
                getResources().getDimension(R.dimen.sm_text_title) / scaledDensity,
                getResources().getDimension(R.dimen.sm_text_body) / scaledDensity,
                getResources().getDimension(R.dimen.sm_text_small) / scaledDensity));
    }

    /**
     * 单 APK 多端方向控制:
     * - 大屏设备(电视/投影仪/平板): 全部页面锁横屏, 保持 TV 遥控体验;
     * - 手机: 除 {@link RotatablePage}(播放页/直播页)外锁竖屏, 这两页不锁方向,
     *   允许用户旋转做横屏全屏播放, 退出恢复竖屏.
     * 档位判定统一走 {@link ScreenUtil}, 与字号 / 网格列数口径一致, 不要再各写一套.
     * 用 ActivityLifecycleCallbacks 统一注入, 无需改动各 Activity 代码.
     */
    private void installDirectionGuard() {
        final boolean largeScreen = ScreenUtil.isLargeScreen(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                if (largeScreen) {
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                } else if (!(activity instanceof RotatablePage)) {
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                }
                // RotatablePage(播放/直播)在手机上不锁方向, 跟随传感器支持横屏全屏
            }

            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    /**
     * wex 加密源会在自己新建的后台线程里 System.load 运行期下载的 native 库.
     * 若该库下载失败(源端对象已删除时会返回错误页 XML), 爬虫的 GoProxy.<clinit>
     * 会抛 UnsatisfiedLinkError. 该异常发生在爬虫自建线程中, 调用方的 try/catch
     * 拦不住, 默认会直接杀掉整个进程. 这里对"native 库加载失败"做兜底:
     * 只结束出错线程, 保留 App 存活, 便于继续切换其它视频源.
     */
    private void installCrashGuard() {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        final Thread main = getMainLooper().getThread();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            // 只在爬虫自建的后台线程上兜底. 主线程一旦被吞掉异常, Looper 会退出,
            // 界面将永久卡死, 因此主线程仍交给系统崩溃重启.
            if (thread != main && isNativeLoadFailure(e)) {
                Log.e("App", "spider native lib load failed on " + thread.getName()
                        + ", keep app alive", e);
                return;
            }
            if (def != null) def.uncaughtException(thread, e);
        });
    }

    /** 异常链中是否包含 native 库加载失败(下载到非 ELF 内容时 dlopen 报 bad ELF magic) */
    private static boolean isNativeLoadFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnsatisfiedLinkError) return true;
            if (t instanceof ExceptionInInitializerError && t.getCause() instanceof UnsatisfiedLinkError) {
                return true;
            }
        }
        return false;
    }
}
