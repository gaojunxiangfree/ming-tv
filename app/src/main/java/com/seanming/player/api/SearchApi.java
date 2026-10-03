package com.seanming.player.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.seanming.player.util.OkHttpUtil;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 搜索辅助接口(对齐影视仓 SearchActivity 使用的两个外部接口):
 *  1) 360kan 热搜榜: 打开搜索页展示的"电视榜/电影榜/综艺榜/儿童榜";
 *  2) 爱奇艺联想词: 输入关键词时实时给出候选词.
 * 两个接口都只依赖公网, 与用户配置的接口源无关, 因此用短超时 + 静默失败.
 */
public class SearchApi {

    /** 热搜榜(返回 JSONP, 需带 referer) */
    private static final String RANK_URL =
            "https://api.web.360kan.com/v1/rank?cat=7&callback=__jp0";
    /** 爱奇艺搜索联想 */
    private static final String SUGGEST_URL =
            "https://suggest.video.iqiyi.com/?if=mobile&key=";

    /** 热榜分类: cat -> 展示名(顺序即展示顺序, 与影视仓一致) */
    private static final String[][] RANK_CATS = {
            {"2", "电视榜"},
            {"1", "电影榜"},
            {"3", "综艺榜"},
            {"4", "儿童榜"},
    };

    /** 短超时客户端: 热榜/联想属于辅助数据, 不能拖慢搜索页 */
    private static final OkHttpClient CLIENT = OkHttpUtil.client().newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build();

    /**
     * 拉取 360kan 热搜榜.
     * 返回有序表: 榜名(如"电视榜") -> 该榜的热搜词列表.
     */
    public static LinkedHashMap<String, List<String>> hotRank() throws IOException {
        Request req = new Request.Builder()
                .url(RANK_URL)
                .header("User-Agent", OkHttpUtil.UA)
                .header("referer", "https://www.360kan.com/rank/index?from=siteslibsubpage")
                .build();
        String body;
        try (Response resp = CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("http " + resp.code());
            }
            body = resp.body().string();
        }
        // JSONP: 截取第一个 '{' 到最后一个 '}' 之间的 JSON
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IOException("bad rank payload");

        JsonObject root = JsonParser.parseString(body.substring(start, end + 1)).getAsJsonObject();
        JsonElement dataEl = root.get("data");
        if (dataEl == null || !dataEl.isJsonArray()) throw new IOException("no rank data");
        JsonArray data = dataEl.getAsJsonArray();

        LinkedHashMap<String, List<String>> out = new LinkedHashMap<>();
        for (String[] cat : RANK_CATS) out.put(cat[1], new ArrayList<>());
        for (JsonElement el : data) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("title") || !o.has("cat")) continue;
            String title = o.get("title").getAsString().trim();
            if (title.isEmpty()) continue;
            String cat = String.valueOf(o.get("cat").getAsInt());
            for (String[] c : RANK_CATS) {
                if (c[0].equals(cat)) {
                    List<String> words = out.get(c[1]);
                    if (words != null && !words.contains(title)) words.add(title);
                    break;
                }
            }
        }
        // 丢掉没有任何词的空榜
        LinkedHashMap<String, List<String>> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : out.entrySet()) {
            if (!e.getValue().isEmpty()) filtered.put(e.getKey(), e.getValue());
        }
        return filtered;
    }

    /** 拉取爱奇艺联想词(与影视仓一致: 去掉书名号/尖括号/连字符) */
    public static List<String> suggest(String key) throws IOException {
        if (key == null || key.trim().isEmpty()) return new ArrayList<>();
        Request req = new Request.Builder()
                .url(SUGGEST_URL + URLEncoder.encode(key.trim(), "UTF-8"))
                .header("User-Agent", OkHttpUtil.UA)
                .build();
        String body;
        try (Response resp = CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("http " + resp.code());
            }
            body = resp.body().string();
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonElement dataEl = root.get("data");
        if (dataEl == null || !dataEl.isJsonArray()) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (JsonElement el : dataEl.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("name")) continue;
            String name = o.get("name").getAsString().trim().replaceAll("<|>|《|》|-", "");
            if (!name.isEmpty() && !out.contains(name)) out.add(name);
            if (out.size() >= 30) break;
        }
        return out;
    }
}
