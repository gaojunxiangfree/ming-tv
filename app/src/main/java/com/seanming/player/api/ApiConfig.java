package com.seanming.player.api;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.seanming.player.bean.LiveChannelGroup;
import com.seanming.player.bean.Parse;
import com.seanming.player.bean.Site;
import com.seanming.player.spider.SpiderManager;
import com.seanming.player.util.OkHttpUtil;
import com.seanming.player.util.PrefUtils;
import com.seanming.player.util.ThreadUtils;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口配置中心: 加载/解析 TVBox 格式接口 JSON, 管理站点列表与直播源.
 */
public class ApiConfig {

    private static final ApiConfig INSTANCE = new ApiConfig();
    public static ApiConfig get() { return INSTANCE; }

    private final Gson gson = new Gson();
    private final List<Site> sites = new ArrayList<>();
    private final List<LiveChannelGroup> liveGroups = new ArrayList<>();
    /** 直播源声明中的 EPG 地址(x-tvg-url) */
    private final List<String> liveEpgUrls = new ArrayList<>();
    private final List<Parse> parses = new ArrayList<>();
    private final Map<String, String> globalHeaders = new LinkedHashMap<>();
    private String apiUrl = "";
    private boolean loaded;

    /** 用户自备默认接口源(与内置 test_config.json 同源), 无历史配置时优先加载, 失败回退内置源 */
    public static final String DEFAULT_API_URL = "http://tvbox.xn--4kq62z5rby2qupq9ub.top/";

    /** 兜底公开直播源: 接口自带直播源整体失效时仍能收看(实测多数频道可用) */
    private static final String[] BUILTIN_LIVE_URLS = {
            "https://iptv-org.github.io/iptv/countries/cn.m3u",
            "https://raw.githubusercontent.com/YanG-1989/m3u/main/Gather.m3u",
    };

    /** 全局 spider jar 地址(可为 ;md5; 格式), 供 type=3 站点共用 */
    private String globalSpider = "";

    /** 顶层 wallpaper 字段, 供开屏/背景使用 */
    private String wallpaper = "";

    public String getGlobalSpider() { return globalSpider; }
    public String getWallpaper() { return wallpaper; }
    public List<Parse> getParses() { return parses; }
    public Map<String, String> getGlobalHeaders() { return globalHeaders; }

    /** 当前接口地址 */
    public String getApiUrl() { return apiUrl; }
    public boolean isLoaded() { return loaded; }

    /** 站点列表(已过滤可切换) */
    public List<Site> getSites() { return sites; }

    /** 当前首页站点 */
    public Site getHomeSite() {
        String key = PrefUtils.get(PrefUtils.K_HOME_SITE, "");
        for (Site s : sites) {
            if (s.getKey().equals(key)) return s;
        }
        return sites.isEmpty() ? null : sites.get(0);
    }

    public void setHomeSite(String key) {
        PrefUtils.put(PrefUtils.K_HOME_SITE, key);
    }

    public List<LiveChannelGroup> getLiveGroups() { return liveGroups; }

    public List<String> getLiveEpgUrls() { return liveEpgUrls; }

    /** 启动时尝试加载上次配置; 无配置时优先加载用户默认源, 失败回退到内置测试源 */
    public void loadLast(ThreadUtils.Callback<Boolean> callback) {
        String url = PrefUtils.get(PrefUtils.K_API_URL, "");
        if (url.isEmpty()) {
            android.util.Log.i("ApiConfig", "no saved api url, try default source: " + DEFAULT_API_URL);
            // 先尝试用户默认源, 失败回退内置 test_config.json
            load(DEFAULT_API_URL, new ThreadUtils.Callback<Boolean>() {
                @Override
                public void onResult(Boolean ok) {
                    callback.onResult(true);
                }

                @Override
                public void onError(Throwable t) {
                    android.util.Log.w("ApiConfig", "default source failed: " + t.getMessage()
                            + " -> fallback built-in");
                    loadBuiltIn(callback);
                }
            });
            return;
        }
        // 接口地址已变更: 强制重新加载
        if (loaded && !url.equals(apiUrl)) {
            loaded = false;
            sites.clear();
            liveGroups.clear();
            liveEpgUrls.clear();
            parses.clear();
            globalHeaders.clear();
            SpiderManager.clear();
        }
        if (loaded) {
            ThreadUtils.main(() -> callback.onResult(true));
            return;
        }
        load(url, callback);
    }

    /** 内置测试源回退: 加载 assets/test_config.json (豆瓣直链 CMS) */
    public void loadBuiltIn(ThreadUtils.Callback<Boolean> callback) {
        ThreadUtils.call(() -> {
            try {
                InputStream is = appCtx.getAssets().open("test_config.json");
                StringBuilder sb = new StringBuilder();
                InputStreamReader r = new InputStreamReader(is);
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
                r.close();
                parse(sb.toString());
                return true;
            } catch (Throwable t) {
                throw new RuntimeException("load built-in failed: " + t.getMessage());
            }
        }, new ThreadUtils.Callback<Boolean>() {
            @Override
            public void onResult(Boolean ok) {
                apiUrl = "assets://test_config.json";
                loaded = true;
                PrefUtils.put(PrefUtils.K_API_URL, "");
                callback.onResult(true);
            }
            @Override
            public void onError(Throwable t) {
                callback.onError(t);
            }
        });
    }

    private static Context appCtx;
    public static void init(Context ctx) { appCtx = ctx.getApplicationContext(); }

    private List<String> apiSources;

    /** 已保存/推送的接口源列表(不含当前激活地址时自动并入) */
    public List<String> getSources() {
        if (apiSources == null) {
            apiSources = new ArrayList<>();
            try {
                String j = PrefUtils.get(PrefUtils.K_API_SOURCES, "");
                if (j != null && !j.isEmpty()) {
                    JsonArray arr = JsonParser.parseString(j).getAsJsonArray();
                    for (JsonElement e : arr) {
                        String u = e.getAsString();
                        if (u != null && !u.isEmpty() && !apiSources.contains(u)) apiSources.add(u);
                    }
                }
            } catch (Throwable ignored) {}
            // 当前激活地址不在列表时并入, 保证列表里一定能看到"当前"
            String cur = apiUrl;
            if (cur != null && !cur.isEmpty() && !cur.startsWith("assets:")) {
                if (!apiSources.contains(cur)) apiSources.add(0, cur);
            }
        }
        return apiSources;
    }

    private void persistSources() {
        JsonArray arr = new JsonArray();
        for (String s : apiSources) arr.add(s);
        PrefUtils.put(PrefUtils.K_API_SOURCES, arr.toString());
    }

    /** 新增一个接口源(去重), 只是入库不切换 */
    public void addSource(String url) {
        if (url == null || url.trim().isEmpty()) return;
        url = url.trim();
        getSources();
        if (!apiSources.contains(url)) {
            apiSources.add(0, url);
            persistSources();
        }
    }

    /** 删除一个接口源; 若删的是当前激活地址则回退默认源 */
    public void removeSource(String url) {
        if (url == null) return;
        getSources();
        apiSources.remove(url);
        persistSources();
        if (url.equals(apiUrl) && PrefUtils.get(PrefUtils.K_API_URL, "").equals(url)) {
            PrefUtils.put(PrefUtils.K_API_URL, "");
        }
    }

    /** 新增接口源并立即切换生效 */
    public void addAndSelect(String url, ThreadUtils.Callback<Boolean> callback) {
        addSource(url);
        load(url, callback);
    }

    /** 加载接口配置(异步) */
    public void load(String url, ThreadUtils.Callback<Boolean> callback) {
        ThreadUtils.call(() -> {
            String json = OkHttpUtil.get(url.trim());
            parse(json);
            return true;
        }, new ThreadUtils.Callback<Boolean>() {
            @Override
            public void onResult(Boolean ok) {
                apiUrl = url.trim();
                loaded = true;
                PrefUtils.put(PrefUtils.K_API_URL, apiUrl);
                // 切换成功的源自动进入多源列表
                addSource(apiUrl);
                callback.onResult(true);
            }

            @Override
            public void onError(Throwable t) {
                callback.onError(t);
            }
        });
    }

    /** 解析接口 JSON (TVBox 单接口格式: sites + lives + parses + headers) */
    private void parse(String json) {
        sites.clear();
        liveGroups.clear();
        liveEpgUrls.clear();
        parses.clear();
        globalHeaders.clear();
        SpiderManager.clear();

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        // 全局 spider jar (TVBox 规范: "spider": "url;md5;xxx")
        globalSpider = root.has("spider") ? root.get("spider").getAsString() : "";

        // 顶层 wallpaper (开屏/首页背景可选)
        wallpaper = root.has("wallpaper") ? root.get("wallpaper").getAsString() : "";

        // 顶层 headers: 全局 HTTP 请求头 (含各站点的 UA / referer / cookie)
        if (root.has("headers") && root.get("headers").isJsonObject()) {
            JsonObject hdr = root.getAsJsonObject("headers");
            mergeHeaders(hdr, globalHeaders);
        }

        // parses: 视频解析接口 (type 0=无, 1=json解析, 2=聚合)
        if (root.has("parses") && root.get("parses").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("parses")) {
                JsonObject o = el.getAsJsonObject();
                Parse p = new Parse();
                p.name = o.has("name") ? o.get("name").getAsString() : "";
                p.type = o.has("type") ? o.get("type").getAsInt() : 0;
                p.url = o.has("url") ? o.get("url").getAsString() : "";
                p.ext = o.has("ext") ? o.get("ext").toString() : "";
                if (!p.url.isEmpty()) parses.add(p);
            }
        }

        // sites
        JsonArray siteArr = root.has("sites") ? root.getAsJsonArray("sites") : new JsonArray();
        for (JsonElement el : siteArr) {
            JsonObject o = el.getAsJsonObject();
            // 先处理 ext: ext 可能是字符串或对象, Site.ext 是 String, 需统一转字符串后再 fromJson
            String extStr = "";
            if (o.has("ext")) {
                JsonElement extEl = o.get("ext");
                if (extEl.isJsonObject()) extStr = extEl.toString();
                else if (extEl.isJsonPrimitive()) extStr = extEl.getAsString();
            }
            // 复制一份避免改原对象, 移除 ext 字段后用 gson 解析, 再单独 setExt
            JsonObject clean = new JsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if ("ext".equals(e.getKey())) continue;
                clean.add(e.getKey(), e.getValue());
            }
            Site site = gson.fromJson(clean, Site.class);
            site.setExt(extStr);
            if (site.getName() == null) site.setName(site.getKey());
            // 过滤不可切换源(changeable 显式为 0)和纯 jar 占位站点(api为空)
            // 注意: TVBox 接口常省略 changeable 字段, 此时默认为可切换
            boolean explicitNoChange = o.has("changeable") && o.get("changeable").getAsInt() == 0;
            boolean noApi = site.getApi() == null || site.getApi().isEmpty();
            if (explicitNoChange || noApi) continue;
            sites.add(site);
        }

        // lives: type 0 = m3u/txt 直播地址; type 3 = spider 直播
        // 异步加载, 避免单个直播源超时阻塞整个 ApiConfig.load() 完成
        JsonArray liveArr = root.has("lives") ? root.getAsJsonArray("lives") : new JsonArray();
        List<String> liveUrls = new ArrayList<>();
        final Map<String, String> perLiveHeaders = new LinkedHashMap<>();
        for (JsonElement el : liveArr) {
            JsonObject o = el.getAsJsonObject();
            int type = o.has("type") ? o.get("type").getAsInt() : 0;
            String url = o.has("url") ? o.get("url").getAsString() : "";
            if (url.isEmpty()) continue;
            if (type == 0) {
                liveUrls.add(url);
                if (o.has("ua")) perLiveHeaders.put("User-Agent", o.get("ua").getAsString());
            }
            // type 3 (spider 直播) 预留: 二期通过 Spider.liveContent 拉取
        }
        // 接口自带的直播源可能整体失效(实测 aptv 列表后端 migu 返回 660、
        // migu_video 的 interface.txt 已缩成空文件), 追加公开 IPTV 源兜底,
        // 保证直播页始终有可播频道.
        for (String builtin : BUILTIN_LIVE_URLS) {
            if (!liveUrls.contains(builtin)) liveUrls.add(builtin);
        }
        if (!liveUrls.isEmpty()) {
            ThreadUtils.io(() -> {
                for (String url : liveUrls) {
                    try {
                        Map<String, String> hdrs = perLiveHeaders.isEmpty() ? null : perLiveHeaders;
                        M3uParser.Result r = M3uParser.parseFromUrl(url, hdrs);
                        if (r != null && !r.groups.isEmpty()) {
                            liveGroups.addAll(r.groups);
                        }
                        if (r != null) {
                            for (String epg : r.epgUrls) {
                                if (!liveEpgUrls.contains(epg)) liveEpgUrls.add(epg);
                            }
                        }
                    } catch (Throwable t) {
                        android.util.Log.w("ApiConfig", "live source failed: " + url + " : " + t);
                    }
                }
                // 直播源就绪后异步加载 EPG 节目单(声明地址不可用时自动回退兜底 EPG)
                EpgManager.get().load(new ArrayList<>(liveEpgUrls));
            });
        }

        // 校验上次选中的站点仍存在
        String homeKey = PrefUtils.get(PrefUtils.K_HOME_SITE, "");
        boolean exists = false;
        for (Site s : sites) {
            if (s.getKey().equals(homeKey)) { exists = true; break; }
        }
        if (!exists && !sites.isEmpty()) {
            // 默认优先选"秒播/速播/瞬播"直链站点(网盘站需扫码登录, 直链站可直接播放)
            setHomeSite(findDefaultSiteKey());
        }
    }

    /**
     * 首页默认站点优选顺序(实测能正常出内容的站点), 按名称关键字命中即用.
     * 注意: "秒播"类里有坏源(如 韩剧┃秒播 长期因源侧异常加载失败),
     * 所以把它排在优选之后, 避免全新安装一进首页就是"加载失败".
     */
    private static final String[] PREFERRED_DEFAULT_SITES = {
            "賤片", "伯伯", "木偶", "爱看",
    };

    /** 默认首页站点: 优先实测可用站, 再挑"秒播/速播/瞬播"直链站, 最后取第一个站点 */
    private String findDefaultSiteKey() {
        for (String prefer : PREFERRED_DEFAULT_SITES) {
            for (Site s : sites) {
                String name = s.getName();
                if (name != null && name.contains(prefer)) {
                    android.util.Log.i("ApiConfig", "findDefaultSiteKey -> 优选站: " + s.getKey() + " / " + name);
                    return s.getKey();
                }
            }
        }
        for (Site s : sites) {
            String name = s.getName();
            if (name != null && (name.contains("秒播") || name.contains("速播") || name.contains("瞬播"))) {
                android.util.Log.i("ApiConfig", "findDefaultSiteKey -> 秒播站: " + s.getKey() + " / " + name);
                return s.getKey();
            }
        }
        android.util.Log.i("ApiConfig", "findDefaultSiteKey -> 无常驻可用站, 取第一个: " + sites.get(0).getKey());
        return sites.get(0).getKey();
    }

    /** 合并 headers 节点(支持嵌套: 全局 + 各站点)到目标 Map */
    private void mergeHeaders(JsonObject hdr, Map<String, String> out) {
        for (Map.Entry<String, JsonElement> e : hdr.entrySet()) {
            if (e.getValue().isJsonObject()) {
                // 子节点如 "Wogg": {"User-Agent": "..."}
                JsonObject child = e.getValue().getAsJsonObject();
                for (Map.Entry<String, JsonElement> c : child.entrySet()) {
                    out.putIfAbsent(c.getKey(), c.getValue().getAsString());
                }
            } else {
                out.putIfAbsent(e.getKey(), e.getValue().getAsString());
            }
        }
    }
}
