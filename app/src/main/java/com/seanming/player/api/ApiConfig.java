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
    /** 当前接口自带的直播地址(仅"跟随接口自带直播源"开启时参与解析) */
    private final List<String> apiLiveUrls = new ArrayList<>();
    private final Map<String, String> apiLiveHeaders = new LinkedHashMap<>();
    /** 独立直播源列表(与接口源解耦, 设置页可增删) */
    private List<String> liveSources;
    /** 直播源变更后置位, 直播页 onResume 时据此重建列表 */
    private volatile boolean liveReloadPending;
    private final List<Parse> parses = new ArrayList<>();
    private final Map<String, String> globalHeaders = new LinkedHashMap<>();
    private String apiUrl = "";
    private boolean loaded;

    /** 默认接口源: 饭太硬(实测影视可播、直播源质量高) */
    public static final String DEFAULT_API_URL = "http://www.xn--sss604efuw.cc/tv";

    /**
     * 实测可用的内置可切换接口源: 首次启动预置到多源列表, 设置页可点击切换 / 长按删除.
     * 饭太硬已实测可加载首页并正常播放(详情页小窗/全屏均可出画);
     * 饭太硬 / 肥猫为图片伪装接口, 见 normalizeConfig();
     * spider 相对地址(./xxx)会按接口地址自动补全.
     */
    private static final String[] BUILTIN_SOURCES = {
            DEFAULT_API_URL,                            // 饭太硬(www.饭太硬.cc/tv) — 默认源
            "https://tv.xn--yhqu5zs87a.top",            // 菜妮丝
            "http://xn--z7x900a.net/tv",                // 肥猫(肥猫.net/tv)
            "http://tvbox.xn--4kq62z5rby2qupq9ub.top/", // 影视仓(旧默认源, 影视站点受 wex 代理影响)
    };

    /**
     * 预置直播源(首次启动写入"直播源"列表): 均为实测可用的公开源, 与接口源解耦.
     * 2026-10 实测: 综合直播/aptv/iptv-org 为央视/卫视电视直播(综合直播带EPG);
     * 虎牙/斗鱼一起看 为简片系(sub.ottiptv.cc, 走 jsdelivr 分发, 稳定性好)的互动直播间.
     */
    private static final String[] PRESET_LIVE_SOURCES = {
            "http://193.123.86.190:14888/TV/iptv.php",                              // 饭太硬·综合直播(222频道, 央视/卫视, 带EPG)
            "https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/iptv.m3u", // aptv(120频道, 含央视/卫视)
            "https://iptv-org.github.io/iptv/countries/cn.m3u",                     // iptv-org 国内源(145频道)
            "https://sub.ottiptv.cc/huyayqk.m3u",                                   // 简片系·虎牙一起看(1093路, 直播互动)
            "https://sub.ottiptv.cc/douyuyqk.m3u",                                  // 简片系·斗鱼一起看(941路, 直播互动)
    };

    /** 兜底直播源: 配置的直播源全部解析失败时才启用, 保证直播页不为空 */
    private static final String[] FALLBACK_LIVE_URLS = {
            "https://raw.githubusercontent.com/YanG-1989/m3u/main/Gather.m3u",
            "https://nos.netease.com/ysf/3d75a78a0fc7ede372c03598d6d10367.m3u",
    };

    /** 伪装接口里的 base64 配置片段(取最长的一段解码) */
    private static final java.util.regex.Pattern B64_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9+/]{512,}={0,2}");

    /** 去掉 UTF-8 BOM 与 JS 风格注释行, 便于判断是否已是 JSON */
    private static String stripComments(String s) {
        if (s == null) return "";
        String t = s;
        if (t.startsWith("\ufeff")) t = t.substring(1);
        return t.replaceAll("(?m)^\\s*//.*$", "").trim();
    }

    /**
     * 接口内容归一化:
     * 部分接口把整段配置 base64 后拼在图片末尾伪装成 .bmp/.jpg
     * (如 饭太硬 http://www.饭太硬.cc/tv 返回 JPEG + base64), 直接当 JSON 解析会失败.
     * 这里先判断是否已是 JSON, 否则取出最长的 base64 片段解码后再交给 Gson.
     */
    private static String normalizeConfig(String body) {
        if (body == null) return "";
        String trimmed = body.trim();
        if (stripComments(trimmed).startsWith("{")) return trimmed;
        java.util.regex.Matcher m = B64_PATTERN.matcher(body);
        String best = null;
        while (m.find()) {
            if (best == null || m.group().length() > best.length()) best = m.group();
        }
        if (best != null) {
            try {
                byte[] dec = android.util.Base64.decode(best, android.util.Base64.DEFAULT);
                String json = new String(dec, java.nio.charset.StandardCharsets.UTF_8).trim();
                if (stripComments(json).startsWith("{")) {
                    android.util.Log.i("ApiConfig", "unwrapped image+base64 config, b64len=" + best.length());
                    return json;
                }
            } catch (Throwable ignored) {}
        }
        return trimmed;
    }

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

    // ================= 直播源(与接口源分开配置) =================

    /** 独立直播源列表(设置页可增删; 首次启动预置内置直播源) */
    public List<String> getLiveSources() {
        if (liveSources == null) {
            liveSources = new ArrayList<>();
            try {
                String saved = PrefUtils.get(PrefUtils.K_LIVE_SOURCES, "");
                if (saved != null && !saved.isEmpty()) {
                    JsonArray arr = JsonParser.parseString(saved).getAsJsonArray();
                    for (JsonElement e : arr) {
                        String u = e.getAsString();
                        if (u != null && !u.isEmpty() && !liveSources.contains(u)) liveSources.add(u);
                    }
                }
            } catch (Throwable ignored) {}
            // 内置直播源一次性并入(之后用户长按删除不再复活)
            if (!PrefUtils.getBool(PrefUtils.K_LIVE_SOURCES_SEEDED, false)) {
                for (String u : PRESET_LIVE_SOURCES) {
                    if (!liveSources.contains(u)) liveSources.add(u);
                }
                PrefUtils.putBool(PrefUtils.K_LIVE_SOURCES_SEEDED, true);
                persistLiveSources();
            }
        }
        return liveSources;
    }

    private void persistLiveSources() {
        JsonArray arr = new JsonArray();
        for (String s : liveSources) arr.add(s);
        PrefUtils.put(PrefUtils.K_LIVE_SOURCES, arr.toString());
    }

    /** 新增直播源并重新解析 */
    public void addLiveSource(String url) {
        if (url == null || url.trim().isEmpty()) return;
        url = url.trim();
        getLiveSources();
        if (!liveSources.contains(url)) {
            liveSources.add(url);
            persistLiveSources();
        }
        liveReloadPending = true;
        loadLiveSources();
    }

    /** 删除直播源并重新解析 */
    public void removeLiveSource(String url) {
        if (url == null) return;
        getLiveSources();
        if (liveSources.remove(url)) {
            persistLiveSources();
            liveReloadPending = true;
            loadLiveSources();
        }
    }

    /** 是否额外跟随接口自带的直播源(默认关: 接口里的失效源会拖慢直播加载) */
    public boolean isLiveUseApi() { return "1".equals(PrefUtils.get(PrefUtils.K_LIVE_USE_API, "0")); }

    public void setLiveUseApi(boolean use) {
        PrefUtils.put(PrefUtils.K_LIVE_USE_API, use ? "1" : "0");
        liveReloadPending = true;
        loadLiveSources();
    }

    /** 当前接口自带的直播源数量(供设置页展示) */
    public int getApiLiveCount() { return apiLiveUrls.size(); }

    /** 直播页取用: 一次性读取"需要重建频道列表"标记 */
    public boolean consumeLiveReloadPending() {
        boolean p = liveReloadPending;
        liveReloadPending = false;
        return p;
    }

    /**
     * 解析直播源: 独立直播源 + (可选)接口自带源; 全部失效时用内置公开源兜底.
     * 结果先攒到局部列表再整体替换, 避免直播页读取时看到半截数据.
     */
    private void loadLiveSources() {
        final List<String> urls = new ArrayList<>(getLiveSources());
        if (isLiveUseApi()) {
            for (String u : apiLiveUrls) if (!urls.contains(u)) urls.add(u);
        }
        final Map<String, String> hdrs = apiLiveHeaders.isEmpty() ? null : new LinkedHashMap<>(apiLiveHeaders);
        ThreadUtils.io(() -> {
            List<LiveChannelGroup> groups = new ArrayList<>();
            List<String> epgs = new ArrayList<>();
            // 多个直播源并行拉取: 避免串行等待(尤其海外/代理源)导致直播页打开慢;
            // M3uParser 内部已有短超时(8s连接/12s读取), 这里再加整体上限兜底
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(Math.min(4, Math.max(1, urls.size())));
            List<java.util.concurrent.Future<M3uParser.Result>> futures = new ArrayList<>();
            try {
                for (String url : urls) {
                    futures.add(pool.submit(() -> parseLiveSafe(url, hdrs)));
                }
                for (java.util.concurrent.Future<M3uParser.Result> f : futures) {
                    try {
                        collectLive(f.get(25, java.util.concurrent.TimeUnit.SECONDS), groups, epgs);
                    } catch (Throwable ignored) {}
                }
            } finally {
                pool.shutdown();
            }
            // 配置的直播源全部失效时启用内置兜底, 保证直播页始终有频道
            if (groups.isEmpty()) {
                android.util.Log.w("ApiConfig", "no live channels from configured sources -> fallback");
                for (String url : FALLBACK_LIVE_URLS) {
                    collectLive(parseLiveSafe(url, null), groups, epgs);
                }
            }
            liveGroups.clear();
            liveGroups.addAll(groups);
            liveEpgUrls.clear();
            liveEpgUrls.addAll(epgs);
            // 直播源就绪后异步加载 EPG 节目单(声明地址不可用时自动回退兜底 EPG)
            EpgManager.get().load(new ArrayList<>(epgs));
        });
    }

    private static void collectLive(M3uParser.Result r, List<LiveChannelGroup> groups, List<String> epgs) {
        if (r == null) return;
        groups.addAll(r.groups);
        for (String epg : r.epgUrls) if (!epgs.contains(epg)) epgs.add(epg);
    }

    private static M3uParser.Result parseLiveSafe(String url, Map<String, String> hdrs) {
        try {
            return M3uParser.parseFromUrl(url, hdrs);
        } catch (Throwable t) {
            android.util.Log.w("ApiConfig", "live source failed: " + url + " : " + t);
            return null;
        }
    }

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
                parse(normalizeConfig(sb.toString()), "");
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
                String saved = PrefUtils.get(PrefUtils.K_API_SOURCES, "");
                if (saved != null && !saved.isEmpty()) {
                    JsonArray arr = JsonParser.parseString(saved).getAsJsonArray();
                    for (JsonElement e : arr) {
                        String u = e.getAsString();
                        if (u != null && !u.isEmpty() && !apiSources.contains(u)) apiSources.add(u);
                    }
                }
            } catch (Throwable ignored) {}
            // 内置源一次性并入(每个安装只并入一次, 之后用户长按删除不再复活)
            if (!PrefUtils.getBool(PrefUtils.K_API_SOURCES_SEEDED, false)) {
                for (String u : BUILTIN_SOURCES) {
                    if (!apiSources.contains(u)) apiSources.add(u);
                }
                PrefUtils.putBool(PrefUtils.K_API_SOURCES_SEEDED, true);
                persistSources();
            }
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
            parse(normalizeConfig(json), url.trim());
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

    /** 解析接口 JSON (TVBox 单接口格式: sites + lives + parses + headers); baseUrl 用于补全相对地址 */
    private void parse(String json, String baseUrl) {
        sites.clear();
        liveGroups.clear();
        liveEpgUrls.clear();
        apiLiveUrls.clear();
        apiLiveHeaders.clear();
        parses.clear();
        globalHeaders.clear();
        SpiderManager.clear();

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        // 全局 spider jar (TVBox 规范: "spider": "url;md5;xxx", url 可为 ./xxx 相对地址)
        globalSpider = root.has("spider") ? resolveUrl(root.get("spider").getAsString(), baseUrl) : "";

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
            // 相对地址(./xxx)按接口地址补全, 让 ./lib/xxx.js、./jar/xxx.jar 这类站点可用
            site.setApi(resolveUrl(site.getApi(), baseUrl));
            site.setJar(resolveUrl(site.getJar(), baseUrl));
            // 过滤不可切换源(changeable 显式为 0)和纯 jar 占位站点(api为空)
            // 注意: TVBox 接口常省略 changeable 字段, 此时默认为可切换
            boolean explicitNoChange = o.has("changeable") && o.get("changeable").getAsInt() == 0;
            boolean noApi = site.getApi() == null || site.getApi().isEmpty();
            if (explicitNoChange || noApi) continue;
            sites.add(site);
        }

        // lives: type 0 = m3u/txt 直播地址; type 3 = spider 直播
        // 接口自带的直播地址单独暂存, 是否启用由"跟随接口自带直播源"开关决定(默认关)
        JsonArray liveArr = root.has("lives") ? root.getAsJsonArray("lives") : new JsonArray();
        apiLiveUrls.clear();
        for (JsonElement el : liveArr) {
            JsonObject o = el.getAsJsonObject();
            int type = o.has("type") ? o.get("type").getAsInt() : 0;
            String url = o.has("url") ? o.get("url").getAsString() : "";
            if (url.isEmpty()) continue;
            if (type == 0) {
                apiLiveUrls.add(resolveUrl(url, baseUrl));
                if (o.has("ua")) apiLiveHeaders.put("User-Agent", o.get("ua").getAsString());
            }
            // type 3 (spider 直播) 预留: 二期通过 Spider.liveContent 拉取
        }
        // 异步加载直播源(与接口加载解耦, 避免单个直播源超时阻塞接口完成)
        loadLiveSources();

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

    /**
     * 把 TVBox 的相对地址(./xxx 或 ../xxx)按接口源地址补全为绝对地址.
     * 仅处理 ./ 与 ../ 前缀: csp_Xxx 这类 spider 类名、以及已有协议的地址原样返回.
     * 保留 ;md5;xxx 校验后缀.
     */
    private static String resolveUrl(String url, String baseUrl) {
        if (url == null || url.isEmpty()) return url;
        if (!url.startsWith("./") && !url.startsWith("../")) return url;
        if (baseUrl == null || baseUrl.isEmpty()) return url;
        String path = url;
        String suffix = "";
        int idx = url.indexOf(";md5;");
        if (idx > 0) {
            path = url.substring(0, idx);
            suffix = url.substring(idx);
        }
        try {
            return new java.net.URL(new java.net.URL(baseUrl), path).toString() + suffix;
        } catch (Throwable t) {
            return url;
        }
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
