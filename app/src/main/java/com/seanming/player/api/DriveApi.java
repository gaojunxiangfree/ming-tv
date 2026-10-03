package com.seanming.player.api;

import android.net.Uri;
import android.util.Xml;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.seanming.player.bean.DriveFile;
import com.seanming.player.bean.StorageDrive;
import com.seanming.player.util.OkHttpUtil;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 网盘直连: WebDAV(PROPFIND) 与 AList/OpenList(v3 API).
 *
 * WebDAV:   PROPFIND + Depth:1, 解析 multistatus.
 * AList:    POST api/auth/login 取 token -> api/fs/list 列目录 -> api/fs/get 取 raw_url;
 *           无账号时回退 api/public/path.
 */
public class DriveApi {

    /** 列目录结果 */
    public static class ListResult {
        public final List<DriveFile> files = new ArrayList<>();
        public String error;
        public boolean ok() { return error == null; }
    }

    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\" ?>" +
            "<D:propfind xmlns:D=\"DAV:\"><D:prop>" +
            "<D:displayname/><D:getcontentlength/><D:getlastmodified/><D:resourcetype/>" +
            "</D:prop></D:propfind>";

    // ==================== 列目录 ====================

    public static ListResult list(StorageDrive d, String path) {
        if (d == null) {
            ListResult r = new ListResult();
            r.error = "网盘配置为空";
            return r;
        }
        String p = normalize(path);
        return d.type == StorageDrive.TYPE_ALIST ? listAlist(d, p) : listWebDav(d, p);
    }

    // ---------- WebDAV ----------

    private static ListResult listWebDav(StorageDrive d, String path) {
        ListResult r = new ListResult();
        String base = d.baseUrl();
        if (base.isEmpty()) { r.error = "地址为空"; return r; }
        String url = base + encodePath(path);
        try {
            Request.Builder b = new Request.Builder()
                    .url(url)
                    .method("PROPFIND", RequestBody.create(XML, PROPFIND_BODY))
                    .header("Depth", "1")
                    .header("User-Agent", OkHttpUtil.UA);
            String auth = d.basicAuth();
            if (auth != null) b.header("Authorization", auth);
            try (Response resp = OkHttpUtil.client().newCall(b.build()).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (resp.code() != 207 && !resp.isSuccessful()) {
                    r.error = "HTTP " + resp.code();
                    return r;
                }
                parseWebDav(body, d, path, r.files);
                if (r.files.isEmpty() && body.isEmpty()) r.error = "无响应内容";
            }
        } catch (Throwable t) {
            r.error = friendly(t);
        }
        return r;
    }

    private static void parseWebDav(String xml, StorageDrive d, String reqPath, List<DriveFile> out) {
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            p.setInput(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "UTF-8");
            int event = p.getEventType();
            String href = null, displayName = null, lastMod = null;
            long size = 0;
            boolean isDir = false, inResp = false;
            while (event != XmlPullParser.END_DOCUMENT) {
                String name = p.getName();
                switch (event) {
                    case XmlPullParser.START_TAG:
                        if ("response".equalsIgnoreCase(name)) {
                            inResp = true;
                            href = null; displayName = null; lastMod = null; size = 0; isDir = false;
                        } else if (inResp && "href".equalsIgnoreCase(name)) {
                            href = p.nextText();
                        } else if (inResp && "displayname".equalsIgnoreCase(name)) {
                            displayName = p.nextText();
                        } else if (inResp && "getcontentlength".equalsIgnoreCase(name)) {
                            size = parseLong(p.nextText());
                        } else if (inResp && "getlastmodified".equalsIgnoreCase(name)) {
                            lastMod = p.nextText();
                        } else if (inResp && "collection".equalsIgnoreCase(name)) {
                            isDir = true;
                        }
                        break;
                    case XmlPullParser.END_TAG:
                        if ("response".equalsIgnoreCase(name) && inResp) {
                            inResp = false;
                            if (href != null) {
                                String rel = relFromHref(href, d);          // 相对网盘根, 形如 "a/b.mkv"
                                String norm = normalize("/" + rel);
                                if (!norm.equals(normalize(reqPath))) {     // 跳过自身
                                    String fn = lastSegment(rel);
                                    String dn = (displayName == null || displayName.trim().isEmpty()) ? fn : displayName.trim();
                                    DriveFile f = new DriveFile(dn, isDir, norm);
                                    f.drive = d;
                                    f.size = size;
                                    f.lastModified = parseHttpDate(lastMod);
                                    out.add(f);
                                }
                            }
                        }
                        break;
                }
                event = p.next();
            }
        } catch (Throwable ignored) {
        }
        sortFiles(out);
    }

    /** href 去掉 base 路径前缀, 得到相对网盘根的路径 */
    private static String relFromHref(String href, StorageDrive d) {
        String hp = href;
        try { hp = new java.net.URL(href).getPath(); } catch (Exception ignored) {}
        if (hp == null) return "";
        hp = Uri.decode(hp);
        String basePath = "/";
        try {
            String bp = new java.net.URL(d.baseUrl()).getPath();
            if (bp != null && !bp.isEmpty()) basePath = bp;
        } catch (Exception ignored) {}
        if (!basePath.endsWith("/")) basePath += "/";
        if (hp.startsWith(basePath)) return hp.substring(basePath.length());
        if (hp.startsWith("/")) return hp.substring(1);
        return hp;
    }

    // ---------- AList ----------

    private static ListResult listAlist(StorageDrive d, String path) {
        ListResult r = new ListResult();
        String base = d.baseUrl();
        if (base.isEmpty()) { r.error = "地址为空"; return r; }
        // 1) 优先认证接口
        String token = login(d);
        if (token != null) {
            ListResult authed = listAlistAuthed(d, base, path, token);
            if (authed.ok()) return authed;
        }
        // 2) 回退公开接口
        return listAlistPublic(d, base, path);
    }

    private static String login(StorageDrive d) {
        if (d.username == null || d.username.isEmpty()) return null;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("username", d.username);
            body.addProperty("password", d.password == null ? "" : d.password);
            JsonObject resp = postJson(d.baseUrl() + "api/auth/login", body, null);
            if (resp != null && resp.has("data")) {
                JsonElement t = resp.getAsJsonObject("data").get("token");
                if (t != null && !t.isJsonNull()) {
                    d.token = t.getAsString();
                    return d.token;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static ListResult listAlistAuthed(StorageDrive d, String base, String path, String token) {
        ListResult r = new ListResult();
        try {
            JsonObject body = new JsonObject();
            body.addProperty("path", normalize(path));
            body.addProperty("password", d.password == null ? "" : d.password);
            body.addProperty("page", 1);
            body.addProperty("per_page", 0);
            body.addProperty("refresh", false);
            JsonObject resp = postJson(base + "api/fs/list", body, token);
            if (resp == null || !resp.has("data")) { r.error = "AList 无数据"; return r; }
            JsonObject data = resp.getAsJsonObject("data");
            if (data.has("content") && data.get("content").isJsonArray()) {
                for (JsonElement e : data.getAsJsonArray("content")) {
                    JsonObject o = e.getAsJsonObject();
                    String nm = optString(o, "name");
                    boolean isDir = optBool(o, "is_dir") || optInt(o, "type") == 1;
                    DriveFile f = new DriveFile(nm, isDir, joinPath(path, nm));
                    f.drive = d;
                    f.size = optLong(o, "size");
                    f.lastModified = parseIso(optString(o, "modified"));
                    out(r.files, f);
                }
            } else if (data.has("files") && data.get("files").isJsonArray()) {
                parsePublicFiles(d, path, data.getAsJsonArray("files"), r.files);
            }
            sortFiles(r.files);
        } catch (Throwable t) {
            r.error = friendly(t);
        }
        return r;
    }

    private static ListResult listAlistPublic(StorageDrive d, String base, String path) {
        ListResult r = new ListResult();
        try {
            JsonObject body = new JsonObject();
            body.addProperty("path", normalize(path));
            body.addProperty("password", d.password == null ? "" : d.password);
            body.addProperty("page_num", 1);
            body.addProperty("page_size", 0);
            JsonObject resp = postJson(base + "api/public/path", body, null);
            if (resp == null || !resp.has("data")) { r.error = "AList 公开接口无数据"; return r; }
            JsonObject data = resp.getAsJsonObject("data");
            JsonArray files = data.has("files") ? data.getAsJsonArray("files")
                    : (data.has("content") ? data.getAsJsonArray("content") : null);
            if (files != null) parsePublicFiles(d, path, files, r.files);
            sortFiles(r.files);
        } catch (Throwable t) {
            r.error = friendly(t);
        }
        return r;
    }

    private static void parsePublicFiles(StorageDrive d, String path, JsonArray arr, List<DriveFile> out) {
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String nm = optString(o, "name");
            boolean isDir = optBool(o, "is_dir") || optInt(o, "type") == 1;
            DriveFile f = new DriveFile(nm, isDir, joinPath(path, nm));
            f.drive = d;
            f.size = optLong(o, "size");
            String mod = optString(o, "modified");
            if (mod == null || mod.isEmpty()) mod = optString(o, "updated_at");
            f.lastModified = parseIso(mod);
            String u = optString(o, "url");
            if (u == null || u.isEmpty()) u = optString(o, "raw_url");
            if (u != null && !u.isEmpty()) f.fileUrl = u;
            out(out, f);
        }
    }

    private static void out(List<DriveFile> list, DriveFile f) {
        if (f.name != null && !f.name.isEmpty()) list.add(f);
    }

    /** 取文件真实播放地址 */
    public static String resolveUrl(StorageDrive d, DriveFile f) throws Exception {
        if (d.type == StorageDrive.TYPE_WEBDAV) {
            return d.baseUrl() + encodePath(f.path);
        }
        // AList: 列表已带 url 直接用, 否则请求 fs/get
        if (f.fileUrl != null && !f.fileUrl.isEmpty()) return f.fileUrl;
        String base = d.baseUrl();
        String token = d.token != null ? d.token : login(d);
        JsonObject body = new JsonObject();
        body.addProperty("path", normalize(f.path));
        body.addProperty("password", d.password == null ? "" : d.password);
        if (token != null) {
            JsonObject resp = postJson(base + "api/fs/get", body, token);
            String u = extractUrl(resp);
            if (u != null && !u.isEmpty()) return u;
        }
        // 公开接口兜底
        JsonObject resp = postJson(base + "api/public/path", body, null);
        String u = extractUrl(resp);
        if (u != null && !u.isEmpty()) return u;
        throw new Exception("无法获取该文件地址, 请检查网盘配置");
    }

    /** 播放请求头(WebDAV 需 Basic 认证) */
    public static Map<String, String> headers(StorageDrive d) {
        Map<String, String> h = new HashMap<>();
        if (d != null && d.type == StorageDrive.TYPE_WEBDAV) {
            String auth = d.basicAuth();
            if (auth != null) h.put("Authorization", auth);
        }
        return h;
    }

    private static String extractUrl(JsonObject resp) {
        if (resp == null || !resp.has("data")) return null;
        JsonElement de = resp.get("data");
        if (de.isJsonObject()) {
            JsonObject data = de.getAsJsonObject();
            for (String k : new String[]{"raw_url", "url"}) {
                if (data.has(k) && !data.get(k).isJsonNull()) {
                    String v = data.get(k).getAsString();
                    if (v != null && !v.isEmpty()) return v;
                }
            }
            if (data.has("files") && data.get("files").isJsonArray()) {
                JsonArray fa = data.getAsJsonArray("files");
                if (fa.size() > 0 && fa.get(0).isJsonObject()) {
                    JsonObject fo = fa.get(0).getAsJsonObject();
                    for (String k : new String[]{"raw_url", "url"}) {
                        if (fo.has(k) && !fo.get(k).isJsonNull()) {
                            String v = fo.get(k).getAsString();
                            if (v != null && !v.isEmpty()) return v;
                        }
                    }
                }
            }
        }
        return null;
    }

    // ==================== HTTP ====================

    private static JsonObject postJson(String url, JsonObject body, String token) throws Exception {
        Request.Builder b = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, body.toString()))
                .header("User-Agent", OkHttpUtil.UA)
                .header("Accept", "application/json, text/plain, */*");
        if (token != null && !token.isEmpty()) b.header("Authorization", token);
        try (Response resp = OkHttpUtil.client().newCall(b.build()).execute()) {
            if (resp.body() == null) throw new Exception("HTTP " + resp.code());
            String s = resp.body().string();
            if (!resp.isSuccessful()) throw new Exception("HTTP " + resp.code());
            return JsonParser.parseString(s).getAsJsonObject();
        }
    }

    // ==================== 工具 ====================

    /** 路径归一化: 保证以 "/" 开头, 去掉末尾 "/"(根除外) 与重复 "/" */
    public static String normalize(String path) {
        if (path == null || path.trim().isEmpty()) return "/";
        String p = path.trim().replace("\\", "/");
        p = p.replaceAll("/{2,}", "/");
        if (!p.startsWith("/")) p = "/" + p;
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    /** 父路径, 根返回 null */
    public static String parent(String path) {
        String p = normalize(path);
        if ("/".equals(p)) return null;
        int i = p.lastIndexOf('/');
        return i <= 0 ? "/" : p.substring(0, i);
    }

    private static String joinPath(String parent, String name) {
        String p = normalize(parent);
        if ("/".equals(p)) return "/" + name;
        return p + "/" + name;
    }

    /** 每个路径段单独 URL 编码(保留 "/") */
    private static String encodePath(String path) {
        String p = normalize(path);
        if ("/".equals(p)) return "/";
        StringBuilder sb = new StringBuilder();
        for (String seg : p.split("/")) {
            if (seg.isEmpty()) continue;
            sb.append('/').append(Uri.encode(seg));
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private static String lastSegment(String rel) {
        String s = rel == null ? "" : rel;
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int i = s.lastIndexOf('/');
        return i < 0 ? s : s.substring(i + 1);
    }

    private static void sortFiles(List<DriveFile> list) {
        Collections.sort(list, new Comparator<DriveFile>() {
            @Override
            public int compare(DriveFile a, DriveFile b) {
                if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                String an = a.name == null ? "" : a.name;
                String bn = b.name == null ? "" : b.name;
                return an.compareToIgnoreCase(bn);
            }
        });
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    private static long parseHttpDate(String s) {
        if (s == null || s.isEmpty()) return 0;
        String[] patterns = {
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        };
        for (String pat : patterns) {
            try {
                java.util.TimeZone gmt = pat.startsWith("EEE") ? java.util.TimeZone.getTimeZone("GMT") : null;
                SimpleDateFormat f = new SimpleDateFormat(pat, Locale.US);
                if (gmt != null) f.setTimeZone(gmt);
                return f.parse(s.trim()).getTime();
            } catch (Exception ignored) {}
        }
        return 0;
    }

    /** 解析 AList 的 ISO 时间(容忍 +08:00 / Z), 取前 19 位 */
    private static long parseIso(String s) {
        if (s == null || s.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            return f.parse(s.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String optString(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        try { return o.get(key).getAsString(); } catch (Exception e) { return ""; }
    }

    private static long optLong(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return 0;
        try { return o.get(key).getAsLong(); } catch (Exception e) { return 0; }
    }

    private static int optInt(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return -1;
        try { return o.get(key).getAsInt(); } catch (Exception e) { return -1; }
    }

    private static boolean optBool(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return false;
        try { return o.get(key).getAsBoolean(); } catch (Exception e) { return false; }
    }

    private static String friendly(Throwable t) {
        String m = t == null ? "" : t.getMessage();
        if (m == null || m.isEmpty()) m = t == null ? "未知错误" : t.getClass().getSimpleName();
        if (m.contains("Failed to connect") || m.contains("ECONNREFUSED")) m = "无法连接服务器";
        else if (m.contains("timeout") || m.contains("timed out")) m = "连接超时";
        else if (m.contains("401")) m = "认证失败(账号/密码错误)";
        else if (m.contains("404")) m = "路径不存在";
        return m;
    }
}