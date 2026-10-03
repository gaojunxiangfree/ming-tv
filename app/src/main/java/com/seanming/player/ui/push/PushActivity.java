package com.seanming.player.ui.push;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.seanming.player.R;
import com.seanming.player.bean.Vod;
import com.seanming.player.server.WifiConfigServer;
import com.seanming.player.service.DlnaService;
import com.seanming.player.ui.play.PlayActivity;
import com.seanming.player.util.QRCodeUtil;

/** 推送页: 展示局域网配置页二维码, 支持手机推送视频链接/接口, 以及电视端本地输入 */
public class PushActivity extends AppCompatActivity {

    private static final String SITE_KEY = "_push";

    private ImageView ivQRCode;
    private TextView tvAddress;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_push);

        ivQRCode = findViewById(R.id.ivQRCode);
        tvAddress = findViewById(R.id.tvAddress);

        View btnBack = findViewById(R.id.btnBack);
        View btnLocal = findViewById(R.id.btnLocal);
        View btnConfirm = findViewById(R.id.btnConfirm);
        View btnDlna = findViewById(R.id.btnDlna);
        setupFocusAnim(btnBack);
        setupFocusAnim(btnLocal);
        setupFocusAnim(btnConfirm);
        setupFocusAnim(btnDlna);
        btnBack.setOnClickListener(v -> finish());
        btnConfirm.setOnClickListener(v -> finish());
        btnLocal.setOnClickListener(v -> showLocalDialog());
        btnDlna.setOnClickListener(v -> toggleDlna());

        // 保证局域网服务在运行, 手机才能扫码访问
        if (!WifiConfigServer.get().isRunning()) WifiConfigServer.get().start(this);

        refreshAddress();
        btnLocal.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAddress();
        refreshDlnaStatus();
    }

    /** 开关 DLNA 投屏接收服务(DMR) */
    private void toggleDlna() {
        if (DlnaService.isRunning()) {
            DlnaService.stop(this);
        } else {
            DlnaService.start(this);
            Toast.makeText(this, "DLNA 投屏接收已启动，可用手机投屏 App 推送到本机", Toast.LENGTH_LONG).show();
        }
        refreshDlnaStatus();
        // startService/stopService 是异步的(服务的 onCreate/onDestroy 稍后才执行),
        // 此处立即刷新拿到的仍是旧状态, 故延迟再刷新一次
        View tv = findViewById(R.id.tvDlnaStatus);
        if (tv != null) tv.postDelayed(this::refreshDlnaStatus, 800);
    }

    private void refreshDlnaStatus() {
        TextView tv = findViewById(R.id.tvDlnaStatus);
        if (tv == null) return;
        if (DlnaService.isRunning()) {
            tv.setText("DLNA 运行中 (DMR)\n控制端: " + DlnaService.getHttpBase() + "/description.xml\n"
                    + "浏览器回退验证: GET " + DlnaService.getHttpBase() + "/dlna/push?url=视频地址");
        } else {
            tv.setText("DLNA 服务未启动");
        }
    }

    private void refreshAddress() {
        String addr = WifiConfigServer.get().getAddress();
        tvAddress.setText("扫描上方二维码访问地址\n" + addr);
        int size = (int) (getResources().getDisplayMetrics().density * 280);
        ivQRCode.setImageBitmap(QRCodeUtil.generate(addr, size));
    }

    /** 电视端手动输入视频链接后本地播放 */
    private void showLocalDialog() {
        View root = getLayoutInflater().inflate(R.layout.dialog_push_url, null);
        EditText etName = root.findViewById(R.id.etName);
        EditText etUrl = root.findViewById(R.id.etUrl);
        new AlertDialog.Builder(this)
                .setTitle("本地推送")
                .setView(root)
                .setPositiveButton("推送播放", (d, w) -> {
                    String url = etUrl.getText().toString().trim();
                    if (url.isEmpty()) {
                        Toast.makeText(this, "请输入视频链接", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    play(this, url, etName.getText().toString().trim());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 用直链播放: 复用 _push 合成站点(非 spider, 直链直播) */
    public static void play(Context ctx, String url, String name) {
        if (ctx == null || url == null) return;
        final String u = url.trim();
        if (u.isEmpty()) return;
        String title = name == null ? "" : name.trim().replace("$", " ").replace("#", " ");
        if (title.isEmpty()) title = "推送视频";

        Vod vod = new Vod();
        vod.vod_id = SITE_KEY + "|" + u.hashCode();
        vod.vod_name = title;
        vod.siteKey = SITE_KEY;
        vod.vod_play_from = "推送";
        vod.vod_play_url = title + "$" + u.replace("#", "%23");

        Intent i = new Intent(ctx, PlayActivity.class);
        i.putExtra("vod", vod);
        i.putExtra("siteKey", SITE_KEY);
        i.putExtra("flag", "推送");
        i.putExtra("index", 0);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    private void setupFocusAnim(View view) {
        if (view == null) return;
        view.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(150).start();
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
            }
        });
    }
}