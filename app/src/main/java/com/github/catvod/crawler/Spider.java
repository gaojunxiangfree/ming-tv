package com.github.catvod.crawler;

import android.content.Context;

import java.util.HashMap;
import java.util.List;

/**
 * TVBox/CatVod 爬虫基类.
 * jar 中的爬虫类 (如 DoubanGuard extends BaseSpiderGuard extends Spider) 需要此基类存在.
 * 包名 com.github.catvod.crawler 必须与 jar 中引用的完全一致.
 *
 * 关键: 方法不能为 abstract. wex 加密的 stub 类 (BaseSpiderGuard 等) 对部分方法
 * 未提供实现, 若基类为 abstract 会抛 AbstractMethodError. 改为非 abstract 并返回
 * 空 JSON "{}", 让 SpiderManager.parse() 能正常处理为空结果, 不崩溃.
 * 真实实现由 .guard 解密后的 native 代码或 stub 子类 override 提供.
 */
public abstract class Spider {

    public void init(Context context, String extend) throws Exception {}

    /** 首页分类. 默认返回空 JSON, 避免 stub 未实现时抛 AbstractMethodError. */
    public String homeContent(boolean filter) throws Exception { return "{}"; }

    /** 首页推荐视频. */
    public String homeVideoContent() throws Exception { return "{}"; }

    /** 分类列表. */
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception { return "{}"; }

    /** 详情. */
    public String detailContent(List<String> ids) throws Exception { return "{}"; }

    /** 搜索. */
    public String searchContent(String key, boolean quick) throws Exception { return "{}"; }

    /** 播放地址解析. */
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception { return "{}"; }

    /** 直播源(spider 型 live). */
    public String liveContent(String url) throws Exception { return ""; }

    public boolean isVideoFormat(String url) throws Exception { return false; }

    public boolean manualVideoCheck() throws Exception { return false; }

    public void destroy() {}
}
