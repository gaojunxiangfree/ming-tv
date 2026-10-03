package com.seanming.player.spider;

import android.content.Context;

import java.util.HashMap;
import java.util.List;

/**
 * 标准爬虫接口, 与 TVBox/CatVod spider 规范一致.
 * jar 爬虫与 js(drpy) 爬虫统一由实现类适配.
 */
public abstract class Spider {

    public void init(Context context, String extend) throws Exception {}

    /** 首页分类 */
    public abstract String homeContent(boolean filter) throws Exception;

    /** 首页推荐视频 */
    public abstract String homeVideoContent() throws Exception;

    /** 分类列表 */
    public abstract String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception;

    /** 详情 */
    public abstract String detailContent(List<String> ids) throws Exception;

    /** 搜索 */
    public abstract String searchContent(String key, boolean quick) throws Exception;

    /** 播放地址解析 */
    public abstract String playerContent(String flag, String id, List<String> vipFlags) throws Exception;

    /** 直播源(spider 型 live) */
    public String liveContent(String url) throws Exception { return ""; }

    public boolean isVideoFormat(String url) throws Exception { return false; }

    public boolean manualVideoCheck() throws Exception { return false; }

    /** 本地代理: 部分源需要本地 HTTP 代理改写请求头/去广告, 返回 {url, headers, body} */
    public Object[] proxyLocal(java.util.Map<String, String> params) throws Exception { return null; }

    public void destroy() {}
}
