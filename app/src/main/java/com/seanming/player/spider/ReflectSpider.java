package com.seanming.player.spider;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;

/** jar 爬虫反射桥接 */
public class ReflectSpider extends Spider {

    private final ClassLoader loader;
    private final Object spiderObj;

    public ReflectSpider(ClassLoader loader, Object obj) {
        this.loader = loader;
        this.spiderObj = obj;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        invoke("init", new Class[]{Context.class, String.class}, context, extend);
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        Object r = invoke("homeContent", new Class[]{boolean.class}, filter);
        return r == null ? "" : r.toString();
    }

    @Override
    public String homeVideoContent() throws Exception {
        Object r = invoke("homeVideoContent", new Class[]{});
        return r == null ? "" : r.toString();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        Object r = invoke("categoryContent", new Class[]{String.class, String.class, boolean.class, HashMap.class},
                tid, pg, filter, extend);
        return r == null ? "" : r.toString();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        Object r = invoke("detailContent", new Class[]{List.class}, ids);
        return r == null ? "" : r.toString();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        Object r = invoke("searchContent", new Class[]{String.class, boolean.class}, key, quick);
        return r == null ? "" : r.toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        Object r = invoke("playerContent", new Class[]{String.class, String.class, List.class}, flag, id, vipFlags);
        return r == null ? "" : r.toString();
    }

    @Override
    public String liveContent(String url) throws Exception {
        Object r = invoke("liveContent", new Class[]{String.class}, url);
        return r == null ? "" : r.toString();
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        Object r = invoke("isVideoFormat", new Class[]{String.class}, url);
        return r != null && (boolean) r;
    }

    @Override
    public boolean manualVideoCheck() throws Exception {
        Object r = invoke("manualVideoCheck", new Class[]{});
        return r != null && (boolean) r;
    }

    @Override
    public Object[] proxyLocal(java.util.Map<String, String> params) throws Exception {
        Object r = invoke("proxyLocal", new Class[]{java.util.Map.class}, params);
        return r == null ? null : (Object[]) r;
    }

    /** 调用 spider jar 里的 com.github.catvod.spider.Proxy.proxy(Map), 用于本地代理 */
    public Object[] proxyInvoke(java.util.Map<String, String> params) {
        try {
            Class<?> proxyCls = Class.forName("com.github.catvod.spider.Proxy", true, loader);
            Method m = proxyCls.getMethod("proxy", java.util.Map.class);
            Object r = m.invoke(null, params);
            return r == null ? null : (Object[]) r;
        } catch (Throwable t) {
            android.util.Log.w("ReflectSpider", "proxyInvoke skip: " + t.getMessage());
            return null;
        }
    }

    @Override
    public void destroy() {
        try { invoke("destroy", new Class[]{}); } catch (Exception ignored) {}
    }

    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        try {
            Method m = spiderObj.getClass().getMethod(name, types);
            // 日志: 方法声明类 — 判断是真实 override 还是继承基类默认
            Class<?> decl = m.getDeclaringClass();
            android.util.Log.i("ReflectSpider", "invoke " + name + " decl=" + decl.getName()
                    + " obj=" + spiderObj.getClass().getName());
            Object r = m.invoke(spiderObj, args);
            android.util.Log.i("ReflectSpider", "invoke " + name + " => " + (r == null ? "null" : r.getClass().getSimpleName() + "(" + r.toString().length() + " chars)"));
            return r;
        } catch (NoSuchMethodException e) {
            android.util.Log.w("ReflectSpider", "method not found: " + name + " on " + spiderObj.getClass().getName());
            return null;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            android.util.Log.e("ReflectSpider", "invoke " + name + " error: " + cause.getClass().getName() + ": " + cause.getMessage(), t);
            throw t;
        }
    }
}
