package com.seanming.player.ui.update;

import android.app.Activity;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.seanming.player.util.PrefUtils;
import com.seanming.player.util.UpdateManager;

import java.io.File;

/**
 * 升级相关弹窗: 新版本提示 / 下载进度 / 安装权限引导.
 * 自动检查与设置页手动检查共用, 保证交互一致.
 */
public class UpdateDialog {

    /** 安装未知来源权限的请求码(SettingsActivity 侧配合使用) */
    public static final int REQUEST_INSTALL_PERMISSION = 0x9911;

    private UpdateDialog() {}

    /**
     * 有新版本时弹窗提示.
     *
     * @param manual true=用户在设置页主动点击检查(忽略过的版本也会提示)
     */
    public static void show(final Activity act, final UpdateManager.ReleaseInfo info, final boolean manual) {
        if (info == null) return;
        if (!info.hasUpdate) {
            if (manual) {
                Toast.makeText(act, "已是最新版本 v" + UpdateManager.currentVersionName(),
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        // 自动检查时, 用户忽略过的版本不再打扰
        if (!manual && PrefUtils.getInt(PrefUtils.K_UPDATE_IGNORE, -1) == info.versionCode) {
            return;
        }

        String body = info.body == null ? "" : info.body.trim();
        // 弹窗不宜过长, 截断避免撑满屏幕
        if (body.length() > 600) body = body.substring(0, 600) + "\n…";
        String msg = "当前版本: v" + UpdateManager.currentVersionName()
                + "\n最新版本: v" + info.versionName
                + "\n\n" + body;

        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle("发现新版本 v" + info.versionName)
                .setMessage(msg)
                .setPositiveButton("立即升级", (d, w) -> startOrDownload(act, info));
        if (manual) {
            b.setNegativeButton("稍后", null);
        } else {
            b.setNegativeButton("忽略此版本", (d, w) ->
                    PrefUtils.putInt(PrefUtils.K_UPDATE_IGNORE, info.versionCode));
        }
        b.show();
    }

    /** 已授权则直接下载, 否则先引导开启"安装未知来源应用" */
    private static void startOrDownload(final Activity act, final UpdateManager.ReleaseInfo info) {
        if (!UpdateManager.canInstall(act)) {
            new AlertDialog.Builder(act)
                    .setTitle("需要安装权限")
                    .setMessage("升级需要允许本应用「安装未知来源应用」。\n"
                            + "点击「去开启」后在系统页面允许安装, 返回后再次点击「检查更新」即可完成升级。")
                    .setPositiveButton("去开启", (d, w) ->
                            UpdateManager.requestInstallPermission(act, REQUEST_INSTALL_PERMISSION))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        download(act, info);
    }

    private static void download(final Activity act, final UpdateManager.ReleaseInfo info) {
        int pad = dp(act, 28);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        final TextView tvTip = new TextView(act);
        tvTip.setText("正在下载安装包… 0%");
        tvTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        box.addView(tvTip);

        final ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(act, 16);
        bar.setLayoutParams(lp);
        box.addView(bar);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("正在升级到 v" + info.versionName)
                .setView(box)
                .setCancelable(false)
                .create();
        dlg.show();

        UpdateManager.download(act, info, new UpdateManager.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                if (!dlg.isShowing()) return;
                bar.setProgress(percent);
                tvTip.setText("正在下载安装包… " + percent + "%");
            }

            @Override
            public void onSuccess(File apk) {
                if (dlg.isShowing()) dlg.dismiss();
                UpdateManager.installApk(act, apk);
            }

            @Override
            public void onError(Throwable t) {
                if (dlg.isShowing()) dlg.dismiss();
                new AlertDialog.Builder(act)
                        .setTitle("升级失败")
                        .setMessage("安装包下载失败: " + t.getMessage()
                                + "\n\n可稍后重试, 或在电脑浏览器打开码云发行版手动下载。")
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
    }

    private static int dp(Activity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }
}
