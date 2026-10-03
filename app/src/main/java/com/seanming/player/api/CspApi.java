package com.seanming.player.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.seanming.player.bean.Result;
import com.seanming.player.bean.Site;
import com.seanming.player.bean.Vod;
import com.seanming.player.bean.VodClass;
import com.seanming.player.util.OkHttpUtil;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 苹果cms 采集接口请求(type 0=xml, type 1=json).
 * 封装首页/分类/详情/搜索 API.
 */
public class CspApi {

    private static String query(String url) {
        try { return OkHttpUtil.get(url); } catch (Exception e) { return null; }
    }

    /** 首页: class + vod list(带海报) */
    public static Result homeContent(Site site) {
        String base = site.getApi() + (site.getApi().endsWith("/") ? "" : "/");
        Result r = new Result();
        // 1. ac=list 获取分类列表(class)
        String json = query(base + "?ac=list");
        if (json == null) return Result.error("network error");
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray clsArr = root.has("class") ? root.getAsJsonArray("class") : new JsonArray();
            for (JsonElement el : clsArr) {
                JsonObject o = el.getAsJsonObject();
                VodClass vc = new VodClass();
                vc.type_id = o.has("type_id") ? o.get("type_id").getAsString() : "";
                vc.type_name = o.has("type_name") ? o.get("type_name").getAsString() : "";
                vc.type_flag = o.has("type_flag") ? o.get("type_flag").getAsString() : "";
                if (!vc.type_name.isEmpty()) r.classes.add(vc);
            }
            // ac=list 的 list 作为兜底(部分源无 vod_pic)
            JsonArray listArr = root.has("list") ? root.getAsJsonArray("list") : new JsonArray();
            for (JsonElement el : listArr) {
                r.list.add(parseVod(el.getAsJsonObject()));
            }
            r.page = root.has("page") ? root.get("page").getAsInt() : 1;
            r.pagecount = root.has("pagecount") ? root.get("pagecount").getAsInt() : 1;
            r.total = root.has("total") ? root.get("total").getAsInt() : r.list.size();
        } catch (Exception e) {
            return Result.error("parse: " + e.getMessage());
        }
        // 2. ac=videolist 获取带 vod_pic 的最近更新列表, 替换掉无海报的兜底列表
        String json2 = query(base + "?ac=videolist&pg=1");
        if (json2 != null) {
            try {
                JsonObject root2 = JsonParser.parseString(json2).getAsJsonObject();
                JsonArray listArr2 = root2.has("list") ? root2.getAsJsonArray("list") : new JsonArray();
                if (listArr2.size() > 0) {
                    r.list.clear();
                    for (JsonElement el : listArr2) {
                        r.list.add(parseVod(el.getAsJsonObject()));
                    }
                    r.total = root2.has("total") ? root2.get("total").getAsInt() : r.list.size();
                }
            } catch (Exception ignored) {}
        }
        return r;
    }

    /** 首页推荐(用最近更新充数, 如需精确则改调用 homeContent) */
    public static Result homeVideoContent(Site site) {
        return homeContent(site);
    }

    /** 分类列表 */
    public static Result categoryContent(Site site, String tid, int pg) {
        StringBuilder sb = new StringBuilder();
        sb.append(site.getApi());
        if (!site.getApi().endsWith("/")) sb.append("/");
        // videolist 返回带 vod_pic 的列表, 供海报展示
        sb.append("?ac=videolist");
        if (tid != null && !tid.isEmpty()) sb.append("&t=").append(tid);
        sb.append("&pg=").append(pg);
        String json = query(sb.toString());
        if (json == null) return Result.error("network error");
        Result r = new Result();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray listArr = root.has("list") ? root.getAsJsonArray("list") : new JsonArray();
            for (JsonElement el : listArr) {
                Vod v = parseVod(el.getAsJsonObject());
                r.list.add(v);
            }
            r.page = root.has("page") ? root.get("page").getAsInt() : pg;
            r.pagecount = root.has("pagecount") ? root.get("pagecount").getAsInt() : 1;
            r.total = root.has("total") ? root.get("total").getAsInt() : r.list.size();
        } catch (Exception e) {
            return Result.error("parse: " + e.getMessage());
        }
        return r;
    }

    /** 详情(支持批量, 只取首个) */
    public static Result detailContent(Site site, List<String> ids) {
        if (ids.isEmpty()) return Result.error("empty id");
        StringBuilder sb = new StringBuilder();
        sb.append(site.getApi());
        if (!site.getApi().endsWith("/")) sb.append("/");
        sb.append("?ac=detail");
        sb.append("&ids=").append(ids.get(0));
        String json = query(sb.toString());
        if (json == null) return Result.error("network error");
        Result r = new Result();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray listArr = root.has("list") ? root.getAsJsonArray("list") : new JsonArray();
            for (JsonElement el : listArr) {
                Vod v = parseVod(el.getAsJsonObject());
                r.list.add(v);
            }
        } catch (Exception e) {
            return Result.error("parse: " + e.getMessage());
        }
        return r;
    }

    /** 搜索(关键词 wd 支持模糊匹配) */
    public static Result searchContent(Site site, String key, int pg) {
        StringBuilder sb = new StringBuilder();
        sb.append(site.getApi());
        if (!site.getApi().endsWith("/")) sb.append("/");
        sb.append("?ac=videolist");
        // 中文关键词需 URL 编码, 否则 URL 非法导致搜索失败
        try {
            sb.append("&wd=").append(URLEncoder.encode(key, "UTF-8"));
        } catch (Throwable ignored) {
            sb.append("&wd=").append(key);
        }
        sb.append("&pg=").append(pg);
        String json = query(sb.toString());
        if (json == null) return Result.error("network error");
        Result r = new Result();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray listArr = root.has("list") ? root.getAsJsonArray("list") : new JsonArray();
            for (JsonElement el : listArr) {
                Vod v = parseVod(el.getAsJsonObject());
                r.list.add(v);
            }
            r.page = root.has("page") ? root.get("page").getAsInt() : pg;
            r.pagecount = root.has("pagecount") ? root.get("pagecount").getAsInt() : 1;
            r.total = root.has("total") ? root.get("total").getAsInt() : r.list.size();
        } catch (Exception e) {
            return Result.error("parse: " + e.getMessage());
        }
        return r;
    }

    private static Vod parseVod(JsonObject o) {
        Vod v = new Vod();
        v.vod_id = optStr(o, "vod_id");
        v.vod_name = optStr(o, "vod_name");
        v.vod_pic = optStr(o, "vod_pic");
        v.vod_remarks = optStr(o, "vod_remarks");
        v.vod_year = optStr(o, "vod_year");
        v.vod_area = optStr(o, "vod_area");
        v.vod_director = optStr(o, "vod_director");
        v.vod_actor = optStr(o, "vod_actor");
        v.vod_content = optStr(o, "vod_content");
        v.vod_play_from = optStr(o, "vod_play_from");
        v.vod_play_url = optStr(o, "vod_play_url");
        v.type_id = optStr(o, "type_id");
        v.type_name = optStr(o, "type_name");
        return v;
    }

    private static String optStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
