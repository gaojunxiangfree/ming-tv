package com.seanming.player.ui.history;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.seanming.player.R;
import com.seanming.player.bean.Vod;
import com.seanming.player.data.AppDatabase;
import com.seanming.player.data.HistoryRecord;
import com.seanming.player.ui.adapter.HistoryAdapter;

import java.util.List;

/** 历史记录页: 列表展示 + 点击续播 + 删除/清空 */
public class HistoryActivity extends AppCompatActivity {

    private HistoryAdapter adapter;
    private TextView tvStatus;
    private List<HistoryRecord> records;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        tvStatus = findViewById(R.id.tvStatus);
        RecyclerView rv = findViewById(R.id.rvHistory);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter();
        rv.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnClear).setOnClickListener(v -> confirmClear());
        // 焦点缩放动画
        setupFocusAnim(findViewById(R.id.btnBack));
        setupFocusAnim(findViewById(R.id.btnClear));

        adapter.setOnItemClickListener(new HistoryAdapter.OnItemClickListener() {
            @Override
            public void onClick(HistoryRecord record) {
                resumePlay(record);
            }
            @Override
            public void onDelete(HistoryRecord record) {
                AppDatabase.get(HistoryActivity.this).historyDao().delete(record.id);
                reload();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        records = AppDatabase.get(this).historyDao().all();
        adapter.submit(records);
        if (records == null || records.isEmpty()) {
            tvStatus.setText("暂无观看历史");
            tvStatus.setVisibility(View.VISIBLE);
        } else {
            tvStatus.setVisibility(View.GONE);
        }
    }

    /** 点击历史记录: 打开详情页, 详情页会加载剧集信息, 点击播放时 PlayActivity 会自动恢复进度 */
    private void resumePlay(HistoryRecord r) {
        Vod vod = new Vod();
        vod.vod_id = r.vodId;
        vod.vod_name = r.vodName;
        vod.vod_pic = r.vodPic;
        vod.siteKey = r.siteKey;
        Intent i = new Intent(this, com.seanming.player.ui.detail.DetailActivity.class);
        i.putExtra("vod", vod);
        // siteKey 是 transient 字段, 单独传递确保详情页能正确查找历史记录
        i.putExtra("siteKey", r.siteKey);
        i.putExtra("resume", true);
        startActivity(i);
    }

    private void confirmClear() {
        if (records == null || records.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("清空历史")
                .setMessage("确定清空全部观看历史？")
                .setPositiveButton("清空", (d, w) -> {
                    AppDatabase.get(this).historyDao().clear();
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 统一设置焦点缩放动画 */
    private void setupFocusAnim(View view) {
        if (view == null) return;
        view.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start();
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
            }
        });
    }
}
