package com.seanming.player.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.seanming.player.R;
import com.seanming.player.api.ApiConfig;
import com.seanming.player.bean.Result;
import com.seanming.player.bean.Site;
import com.seanming.player.bean.Vod;
import com.seanming.player.bean.VodClass;
import com.seanming.player.spider.SpiderManager;
import com.seanming.player.ui.adapter.VodAdapter;
import com.seanming.player.ui.detail.DetailActivity;
import com.seanming.player.ui.drive.DriveActivity;
import com.seanming.player.ui.history.HistoryActivity;
import com.seanming.player.ui.collect.CollectActivity;
import com.seanming.player.ui.live.LiveActivity;
import com.seanming.player.ui.push.PushActivity;
import com.seanming.player.ui.search.SearchActivity;
import com.seanming.player.ui.settings.SettingsActivity;
import com.seanming.player.util.ThreadUtils;
import com.seanming.player.util.ScreenUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** 首页: 顶部站点+功能图标行 + 分类 Tab + 视频网格 */
public class HomeActivity extends AppCompatActivity {

    private LinearLayout tabContainer;
    private RecyclerView gridVod;
    private TextView tvStatus;
    private TextView tvSiteName;
    private TextView tvUpdateDate;
    private VodAdapter adapter;
    private Site currentSite;
    private List<VodClass> classes = new ArrayList<>();
    /** homeContent 返回的推荐位; 仅当非空时才显示"主页"Tab */
    private List<Vod> homeList = new ArrayList<>();
    /** Tab 下标偏移: 显示"主页"Tab 时为 1, 否则为 0 */
    private int tabOffset = 0;
    /** 选中 Tab 在 tabContainer 中的下标; -1 表示尚未选中 */
    private int currentTabIndex = -1;
    private int currentPage = 1;
    private int pageCount = 1;
    private boolean loading;

    /** 实时时钟, 每秒刷新一次显示本机日期时间 */
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private final Runnable clockTicker = new Runnable() {
        @Override
        public void run() {
            if (tvUpdateDate != null) {
                tvUpdateDate.setText(new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.getDefault())
                        .format(new Date()));
            }
            clockHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        tabContainer = findViewById(R.id.tabContainer);
        gridVod = findViewById(R.id.gridVod);
        tvStatus = findViewById(R.id.tvStatus);
        tvSiteName = findViewById(R.id.tvSiteName);
        tvUpdateDate = findViewById(R.id.tvUpdateDate);

        adapter = new VodAdapter();
        // 根据屏幕宽度动态计算列数, 适配投影仪/电视等不同分辨率(投影仪上海报更大)
        gridVod.setLayoutManager(new GridLayoutManager(this, ScreenUtil.posterColumns(this)));
        gridVod.setAdapter(adapter);
        adapter.setOnItemClickListener(vod -> {
            Intent i = new Intent(this, DetailActivity.class);
            i.putExtra("vod", vod);
            startActivity(i);
        });

        setupFocusAnim(findViewById(R.id.btnHistory));
        setupFocusAnim(findViewById(R.id.btnFavorite));
        setupFocusAnim(findViewById(R.id.btnSearch));
        setupFocusAnim(findViewById(R.id.btnConfig));
        setupFocusAnim(findViewById(R.id.btnDrive));
        setupFocusAnim(findViewById(R.id.btnPush));
        setupFocusAnim(findViewById(R.id.btnLive));
        setupFocusAnim(findViewById(R.id.btnSettings));
        findViewById(R.id.btnHistory).setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));
        findViewById(R.id.btnFavorite).setOnClickListener(v ->
                startActivity(new Intent(this, CollectActivity.class)));
        findViewById(R.id.btnSearch).setOnClickListener(v ->
                startActivity(new Intent(this, SearchActivity.class)));
        // 配置(影视仓: 扫码远程推送接口配置, 与推送页同源)
        findViewById(R.id.btnConfig).setOnClickListener(v ->
                startActivity(new Intent(this, PushActivity.class)));
        // 网盘(WebDAV / AList 直连)
        findViewById(R.id.btnDrive).setOnClickListener(v ->
                startActivity(new Intent(this, DriveActivity.class)));
        // 推送(手机扫码推送视频链接/接口)
        findViewById(R.id.btnPush).setOnClickListener(v ->
                startActivity(new Intent(this, PushActivity.class)));
        findViewById(R.id.btnLive).setOnClickListener(v ->
                startActivity(new Intent(this, LiveActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        // 功能行"线路": 弹出站点选择框
        setupFocusAnim(findViewById(R.id.btnSwitchSite));
        findViewById(R.id.btnSwitchSite).setOnClickListener(v -> showSiteDialog());
        tvSiteName.setOnClickListener(v -> showSiteDialog());

        gridVod.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                super.onScrolled(rv, dx, dy);
                if (loading || currentPage >= pageCount) return;
                GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 5) {
                    loadMore();
                }
            }
        });

        loadHome();
        // 启动实时时钟
        clockHandler.post(clockTicker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 设置页可能切换了接口或站点, 用 key 比较避免引用陷阱
        Site home = ApiConfig.get().getHomeSite();
        if (home == null) return;
        boolean changed = currentSite == null || !home.getKey().equals(currentSite.getKey());
        if (changed) {
            loadHome();
        }
        // 回到前台继续走表
        clockHandler.removeCallbacks(clockTicker);
        clockHandler.post(clockTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        clockHandler.removeCallbacks(clockTicker);
    }

    private void loadHome() {
        if (!ApiConfig.get().isLoaded()) {
            showStatus(getString(R.string.msg_loading));
            ApiConfig.get().loadLast(new ThreadUtils.Callback<Boolean>() {
                @Override
                public void onResult(Boolean ok) {
                    if (!ok) {
                        showStatus(getString(R.string.msg_no_api));
                        return;
                    }
                    initSite();
                }
                @Override
                public void onError(Throwable t) {
                    showStatus(getString(R.string.msg_load_failed));
                }
            });
            return;
        }
        initSite();
    }

    private void initSite() {
        currentSite = ApiConfig.get().getHomeSite();
        if (currentSite == null) {
            showStatus(getString(R.string.msg_no_api));
            return;
        }
        tvSiteName.setText(currentSite.getName());
        // 顶部日期时间 = 本机时间, 格式 yyyy年MM月dd日 HH:mm:ss, 每秒刷新
        tvUpdateDate.setText(new SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.getDefault())
                .format(new Date()));
        // 站点切换时重置分类选中状态
        currentTabIndex = -1;
        currentPage = 1;
        pageCount = 1;
        showStatus(getString(R.string.msg_loading));
        ThreadUtils.io(() -> {
            Result r = SpiderManager.homeContent(currentSite);
            android.util.Log.e("HomeActivity", "homeContent: error=" + r.hasError() + " msg=" + r.msg + " classes=" + r.classes.size() + " list=" + (r.list == null ? -1 : r.list.size()));
            ThreadUtils.main(() -> {
                if (r.hasError()) {
                    showStatus(getString(R.string.msg_load_failed) + ": " + r.msg);
                    return;
                }
                classes = r.classes;
                homeList = r.list == null ? new ArrayList<>() : r.list;
                // 只有该源真的返回了推荐位, 才显示"主页"Tab
                buildTabs(!homeList.isEmpty());
                if (classes.isEmpty() && homeList.isEmpty()) {
                    showStatus("无分类");
                    return;
                }
                // 有推荐位默认停在"主页", 否则直接进第一个分类
                selectTab(0);
            });
        });
    }

    /** 顶部"换源"弹窗: 双列网格列出所有可切换站点, 选中后切换首页站点并重新加载 */
    private void showSiteDialog() {
        List<Site> sites = ApiConfig.get().getSites();
        if (sites == null || sites.isEmpty()) {
            Toast.makeText(this, "暂无可切换站点", Toast.LENGTH_SHORT).show();
            return;
        }
        final String homeKey = currentSite != null ? currentSite.getKey() : "";
        final float density = getResources().getDisplayMetrics().density;
        final int gap = (int) (density * 6);
        final int rowGap = (int) (density * 8);

        // 电视横屏很宽, 单列会把列表拉得极长; 双列能在同样高度里多放一倍站点
        final int cols = 2;
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (density * 14);
        grid.setPadding(pad, pad, pad, pad);

        final ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.addView(grid);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("切换视频源")
                .setView(scroll)
                .create();

        TextView focusTarget = null;
        LinearLayout row = null;
        for (int i = 0; i < sites.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
            }
            final Site s = sites.get(i);
            final boolean active = s.getKey().equals(homeKey);
            TextView item = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_tab, row, false);
            item.setText(s.getName() + (active ? "  ●当前" : ""));
            item.setSelected(active);
            item.setSingleLine(true);
            item.setEllipsize(TextUtils.TruncateAt.END);
            // 两列等宽铺满弹窗
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(gap, 0, gap, rowGap);
            item.setLayoutParams(lp);
            item.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start();
                else v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            });
            item.setOnClickListener(v -> {
                dialog.dismiss();
                if (s.getKey().equals(homeKey)) return;   // 未变化
                ApiConfig.get().setHomeSite(s.getKey());
                Toast.makeText(this, "已切换到: " + s.getName(), Toast.LENGTH_SHORT).show();
                initSite(); // 重新加载首页内容
            });
            row.addView(item);
            if (active) {
                focusTarget = item;
                // 触摸模式下普通 focusable 视图无法用代码取焦, 需额外允许 touch-mode 聚焦
                item.setFocusableInTouchMode(true);
            }
        }
        // 站点数为奇数时末行只有一个, 补一个占位块保持两列等宽
        if (row != null && sites.size() % cols != 0) {
            View spacer = new View(this);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            sp.setMargins(gap, 0, gap, rowGap);
            spacer.setLayoutParams(sp);
            row.addView(spacer);
        }

        // 焦点默认落在当前源上, 遥控器一打开就能就近切换
        final TextView ft = focusTarget;
        dialog.setOnShowListener(d -> {
            if (ft != null) ft.postDelayed(ft::requestFocus, 120);
        });

        dialog.show();
        // 弹窗铺满大半个屏幕, 才装得下双列
        final android.view.Window win = dialog.getWindow();
        final DisplayMetrics dm = getResources().getDisplayMetrics();
        if (win != null) {
            win.setLayout((int) (dm.widthPixels * 0.88f),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
        // 内容超过屏幕时把滚动区高度压到 82%, 避免弹窗溢出屏幕
        scroll.post(() -> {
            int maxH = (int) (dm.heightPixels * 0.82f);
            if (scroll.getHeight() > maxH) {
                ViewGroup.LayoutParams p = scroll.getLayoutParams();
                p.height = maxH;
                scroll.setLayoutParams(p);
            }
        });
    }

    /** Tab 焦点变化时的缩放动画 */
    private final View.OnFocusChangeListener tabZoom = (view, hasFocus) -> {
        float scale = hasFocus ? 1.1f : 1.0f;
        view.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
    };

    /**
     * 构建分类 Tab.
     * @param withHome 该源是否返回了推荐位; 有才插入"主页"Tab(下标 0), 分类顺延
     */
    private void buildTabs(boolean withHome) {
        tabContainer.removeAllViews();
        tabOffset = withHome ? 1 : 0;
        if (withHome) {
            TextView homeTab = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_tab, tabContainer, false);
            homeTab.setText("主页");
            homeTab.setSelected(false);
            homeTab.setOnClickListener(v -> selectTab(0));
            homeTab.setOnFocusChangeListener(tabZoom);
            tabContainer.addView(homeTab);
        }
        for (int i = 0; i < classes.size(); i++) {
            VodClass vc = classes.get(i);
            TextView tab = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_tab, tabContainer, false);
            tab.setText(vc.type_name);
            final int idx = i + tabOffset;
            tab.setSelected(false);
            tab.setOnClickListener(v -> selectTab(idx));
            tab.setOnFocusChangeListener(tabZoom);
            tabContainer.addView(tab);
        }
        currentTabIndex = -1; // 尚未选中任何 Tab
    }

    private void selectTab(int index) {
        if (index == currentTabIndex) return;
        if (index < 0 || index >= tabContainer.getChildCount()) return;
        for (int i = 0; i < tabContainer.getChildCount(); i++) {
            tabContainer.getChildAt(i).setSelected(i == index);
        }
        currentTabIndex = index;
        currentPage = 1;
        pageCount = 1;
        if (index == 0 && tabOffset == 1) {
            // "主页": 直接展示 homeContent 的推荐位, 无需再请求
            adapter.setCurrentTabName("主页");
            adapter.submitList(homeList);
            hideStatus();
            focusFirstPoster();
            return;
        }
        int cls = index - tabOffset;
        if (cls < 0 || cls >= classes.size()) return;
        adapter.submitList(null);
        adapter.setCurrentTabName(classes.get(cls).type_name);
        loadCategory(classes.get(cls).type_id, currentPage);
    }

    private void loadCategory(String tid, int pg) {
        loading = true;
        showStatus(getString(R.string.msg_loading));
        ThreadUtils.io(() -> {
            Result r = SpiderManager.categoryContent(currentSite, tid, pg, false, new HashMap<>());
            ThreadUtils.main(() -> {
                loading = false;
                if (r.hasError()) {
                    showStatus(getString(R.string.msg_load_failed));
                    return;
                }
                hideStatus();
                if (pg == 1) adapter.submitList(r.list);
                else adapter.appendList(r.list);
                currentPage = r.page;
                pageCount = r.pagecount;
                if (pg == 1) focusFirstPoster();
            });
        });
    }

    private void loadMore() {
        int cls = currentTabIndex - tabOffset;
        if (cls < 0 || cls >= classes.size()) return;
        loadCategory(classes.get(cls).type_id, currentPage + 1);
    }

    /** 把焦点落到网格首个海报(等布局完成后), 方便遥控器立即操作 */
    private void focusFirstPoster() {
        gridVod.post(() -> {
            if (adapter.getItemCount() > 0) {
                RecyclerView.ViewHolder vh = gridVod.findViewHolderForAdapterPosition(0);
                if (vh != null) vh.itemView.requestFocus();
                else gridVod.requestFocus();
            }
        });
    }

    private void showStatus(String msg) {
        tvStatus.setText(msg);
        tvStatus.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        tvStatus.setVisibility(View.GONE);
    }

    /** 统一设置焦点缩放动画 */
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
