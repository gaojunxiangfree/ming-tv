package com.seanming.player;

import android.app.Application;
import android.util.Log;

import com.seanming.player.api.ApiConfig;
import com.seanming.player.server.LocalProxyServer;
import com.seanming.player.spider.SpiderManager;
import com.seanming.player.util.PrefUtils;

/** 茗影院 Application */
public class App extends Application {

    private static App instance;

    public static App get() { return instance; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        installCrashGuard();
        PrefUtils.init(this);
        SpiderManager.init(this);
        ApiConfig.init(this);
        // 常驻本地代理服务(影视仓 9978 端口): 承载爬虫 /proxy 中转, 缺它部分源取不到数据
        LocalProxyServer.get().start(this);
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
