package com.seanming.player.ui.search;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.seanming.player.R;
import com.seanming.player.api.ApiConfig;
import com.seanming.player.bean.Result;
import com.seanming.player.bean.Site;
import com.seanming.player.bean.Vod;
import com.seanming.player.spider.SpiderManager;
import com.seanming.player.ui.adapter.VodAdapter;
import com.seanming.player.ui.detail.DetailActivity;
import com.seanming.player.util.PrefUtils;
import com.seanming.player.util.ScreenUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全源秒搜(对齐影视仓 FastSearchActivity).
 *
 * 影视仓搜得快的三个关键点, 这里 1:1 复刻:
 *  1) 固定 8 线程池并发搜索全部可搜索站点(不是一个个排队等);
 *  2) 每个站点一返回就立刻追加到结果网格(增量回填), 首屏结果通常 1 秒内出现;
 *  3) 顶部实时显示"搜索(命中源数/总源数)", 并支持按来源筛选.
 * 另外: 首页站点排在最前(最先出结果); 重新搜索时真正取消上一轮(shutdownNow).
 */
public class FastSearchActivity extends AppCompatActivity {

    /** 外部(广播/搜索页)传入关键词的 extra, 与影视仓保持一致用 "title" */
    public static final String EXTRA_KEYWORD = "title";

    private static final String TAG = "FastSearch";
    /** 并发线程数, 与影视仓一致 */
    private static final int SEARCH_THREADS = 8;
    /** 兜底: 个别站点长时间不返回时, 到点强制结束进度(内核超时不会永远挂着) */
    private static final long WATCHDOG_MS = 30000L;

    private EditText etKeyword;
    private RecyclerView rvResult;
    private TextView tvStatus;
    private TextView tvProgress;
    private LinearLayout sourceContainer;
    private VodAdapter adapter;

    /** 全部命中结果(带来源信息) */
    private final List<Item> all = new ArrayList<>();
    /** 来源筛选: null = 全部 */
    private String filterKey = null;
    /** 已出结果的来源 key -> 展示名(保持命中顺序) */
    private final Map<String, String> hitSources = new LinkedHashMap<>();

    /** 本次搜索的线程池 */
    private ExecutorService pool;
    /** 搜索批次号: 每次新搜索自增, 旧批次的任务全部作废 */
    private final AtomicInteger generation = new AtomicInteger(0);
    /** 本轮参与搜索的站点总数(进度分母) */
    private int totalSites;
    private final Handler watchdog = new Handler(Looper.getMainLooper());
    private Runnable watchdogTask;

    /** 单条聚合结果: 视频 + 来源 */
    private static class Item {
        final Vod vod;
        final String sourceKey;
        Item(Vod vod, String sourceKey) {
            this.vod = vod;
            this.sourceKey = sourceKey;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fast_search);

        etKeyword = findViewById(R.id.etKeyword);
        rvResult = findViewById(R.id.rvResult);
        tvStatus = findViewById(R.id.tvStatus);
        tvProgress = findViewById(R.id.tvProgress);
        sourceContainer = findViewById(R.id.sourceContainer);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        setupFocusAnim(findViewById(R.id.btnBack));
        setupFocusAnim(findViewById(R.id.btnSearch));

        adapter = new VodAdapter();
        rvResult.setLayoutManager(new GridLayoutManager(this, ScreenUtil.posterColumns(this)));
        rvResult.setAdapter(adapter);
        adapter.setOnItemClickListener(vod -> openDetail(vod));

        findViewById(R.id.btnSearch).setOnClickListener(v -> startSearch(currentKeyword()));
        etKeyword.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                startSearch(currentKeyword());
                return true;
            }
            return false;
        });

        // 进入即按影视仓约定: Intent 带 title 则自动搜索
        String keyword = getIntent().getStringExtra(EXTRA_KEYWORD);
        if (keyword != null && !keyword.trim().isEmpty()) {
            etKeyword.setText(keyword.trim());
            etKeyword.setSelection(etKeyword.getText().length());
            startSearch(keyword.trim());
        } else {
            etKeyword.requestFocus();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String keyword = intent.getStringExtra(EXTRA_KEYWORD);
        if (keyword != null && !keyword.trim().isEmpty()) {
            etKeyword.setText(keyword.trim());
            startSearch(keyword.trim());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelRunning();
    }

    private String currentKeyword() {
        return etKeyword.getText().toString().trim();
    }

    /** 取消上一轮搜索: 关线程池 + 停兜底计时(线程内用 generation 再兜一层) */
    private void cancelRunning() {
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        if (watchdogTask != null) {
            watchdog.removeCallbacks(watchdogTask);
            watchdogTask = null;
        }
    }

    /** 开始一次新的并发聚合搜索 */
    private void startSearch(String keyword) {
        if (keyword.isEmpty()) {
            Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show();
            return;
        }
        saveKeyword(keyword);

        final int gen = generation.incrementAndGet();
        cancelRunning();

        all.clear();
        hitSources.clear();
        filterKey = null;
        adapter.submitList(new ArrayList<>());
        rebuildSourceChips();
        tvStatus.setVisibility(View.GONE);

        final List<Site> targets = pickTargets();
        totalSites = targets.size();
        updateProgress();
        if (targets.isEmpty()) {
            showStatus("没有可搜索的站点, 请检查接口源");
            return;
        }

        final AtomicInteger pending = new AtomicInteger(totalSites);
        pool = Executors.newFixedThreadPool(SEARCH_THREADS);
        for (final Site site : targets) {
            pool.execute(() -> {
                if (gen != generation.get()) return;
                try {
                    Result r = SpiderManager.searchContent(site, keyword, true);
                    if (gen != generation.get()) return;
                    if (r != null && r.list != null && !r.list.isEmpty()) {
                        final List<Vod> vods = new ArrayList<>(r.list);
                        for (Vod v : vods) v.siteKey = site.getKey();
                        runOnUiThread(() -> {
                            if (gen == generation.get()) addResults(site, vods);
                        });
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "search fail on " + site.getKey() + ": " + t.getMessage());
                } finally {
                    if (pending.decrementAndGet() == 0) {
                        runOnUiThread(() -> {
                            if (gen == generation.get()) finishSearch(keyword);
                        });
                    }
                }
            });
        }
        startWatchdog(gen, keyword);
    }

    /** 兜底: 超过 WATCHDOG_MS 仍有站点没返回时, 直接收尾显示当前结果 */
    private void startWatchdog(final int gen, final String keyword) {
        if (watchdogTask != null) watchdog.removeCallbacks(watchdogTask);
        watchdogTask = () -> {
            if (gen == generation.get()) {
                Log.w(TAG, "watchdog timeout, finish with current results");
                finishSearch(keyword);
            }
        };
        watchdog.postDelayed(watchdogTask, WATCHDOG_MS);
    }

    /** 所有站点返回(或超时)后收尾 */
    private void finishSearch(String keyword) {
        if (watchdogTask != null) {
            watchdog.removeCallbacks(watchdogTask);
            watchdogTask = null;
        }
        updateProgress();
        if (all.isEmpty()) {
            showStatus("未找到「" + keyword + "」相关资源");
        } else {
            tvStatus.setVisibility(View.GONE);
        }
    }

    /** 待搜索站点: 指定搜索源(若已选)过滤 + 首页站点优先 */
    private List<Site> pickTargets() {
        Set<String> only = loadSelectedSites();
        List<Site> targets = new ArrayList<>();
        Site home = ApiConfig.get().getHomeSite();
        String homeKey = home == null ? null : home.getKey();

        // 首页站点放最前, 让用户最先看到结果
        if (home != null && isSearchableVodSite(home)
                && (only.isEmpty() || only.contains(home.getKey()))) {
            targets.add(home);
        }
        for (Site s : searchableSites()) {
            if (homeKey != null && homeKey.equals(s.getKey())) continue;
            if (!only.isEmpty() && !only.contains(s.getKey())) continue;
            targets.add(s);
        }
        return targets;
    }

    /** 全部可参与秒搜的影视站点(供搜索页后台预热爬虫复用) */
    public static List<Site> searchableSites() {
        List<Site> list = new ArrayList<>();
        for (Site s : ApiConfig.get().getSites()) {
            if (isSearchableVodSite(s)) list.add(s);
        }
        return list;
    }

    /** 读取"指定搜索源"(空 = 全部可搜索站点) */
    private Set<String> loadSelectedSites() {
        Set<String> set = new HashSet<>();
        try {
            String json = PrefUtils.get(PrefUtils.K_SEARCH_SITES, "");
            if (json != null && !json.isEmpty()) {
                List<String> list = new Gson().fromJson(json,
                        new TypeToken<List<String>>() {}.getType());
                if (list != null) set.addAll(list);
            }
        } catch (Throwable ignored) {}
        return set;
    }

    /** 主线程: 追加某来源的结果(增量回填, 不整表刷新) */
    private void addResults(Site site, List<Vod> vods) {
        String name = site.getName() == null ? site.getKey() : site.getName();
        hitSources.put(site.getKey(), name);
        for (Vod v : vods) {
            v.vod_remarks = "【" + name + "】" + (v.vod_remarks == null ? "" : v.vod_remarks);
            all.add(new Item(v, site.getKey()));
        }
        rebuildSourceChips();
        if (filterKey == null) {
            adapter.appendList(vods);
        } else {
            applyFilter();
        }
        updateProgress();
    }

    /** 顶部进度: 搜索(命中源数/总源数), 与影视仓同款 */
    private void updateProgress() {
        tvProgress.setText("搜索(" + hitSources.size() + "/" + totalSites + ")");
    }

    /** 顶部来源筛选条: 全部 + 各命中源 */
    private void rebuildSourceChips() {
        sourceContainer.removeAllViews();
        addChip("全部", null);
        for (Map.Entry<String, String> e : hitSources.entrySet()) {
            addChip(e.getValue(), e.getKey());
        }
    }

    private void addChip(String label, String key) {
        TextView tv = new TextView(this);
        boolean active = (key == null && filterKey == null) || (key != null && key.equals(filterKey));
        tv.setText(label);
        ScreenUtil.setTextSize(tv, R.dimen.sm_text_search_info);
        tv.setTextColor(getResources().getColor(active ? R.color.sm_primary : R.color.sm_text));
        tv.setBackgroundResource(R.drawable.bg_button_selector);
        tv.setPadding(28, 10, 28, 10);
        tv.setFocusable(true);
        tv.setClickable(true);
        tv.setSelected(active);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 14, 0);
        tv.setLayoutParams(lp);
        setupFocusAnim(tv);
        tv.setOnClickListener(v -> {
            filterKey = key;
            rebuildSourceChips();
            applyFilter();
        });
        sourceContainer.addView(tv);
    }

    /** 按当前来源筛选, 刷新结果网格 */
    private void applyFilter() {
        List<Vod> show = new ArrayList<>();
        for (Item it : all) {
            if (filterKey == null || filterKey.equals(it.sourceKey)) show.add(it.vod);
        }
        adapter.submitList(show);
    }

    /** 打开详情页(带上来源站点 key, 供详情/播放使用正确站点) */
    private void openDetail(Vod vod) {
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("vod", vod);
        i.putExtra("siteKey", vod.siteKey);
        startActivity(i);
    }

    private void showStatus(String text) {
        tvStatus.setText(text);
        tvStatus.setVisibility(View.VISIBLE);
    }

    /**
     * 是否参与快速搜索: 仅影视类站点.
     * 过滤推荐站(Douban)、个人网盘(需登录)、直播/听书/儿歌/教育/戏曲/体育/配置中心等.
     */
    private static boolean isSearchableVodSite(Site s) {
        if (s.getType() != 3) return false;
        if (s.getSearchable() != 1) return false;
        String api = s.getApi();
        if (api == null || api.isEmpty()) return false;
        String name = s.getName() == null ? "" : s.getName();
        // 推荐/配置/推送类
        if (api.contains("Douban") || api.contains("config") || api.contains("Push")
                || api.contains("AList")) return false;
        // 个人网盘(需扫码登录), 名称统一带"我的"
        if (name.contains("我的")) return false;
        // 非影视内容站
        String[] skip = {"听书", "儿歌", "教育", "课堂", "戏曲", "跳舞", "KTV",
                "舞曲", "音乐", "体育", "直播", "配置中心", "推送", "合集", "歌曲"};
        for (String k : skip) {
            if (name.contains(k)) return false;
        }
        return true;
    }

    /** 保存搜索历史(与搜索页共用 PrefUtils.K_SEARCH_HISTORY, 去重最新在前) */
    private void saveKeyword(String key) {
        List<String> list = new ArrayList<>();
        try {
            String json = PrefUtils.get(PrefUtils.K_SEARCH_HISTORY, "");
            if (!json.isEmpty()) {
                List<String> old = new Gson().fromJson(json,
                        new TypeToken<List<String>>() {}.getType());
                if (old != null) list.addAll(old);
            }
        } catch (Throwable ignored) {}
        list.remove(key);
        list.add(0, key);
        if (list.size() > 20) list = new ArrayList<>(list.subList(0, 20));
        PrefUtils.put(PrefUtils.K_SEARCH_HISTORY, new Gson().toJson(list));
    }

    private void setupFocusAnim(View view) {
        if (view == null) return;
        view.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start();
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
            }
        });
    }
}
