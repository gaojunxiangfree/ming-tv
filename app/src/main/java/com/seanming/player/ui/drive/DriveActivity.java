package com.seanming.player.ui.drive;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.seanming.player.R;
import com.seanming.player.api.DriveApi;
import com.seanming.player.api.DriveStore;
import com.seanming.player.bean.DriveFile;
import com.seanming.player.bean.StorageDrive;
import com.seanming.player.bean.Vod;
import com.seanming.player.ui.adapter.DriveFileAdapter;
import com.seanming.player.ui.play.PlayActivity;
import com.seanming.player.util.ThreadUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 网盘(存储空间)页, 对标影视仓 DriveActivity.
 * 支持 WebDAV 与 AList/OpenList 的浏览与直连播放.
 *
 * 遥控键位:
 *  - 上/下: 在列表中移动焦点
 *  - OK:    进入目录 / 播放文件
 *  - 左/返回: 返回上一级目录(根目录时退出)
 *  - 菜单键: 根目录时弹出"添加网盘"
 */
public class DriveActivity extends AppCompatActivity {

    private RecyclerView rvFiles;
    private DriveFileAdapter adapter;
    private TextView tvTitle, tvStatus, btnAdd, btnDelete;

    /** null 表示根页面(网盘列表) */
    private StorageDrive currentDrive;
    private String currentPath = "/";
    private boolean loading;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drive);

        rvFiles = findViewById(R.id.rvFiles);
        tvTitle = findViewById(R.id.tvTitle);
        tvStatus = findViewById(R.id.tvStatus);
        btnAdd = findViewById(R.id.btnAdd);
        btnDelete = findViewById(R.id.btnDelete);

        adapter = new DriveFileAdapter();
        adapter.setOnClick(new DriveFileAdapter.OnClick() {
            @Override
            public void onClick(DriveFile f) { onFileClicked(f); }
            @Override
            public void onLongClick(DriveFile f) {
                // 根页面长按删除该网盘
                if (currentDrive == null && f.drive != null) confirmDeleteDrive(f.drive);
            }
        });
        rvFiles.setLayoutManager(new LinearLayoutManager(this));
        rvFiles.setAdapter(adapter);

        View btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> onBack());
        setupFocusAnim(btnBack);
        btnAdd.setOnClickListener(v -> showAddDialog());
        btnDelete.setOnClickListener(v -> showDeletePicker());
        setupFocusAnim(btnAdd);
        setupFocusAnim(btnDelete);

        showRoot();
    }

    // ==================== 页面状态 ====================

    /** 根页面: 展示已配置的网盘列表 */
    private void showRoot() {
        currentDrive = null;
        currentPath = "/";
        tvTitle.setText("存储空间");
        btnAdd.setVisibility(View.VISIBLE);
        btnDelete.setVisibility(View.VISIBLE);
        List<StorageDrive> drives = DriveStore.getAll();
        List<DriveFile> list = new ArrayList<>();
        for (StorageDrive d : drives) {
            DriveFile f = new DriveFile();
            f.name = d.name == null || d.name.isEmpty() ? d.baseUrl() : d.name;
            f.isDir = true;
            f.drive = d;
            f.path = null;   // 根条目: 适配器据此显示网盘类型
            list.add(f);
        }
        adapter.submit(list);
        if (list.isEmpty()) {
            showStatus("暂无网盘, 点右上角「＋ 添加网盘」\n支持 WebDAV 与 AList");
        } else {
            hideStatus();
            focusFirst();
        }
    }

    /** 进入某个网盘/目录 */
    private void openDir(StorageDrive d, String path) {
        currentDrive = d;
        currentPath = DriveApi.normalize(path);
        tvTitle.setText(currentDrive.name + "  " + currentPath);
        btnAdd.setVisibility(View.GONE);
        btnDelete.setVisibility(View.GONE);
        loadDir();
    }

    private void loadDir() {
        if (currentDrive == null) { showRoot(); return; }
        loading = true;
        showStatus("加载中...");
        final StorageDrive d = currentDrive;
        final String path = currentPath;
        ThreadUtils.io(() -> {
            DriveApi.ListResult r = DriveApi.list(d, path);
            ThreadUtils.main(() -> {
                loading = false;
                if (currentDrive != d || !currentPath.equals(path)) return; // 已切换
                tvTitle.setText(d.name + "  " + path);
                if (!r.ok()) {
                    showStatus("加载失败: " + r.error);
                    adapter.submit(new ArrayList<>());
                    return;
                }
                List<DriveFile> list = new ArrayList<>();
                if (!"/".equals(path)) list.add(DriveFile.up());
                list.addAll(r.files);
                adapter.submit(list);
                if (r.files.isEmpty()) showStatus("该目录为空");
                else { hideStatus(); focusFirst(); }
            });
        });
    }

    /** 返回上一级目录(根目录时回到网盘列表) */
    private void goUp() {
        if (currentDrive == null) return;
        String parent = DriveApi.parent(currentPath);
        if (parent == null) {
            showRoot();
            return;
        }
        currentPath = parent;
        loadDir();
    }

    // ==================== 交互 ====================

    private void onFileClicked(DriveFile f) {
        if (f == null || loading) return;
        if (f.isUp) { goUp(); return; }
        if (currentDrive == null) {
            // 根页面: 进入网盘
            StorageDrive d = f.drive;
            if (d == null) return;
            String init = (d.initPath == null || d.initPath.trim().isEmpty()) ? "/" : d.initPath;
            openDir(d, init);
            return;
        }
        if (f.isDir) {
            openDir(currentDrive, f.path);
            return;
        }
        playFile(f);
    }

    private void playFile(DriveFile f) {
        if (!f.isMedia()) {
            Toast.makeText(this, "不支持的文件类型: " + f.ext(), Toast.LENGTH_SHORT).show();
            return;
        }
        final StorageDrive d = currentDrive;
        showStatus("解析播放地址...");
        ThreadUtils.io(() -> {
            try {
                String url = DriveApi.resolveUrl(d, f);
                Map<String, String> headers = DriveApi.headers(d);
                ThreadUtils.main(() -> {
                    hideStatus();
                    if (url == null || url.isEmpty()) {
                        Toast.makeText(this, "无法获取播放地址", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Vod vod = new Vod();
                    vod.vod_id = "drive|" + d.id + "|" + f.path;
                    vod.vod_name = f.name;
                    vod.siteKey = "_drive";
                    vod.vod_play_from = "网盘";
                    vod.vod_play_url = f.name + "$" + url.replace("#", "%23");
                    Intent i = new Intent(this, PlayActivity.class);
                    i.putExtra("vod", vod);
                    i.putExtra("siteKey", "_drive");
                    i.putExtra("flag", "网盘");
                    i.putExtra("index", 0);
                    if (headers != null && !headers.isEmpty()) {
                        i.putExtra("headers", new Gson().toJson(headers));
                    }
                    startActivity(i);
                });
            } catch (Throwable t) {
                ThreadUtils.main(() -> {
                    hideStatus();
                    Toast.makeText(this, "播放失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                onBack();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // 焦点在列表中时左键返回上级(根页面不处理, 让焦点正常移动)
                if (currentDrive != null) {
                    goUp();
                    return true;
                }
                return super.onKeyDown(keyCode, event);
            case KeyEvent.KEYCODE_MENU:
                if (currentDrive == null) showAddDialog();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public void onBackPressed() {
        onBack();
    }

    private void onBack() {
        if (loading) return;
        if (currentDrive != null) goUp();
        else super.onBackPressed();
    }

    // ==================== 添加 / 删除 ====================

    private void showAddDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_drive_add, null);
        EditText etName = v.findViewById(R.id.etName);
        EditText etUrl = v.findViewById(R.id.etUrl);
        EditText etUser = v.findViewById(R.id.etUser);
        EditText etPwd = v.findViewById(R.id.etPwd);
        EditText etInit = v.findViewById(R.id.etInitPath);
        RadioGroup rg = v.findViewById(R.id.rgType);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("添加网盘")
                .setView(v)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.show();
        // 手动处理确定, 做输入校验(不自动关闭)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            String url = etUrl.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "请填写地址", Toast.LENGTH_SHORT).show();
                return;
            }
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) name = url;
            int type = rg.getCheckedRadioButtonId() == R.id.rbAlist
                    ? StorageDrive.TYPE_ALIST : StorageDrive.TYPE_WEBDAV;
            StorageDrive d = new StorageDrive(0, name, type, url,
                    etUser.getText().toString().trim(),
                    etPwd.getText().toString(),
                    etInit.getText().toString().trim());
            DriveStore.add(d);
            dialog.dismiss();
            Toast.makeText(this, "已添加: " + name, Toast.LENGTH_SHORT).show();
            showRoot();
        });
    }

    private void showDeletePicker() {
        List<StorageDrive> drives = DriveStore.getAll();
        if (drives.isEmpty()) {
            Toast.makeText(this, "暂无可删除的网盘", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[drives.size()];
        for (int i = 0; i < drives.size(); i++) {
            labels[i] = drives.get(i).name + "  (" + drives.get(i).typeName() + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("删除网盘")
                .setItems(labels, (d, which) -> {
                    if (which >= 0 && which < drives.size()) confirmDeleteDrive(drives.get(which));
                })
                .show();
    }

    private void confirmDeleteDrive(StorageDrive d) {
        new AlertDialog.Builder(this)
                .setTitle("删除网盘")
                .setMessage("确定删除「" + d.name + "」?")
                .setPositiveButton("删除", (dlg, w) -> {
                    DriveStore.remove(d.id);
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    showRoot();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== UI 工具 ====================

    private void showStatus(String msg) {
        tvStatus.setText(msg);
        tvStatus.setVisibility(View.VISIBLE);
    }

    private void hideStatus() { tvStatus.setVisibility(View.GONE); }

    private void focusFirst() {
        rvFiles.post(() -> {
            if (adapter.getItemCount() == 0) { rvFiles.requestFocus(); return; }
            RecyclerView.ViewHolder vh = rvFiles.findViewHolderForAdapterPosition(0);
            if (vh != null) vh.itemView.requestFocus();
            else rvFiles.requestFocus();
        });
    }

    private void setupFocusAnim(View view) {
        if (view == null) return;
        view.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start();
            else v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
        });
    }
}