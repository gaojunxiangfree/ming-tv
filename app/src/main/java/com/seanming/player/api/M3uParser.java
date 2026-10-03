package com.seanming.player.api;

import com.seanming.player.bean.LiveChannel;
import com.seanming.player.bean.LiveChannelGroup;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 直播源解析: 同时兼容 标准 m3u/m3u8 与 TVBox 直播 txt 两种格式.
 *
 * m3u: "#EXTINF:-1 tvg-id=..,频道名" + 下一行 url
 * txt: "分组名,#genre#" 定义分组; "频道名,url1#url2" 定义频道
 *
 * 同时提取 x-tvg-url(EPG 地址).
 * 针对国内 GitHub 代理源(ghfast.top / gh-proxy.com 等)做了自动回退:
 * 代理不可用时, 尝试直连 raw.githubusercontent.com.
 */
public class M3uParser {

    /** 解析结果: 频道分组 + EPG 地址列表 */
    public static class Result {
        public final List<LiveChannelGroup> groups = new ArrayList<>();
        public final List<String> epgUrls = new ArrayList<>();
        public boolean isEmpty() { return groups.isEmpty(); }
    }

    /** 已知的 GitHub 反代前缀(这些前缀后拼接完整原始 URL) */
    private static final String[] PROXY_PREFIXES = {
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://hk.gh-proxy.org/",
            "https://gh.aptv.app/",
            "https://raw.githubusercontents.com/",
            "https://ghproxy.net/",
    };

    /** 从 URL 拉取并解析, 失败时自动尝试直连/代理回退 */
    public static Result parseFromUrl(String url, Map<String, String> headers) {
        Exception last = null;
        for (String candidate : candidateUrls(url)) {
            try {
                String text = fetch(candidate, headers);
                if (text != null && !text.trim().isEmpty()) {
                    Result r = parseSource(text);
                    if (!r.isEmpty() || text.contains("#EXTM3U")) {
                        android.util.Log.i("M3uParser", "parsed ok via " + candidate
                                + " groups=" + r.groups.size() + " epg=" + r.epgUrls.size());
                        return r;
                    }
                }
            } catch (Exception e) {
                last = e;
                android.util.Log.w("M3uParser", "fetch failed " + candidate + " : " + e);
            }
        }
        android.util.Log.w("M3uParser", "all candidates failed for " + url + " : " + last);
        return new Result();
    }

    /** 直播源单次拉取: 短超时(避免代理卡住整个直播列表加载), 不做多重试 */
    private static String fetch(String url, Map<String, String> headers) throws Exception {
        okhttp3.Request.Builder b = new okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", com.seanming.player.util.OkHttpUtil.UA);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) b.header(e.getKey(), e.getValue());
        }
        okhttp3.OkHttpClient c = LIVE_CLIENT;
        try (okhttp3.Response resp = c.newCall(b.build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new java.io.IOException("http " + resp.code());
            }
            return resp.body().string();
        }
    }

    private static final okhttp3.OkHttpClient LIVE_CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    /**
     * 生成候选 URL:
     *  - 带已知反代前缀: 优先直连 raw.githubusercontent.com(更快), 再回退到原代理与其它代理
     *  - 直连 github: 原地址优先, 再依次套用代理前缀
     */
    private static List<String> candidateUrls(String url) {
        List<String> list = new ArrayList<>();
        if (url == null || url.isEmpty()) return list;
        String raw = extractRaw(url);
        boolean hasProxyPrefix = raw != null && !url.startsWith("https://raw.githubusercontent.com");
        if (hasProxyPrefix) {
            list.add(raw);
            list.add(url);
            for (String p : PROXY_PREFIXES) {
                String u = p + raw;
                if (!list.contains(u)) list.add(u);
            }
        } else {
            list.add(url);
            if (url.contains("raw.githubusercontent.com")) {
                for (String p : PROXY_PREFIXES) {
                    String u = p + url;
                    if (!list.contains(u)) list.add(u);
                }
            }
        }
        return list;
    }

    /** 从代理 URL 中还原直连 raw.githubusercontent.com 地址 */
    private static String extractRaw(String url) {
        int i = url.indexOf("raw.githubusercontent.com/");
        if (i < 0) return null;
        return "https://" + url.substring(i);
    }

    /** 向后兼容: 仅返回分组 */
    public static List<LiveChannelGroup> parse(String text) {
        return parseSource(text).groups;
    }

    public static Result parseSource(String text) {
        Result result = new Result();
        if (text == null || text.isEmpty()) return result;
        if (text.contains("#EXTINF") || text.contains("#EXTM3U")) {
            parseM3u(text, result);
        } else {
            parseTxt(text, result);
        }
        return result;
    }

    // ============ m3u ============

    private static void parseM3u(String text, Result result) {
        List<LiveChannel> all = new ArrayList<>();
        LiveChannel current = null;
        int number = 0;

        BufferedReader br = new BufferedReader(new StringReader(text));
        String line;
        try {
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#EXTM3U")) {
                    // x-tvg-url="a,b,c" 或 url-tvg=".."
                    collectEpg(line, "x-tvg-url=", result);
                    collectEpg(line, "url-tvg=", result);
                } else if (line.startsWith("#EXTINF:")) {
                    current = new LiveChannel();
                    current.name = extractValue(line, "tvg-name=");
                    if (current.name == null || current.name.isEmpty()) {
                        int comma = line.lastIndexOf(',');
                        current.name = comma > 0 ? line.substring(comma + 1).trim() : "";
                    }
                    current.logo = extractValue(line, "tvg-logo=");
                    current.epgId = extractValue(line, "tvg-id=");
                    current.group = extractValue(line, "group-title=");
                    current.httpUserAgent = extractValue(line, "http-user-agent=");
                    // 回看地址 tvg-rec="url1,url2" 或 url-rec=".."
                    collectRec(current, line, "tvg-rec=");
                    collectRec(current, line, "url-rec=");
                    current.number = ++number;
                } else if (current != null && !line.isEmpty() && !line.startsWith("#")) {
                    current.urls.add(line);
                    all.add(current);
                    current = null;
                }
            }
        } catch (Exception ignored) {}

        groupChannels(all, result);
    }

    /** 收集 x-tvg-url 中的多个 EPG 地址(逗号分隔) */
    private static void collectEpg(String line, String key, Result result) {
        String v = extractValue(line, key);
        if (v == null || v.isEmpty()) return;
        for (String u : v.split(",")) {
            u = u.trim();
            if (!u.isEmpty() && !result.epgUrls.contains(u)) result.epgUrls.add(u);
        }
    }

    /** 收集频道的回看地址(逗号分隔) */
    private static void collectRec(LiveChannel ch, String line, String key) {
        String v = extractValue(line, key);
        if (v == null || v.isEmpty()) return;
        for (String u : v.split(",")) {
            u = u.trim();
            if (!u.isEmpty() && !ch.recUrls.contains(u)) ch.recUrls.add(u);
        }
        if (!ch.recUrls.isEmpty() && ch.recUrls.get(0).contains("{") && ch.recUrls.get(0).contains("}")) {
            ch.recTemplate = ch.recUrls.get(0);
        }
    }

    // ============ TVBox 直播 txt ============

    private static void parseTxt(String text, Result result) {
        List<LiveChannel> all = new ArrayList<>();
        int number = 0;
        String group = null; // 当前分组名
        BufferedReader br = new BufferedReader(new StringReader(text));
        String line;
        try {
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int comma = line.indexOf(',');
                if (comma <= 0) continue;
                String name = line.substring(0, comma).trim();
                String value = line.substring(comma + 1).trim();
                // 分组定义: "频道/分类名,#genre#"
                if ("#genre#".equals(value)) {
                    group = name;
                    continue;
                }
                if (value.isEmpty()) continue;
                LiveChannel c = new LiveChannel();
                c.name = name;
                c.group = group;
                c.number = ++number;
                for (String u : value.split("#")) {
                    u = u.trim();
                    if (!u.isEmpty()) c.urls.add(u);
                }
                if (!c.urls.isEmpty()) all.add(c);
            }
        } catch (Exception ignored) {}

        groupChannels(all, result);
    }

    private static void groupChannels(List<LiveChannel> all, Result result) {
        Map<String, LiveChannelGroup> map = new LinkedHashMap<>();
        for (LiveChannel c : all) {
            String g = c.group == null || c.group.isEmpty() ? "未分组" : c.group;
            LiveChannelGroup group = map.get(g);
            if (group == null) {
                group = new LiveChannelGroup(g);
                map.put(g, group);
            }
            group.channels.add(c);
        }
        result.groups.addAll(map.values());
    }

    private static String extractValue(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return null;
        i += key.length();
        if (i < line.length() && line.charAt(i) == '"') {
            int j = line.indexOf('"', i + 1);
            return j > i ? line.substring(i + 1, j) : "";
        }
        return null;
    }
}