package com.seanming.player.ui.detail;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.seanming.player.R;
import com.seanming.player.api.ApiConfig;
import com.seanming.player.bean.Result;
import com.seanming.player.bean.Site;
import com.seanming.player.bean.Vod;
import com.seanming.player.data.AppDatabase;
import com.seanming.player.data.Favorite;
import com.seanming.player.data.HistoryRecord;
import com.seanming.player.spider.SpiderManager;
import com.seanming.player.ui.adapter.EpisodeAdapter;
import com.seanming.player.ui.search.FastSearchActivity;
import com.seanming.player.ui.play.ExoKernel;
import com.seanming.player.ui.play.PlayActivity;
import com.seanming.player.ui.play.PlayerKernel;
import com.seanming.player.ui.play.PlayUrlResolver;
import com.seanming.player.util.ThreadUtils;
import com.seanming.player.util.ScreenUtil;
import com.seanming.player.util.ImageUtil;
import com.seanming.player.util.PrefUtils;
import com.seanming.player.util.TextScaleUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 详情页: 海报 + 简介 + 线路/选集 */
public class DetailActivity extends AppCompatActivity {

    private Vod vod;
    private Site site;
    private TextView tvName, tvMeta, tvContent, btnFavorite;
    private TextView btnReverse, btnContentToggle;
    private ImageView ivPoster;
    private LinearLayout episodeContainer;
    private boolean favorited;
    private String currentFlag;
    private List<Vod.Episode> currentEpisodes = new ArrayList<>();
    /** 当前选集网格视图(切换线路/倒序时先移除旧网格, 避免叠加) */
    private RecyclerView episodeGrid;
    /** 选集倒序显示(影视仓"倒序"按钮) */
    private boolean episodesReversed = false;
    /** 简介是否展开(影视仓"内容简介"折叠开关) */
    private boolean contentExpanded = false;
    /** 是否从历史记录进入, 用于加载完剧集后自动续播 */
    private boolean resumeFromHistory;

    // ---- 小窗预览 ----
    private android.widget.FrameLayout previewFrame;
    private TextView tvPreviewHint;
    private PlayerKernel previewKernel;
    private String previewUrl;
    private Map<String, String> previewHeaders;
    /** 点小窗进全屏前暂停预览, 返回后从这里续播 */
    private long previewPausePos;
    /** 全屏播放期间预览是否曾播放(用于返回后自动续播) */
    private boolean previewStarted;
    /** 当前是否处于"全屏播放中"(从预览点入), 返回后恢复小窗 */
    private boolean inFullscreen; 

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        vod = (Vod) getIntent().getSerializableExtra("vod");
        if (vod == null) { finish(); return; }
        // siteKey 是 transient 字段, Intent 序列化 vod 后会丢失,
        // 优先使用单独传递的 siteKey extra(历史记录/跨站搜索都会传)
        String siteKey = getIntent().getStringExtra("siteKey");
        site = findSite(siteKey != null ? siteKey : vod.siteKey);
        // 恢复 vod.siteKey, 供历史记录查找/保存使用
        vod.siteKey = site.getKey();
        resumeFromHistory = getIntent().getBooleanExtra("resume", false);

        tvName = findViewById(R.id.tvName);
        tvMeta = findViewById(R.id.tvMeta);
        tvContent = findViewById(R.id.tvContent);
        ivPoster = findViewById(R.id.ivPoster);
        btnFavorite = findViewById(R.id.btnFavorite);
        btnReverse = findViewById(R.id.btnReverse);
        btnContentToggle = findViewById(R.id.btnContentToggle);
        episodeContainer = findViewById(R.id.episodeContainer);
        previewFrame = findViewById(R.id.previewFrame);
        tvPreviewHint = findViewById(R.id.tvPreviewHint);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPlay).setOnClickListener(v -> play(0));
        btnFavorite.setOnClickListener(v -> toggleFavorite());
        // 影视仓 4 胶囊按钮: 快速搜索 / 倒序 / 加入收藏 / 内容简介
        findViewById(R.id.btnQuickSearch).setOnClickListener(v -> quickSearch());
        btnReverse.setOnClickListener(v -> toggleReverse());
        btnContentToggle.setOnClickListener(v -> toggleContent());
        // 焦点缩放动画
        setupFocusAnim(findViewById(R.id.btnBack));
        setupFocusAnim(findViewById(R.id.btnPlay));
        setupFocusAnim(btnFavorite);
        setupFocusAnim(findViewById(R.id.btnQuickSearch));
        setupFocusAnim(btnReverse);
        setupFocusAnim(btnContentToggle);
        setupFocusAnim(previewFrame);

        // 简介小窗预览: 点击放大全屏播放, 返回后回到小窗续播
        previewFrame.setOnClickListener(v -> openFullscreenFromPreview());

        // 动态文字大小(设置页"文字大小"): 应用到标题/简介
        applyTextScale();

        bindBasic();
        loadDetail();
    }

    /** 动态文字大小: 按全局缩放系数应用到本页关键文字 */
    private void applyTextScale() {
        TextScaleUtil.apply(tvName, 36);
        TextScaleUtil.apply(tvMeta, 24);
        TextScaleUtil.apply(tvContent, 24);
        TextScaleUtil.apply(tvPreviewHint, 24);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开详情页(如进入全屏播放): 暂停小窗并记录位置
        if (previewKernel != null && previewStarted) {
            previewPausePos = previewKernel.getPosition();
            previewKernel.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从全屏返回: 小窗自动续播(优先从全屏结束位置, 否则用小窗暂停时位置)
        if (inFullscreen && previewKernel != null) {
            inFullscreen = false;
            long resume = (PlayActivity.lastPlayPosition > 0) ? PlayActivity.lastPlayPosition : previewPausePos;
            if (resume > 0) previewKernel.seekTo(resume);
            if (previewStarted) previewKernel.play();
        }
    }

    @Override
    protected void onDestroy() {
        if (previewKernel != null) {
            previewKernel.release();
            previewKernel = null;
        }
        super.onDestroy();
    }

    /** 点小窗 → 全屏播放(暂停小窗, 打开全屏页) */
    private void openFullscreenFromPreview() {
        if (currentEpisodes.isEmpty() && previewUrl == null) {
            Toast.makeText(this, "暂无播放源", Toast.LENGTH_SHORT).show();
            return;
        }
        int idx = previewIndex();
        if (previewKernel != null && previewStarted) {
            previewPausePos = previewKernel.getPosition();
        }
        inFullscreen = true;
        PlayActivity.lastPlayPosition = 0; // 重置, 返回时以实际全屏结束位置为准
        play(idx);
    }

    private int previewIndex() { return 0; }

    /** 在小窗中播放指定集(自动取第一集, 由播放解析完成回调) */
    private void startPreview(final int index) {
        if (previewFrame == null || currentEpisodes.isEmpty()) return;
        final Vod.Episode ep = currentEpisodes.get(Math.max(0, index));
        final String flag = currentFlag;
        // 需在主线/IO 解析真实播放地址(复用全屏同一套解析逻辑)
        ThreadUtils.io(() -> {
            PlayUrlResolver.Resolved r = PlayUrlResolver.resolve(site, flag, ep.url, null);
            if (r == null || r.url == null || r.url.isEmpty()) {
                // 解析失败: 显示点击提示(默认是小窗自动播放, 无文字)
                ThreadUtils.main(() -> {
                    if (previewFrame == null || isFinishing()) return;
                    tvPreviewHint.setVisibility(View.VISIBLE);
                });
                return;
            }
            // P2P/网盘 无法直接小窗解码, 跳过自动预览
            String u = r.url;
            if (u.startsWith("magnet:") || u.startsWith("thunder:") || u.startsWith("ed2k://")) return;
            final String fUrl = u;
            final Map<String, String> fHeaders = r.headers;
            previewUrl = fUrl;
            previewHeaders = fHeaders;
            ThreadUtils.main(() -> {
                if (previewFrame == null || isFinishing()) return;
                tvPreviewHint.setVisibility(View.GONE);
                if (previewKernel != null) {
                    previewKernel.release();
                    previewKernel = null;
                }
                previewKernel = new ExoKernel();
                previewKernel.init(DetailActivity.this, previewFrame);
                previewKernel.setListener(new PlayerKernel.Listener() {
                    @Override public void onStateChanged(int state) {}
                    @Override public void onIsPlayingChanged(boolean p) {
                        if (p) previewStarted = true;
                    }
                    @Override public void onError(String message) {}
                    @Override public void onQualitiesChanged(List<PlayerKernel.Quality> q) {}
                    @Override public void onVideoSizeChanged(int w, int h) {}
                });
                previewKernel.setDataSource(fUrl, fHeaders, 0);
                previewKernel.play();
                tvPreviewHint.setVisibility(View.GONE);
            });
        });
    }

    /** 切换线路/选集后刷新小窗预览 */
    private void refreshPreview() {
        if (previewKernel != null) {
            previewKernel.release();
            previewKernel = null;
        }
        previewStarted = false;
        previewPausePos = 0;
        previewUrl = null;
        // 默认小窗自动播放(无文字); 解析失败时由 startPreview 自行提示
        tvPreviewHint.setVisibility(View.GONE);
        if (!currentEpisodes.isEmpty()) startPreview(0);
    }

    private Site findSite(String key) {
        for (Site s : ApiConfig.get().getSites()) {
            if (s.getKey().equals(key)) return s;
        }
        return ApiConfig.get().getHomeSite();
    }

    private void bindBasic() {
        tvName.setText(vod.vod_name);
        StringBuilder meta = new StringBuilder();
        if (vod.vod_year != null && !vod.vod_year.isEmpty()) meta.append(vod.vod_year).append("  ");
        if (vod.vod_area != null && !vod.vod_area.isEmpty()) meta.append(vod.vod_area).append("  ");
        if (vod.vod_director != null && !vod.vod_director.isEmpty()) meta.append("导演: ").append(vod.vod_director);
        tvMeta.setText(meta.toString());
        tvContent.setText(vod.vod_content == null ? "" : vod.vod_content.trim());
        ImageUtil.loadPoster(ivPoster, vod.vod_pic);
        refreshFavorite();
    }

    private void loadDetail() {
        if (vod.playFromParsed) { renderEpisodes(); return; }
        ThreadUtils.io(() -> {
            List<String> ids = new ArrayList<>();
            ids.add(vod.vod_id);
            Result r = SpiderManager.detailContent(site, ids);

            // 跨站搜索回退: 当前站点(如 Douban 推荐站)不支持详情时,
            // 用视频名在其他视频站点搜索,找到后加载该站详情.
            // vod_id 以 "msearch:" 开头表示需要多站搜索.
            if (r.hasError() || r.list == null || r.list.isEmpty()) {
                android.util.Log.i("DetailActivity", "detailContent empty, cross-site search: " + vod.vod_name);
                r = searchAndDetail(vod.vod_name);
            }

            final Result fr = r;
            ThreadUtils.main(() -> {
                if (fr.hasError() || fr.list == null || fr.list.isEmpty()) {
                    Toast.makeText(this, "暂无可用片源", Toast.LENGTH_SHORT).show();
                    return;
                }
                Vod full = fr.list.get(0);
                vod.vod_content = full.vod_content;
                vod.vod_play_from = full.vod_play_from;
                vod.vod_play_url = full.vod_play_url;
                vod.parsePlayFrom();
                android.util.Log.i("DetailActivity", "parsePlayFrom: flags=" + vod.playFlags.size()
                        + " from=" + (vod.vod_play_from == null ? "null" : vod.vod_play_from.length() + " chars")
                        + " url=" + (vod.vod_play_url == null ? "null" : vod.vod_play_url.length() + " chars"));
                for (Map.Entry<String, List<Vod.Episode>> e : vod.playFlags.entrySet()) {
                    android.util.Log.i("DetailActivity", "  flag=" + e.getKey() + " eps=" + e.getValue().size());
                }
                bindBasic();
                renderEpisodes();
            });
        });
    }

    /**
     * 跨站搜索: 用关键词在各视频站点搜索, 找到后加载该站详情.
     * 跳过推荐站(Douban)、直播站、听书/儿歌/教育等非影视站.
     */
    private Result searchAndDetail(String keyword) {
        if (keyword == null || keyword.isEmpty()) return Result.error("empty keyword");
        int tried = 0;
        for (Site s : ApiConfig.get().getSites()) {
            if (s.getType() != 3) continue;
            if (s.getKey().equals(site.getKey())) continue;
            String api = s.getApi();
            if (api == null) continue;
            // 跳过推荐站、直播站、非影视站
            if (api.contains("Douban") || api.contains("Live") || api.contains("HuYa")
                    || api.contains("DouYu") || api.contains("BiLiLive") || api.contains("Push"))
                continue;
            if (api.contains("tingshu") || api.contains("erge") || api.contains("baobao")
                    || api.contains("beiwa") || api.contains("tuxiaobei") || api.contains("Iktv")
                    || api.contains("Lunhui") || api.contains("tangdou") || api.contains("liyuan")
                    || api.contains("Kanqiu") || api.contains("GZsport") || api.contains("diy")
                    || api.contains("AList") || api.contains("config") || api.contains("Notice")
                    || api.contains("Market") || api.contains("Local") || api.contains("Quark")
                    || api.contains("Drag") || api.contains("115") || api.contains("189")
                    || api.contains("123") || api.contains("ucpan") || api.contains("ydpan")
                    || api.contains("XunLei") || api.contains("BaiduPan") || api.contains("Woquark"))
                continue;
            if (++tried > 12) break; // 限制搜索站点数, 避免太慢
            try {
                android.util.Log.i("DetailActivity", "search on " + s.getKey() + " for: " + keyword);
                Result sr = SpiderManager.searchContent(s, keyword, true);
                if (sr.list == null || sr.list.isEmpty()) continue;
                Vod found = sr.list.get(0);
                android.util.Log.i("DetailActivity", "found on " + s.getKey() + " id=" + found.vod_id);
                List<String> dids = new ArrayList<>();
                dids.add(found.vod_id);
                Result dr = SpiderManager.detailContent(s, dids);
                if (!dr.hasError() && dr.list != null && !dr.list.isEmpty()) {
                    site = s;
                    vod.siteKey = s.getKey();
                    return dr;
                }
            } catch (Throwable t) {
                android.util.Log.w("DetailActivity", "search fail on " + s.getKey() + ": " + t.getMessage());
            }
        }
        return Result.error("no source found");
    }

    private void renderEpisodes() {
        episodeContainer.removeAllViews();
        // 遥控器初始焦点落到"播放"按钮, 方便直接确定键起播.
        // 放在最前面: 即使该片无线路(playFlags 为空), ScrollView 也会自动
        // 滚动让"播放"按钮可见, 避免遥控器焦点停在"返回"、找不到播放入口.
        findViewById(R.id.btnPlay).post(() -> findViewById(R.id.btnPlay).requestFocus());
        if (vod.playFlags.isEmpty()) return;
        // 若从历史记录进入, 优先选择上次观看的线路
        String resumeFlag = null;
        int resumeIndex = 0;
        if (resumeFromHistory) {
            HistoryRecord h = AppDatabase.get(this).historyDao().find(vod.siteKey + "|" + vod.vod_id);
            if (h != null && vod.playFlags.containsKey(h.flag)) {
                resumeFlag = h.flag;
                if (h.episodeIndex >= 0 && h.episodeIndex < vod.playFlags.get(h.flag).size()) {
                    resumeIndex = h.episodeIndex;
                }
            }
        }
        if (resumeFlag != null) {
            currentFlag = resumeFlag;
            currentEpisodes = vod.playFlags.get(resumeFlag);
        } else {
            for (Map.Entry<String, List<Vod.Episode>> entry : vod.playFlags.entrySet()) {
                currentFlag = entry.getKey();
                currentEpisodes = entry.getValue();
                break;
            }
        }
        // 倒序: 反转选集顺序, 续播/预览下标同步映射到反转后的列表
        if (episodesReversed && currentEpisodes != null && currentEpisodes.size() > 1) {
            resumeIndex = currentEpisodes.size() - 1 - resumeIndex;
            currentEpisodes = new ArrayList<>(currentEpisodes);
            java.util.Collections.reverse(currentEpisodes);
        }
        buildFlagTabs();
        buildEpisodeGrid(currentEpisodes);
        // 小窗预览: 自动播放第一集(或历史续播集)
        startPreview(resumeFromHistory ? resumeIndex : 0);
        // 从历史记录进入时, 自动续播到上次的集
        if (resumeFromHistory && resumeFlag != null) {
            play(resumeIndex);
        }
    }

    private void buildFlagTabs() {
        if (vod.playFlags.size() <= 1) return;
        // 线路选择行放入横向滚动容器: 賤片源等有 12+ 条线路,
        // 若用普通 LinearLayout 会被父宽挤压导致文字换行、按钮被撑高.
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setFocusable(false);
        hsv.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String flag : vod.playFlags.keySet()) {
            TextView tv = new TextView(this);
            tv.setText(flag);
            TextScaleUtil.apply(tv, 24);
            tv.setTextColor(getResources().getColor(R.color.sm_text));
            tv.setBackgroundResource(R.drawable.bg_button_selector);
            tv.setPadding(30, 12, 30, 12);
            tv.setSingleLine(true);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setFocusable(true);
            tv.setClickable(true);
            tv.setSelected(flag.equals(currentFlag));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 16, 0);
            tv.setLayoutParams(lp);
            // 焦点缩放动画
            tv.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus) {
                    view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start();
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                }
            });
            tv.setOnClickListener(v -> {
                currentFlag = flag;
                currentEpisodes = vod.playFlags.get(flag);
                if (episodesReversed && currentEpisodes != null && currentEpisodes.size() > 1) {
                    currentEpisodes = new ArrayList<>(currentEpisodes);
                    java.util.Collections.reverse(currentEpisodes);
                }
                // 更新选中状态
                for (int i = 0; i < row.getChildCount(); i++) {
                    View child = row.getChildAt(i);
                    if (child instanceof TextView) {
                        ((TextView) child).setSelected(((TextView) child).getText().toString().equals(flag));
                    }
                }
                buildEpisodeGrid(currentEpisodes);
                // 切换线路后刷新小窗预览
                refreshPreview();
            });
            row.addView(tv);
        }
        hsv.addView(row);
        episodeContainer.addView(hsv);
    }

    private void buildEpisodeGrid(List<Vod.Episode> episodes) {
        RecyclerView rv = new RecyclerView(this);
        // 切换线路/倒序时先移除旧的选集网格, 避免在容器中叠加
        if (episodeGrid != null) {
            episodeContainer.removeView(episodeGrid);
            episodeGrid = null;
        }
        // 播放列表布局: 默认 "grid" 平铺网格(每集平铺展示, 遥控器好选);
        // 仍保留设置页可切回 "column" 垂直列表
        String layout = PrefUtils.get(PrefUtils.K_PLAYLIST_LAYOUT, "grid");
        if ("column".equals(layout)) {
            rv.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        } else {
            rv.setLayoutManager(new GridLayoutManager(this, ScreenUtil.episodeColumns(this)));
        }
        // 遥控器: 选集区按"上"回到播放按钮, 避免焦点在 ScrollView 内丢失
        rv.setFocusable(true);
        rv.setNextFocusUpId(R.id.btnPlay);
        EpisodeAdapter adapter = new EpisodeAdapter();
        rv.setAdapter(adapter);
        adapter.submit(episodes, -1);
        adapter.setOnClick((index, ep) -> play(index));
        episodeGrid = rv;
        episodeContainer.addView(rv);
    }

    private void play(int index) {
        if (currentEpisodes.isEmpty()) {
            Toast.makeText(this, "暂无播放源", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, PlayActivity.class);
        i.putExtra("vod", vod);
        // siteKey 是 transient 字段, Intent 序列化 vod 时会丢失,
        // 单独传递确保 PlayActivity 能找到正确的站点(跨站搜索后可能已切换站点)
        i.putExtra("siteKey", site.getKey());
        i.putExtra("flag", currentFlag);
        i.putExtra("index", index);
        // 从小窗预览进入全屏时, 带上小窗已播位置, 全屏从此续播
        if (previewStarted && previewPausePos > 0) {
            i.putExtra("startPos", previewPausePos);
        }
        startActivity(i);
    }

    private void refreshFavorite() {
        favorited = AppDatabase.get(this).favoriteDao().find(favId()) != null;
        btnFavorite.setText(favorited ? R.string.detail_unfavorite : R.string.detail_favorite);
    }

    private void toggleFavorite() {
        Favorite f = new Favorite();
        f.id = favId();
        f.siteKey = vod.siteKey;
        f.vodId = vod.vod_id;
        f.vodName = vod.vod_name;
        f.vodPic = vod.vod_pic;
        f.vodRemarks = vod.vod_remarks;
        f.createTime = System.currentTimeMillis();
        if (favorited) {
            AppDatabase.get(this).favoriteDao().remove(favId());
        } else {
            AppDatabase.get(this).favoriteDao().add(f);
        }
        refreshFavorite();
    }

    private String favId() { return vod.siteKey + "|" + vod.vod_id; }

    /** 快速搜索: 用片名聚合搜索所有影视站点(对齐影视仓 FastSearchActivity) */
    private void quickSearch() {
        Intent i = new Intent(this, FastSearchActivity.class);
        i.putExtra(FastSearchActivity.EXTRA_KEYWORD, vod.vod_name);
        startActivity(i);
    }

    /** 倒序: 反转当前线路选集顺序, 再点恢复正序 */
    private void toggleReverse() {
        episodesReversed = !episodesReversed;
        btnReverse.setText(episodesReversed ? "正序" : "倒序");
        if (currentEpisodes != null && currentEpisodes.size() > 1) {
            currentEpisodes = new ArrayList<>(currentEpisodes);
            java.util.Collections.reverse(currentEpisodes);
        }
        buildEpisodeGrid(currentEpisodes);
        // 选集顺序变化, 小窗从头(倒序后为最后一集)预览
        refreshPreview();
    }

    /** 内容简介: 展开/收起简介文字(影视仓折叠开关) */
    private void toggleContent() {
        contentExpanded = !contentExpanded;
        tvContent.setMaxLines(contentExpanded ? 200 : 5);
        btnContentToggle.setText(contentExpanded ? "收起简介" : "内容简介");
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
