package com.seanming.player.spider;

import android.content.Context;

import com.google.gson.Gson;
import com.seanming.player.api.CspApi;
import com.seanming.player.bean.Result;
import com.seanming.player.bean.Site;
import com.seanming.player.bean.Vod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 爬虫统一门面. 按 Site.type 分发:
 *  - type 0/1: CspApi (苹果cms xml/json 采集)
 *  - type 3:   SpiderLoader 加载 jar, DexClassLoader 反射调用
 *  - type 4:   SpiderLoader 加载 drpy js, QuickJS 执行
 * 上层(UI)只调用本类的静态方法.
 */
public class SpiderManager {

    private static final Gson GSON = new Gson();
    private static final Map<String, Spider> SPIDERS = new ConcurrentHashMap<>();
    /** 每个站点一把锁: 保证同一个爬虫的"加载"与"调用"串行, 不同站点之间仍可并行 */
    private static final Map<String, Object> SITE_LOCKS = new ConcurrentHashMap<>();
    private static Context appCtx;

    public static void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
    }

    /** 取站点互斥锁(不存在则创建) */
    private static Object lockFor(String siteKey) {
        return SITE_LOCKS.computeIfAbsent(siteKey, k -> new Object());
    }

    /** 后台预热线程(单线程, 低优先级): 串行加载爬虫, 与并发搜索错开避免 native 冲突 */
    private static final ExecutorService PRELOAD =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "spider-preload");
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
    private static final AtomicBoolean PRELOADING = new AtomicBoolean(false);
    /** 接口配置代次: 切换接口时自增, 用于让过期的后台预热任务主动退出 */
    private static final AtomicInteger CONFIG_GEN = new AtomicInteger(0);

    /**
     * 后台预热站点爬虫. 打开搜索页时调用, 用户挑词/输入的这几秒里把爬虫提前加载好,
     * 真正点搜索时就能直接并发开搜(否则首次搜索要等 20 多个爬虫逐个加载).
     */
    public static void preloadAsync(List<Site> sites) {
        if (sites == null || sites.isEmpty()) return;
        if (!PRELOADING.compareAndSet(false, true)) return;
        final List<Site> targets = new ArrayList<>(sites);
        final int gen = CONFIG_GEN.get();
        PRELOAD.execute(() -> {
            try {
                for (Site s : targets) {
                    if (gen != CONFIG_GEN.get()) return;  // 接口已切换, 放弃本轮预热
                    try {
                        if (!s.isSpider()) continue;
                        if (SPIDERS.containsKey(s.getKey())) continue;
                        getSpider(s);
                    } catch (Throwable ignored) {}
                }
            } finally {
                PRELOADING.set(false);
            }
        });
    }

    /** 清理已加载爬虫(切换接口配置时调用) */
    public static void clear() {
        CONFIG_GEN.incrementAndGet();
        for (Spider s : SPIDERS.values()) {
            try { s.destroy(); } catch (Throwable ignored) {}
        }
        SPIDERS.clear();
    }

    /** 取(必要时加载)站点爬虫. 必须在持有该站点锁时调用, 避免并发重复加载同一个爬虫 */
    private static Spider getSpiderLocked(Site site) throws Exception {
        Spider cached = SPIDERS.get(site.getKey());
        if (cached != null) return cached;
        Spider spider = site.getType() == 4
                ? SpiderLoader.loadJs(appCtx, site)
                : SpiderLoader.loadJar(appCtx, site);
        SPIDERS.put(site.getKey(), spider);
        return spider;
    }

    private static Spider getSpider(Site site) throws Exception {
        Spider cached = SPIDERS.get(site.getKey());
        if (cached != null) return cached;
        synchronized (lockFor(site.getKey())) {
            return getSpiderLocked(site);
        }
    }

    /**
     * 取异常根因的可读描述. 反射调用爬虫方法失败时外层是 InvocationTargetException,
     * 其 getMessage() 为 null, 直接当成错误信息会让界面把"加载失败"显示成"无分类".
     */
    private static String errMsg(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String msg = cause.getMessage();
        return (msg == null || msg.isEmpty()) ? cause.getClass().getSimpleName() : msg;
    }

    private static Result parse(String json) {
        try {
            android.util.Log.i("SpiderManager", "parse json len=" + (json == null ? 0 : json.length())
                    + " preview=" + (json == null ? "null" : json.substring(0, Math.min(200, json.length()))));
            Result r = GSON.fromJson(json, Result.class);
            return r == null ? Result.error("empty result") : r;
        } catch (Throwable t) {
            android.util.Log.e("SpiderManager", "parse error: " + t.getMessage() + " json=" + json);
            return Result.error("parse error: " + t.getMessage());
        }
    }

    public static Result homeContent(Site site) {
        try {
            if (site.isSpider()) return parse(getSpider(site).homeContent(site.getFilterable() == 1));
            return CspApi.homeContent(site);
        } catch (Throwable t) {
            return Result.error(errMsg(t));
        }
    }

    public static Result homeVideoContent(Site site) {
        try {
            if (site.isSpider()) return parse(getSpider(site).homeVideoContent());
            return CspApi.homeVideoContent(site);
        } catch (Throwable t) {
            return Result.error(errMsg(t));
        }
    }

    public static Result categoryContent(Site site, String tid, int pg, boolean filter, HashMap<String, String> extend) {
        try {
            if (site.isSpider()) return parse(getSpider(site).categoryContent(tid, String.valueOf(pg), filter, extend));
            return CspApi.categoryContent(site, tid, pg);
        } catch (Throwable t) {
            return Result.error(errMsg(t));
        }
    }

    public static Result detailContent(Site site, List<String> ids) {
        try {
            Result r = site.isSpider()
                    ? parse(getSpider(site).detailContent(ids))
                    : CspApi.detailContent(site, ids);
            if (r.list != null) {
                for (Vod v : r.list) {
                    v.siteKey = site.getKey();
                    v.parsePlayFrom();
                }
            }
            return r;
        } catch (Throwable t) {
            return Result.error(errMsg(t));
        }
    }

    /**
     * 站点搜索. 同一站点的爬虫(JAR 原生库 / QuickJS 运行时)都不是线程安全的,
     * 因此用站点锁把"加载 + 调用"串起来; 不同站点各自持锁, 可并发执行.
     */
    public static Result searchContent(Site site, String key, boolean quick) {
        if (!site.isSpider()) {
            try {
                return CspApi.searchContent(site, key, 1);
            } catch (Throwable t) {
                return Result.error(t.getMessage());
            }
        }
        synchronized (lockFor(site.getKey())) {
            try {
                return parse(getSpiderLocked(site).searchContent(key, quick));
            } catch (Throwable t) {
                return Result.error(errMsg(t));
            }
        }
    }

    /**
     * 解析真实播放地址.
     * 返回 Result: url(播放地址), parse(0 直连 / 1 嗅探), header(json 请求头), jx(解析口)
     */
    public static Result playerContent(Site site, String flag, String id, List<String> vipFlags) {
        try {
            if (site.isSpider()) {
                return parse(getSpider(site).playerContent(flag, id, vipFlags));
            }
            Result r = new Result();
            r.parse = 0;
            r.url = id;
            r.header = null;
            return r;
        } catch (Throwable t) {
            return Result.error(errMsg(t));
        }
    }

    /**
     * 本地代理分发(对齐影视仓 oo0oO0.proxyLocal): 由 LocalProxyServer 在收到
     * GET /proxy?do=&lt;站点key&gt;&amp;... 时调用, 转交给对应爬虫 jar 的
     * com.github.catvod.spider.Proxy.proxy(map).
     *
     * 爬虫(尤其 wex 加密源)会生成指向本机 9978 端口的 URL 用于中转取数/取流,
     * 没有这个服务就会拿不到数据.
     */
    public static Object[] proxyLocal(java.util.Map<String, String> params) {
        String key = params == null ? null : params.get("do");
        Spider target = key == null ? null : SPIDERS.get(key);
        if (target instanceof ReflectSpider) {
            Object[] r = ((ReflectSpider) target).proxyInvoke(params);
            if (r != null) return r;
        }
        // 兜底: wex 各站点共用同一个 guard jar, 任一已加载的 jar 爬虫都能处理
        for (Spider sp : SPIDERS.values()) {
            if (sp instanceof ReflectSpider) {
                Object[] r = ((ReflectSpider) sp).proxyInvoke(params);
                if (r != null) return r;
            }
        }
        return null;
    }
}
