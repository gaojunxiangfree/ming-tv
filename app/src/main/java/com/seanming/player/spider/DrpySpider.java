package com.seanming.player.spider;

import android.content.Context;

import com.whl.quickjs.wrapper.QuickJSContext;

/** drpy js 爬虫桥接 */
public class DrpySpider extends Spider {

    private final QuickJSContext qjs;
    private final String ext;

    public DrpySpider(QuickJSContext qjs, String ext) {
        this.qjs = qjs;
        this.ext = ext;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        // drpy 通常在 JS 内部已初始化, 如需可调用 qjs.evaluate("init(...)");
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        Object r = qjs.evaluate("homeContent(" + filter + ")");
        return r == null ? "" : r.toString();
    }

    @Override
    public String homeVideoContent() throws Exception {
        Object r = qjs.evaluate("homeVideoContent()");
        return r == null ? "" : r.toString();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, java.util.HashMap<String, String> extend) throws Exception {
        String extJson = extend == null ? "{}" : new com.google.gson.Gson().toJson(extend);
        Object r = qjs.evaluate("categoryContent('" + tid + "','" + pg + "'," + filter + "," + extJson + ")");
        return r == null ? "" : r.toString();
    }

    @Override
    public String detailContent(java.util.List<String> ids) throws Exception {
        String idsJson = new com.google.gson.Gson().toJson(ids);
        Object r = qjs.evaluate("detailContent(" + idsJson + ")");
        return r == null ? "" : r.toString();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        Object r = qjs.evaluate("searchContent('" + key.replace("'", "\\'") + "'," + quick + ")");
        return r == null ? "" : r.toString();
    }

    @Override
    public String playerContent(String flag, String id, java.util.List<String> vipFlags) throws Exception {
        String flagsJson = vipFlags == null ? "[]" : new com.google.gson.Gson().toJson(vipFlags);
        Object r = qjs.evaluate("playerContent('" + flag + "','" + id + "'," + flagsJson + ")");
        return r == null ? "" : r.toString();
    }

    @Override
    public String liveContent(String url) throws Exception {
        Object r = qjs.evaluate("liveContent('" + url + "')");
        return r == null ? "" : r.toString();
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        Object r = qjs.evaluate("isVideoFormat('" + url + "')");
        return r != null && Boolean.parseBoolean(r.toString());
    }

    @Override
    public boolean manualVideoCheck() throws Exception {
        Object r = qjs.evaluate("manualVideoCheck()");
        return r != null && Boolean.parseBoolean(r.toString());
    }

    @Override
    public void destroy() {
        try { qjs.destroy(); } catch (Exception ignored) {}
    }
}
