package com.seanming.player.ui.play;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.seanming.player.api.ApiConfig;
import com.seanming.player.bean.Parse;
import com.seanming.player.bean.Result;
import com.seanming.player.bean.Site;
import com.seanming.player.spider.SpiderManager;
import com.seanming.player.util.OkHttpUtil;

import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

/**
 * 统一解析(视频)真实播放地址的辅助类.
 * 全屏(PlayActivity)与详情页小窗预览(DetailActivity)共用同一套解析逻辑,
 * 保证: 爬虫站经 playerContent 解析, 失败时回退到直链, 需要二次解析时走 parses 链路.
 */
public final class PlayUrlResolver {

    /** 解析结果: 播放地址 + 可选请求头(如 WebDAV Basic / 防盗链 referer) */
    public static class Resolved {
        public final String url;
        public final Map<String, String> headers;
        public Resolved(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    private PlayUrlResolver() {}

    /**
     * 解析真实播放地址.
     * @param site    当前站点
     * @param flag    线路标识(vod.playFlags 的 key)
     * @param epUrl   选集地址(爬虫站为编码 ID, 直链站即真实地址)
     * @param directHeaders 网盘/推送直连时由外部传入的请求头
     */
    public static Resolved resolve(Site site, String flag, String epUrl,
                                   Map<String, String> directHeaders) {
        String url = null;
        Map<String, String> headers = null;
        int needParse = 0;
        if (site != null && site.isSpider()) {
            // 爬虫站点 epUrl 是编码后的 ID, 需经 playerContent 解析
            try {
                Result r = SpiderManager.playerContent(site, flag, epUrl, null);
                if (r != null && !r.hasError() && r.url != null && !r.url.isEmpty()) {
                    url = r.url;
                    needParse = r.parse;
                    if (r.header != null && !r.header.isEmpty()) {
                        try {
                            headers = new Gson().fromJson(r.header, Map.class);
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("PlayUrlResolver", "playerContent fail: " + t.getMessage());
            }
        } else {
            url = epUrl;
            headers = directHeaders;
        }
        // 回退: 爬虫解析失败(如加密原生库加载失败)时, 若 epUrl 本身已是直链则直接使用
        if ((url == null || url.isEmpty()) && isDirectUrl(epUrl)) {
            url = epUrl;
        }
        // 爬虫要求二次解析(parse=1): 经接口配置中的 parses 链路解析为可播放地址
        if (needParse == 1 && url != null) {
            url = resolveWithParses(url);
        }
        return url == null ? null : new Resolved(url, headers);
    }

    /** 判断 url 是否本身已是可直接播放的直链(带视频扩展名的 http 地址) */
    public static boolean isDirectUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        return lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".ts")
                || lower.contains(".flv") || lower.contains(".mkv") || lower.contains(".avi")
                || lower.contains(".mov") || lower.contains(".webm") || lower.contains(".mp3");
    }

    /** 经接口配置的解析链路把播放地址解析为可播放地址 */
    private static String resolveWithParses(String rawUrl) {
        List<Parse> parses = ApiConfig.get().getParses();
        if (parses.isEmpty()) return rawUrl;
        for (Parse p : parses) {
            if (p.type != 1) continue;
            try {
                String full = p.url + URLEncoder.encode(rawUrl, "UTF-8");
                String resp = OkHttpUtil.get(full);
                if (resp == null || resp.isEmpty()) continue;
                String resolved = extractPlayableUrl(resp);
                if (resolved != null && !resolved.isEmpty()) return resolved;
            } catch (Throwable ignored) {}
        }
        return rawUrl;
    }

    /** 从解析接口的 JSON 响应中提取真实播放地址 */
    private static String extractPlayableUrl(String resp) {
        try {
            JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
            String[] keys = {"url", "playUrl", "alyUrl", "src"};
            for (String k : keys) {
                if (o.has(k) && o.get(k).isJsonPrimitive()) {
                    String v = o.get(k).getAsString();
                    if (v.startsWith("http")) return v;
                }
            }
            if (o.has("data") && o.get("data").isJsonObject()) {
                JsonObject d = o.getAsJsonObject("data");
                for (String k : keys) {
                    if (d.has(k) && d.get(k).isJsonPrimitive()) {
                        String v = d.get(k).getAsString();
                        if (v.startsWith("http")) return v;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}