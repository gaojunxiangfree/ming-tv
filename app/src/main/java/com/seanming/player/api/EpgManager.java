package com.seanming.player.api;

import android.util.Xml;

import com.seanming.player.bean.EpgProgram;
import com.seanming.player.util.OkHttpUtil;
import com.seanming.player.util.ThreadUtils;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/**
 * EPG(XMLTV) 加载与查询.
 * 支持 .xml / .xml.gz, 解析 &lt;programme channel="" start="" stop=""&gt;&lt;title/&gt;,
 * 按 channelId 建立节目表, 提供当前/下一个节目查询.
 */
public class EpgManager {

    private static final EpgManager INSTANCE = new EpgManager();
    public static EpgManager get() { return INSTANCE; }

    /** channelId -> 按时间升序的节目列表 */
    private final Map<String, List<EpgProgram>> table = new LinkedHashMap<>();
    /**
     * 归一化索引: "CCTV1综合" -> "CCTV1", 用于兼容直播源 tvg-id(CCTV1)
     * 与 EPG channel id(CCTV1综合) 的命名差异.
     */
    private final Map<String, String> normIndex = new LinkedHashMap<>();
    private volatile boolean loading;
    private volatile boolean loaded;

    /** 兜底 EPG(DIYP 标准节目单), 直播源未声明 x-tvg-url 或声明地址不可用时使用 */
    private static final String[] DEFAULT_EPG_URLS = {
            "https://epg.112114.xyz/pp.xml",
            "http://epg.112114.xyz/pp.xml",
    };

    public boolean isLoaded() { return loaded; }

    /** 异步加载多个 EPG 地址(取首个解析成功且非空的结果) */
    public void load(List<String> urls) {
        if (loaded || loading) return;
        loading = true;
        ThreadUtils.io(() -> {
            List<String> tried = new ArrayList<>();
            if (urls != null) tried.addAll(urls);
            for (String d : DEFAULT_EPG_URLS) {
                if (!tried.contains(d)) tried.add(d);
            }
            for (String url : tried) {
                try {
                    byte[] data = OkHttpUtil.getBytes(url);
                    if (data == null || data.length == 0) continue;
                    Map<String, List<EpgProgram>> parsed = parse(unwrapGzip(data));
                    if (!parsed.isEmpty()) {
                        table.clear();
                        normIndex.clear();
                        table.putAll(parsed);
                        buildNormIndex();
                        loaded = true;
                        android.util.Log.i("EpgManager", "EPG loaded from " + url
                                + " channels=" + parsed.size());
                        return;
                    }
                } catch (Throwable t) {
                    android.util.Log.w("EpgManager", "EPG load failed " + url + " : " + t);
                }
            }
            android.util.Log.w("EpgManager", "EPG 全部加载失败");
        });
    }

    /** gz 魔数检测并解压 */
    private static byte[] unwrapGzip(byte[] data) throws Exception {
        if (data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = gz.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        }
        return data;
    }

    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);

    private Map<String, List<EpgProgram>> parse(byte[] xml) throws Exception {
        Map<String, List<EpgProgram>> map = new LinkedHashMap<>();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new ByteArrayInputStream(xml), "UTF-8");
        int event = p.getEventType();
        String curChannel = null;
        long curStart = 0, curStop = 0;
        String title = null, desc = null;
        boolean inProgramme = false;
        while (event != XmlPullParser.END_DOCUMENT) {
            String name = p.getName();
            switch (event) {
                case XmlPullParser.START_TAG:
                    if ("programme".equalsIgnoreCase(name)) {
                        inProgramme = true;
                        curChannel = p.getAttributeValue(null, "channel");
                        curStart = parseTime(p.getAttributeValue(null, "start"));
                        curStop = parseTime(p.getAttributeValue(null, "stop"));
                        title = null;
                        desc = null;
                    } else if (inProgramme && "title".equalsIgnoreCase(name)) {
                        title = p.nextText();
                    } else if (inProgramme && "desc".equalsIgnoreCase(name)) {
                        desc = p.nextText();
                    }
                    break;
                case XmlPullParser.END_TAG:
                    if ("programme".equalsIgnoreCase(name) && inProgramme) {
                        inProgramme = false;
                        if (curChannel != null && title != null && curStop > curStart) {
                            List<EpgProgram> list = map.get(curChannel);
                            if (list == null) {
                                list = new ArrayList<>();
                                map.put(curChannel, list);
                            }
                            list.add(new EpgProgram(curChannel, curStart, curStop,
                                    title == null ? "" : title.trim(),
                                    desc == null ? "" : desc.trim()));
                        }
                    }
                    break;
            }
            event = p.next();
        }
        // 按开始时间排序, 便于时间轴查找
        for (List<EpgProgram> list : map.values()) {
            Collections.sort(list, Comparator.comparingLong(a -> a.start));
        }
        return map;
    }

    /** XMLTV 时间形如 "20261002120000 +0800"; 取前 14 位按本地时区解析 */
    private long parseTime(String s) {
        if (s == null || s.length() < 14) return 0;
        try {
            fmt.setTimeZone(TimeZone.getDefault());
            return fmt.parse(s.substring(0, 14)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 与频道 epgId / 名称匹配的节目单 */
    public List<EpgProgram> programs(String epgId, String channelName) {
        List<EpgProgram> list = null;
        if (epgId != null && !epgId.isEmpty()) list = table.get(epgId);
        if (list == null && channelName != null) list = table.get(channelName);
        // 命名差异兜底: "CCTV1" 匹配 EPG 的 "CCTV1综合"
        if (list == null && epgId != null) list = byNorm(epgId);
        if (list == null && channelName != null) list = byNorm(channelName);
        android.util.Log.d("EpgManager", "programs epgId=" + epgId + " name=" + channelName
                + " -> " + (list == null ? 0 : list.size()));
        return list;
    }

    private List<EpgProgram> byNorm(String key) {
        String nk = normalize(key);
        if (nk.length() < 2) return null;
        String raw = normIndex.get(nk);
        if (raw != null) return table.get(raw);
        // 前缀匹配: 直播源 id 为 EPG id 的前缀(或反之)
        for (Map.Entry<String, String> e : normIndex.entrySet()) {
            String k = e.getKey();
            if (k.startsWith(nk) || nk.startsWith(k)) return table.get(e.getValue());
        }
        return null;
    }

    private void buildNormIndex() {
        for (String cid : table.keySet()) {
            String nk = normalize(cid);
            if (nk.length() >= 2 && !normIndex.containsKey(nk)) normIndex.put(nk, cid);
        }
    }

    /** 归一化: 仅保留字母与数字并大写, "CCTV1综合" -> "CCTV1" */
    private static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toUpperCase(s.charAt(i));
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }

    /** 当前正在播出的节目 */
    public EpgProgram current(String epgId, String channelName) {
        List<EpgProgram> list = programs(epgId, channelName);
        if (list == null) return null;
        long now = System.currentTimeMillis();
        for (EpgProgram pg : list) {
            if (pg.isPlaying(now)) return pg;
        }
        return null;
    }

    /** 下一个节目 */
    public EpgProgram next(String epgId, String channelName) {
        List<EpgProgram> list = programs(epgId, channelName);
        if (list == null) return null;
        long now = System.currentTimeMillis();
        for (EpgProgram pg : list) {
            if (pg.start > now) return pg;
        }
        return null;
    }

    /** 今日剩余节目单(用于节目单弹窗) */
    public List<EpgProgram> today(String epgId, String channelName) {
        List<EpgProgram> list = programs(epgId, channelName);
        if (list == null) return Collections.emptyList();
        long dayEnd = System.currentTimeMillis() + 24 * 3600_000L;
        List<EpgProgram> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (EpgProgram pg : list) {
            if (pg.stop > now && pg.start < dayEnd) out.add(pg);
        }
        return out;
    }
}