package com.seanming.player.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.seanming.player.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 在线升级: 从码云(Gitee)发行版检查新版本, 下载 APK 并调起系统安装.
 *
 * 选择码云的原因: 国内可直连, 无需代理; release.sh --gitee 已会把 APK 同步到码云发行版,
 * 应用直接读取 /releases/latest 即可拿到 tag_name 与 APK 资产下载地址.
 *
 * 版本号规则与 release.sh / build.gradle 一致: versionCode = 主*10000 + 次*100 + 修订.
 */
public class UpdateManager {

    private static final String TAG = "UpdateManager";

    /** 码云仓库(与 release.sh 的 gitee remote 保持一致) */
    private static final String GITEE_OWNER = "lovecodefree";
    private static final String GITEE_REPO = "ming-tv";
    private static final String API_LATEST =
            "https://gitee.com/api/v5/repos/" + GITEE_OWNER + "/" + GITEE_REPO + "/releases/latest";
    private static final String TAG_PAGE =
            "https://gitee.com/" + GITEE_OWNER + "/" + GITEE_REPO + "/releases/tag/";

    /** 下载 APK 用独立客户端: 包体较大, 放宽超时; 其余复用全局 SafeDns */
    private static final OkHttpClient DL_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .dns(new SafeDns())
            .build();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 一次检查的结果 */
    public static class ReleaseInfo {
        /** 版本号(去掉 tag 前缀 v), 如 1.0.7 */
        public String versionName = "";
        /** 由版本号换算出的 versionCode */
        public int versionCode;
        /** 发行版说明(更新日志) */
        public String body = "";
        /** APK 下载直链 */
        public String apkUrl = "";
        /** APK 文件名 */
        public String apkName = "";
        /** 发行版页面地址(下载失败时可让用户手动打开) */
        public String pageUrl = "";
        /** 是否比当前安装版本更新 */
        public boolean hasUpdate;
    }

    public interface CheckCallback {
        /** 无更新时 info.hasUpdate == false */
        void onResult(ReleaseInfo info);

        void onError(Throwable t);
    }

    public interface DownloadCallback {
        void onProgress(int percent);

        void onSuccess(File apk);

        void onError(Throwable t);
    }

    public static int currentVersionCode() { return BuildConfig.VERSION_CODE; }

    public static String currentVersionName() { return BuildConfig.VERSION_NAME; }

    /** 检查更新(异步, 结果在主线程回调) */
    public static void check(final CheckCallback cb) {
        ThreadUtils.io(() -> {
            try {
                String json = OkHttpUtil.get(API_LATEST);
                final ReleaseInfo info = parse(json);
                MAIN.post(() -> cb.onResult(info));
            } catch (Throwable t) {
                Log.w(TAG, "检查更新失败: " + t);
                MAIN.post(() -> cb.onError(t));
            }
        });
    }

    /** 解析码云 /releases/latest 返回的 JSON */
    private static ReleaseInfo parse(String json) {
        ReleaseInfo info = new ReleaseInfo();
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String tag = optString(o, "tag_name");
        info.versionName = stripV(tag);
        info.versionCode = versionCodeOf(info.versionName);
        info.body = optString(o, "body");
        info.pageUrl = TAG_PAGE + tag;

        if (o.has("assets") && o.get("assets").isJsonArray()) {
            JsonArray assets = o.getAsJsonArray("assets");
            for (JsonElement e : assets) {
                JsonObject a = e.getAsJsonObject();
                String name = optString(a, "name");
                if (name.toLowerCase().endsWith(".apk")) {
                    info.apkName = name;
                    info.apkUrl = optString(a, "browser_download_url");
                    break;
                }
            }
        }
        // 附件接口偶发返回不全时, 按 release.sh 的命名规则回退拼一个直链
        if ((info.apkUrl == null || info.apkUrl.isEmpty()) && !tag.isEmpty()) {
            String guess = "ming-tv-" + tag + ".apk";
            info.apkName = guess;
            info.apkUrl = "https://gitee.com/" + GITEE_OWNER + "/" + GITEE_REPO
                    + "/releases/download/" + tag + "/" + guess;
        }
        info.hasUpdate = info.versionCode > BuildConfig.VERSION_CODE
                && info.apkUrl != null && !info.apkUrl.isEmpty();
        return info;
    }

    private static String optString(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        try {
            return o.get(key).getAsString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String stripV(String tag) {
        if (tag == null) return "";
        return tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
    }

    /** "1.0.7" -> 10007, 与 build.gradle 的 versionCode 规则一致 */
    static int versionCodeOf(String version) {
        if (version == null) return 0;
        String[] p = version.trim().split("\\.");
        if (p.length < 3) return 0;
        try {
            return Integer.parseInt(p[0].trim()) * 10000
                    + Integer.parseInt(p[1].trim()) * 100
                    + Integer.parseInt(p[2].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 下载 APK(异步, 进度与结果在主线程回调) */
    public static void download(final Context ctx, final ReleaseInfo info, final DownloadCallback cb) {
        ThreadUtils.io(() -> {
            File dir = ctx.getExternalFilesDir("apk");
            if (dir == null) dir = ctx.getFilesDir();
            if (dir != null && !dir.exists()) dir.mkdirs();
            String name = info.apkName != null && !info.apkName.isEmpty() ? info.apkName : "ming-tv.apk";
            final File out = new File(dir, name);
            final File tmp = new File(dir, name + ".part");
            try {
                Request req = new Request.Builder()
                        .url(info.apkUrl)
                        .header("User-Agent", OkHttpUtil.UA)
                        .build();
                try (Response resp = DL_CLIENT.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        throw new IOException("http " + resp.code() + " for " + info.apkUrl);
                    }
                    ResponseBody body = resp.body();
                    long total = body.contentLength();
                    try (InputStream in = body.byteStream();
                         FileOutputStream fos = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[64 * 1024];
                        long done = 0;
                        int lastPct = -1;
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                            done += n;
                            if (total > 0) {
                                int pct = (int) (done * 100 / total);
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    final int p = pct;
                                    MAIN.post(() -> cb.onProgress(p));
                                }
                            }
                        }
                        fos.flush();
                    }
                }
                if (out.exists() && !out.delete()) {
                    throw new IOException("无法覆盖旧安装包");
                }
                if (!tmp.renameTo(out)) {
                    throw new IOException("安装包重命名失败");
                }
                MAIN.post(() -> cb.onSuccess(out));
            } catch (Throwable t) {
                Log.w(TAG, "下载失败: " + t);
                if (tmp.exists()) tmp.delete();
                MAIN.post(() -> cb.onError(t));
            }
        });
    }

    /** 是否已允许"安装未知来源应用"(Android 8.0 起需单独授权) */
    public static boolean canInstall(Activity act) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return act.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    /** 跳转到系统"安装未知来源应用"授权页 */
    public static void requestInstallPermission(Activity act, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName()));
                act.startActivityForResult(i, requestCode);
            } catch (Throwable t) {
                Log.w(TAG, "打开未知来源授权页失败: " + t);
            }
        }
    }

    /** 调起系统安装器 */
    public static void installApk(Activity act, File apk) {
        Uri uri = FileProvider.getUriForFile(act,
                act.getPackageName() + ".fileprovider", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        act.startActivity(i);
    }
}
