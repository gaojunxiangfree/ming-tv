package com.seanming.player.bean;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 视频条目, 兼容苹果cms / TVBox vod 字段.
 */
public class Vod implements Serializable {

    public String vod_id;
    public String vod_name;
    public String vod_pic;
    public String vod_remarks;
    public String vod_score;
    public String vod_year;
    public String vod_area;
    public String vod_director;
    public String vod_actor;
    public String vod_content;
    public String vod_play_from;
    public String vod_play_url;
    public String type_id;
    public String type_name;
    public String vod_tag;
    public String style;

    /** 站点标识, 运行时填充 */
    public transient String siteKey;

    /** 解析后的播放线路: 线路名 -> 该线路的剧集列表 */
    public transient LinkedHashMap<String, List<Episode>> playFlags = new LinkedHashMap<>();

    /** 详情页是否已解析 playFlags */
    public transient boolean playFromParsed;

    /** 解析 vod_play_from / vod_play_url (格式: flag1$$$flag2, url1#url2) */
    public void parsePlayFrom() {
        if (playFlags == null) playFlags = new LinkedHashMap<>();
        playFlags.clear();
        playFromParsed = true;
        if (vod_play_from == null || vod_play_url == null) return;
        String[] froms = vod_play_from.split("\\$\\$\\$");
        String[] urls = vod_play_url.split("\\$\\$\\$");
        int n = Math.min(froms.length, urls.length);
        for (int i = 0; i < n; i++) {
            List<Episode> episodes = new ArrayList<>();
            for (String item : urls[i].split("#")) {
                int idx = item.indexOf('$');
                if (idx > 0) {
                    episodes.add(new Episode(item.substring(0, idx), item.substring(idx + 1)));
                } else if (!item.isEmpty()) {
                    episodes.add(new Episode(item, item));
                }
            }
            playFlags.put(froms[i], episodes);
        }
    }

    /** 剧集: 名称 + 播放地址(或爬虫id) */
    public static class Episode implements Serializable {
        public String name;
        public String url;

        public Episode(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }
}
