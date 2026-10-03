package com.seanming.player.ui.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.seanming.player.R;
import com.seanming.player.util.ThreadUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 版本升级: 从仓库的 version.json 读取最新版本, 与已安装版本比对后提示升级,
 * 下载 APK 并交给系统安装器.
 *
 * 发版流程:
 *  1. 改 app/build.gradle 里的 appVersionCode / appVersionName
 *  2. ./gradlew :app:assembleRelease  (产物自动归档到 release/ming-tv-v<版本>.apk)
 *  3. 更新仓库根目录 version.json 的 versionCode / versionName / apkUrl / notes
 *  4. git 提交并推送(可打 tag v<版本>)
 */
public class Updater {

    private static final String TAG = "Updater";

    /** 版本清单地址: raw.githubusercontent 国内常被墙, 依次回退 GitHub 反代与 jsDelivr */
    private static final String RAW_JSON =
            "https://raw.githubusercontent.com/gaojunxiangfree/ming-tv/main/version.json";
    private static final String[] MANIFEST_URLS = {
            RAW_JSON,
            "https://gh-proxy.com/" + RAW_JSON,
            "https://cdn.jsdelivr.net/gh/gaojunxiangfree/ming-tv@main/version.json",
    };

    /** APK 下载地址的回退前缀(与清单地址同理) */
    private static final String[] APK_PROXY_PREFIXES = {
            "",
            "https://gh-proxy.com/",
    };

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private Updater() {}

    /** 版本清单 */
    public static class Info {
        public int versionCode;
        public String versionName = "";
        public String apkUrl = "";
        public String notes = "";
        public boolean force;
    }

    public interface CheckCallback {
        /** info 为 null 表示已是最新版本 */
        void onResult(Info info);
        void onError(Throwable t);
    }

    /** 已安装版本, 形如 1.0.0(10000) */
    public static String installedVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? pi.getLongVersionCode() : pi.versionCode;
            return pi.versionName + "(" + code + ")";
        } catch (Throwable t) {
            return "未知";
        }
    }

    private static int installedVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 检查更新; 已是最新时 onResult(null) */
    public static void check(Context ctx, CheckCallback cb) {
        final int current = installedVersionCode(ctx);
        ThreadUtils.io(() -> {
            Throwable last = null;
            for (String url : MANIFEST_URLS) {
                try {
                    String body = fetch(url);
                    Info info = parse(body);
                    if (info != null && info.versionCode > 0) {
                        Log.i(TAG, "manifest ok via " + url + " v" + info.versionName);
                        final Info newer = info.versionCode > current ? info : null;
                        ThreadUtils.main(() -> cb.onResult(newer));
                        return;
                    }
                } catch (Throwable t) {
                    last = t;
                    Log.w(TAG, "manifest failed " + url + " : " + t);
                }
            }
            final Throwable e = last;
            ThreadUtils.main(() -> cb.onError(e));
        });
    }

    private static String fetch(String url) throws Exception {
        Request req = new Request.Builder().url(url).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new java.io.IOException("http " + resp.code());
            }
            return resp.body().string();
        }
    }

    private static Info parse(String body) {
        if (body == null || body.trim().isEmpty()) return null;
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        Info info = new Info();
        if (o.has("versionCode")) info.versionCode = o.get("versionCode").getAsInt();
        if (o.has("versionName")) info.versionName = o.get("versionName").getAsString();
        if (o.has("apkUrl")) info.apkUrl = o.get("apkUrl").getAsString();
        if (o.has("notes")) info.notes = o.get("notes").getAsString();
        if (o.has("force")) info.force = o.get("force").getAsBoolean();
        return info;
    }

    // ================= 弹窗与下载安装 =================

    /** 弹出升级提示; manual 表示用户手动点「检查更新」触发, 用于给出"已是最新"的反馈 */
    public static void showDialog(Activity act, Info info, boolean manual) {
        if (act == null || act.isFinishing()) return;
        View view = LayoutInflater.from(act).inflate(R.layout.dialog_update, null);
        TextView tvVersion = view.findViewById(R.id.tvUpdateVersion);
        TextView tvNotes = view.findViewById(R.id.tvUpdateNotes);
        TextView tvProgress = view.findViewById(R.id.tvUpdateProgress);
        ProgressBar bar = view.findViewById(R.id.updateProgress);

        tvVersion.setText("发现新版本 " + info.versionName);
        tvNotes.setText(info.notes == null || info.notes.isEmpty() ? "修复若干问题" : info.notes);
        tvProgress.setText("当前版本 " + installedVersion(act));

        AlertDialog dialog = new AlertDialog.Builder(act)
                .setView(view)
                .setPositiveButton("立即升级", null)
                .setNegativeButton(info.force ? null : "以后再说", null)
                .setCancelable(!info.force)
                .create();
        dialog.show();
        // 下载过程要实时刷新进度, 不能走默认的自动关闭, 这里自行接管点击
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            v.setEnabled(false);
            bar.setVisibility(View.VISIBLE);
            download(act, info, new DownloadCallback() {
                @Override
                public void onProgress(long done, long total) {
                    tvProgress.setText(total > 0
                            ? "正在下载 " + (done * 100 / total) + "%"
                            : "正在下载 " + (done / 1024 / 1024) + "MB");
                    if (total > 0) bar.setProgress((int) (done * 100 / total));
                }

                @Override
                public void onDone(File apk) {
                    tvProgress.setText("下载完成, 正在安装…");
                    dialog.dismiss();
                    install(act, apk);
                }

                @Override
                public void onError(Throwable t) {
                    v.setEnabled(true);
                    bar.setVisibility(View.GONE);
                    tvProgress.setText("下载失败: " + t.getMessage());
                }
            });
        });
    }

    public interface DownloadCallback {
        void onProgress(long done, long total);
        void onDone(File apk);
        void onError(Throwable t);
    }

    /** 下载 APK 到应用私有缓存目录(无需存储权限) */
    public static void download(Context ctx, Info info, DownloadCallback cb) {
        ThreadUtils.io(() -> {
            Throwable last = null;
            for (String prefix : APK_PROXY_PREFIXES) {
                String url = prefix + info.apkUrl;
                try {
                    File dir = new File(ctx.getCacheDir(), "apk");
                    if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("无法创建缓存目录");
                    File out = new File(dir, "ming-tv-v" + info.versionName + ".apk");
                    Request req = new Request.Builder().url(url).build();
                    try (Response resp = CLIENT.newCall(req).execute()) {
                        if (!resp.isSuccessful() || resp.body() == null) {
                            throw new java.io.IOException("http " + resp.code());
                        }
                        long total = resp.body().contentLength();
                        long done = 0;
                        int lastPercent = -1;
                        try (InputStream in = resp.body().byteStream();
                             FileOutputStream fos = new FileOutputStream(out)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                fos.write(buf, 0, n);
                                done += n;
                                int percent = total > 0 ? (int) (done * 100 / total) : (int) (done / 1024 / 1024);
                                if (percent != lastPercent) {
                                    lastPercent = percent;
                                    final long d = done, t = total;
                                    ThreadUtils.main(() -> cb.onProgress(d, t));
                                }
                            }
                        }
                    }
                    if (out.length() <= 0) throw new java.io.IOException("下载内容为空");
                    final File apk = out;
                    ThreadUtils.main(() -> cb.onDone(apk));
                    return;
                } catch (Throwable t) {
                    last = t;
                    Log.w(TAG, "apk download failed " + url + " : " + t);
                }
            }
            final Throwable e = last == null ? new java.io.IOException("未知错误") : last;
            ThreadUtils.main(() -> cb.onError(e));
        });
    }

    /** 交给系统安装器; Android 8.0+ 未授权"安装未知应用"时先跳到授权页 */
    public static void install(Context ctx, File apk) {
        if (apk == null || !apk.exists()) {
            Toast.makeText(ctx, "安装包不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ctx.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(ctx, "请先允许本应用安装应用", Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:" + ctx.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable ignored) {}
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(ctx, "无法启动安装器: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
