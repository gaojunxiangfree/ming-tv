package com.seanming.player.ui.collect;

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
import com.seanming.player.data.Favorite;
import com.seanming.player.ui.adapter.FavoriteAdapter;
import com.seanming.player.ui.detail.DetailActivity;

import java.util.List;

/** 收藏页: 展示收藏列表, 点击打开详情, 支持取消收藏/清空 */
public class CollectActivity extends AppCompatActivity {

    private FavoriteAdapter adapter;
    private TextView tvStatus;
    private List<Favorite> items;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_collect);

        tvStatus = findViewById(R.id.tvStatus);
        RecyclerView rv = findViewById(R.id.rvCollect);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FavoriteAdapter();
        rv.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnClear).setOnClickListener(v -> confirmClear());
        setupFocusAnim(findViewById(R.id.btnBack));
        setupFocusAnim(findViewById(R.id.btnClear));

        adapter.setOnItemClickListener(new FavoriteAdapter.OnItemClickListener() {
            @Override
            public void onClick(Favorite favorite) {
                openDetail(favorite);
            }
            @Override
            public void onDelete(Favorite favorite) {
                AppDatabase.get(CollectActivity.this).favoriteDao().remove(favorite.id);
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
        items = AppDatabase.get(this).favoriteDao().all();
        adapter.submit(items);
        if (items == null || items.isEmpty()) {
            tvStatus.setText("暂无收藏");
            tvStatus.setVisibility(View.VISIBLE);
        } else {
            tvStatus.setVisibility(View.GONE);
        }
    }

    /** 打开详情页(siteKey 是 transient 字段, 单独传) */
    private void openDetail(Favorite f) {
        Vod vod = new Vod();
        vod.vod_id = f.vodId;
        vod.vod_name = f.vodName;
        vod.vod_pic = f.vodPic;
        vod.siteKey = f.siteKey;
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("vod", vod);
        i.putExtra("siteKey", f.siteKey);
        startActivity(i);
    }

    private void confirmClear() {
        if (items == null || items.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("清空收藏")
                .setMessage("确定清空全部收藏？")
                .setPositiveButton("清空", (d, w) -> {
                    AppDatabase.get(this).favoriteDao().clear();
                    reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

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
