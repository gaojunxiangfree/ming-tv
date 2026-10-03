package com.seanming.player.spider;

import android.content.Context;

import com.seanming.player.bean.Site;
import com.seanming.player.util.OkHttpUtil;
import com.whl.quickjs.android.QuickJSLoader;
import com.whl.quickjs.wrapper.QuickJSContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

/**
 * 爬虫加载器:
 *  - jar 型 (type=3): 下载 jar -> 释放 native .so -> 加载 dex -> 反射创建 Spider 实例
 *  - js 型 (type=4): 下载 js  -> QuickJS 执行封装 -> 创建 Spider 实例
 *
 * Android 10+ (API 29+) 禁止 DexClassLoader 加载可写 dex, 需 setReadOnly 满足限制.
 * wex 加密 jar 需用 DexClassLoader 加载完整 JAR (非 InMemoryDexClassLoader),
 * 因为 DexNative.<clinit> 需通过 ClassLoader.getResourceAsStream 读取 JAR 内的 .guard 文件.
 */
public class SpiderLoader {

    /**
     * 爬虫加载全局锁. 影视仓类接口里几十个 wex 站点共用同一个 guard jar(同一缓存目录),
     * 并发加载会把 jar/.so 写坏并让 DexNative 拿到 null ClassLoader 直接 SIGABRT,
     * 因此所有 jar 爬虫的加载必须串行(加载很快, 主要耗时在搜索本身).
     */
    private static final Object LOAD_LOCK = new Object();

    /** 加载 jar 爬虫 */
    public static Spider loadJar(Context ctx, Site site) throws Exception {
        ensureLoadNiMa(ctx);
        // jar 地址优先级: site.jar > 全局 spider > site.api
        String jarUrl = site.getJar() != null && !site.getJar().isEmpty()
                ? site.getJar()
                : com.seanming.player.api.ApiConfig.get().getGlobalSpider();
        if (jarUrl == null || jarUrl.isEmpty()) jarUrl = site.getApi();
        // 去掉 TVBox 的 ;md5;xxx 校验后缀
        jarUrl = stripMd5Suffix(jarUrl);

        // 所有 wex 站点共用同一个 guard jar(同一缓存目录). 并发加载会出现:
        // 多个线程同时下载/解包同一 jar -> jar 与 .so 被写坏 -> DexNative 拿到 null
        // ClassLoader 直接 SIGABRT. 因此这里必须串行加载.
        synchronized (LOAD_LOCK) {
            return loadJarLocked(ctx, site, jarUrl);
        }
    }

    /** 串行执行的加载主体: 保证共用 jar 的下载与解包只发生一次且完整 */
    private static Spider loadJarLocked(Context ctx, Site site, String jarUrl) throws Exception {
        // 以 jar url 的 md5 作为缓存目录名, 避免不同接口的 jar 互相覆盖
        String cacheKey = md5Hex(jarUrl);
        File jarDir = new File(ctx.getDir("jars", Context.MODE_PRIVATE), cacheKey);
        if (!jarDir.exists()) jarDir.mkdirs();
        File dexFile = new File(jarDir, "spider.jar");
        // native 库释放目录: lib/<abi>/*.so
        File libDir = new File(jarDir, "lib");
        // 释放完成标记: 只有完整释放过才复用 lib 目录, 避免上次中断留下半截 .so
        File okStamp = new File(libDir, ".extracted");

        if (!isJarValid(dexFile)) {
            // 先写临时文件再改名, 保证缓存里的 jar 永远是完整的
            byte[] data = OkHttpUtil.getBytes(jarUrl);
            File tmp = new File(jarDir, "spider.jar.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
            }
            if (tmp.length() <= 0) throw new IOException("empty jar: " + jarUrl);
            if (dexFile.exists() && !dexFile.delete()) {
                android.util.Log.w("SpiderLoader", "delete stale jar failed: " + dexFile);
            }
            if (!tmp.renameTo(dexFile)) throw new IOException("rename jar failed");
            android.util.Log.i("SpiderLoader", "jar downloaded: " + jarUrl + " bytes=" + dexFile.length());
        }

        if (!okStamp.exists()) {
            deleteRecursively(libDir);
            libDir.mkdirs();
            extractNativeLibs(dexFile, libDir);
            try (FileOutputStream fos = new FileOutputStream(okStamp)) {
                fos.write('1');
            }
        }
        // Android 10+ 禁止 DexClassLoader 加载可写 dex, 必须设为只读
        setReadOnly(dexFile);

        // 按当前 abi 选择 native 库子目录
        String abi = pickAbi();
        File abiDir = new File(libDir, abi);
        String libPath = abiDir.exists() ? abiDir.getAbsolutePath() : libDir.getAbsolutePath();

        // 调试: 列出 abiDir 中的文件
        if (abiDir.exists()) {
            File[] files = abiDir.listFiles();
            if (files != null) {
                StringBuilder sb = new StringBuilder("abiDir files: ");
                for (File f : files) sb.append(f.getName()).append("(").append(f.length()).append(") ");
                android.util.Log.i("SpiderLoader", sb.toString());
            }
        } else {
            android.util.Log.w("SpiderLoader", "abiDir not exist: " + abiDir);
        }

        // 加载 dex: 用 DexClassLoader 加载完整 JAR (支持访问 JAR 内 .guard 资源)
        // libPath 作为 ClassLoader 的 native 库搜索路径, 供 DexNative.<clinit> 中的
        // System.loadLibrary("wexguard") 查找 libwexguard.so
        ClassLoader loader = createDexLoader(ctx, dexFile, jarDir, libPath);

        // 触发 DexNative.<clinit>: 它会调用 System.loadLibrary("wexguard") 加载 .so,
        // JNI_OnLoad 在此 ClassLoader 上下文中运行, FindClass 能找到 DexNative 类,
        // 并注册 getLoader/getSpider/proxyInvoke 等 native 方法.
        // 注意: 不能在创建 ClassLoader 前调 System.load(), 否则 FindClass 找不到 DexNative.
        // 关键: DexNative.<clinit> 会调用 Init.getContext().getCacheDir(),
        // 必须先初始化 Init 类注入 app Context, 否则 NPE.
        try {
            // 1. 初始化 Init 类: 反射调用 Init.init(Context) 或类似方法注入 app Context
            try {
                Class<?> initCls = Class.forName("com.github.catvod.spider.Init", true, loader);
                java.lang.reflect.Method[] ms = initCls.getDeclaredMethods();
                for (java.lang.reflect.Method m : ms) {
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    Class<?>[] params = m.getParameterTypes();
                    // 找到接收单个 Context 参数的静态方法 (如 init/setApp/initApp)
                    if (params.length == 1 && params[0] == Context.class) {
                        m.invoke(null, ctx.getApplicationContext());
                        android.util.Log.i("SpiderLoader", "Init." + m.getName() + "(ctx) called");
                        break;
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("SpiderLoader", "Init init skip: " + t.getMessage());
            }

            // 2. 触发 DexNative.<clinit>
            Class.forName("com.github.catvod.spider.DexNative", true, loader);
            android.util.Log.i("SpiderLoader", "DexNative initialized");
        } catch (Throwable t) {
            // 遍历到根因, 打印完整异常链
            Throwable cause = t;
            while (cause.getCause() != null) cause = cause.getCause();
            android.util.Log.e("SpiderLoader", "DexNative init fail: " + t.getClass().getSimpleName()
                    + " root=" + cause.getClass().getSimpleName() + ": " + cause.getMessage(), t);
        }

        // TVBox 规范: api=csp_Xxx -> com.github.catvod.spider.Xxx
        Class<?> cls = loadSpiderClass(loader, site);
        Object obj = cls.getDeclaredConstructor().newInstance();

        // wex 加密: stub 类(BaseSpiderGuard 子类)的构造器已通过 Init.getSpider() 获取
        // 真实 Spider 实例并存入私有字段 oOoOoOoOoOoOoO0o. 所有方法委托给它.
        // 直接取出真实 spider, 绕过 stub 委托层, 方便调试和直接调用.
        try {
            java.lang.reflect.Field f = cls.getSuperclass().getDeclaredField("oOoOoOoOoOoOoO0o");
            f.setAccessible(true);
            Object realSpider = f.get(obj);
            if (realSpider != null) {
                android.util.Log.i("SpiderLoader", "Real spider: " + realSpider.getClass().getName()
                        + " loader=" + realSpider.getClass().getClassLoader()
                        + " super=" + realSpider.getClass().getSuperclass().getName());
                obj = realSpider;
            } else {
                android.util.Log.w("SpiderLoader", "oOoO field is null! Using stub.");
            }
        } catch (Throwable t) {
            android.util.Log.w("SpiderLoader", "Access oOoO fail: " + t.getMessage());
        }

        ReflectSpider spider = new ReflectSpider(loader, obj);
        spider.init(ctx, site.getExt());
        return spider;
    }

    /**
     * 创建 dex ClassLoader.
     * 优先用 DexClassLoader 加载完整 JAR 文件 (非 InMemoryDexClassLoader),
     * 因为 wex 加密的 DexNative.<clinit> 需要通过 ClassLoader.getResourceAsStream
     * 读取 JAR 中的 assets/wexshinidie.guard 文件. InMemoryDexClassLoader 只加载 dex
     * 字节, 无法访问 JAR 中的其他资源.
     * dex 文件已设为只读, 满足 Android 10+ 的限制.
     */
    private static ClassLoader createDexLoader(Context ctx, File dexFile, File jarDir, String libPath) throws Exception {
        // 验证 dex 文件只读 (Android 10+ 要求)
        setReadOnly(dexFile);

        // DexClassLoader: 加载完整 JAR, 支持 getResourceAsStream 访问 JAR 内资源
        return new DexClassLoader(
                dexFile.getAbsolutePath(),
                jarDir.getAbsolutePath(),
                libPath,
                ctx.getClassLoader()
        );
    }

    /**
     * wex 加密源在运行期需要 native 字符串解密器 libLoadNiMa.so.
     * 该 .so 不在 spider jar 内, 由宿主提供; com.wexfnw.libso.LoadNiMa.<clinit>
     * 只会读取 files/TV/libLoadNiMa.so, 复制为隐藏临时名后再 System.load.
     * 因此这里先把随包 assets 的对应 abi 版本释放到该固定路径.
     * (算法与库内纯 Java 的 SaZ.d 完全一致: hex 解码后按 "ywAqAs" 循环异或)
     */
    private static void ensureLoadNiMa(Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir(), "TV");
            if (!dir.exists()) dir.mkdirs();
            File target = new File(dir, "libLoadNiMa.so");
            if (target.exists() && target.length() > 0) return;

            boolean is64 = "arm64-v8a".equals(pickAbi());
            String asset = is64 ? "libLoadNiMa_v8.so" : "libLoadNiMa_v7.so";
            InputStream is;
            try {
                is = ctx.getAssets().open(asset);
            } catch (Throwable t) {
                is = ctx.getAssets().open(is64 ? "libLoadNiMa_v7.so" : "libLoadNiMa_v8.so");
            }
            try (InputStream in = is; FileOutputStream fos = new FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            target.setReadable(true, false);
            android.util.Log.i("SpiderLoader", "libLoadNiMa.so extracted: " + target + " len=" + target.length());
        } catch (Throwable t) {
            android.util.Log.e("SpiderLoader", "extract libLoadNiMa.so fail: " + t, t);
        }
    }

    /** 释放 jar 内的 assets/*.so、assets/*.guard 与 lib/<abi>/*.so 到目标目录 */
    private static void extractNativeLibs(File jarFile, File outDir) {
        try (ZipFile zip = new ZipFile(jarFile)) {
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                // 兼容 assets/wexguard_v7.so、lib/armeabi-v7a/libX.so 等路径
                String targetName = null;
                String targetAbiDir = null;
                if (name.startsWith("assets/") && name.endsWith(".so")) {
                    // assets/wexguard_v7.so -> lib/armeabi-v7a/libwexguard.so
                    String base = new File(name).getName();
                    if (base.contains("_v7")) targetAbiDir = "armeabi-v7a";
                    else if (base.contains("_v8") || base.contains("_v64")) targetAbiDir = "arm64-v8a";
                    else targetAbiDir = "armeabi-v7a"; // 兜底
                    // 关键: 重命名为 libwexguard.so, 让 System.loadLibrary("wexguard") 能找到
                    targetName = base.startsWith("wexguard") ? "libwexguard.so" : base;
                } else if (name.startsWith("assets/") && name.endsWith(".guard")) {
                    // assets/wexshinidie.guard -> lib/wexshinidie.guard (放根目录, 供 native 读取)
                    targetName = new File(name).getName();
                    targetAbiDir = ""; // 根目录
                } else if (name.startsWith("lib/")) {
                    // lib/<abi>/<file>.so -> 保持原结构
                    String[] parts = name.split("/");
                    if (parts.length >= 3) {
                        targetAbiDir = parts[1];
                        targetName = parts[parts.length - 1];
                    }
                } else if (name.endsWith(".so") && !name.contains("/")) {
                    // 根目录 .so -> 兜底放 armeabi-v7a
                    targetAbiDir = "armeabi-v7a";
                    targetName = name;
                }
                if (targetName == null) continue;

                File abiOut = targetAbiDir.isEmpty() ? outDir : new File(outDir, targetAbiDir);
                if (!abiOut.exists()) abiOut.mkdirs();
                File outFile = new File(abiOut, targetName);
                if (outFile.exists()) continue;
                try (InputStream is = zip.getInputStream(e);
                     FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 当前进程使用的 abi (与 build.gradle abiFilters 对齐) */
    private static String pickAbi() {
        String[] abis = android.os.Build.SUPPORTED_ABIS;
        if (abis == null || abis.length == 0) return "armeabi-v7a";
        for (String a : abis) {
            if ("arm64-v8a".equals(a)) return "arm64-v8a";
            if ("armeabi-v7a".equals(a)) return "armeabi-v7a";
        }
        return abis[0];
    }

    /** 校验缓存 jar 是否是完整可读的 zip(下载中断会留下截断文件, 必须识别出来重下) */
    private static boolean isJarValid(File jar) {
        if (jar == null || !jar.exists() || jar.length() == 0) return false;
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar)) {
            return z.size() > 0;
        } catch (Throwable t) {
            android.util.Log.w("SpiderLoader", "cached jar corrupt, will re-download: " + t.getMessage());
            return false;
        }
    }

    /** 递归删除目录(用于清理可能残留的半截 native 库) */
    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        // 只读文件需先恢复可写才能删除
        f.setWritable(true);
        if (!f.delete()) {
            android.util.Log.w("SpiderLoader", "delete fail: " + f);
        }
    }

    /**
     * Android 10+ 禁止 DexClassLoader 加载可写 dex 文件.
     * 将 dex 及其所在目录树设为只读, 满足 SELinux/ART 限制.
     */
    private static void setReadOnly(File f) {
        try {
            f.setReadable(true, false);
            f.setWritable(false, false);
            f.setExecutable(false, false);
        } catch (Throwable ignored) {}
    }

    /** 截取 ;md5; 之前的真实下载地址 */
    private static String stripMd5Suffix(String url) {
        if (url == null) return null;
        int idx = url.indexOf(";md5;");
        return idx > 0 ? url.substring(0, idx) : url;
    }

    /** 按 TVBox 规范推导 Spider 类名并加载 */
    private static Class<?> loadSpiderClass(ClassLoader loader, Site site) throws Exception {
        String api = site.getApi(); // e.g. csp_DoubanGuard
        String shortName = api != null && api.startsWith("csp_") ? api.substring(4) : api;
        String[] candidates = new String[] {
                "com.github.catvod.spider." + shortName,
                "com.github.catvod.spider." + (shortName == null ? "" : shortName.replace("Guard", "")),
                extractClassFromExt(site.getExt()),
        };
        Throwable last = null;
        for (String name : candidates) {
            if (name == null) continue;
            try {
                return loader.loadClass(name);
            } catch (Throwable e) {
                last = e;
                android.util.Log.w("SpiderLoader", "loadClass fail: " + name + " : " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
        if (last instanceof Exception) throw (Exception) last;
        throw new Exception("no spider class for " + api, last);
    }

    private static String md5Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    /** 加载 drpy js 爬虫 */
    public static Spider loadJs(Context ctx, Site site) throws Exception {
        String jsUrl = site.getApi();
        String jsCode = OkHttpUtil.get(jsUrl);
        QuickJSContext qjs = QuickJSContext.create();
        QuickJSLoader.initConsoleLog(qjs);

        // 注入辅助库: crypto, url, UA 等
        qjs.getGlobalObject().setProperty("local", qjs.createNewJSObject());
        // drpy 核心: 运行 js 后构造 Spider 对象
        qjs.evaluateModule(jsCode, jsUrl);

        return new DrpySpider(qjs, site.getExt());
    }

    private static String extractClassFromExt(String ext) {
        if (ext == null) return null;
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(ext).getAsJsonObject();
            if (o.has("spider")) return o.get("spider").getAsString();
        } catch (Exception ignored) {}
        return null;
    }
}
