package com.seanming.player.bean;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 直播频道 */
public class LiveChannel implements Serializable {
    public String name;
    public String logo;
    public String epgId;
    public String group;
    /** 频道专属 UA(m3u 的 http-user-agent), 为空则用全局 UA */
    public String httpUserAgent;
    public final List<String> urls = new ArrayList<>();
    public int urlIndex;
    public int number; // 数字选台编号

    /** 回看地址(m3u 的 tvg-rec, 逗号分隔多地址), 用于 EPG 历史节目回看 */
    public final List<String> recUrls = new ArrayList<>();
    /** 回看 URL 模板中的占位替换值(部分源用 {date} 等占位拼接历史地址), 空则不替换 */
    public String recTemplate = "";

    public String currentUrl() {
        if (urls.isEmpty()) return "";
        if (urlIndex >= urls.size()) urlIndex = 0;
        return urls.get(urlIndex);
    }
}
