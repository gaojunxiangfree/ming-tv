package com.seanming.player.ui.live;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.seanming.player.R;
import com.seanming.player.api.ApiConfig;
import com.seanming.player.api.EpgManager;
import com.seanming.player.bean.EpgProgram;
import com.seanming.player.bean.LiveChannel;
import com.seanming.player.bean.LiveChannelGroup;
import com.seanming.player.ui.adapter.LiveChannelAdapter;
import com.seanming.player.ui.adapter.LiveGroupAdapter;
import com.seanming.player.ui.play.IjkKernel;
import com.seanming.player.ui.play.PlayerKernel;
import com.seanming.player.util.OkHttpUtil;
import com.seanming.player.util.PrefUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 直播页: 分组 Tab + 频道列表 + EPG 节目单, 完整遥控器适配.
 *
 * 遥控键位:
 *  - 列表获得焦点: 上下移动选台(移动即切台), 左右切分组, OK 收起面板
 *  - 面板收起(全屏): 上/下 切频道, 左/右 切分组, OK 呼出面板, 播放/暂停键暂停
 *  - 数字键: 直接跳台(如按 1 3 选 13 台)
 *  - 返回键: 先收面板, 再按退出
 */
public class LiveActivity extends AppCompatActivity {

    private static final String TAG = "LiveActivity";

    private PlayerView playerView;
    /** IJK 兜底播放时的渲染容器(与 playerView 同尺寸叠加, 默认隐藏) */
    private FrameLayout playerContainer;
    /** IJK 兜底内核: 一起看/斗鱼等 FLV 分组 Media3 不支持, 降级到 IJK(FFmpeg) 播放 */
    private IjkKernel ijkKernel;
    /** 当前频道是否已尝试过 IJK 兜底(每频道只降级一次, 避免循环) */
    private boolean ijkFallback = false;
    /** IJK 当前是否正在使用中 */
    private boolean ijkActive = false;
    private ExoPlayer player;
    private DefaultHttpDataSource.Factory httpFactory;
    /** 普通文件(HLS 之外的)与 HLS 两套 MediaSource 工厂, 由地址后缀自动选择 */
    private DefaultMediaSourceFactory defaultFactory;
    private HlsMediaSource.Factory hlsFactory;

    private LiveChannelAdapter channelAdapter;
    private LiveGroupAdapter groupAdapter;
    private RecyclerView rvChannels, rvGroups;

    private TextView tvChannelName, tvNow, tvNext;
    /** 数字键选台提示浮层 */
    private TextView tvNumberInput;
    private View channelPanel, infoPanel;

    private List<LiveChannelGroup> groups = new ArrayList<>();
    private List<LiveChannel> currentChannels;
    private int currentGroup = -1;
    private int currentChannel = -1;
    private boolean panelVisible = true;

    // ===== EPG 时间线 与 回看 / 多屏 =====
    private HorizontalScrollView epgTimelineScroll;
    private LinearLayout epgTimeline;
    private TextView btnLookback, btnMulti;
    private LinearLayout multiContainer;
    private PlayerView[] multiViews = new PlayerView[4];
    private ExoPlayer[] multiPlayers = new ExoPlayer[4];
    private boolean multiMode = false;
    /** 时间线中当前选中的节目(用于回看) */
    private EpgProgram selectedProg;
    /** 回看临时覆盖的频道名(回看结束后恢复直播) */
    private String lookbackChannelName;

    /** 直播源是异步加载的, 空列表时定时重试 */
    private int retryCount = 0;
    private static final int MAX_RETRY = 8;
    /** 连续播放失败自动跳台的次数上限, 防止源整体失效时无限跳台 */
    private int autoSkipCount = 0;
    private static final int MAX_AUTO_SKIP = 40;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            if (groups.isEmpty() && retryCount < MAX_RETRY) {
                retryCount++;
                loadGroups();
            }
        }
    };

    /** EPG 定时刷新: 每 30s 更新"正在播/下一节目" */
    private final Runnable epgTicker = new Runnable() {
        @Override
        public void run() {
            refreshEpg();
            handler.postDelayed(this, 30_000L);
        }
    };

    /** EPG 异步加载完成后的一次性补偿刷新 */
    private final Runnable epgRefreshOnce = this::refreshEpg;

    /** 数字键选台缓冲 */
    private final StringBuilder numberBuf = new StringBuilder();
    private final Runnable numberCommit = this::commitNumber;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live);

        playerView = findViewById(R.id.playerView);
        playerContainer = findViewById(R.id.playerContainer);
        rvChannels = findViewById(R.id.rvChannels);
        rvGroups = findViewById(R.id.rvGroups);
        tvChannelName = findViewById(R.id.tvChannelName);
        tvNow = findViewById(R.id.tvNow);
        tvNext = findViewById(R.id.tvNext);
        channelPanel = findViewById(R.id.channelPanel);
        infoPanel = findViewById(R.id.infoPanel);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        setupFocusAnim(findViewById(R.id.btnBack));
        tvNumberInput = findViewById(R.id.tvNumberInput);
        // 仅"节目信息区"点击查看完整节目单, 面板空白/EPG 时间线区域不再误触发
        findViewById(R.id.programInfo).setOnClickListener(v -> showEpgDialog());
        // 信息区自身吸收点击, 避免空白处穿透到播放器把选台面板收起
        infoPanel.setClickable(true);
        // 触屏: 点击画面呼出/收起选台面板(遥控器仍用 OK 键)
        // 不加 focusable, 避免触点先被用于取焦点而吞掉第一次点击
        playerView.setOnClickListener(v -> togglePanel());
        // 面板自身吸收点击: 点面板内边距/空白处不应穿透到播放器把面板收起
        channelPanel.setClickable(true);

        epgTimelineScroll = findViewById(R.id.epgTimelineScroll);
        epgTimeline = findViewById(R.id.epgTimeline);
        btnLookback = findViewById(R.id.btnLookback);
        btnMulti = findViewById(R.id.btnMulti);
        multiContainer = findViewById(R.id.multiContainer);
        multiViews[0] = findViewById(R.id.mv1);
        multiViews[1] = findViewById(R.id.mv2);
        multiViews[2] = findViewById(R.id.mv3);
        multiViews[3] = findViewById(R.id.mv4);
        setupFocusAnim(btnLookback);
        setupFocusAnim(btnMulti);
        // 回看: 打开当前频道今日已播节目选择
        btnLookback.setOnClickListener(v -> showLookbackDialog());
        // 多屏: 2x2 同看当前分组前 4 个频道
        btnMulti.setOnClickListener(v -> toggleMulti());

        // 时间线按钮为滚动容器, 不抢列表焦点, 焦点由回看/多屏按钮触发
        epgTimelineScroll.setFocusable(false);

        channelAdapter = new LiveChannelAdapter();
        rvChannels.setLayoutManager(new LinearLayoutManager(this));
        rvChannels.setAdapter(channelAdapter);
        // 影视仓行为: 列表项获得焦点即切台
        // (焦点落回当前频道时跳过重播, 避免呼出面板/回滚列表时无谓重连)
        channelAdapter.setOnFocus(this::onChannelFocused);
        channelAdapter.setOnClick(this::playChannel);

        groupAdapter = new LiveGroupAdapter();
        rvGroups.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvGroups.setAdapter(groupAdapter);
        groupAdapter.setOnClick(this::selectGroup);

        initPlayer();
        loadGroups();
        handler.post(epgTicker);

        // 首次进入把焦点交给频道列表, 遥控器可立即上下选台
        rvChannels.post(() -> rvChannels.requestFocus());
    }

    // ================= 数据加载 =================

    private void loadGroups() {
        groups = ApiConfig.get().getLiveGroups();
        if (groups.isEmpty()) {
            if (retryCount >= MAX_RETRY) {
                Toast.makeText(this, "暂无直播源, 请检查接口配置", Toast.LENGTH_LONG).show();
            } else {
                handler.postDelayed(retryRunnable, 1500L);
            }
            return;
        }
        List<String> names = new ArrayList<>();
        for (LiveChannelGroup g : groups) names.add(g.name);
        groupAdapter.submit(names);
        // 恢复上次停留的分组(播放频道记忆)
        int rememberGroup = PrefUtils.getInt(PrefUtils.K_LIVE_GROUP, 0);
        selectGroup(rememberGroup >= 0 && rememberGroup < groups.size() ? rememberGroup : 0);
    }

    private void selectGroup(int index) {
        if (index < 0 || index >= groups.size()) return;
        currentGroup = index;
        groupAdapter.setSelected(index);
        currentChannels = groups.get(index).channels;
        channelAdapter.submit(currentChannels);
        currentChannel = -1;
        // 记忆: 正在显示该分组时, 恢复上次在该分组停留的频道
        int rememberChannel = currentGroup == PrefUtils.getInt(PrefUtils.K_LIVE_GROUP, -1)
                ? PrefUtils.getInt(PrefUtils.K_LIVE_CHANNEL, 0) : 0;
        if (!currentChannels.isEmpty()) {
            int target = rememberChannel >= 0 && rememberChannel < currentChannels.size()
                    ? rememberChannel : 0;
            channelAdapter.setSelected(target);
            playChannel(currentChannels.get(target));
            scrollChannelTo(target);
        }
    }

    private void scrollChannelTo(int position) {
        if (position < 0) return;
        // RecyclerView 布局/滚动中不能同步操作; 两次 post 确保 adapter 已完成重绑
        rvChannels.post(() -> {
            rvChannels.scrollToPosition(position);
            rvChannels.post(() -> {
                RecyclerView.ViewHolder vh = rvChannels.findViewHolderForAdapterPosition(position);
                if (vh != null) vh.itemView.requestFocus();
                else rvChannels.requestFocus();
            });
        });
    }

    /** 选中项变更: RecyclerView 正在布局时必须延后, 否则抛 IllegalStateException */
    private void selectItemSafe(int idx) {
        if (rvChannels.isComputingLayout()) {
            rvChannels.post(() -> channelAdapter.setSelected(idx));
        } else {
            channelAdapter.setSelected(idx);
        }
    }

    /** 已知媒体后缀(这些交给 Media3 自行推断类型) */
    private static final java.util.regex.Pattern MEDIA_EXT =
            java.util.regex.Pattern.compile("(?i)\\.(m3u8?|mp4|flv|ts|mkv|webm|avi|mov)(\\?|$)");

    /**
     * 直播地址常写成 live.php?id=xx 这类没有媒体后缀的形式, Media3 会按普通文件解析,
     * 拿到 m3u8 内容后报 ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, 这里显式走 HLS.
     */
    private MediaSource liveMediaSource(String url) {
        MediaItem item = MediaItem.fromUri(url);
        return MEDIA_EXT.matcher(url).find()
                ? defaultFactory.createMediaSource(item)
                : hlsFactory.createMediaSource(item);
    }

    /** 设置直播源并起播(地址类型自动判断 HLS / 普通文件) */
    private void playMedia(String url) {
        player.setMediaSource(liveMediaSource(url));
        player.prepare();
        player.play();
    }

    private void playChannel(LiveChannel ch) {
        if (ch == null) return;
        // 切台: 退出上一频道的 IJK 兜底态, 回到 Exo 主链路重新尝试
        if (ijkActive) releaseIjk();
        ijkFallback = false;
        int idx = currentChannels != null ? currentChannels.indexOf(ch) : -1;
        if (idx >= 0) {
            currentChannel = idx;
            selectItemSafe(idx);
            // 记忆当前分组与频道, 供下次进入直播页恢复
            PrefUtils.putInt(PrefUtils.K_LIVE_GROUP, currentGroup);
            PrefUtils.putInt(PrefUtils.K_LIVE_CHANNEL, idx);
        }
        tvChannelName.setText(ch.name);
        String url = ch.currentUrl();
        refreshEpg();
        // EPG 是异步加载的, 8s 后补偿刷新一次
        handler.removeCallbacks(epgRefreshOnce);
        handler.postDelayed(epgRefreshOnce, 8000L);
        if (url.isEmpty()) {
            Toast.makeText(this, "该频道暂无信号", Toast.LENGTH_SHORT).show();
            return;
        }
        // 部分直播源需要特定 UA, 频道有声明则覆盖(错误 UA 会被服务端直接 404)
        String ua = ch.httpUserAgent != null && !ch.httpUserAgent.isEmpty()
                ? ch.httpUserAgent : OkHttpUtil.UA;
        Log.i(TAG, "play " + ch.name + " ua=" + ua + " url=" + url);
        Map<String, String> hdrs = new HashMap<>();
        hdrs.put("User-Agent", ua);
        httpFactory.setDefaultRequestProperties(hdrs);
        playMedia(url);
    }

    private void refreshEpg() {
        LiveChannel ch = currentChannelObj();
        if (ch == null) return;
        EpgManager epg = EpgManager.get();
        EpgProgram now = epg.current(ch.epgId, ch.name);
        EpgProgram next = epg.next(ch.epgId, ch.name);
        buildTimeline();  // 时间线自行处理未加载/空数据的兜底提示
        if (!epg.isLoaded()) {
            tvNow.setText("节目单加载中...");
            tvNext.setText("");
            return;
        }
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        tvNow.setText(now == null ? "暂无节目信息"
                : "正在播: " + now.title + "  " + hm.format(new Date(now.start))
                  + "-" + hm.format(new Date(now.stop)));
        tvNext.setText(next == null ? ""
                : "下一档: " + next.title + "  " + hm.format(new Date(next.start)));
    }

    // ================= EPG 时间线 =================

    /** 渲染底部 EPG 时间线横幅: 横向展示当日节目, 当前节目高亮, 已播节目可回看 */
    private void buildTimeline() {
        if (epgTimeline == null) return;
        LiveChannel ch = currentChannelObj();
        epgTimeline.removeAllViews();
        if (ch == null) return;
        if (!EpgManager.get().isLoaded()) {
            TextView t = new TextView(this);
            t.setTextColor(getResources().getColor(R.color.sm_text_dim));
            t.setTextSize(20);
            t.setText("EPG 加载中...");
            epgTimeline.addView(t);
            return;
        }
        List<EpgProgram> list = EpgManager.get().programs(ch.epgId, ch.name);
        if (list == null || list.isEmpty()) {
            TextView t = new TextView(this);
            t.setTextColor(getResources().getColor(R.color.sm_text_dim));
            t.setTextSize(20);
            t.setText("今日暂无节目(可回看源需频道支持 tvg-rec)");
            epgTimeline.addView(t);
            return;
        }
        long now = System.currentTimeMillis();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        final int[] indexNow = {-1};
        for (int i = 0; i < list.size(); i++) {
            final EpgProgram pg = list.get(i);
            if (pg.isPlaying(now)) indexNow[0] = i;
            boolean playing = pg.isPlaying(now);
            boolean past = pg.stop <= now;
            TextView seg = new TextView(this);
            seg.setPadding(dp(12), dp(6), dp(12), dp(6));
            seg.setTextSize(18);
            seg.setText(hm.format(new Date(pg.start)) + " " + pg.title);
            if (playing) {
                seg.setBackgroundColor(0xFF00BCD4);
                seg.setTextColor(0xFF000000);
            } else if (past) {
                seg.setBackgroundColor(0xFF334455);
                seg.setTextColor(0xFF90A4AE);
                // 已播节目点击 → 回看
                seg.setOnClickListener(v -> playLookback(pg, ch));
                seg.setOnFocusChangeListener((view, hasFocus) -> {
                    if (hasFocus) view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(100).start();
                    else view.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                });
            } else {
                seg.setBackgroundColor(0xFF263238);
                seg.setTextColor(0xFFB0BEC5);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            epgTimeline.addView(seg, lp);
        }
        // 滚动时间线让当前节目可见
        if (indexNow[0] >= 0) {
            epgTimelineScroll.post(() -> {
                View v = epgTimeline.getChildAt(indexNow[0]);
                if (v != null) epgTimelineScroll.smoothScrollTo(0, (int) v.getY());
            });
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LiveChannel currentChannelObj() {
        if (currentChannels == null || currentChannel < 0 || currentChannel >= currentChannels.size()) {
            return null;
        }
        return currentChannels.get(currentChannel);
    }

    /** 节目单弹窗: 展示当前频道今日剩余节目 */
    private void showEpgDialog() {
        LiveChannel ch = currentChannelObj();
        if (ch == null) return;
        List<EpgProgram> list = EpgManager.get().today(ch.epgId, ch.name);
        if (list.isEmpty()) {
            Toast.makeText(this, EpgManager.get().isLoaded() ? "该频道暂无节目单" : "节目单加载中...",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String[] items = new String[list.size()];
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.size(); i++) {
            EpgProgram pg = list.get(i);
            String prefix = pg.isPlaying(now) ? "▶ " : "   ";
            items[i] = prefix + hm.format(new Date(pg.start)) + "  " + pg.title;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(ch.name + " - 节目单")
                .setItems(items, null)
                .setPositiveButton("关闭", null)
                .show();
    }

    // ================= 回看 =================

    /** 回看弹窗: 列出当前频道今日已播节目, 选中即回看 */
    private void showLookbackDialog() {
        LiveChannel ch = currentChannelObj();
        if (ch == null) return;
        List<EpgProgram> list = EpgManager.get().programs(ch.epgId, ch.name);
        long now = System.currentTimeMillis();
        List<EpgProgram> past = new ArrayList<>();
        for (EpgProgram pg : list != null ? list : new ArrayList<EpgProgram>()) {
            if (pg.stop <= now) past.add(pg);
        }
        if (past.isEmpty()) {
            Toast.makeText(this, EpgManager.get().isLoaded()
                            ? "今日暂无已播节目可供回看" : "节目单加载中...",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String[] items = new String[past.size()];
        for (int i = 0; i < past.size(); i++) {
            EpgProgram pg = past.get(i);
            items[i] = hm.format(new Date(pg.start)) + "-" + hm.format(new Date(pg.stop)) + "  " + pg.title;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(ch.name + " - 回看")
                .setItems(items, (d, which) -> {
                    if (which < 0 || which >= past.size()) return;
                    playLookback(past.get(which), ch);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 播放历史节目: 优先用频道回看地址; 无 tvg-rec 时提示源不支持 */
    private void playLookback(EpgProgram pg, LiveChannel ch) {
        if (ch == null || pg == null) return;
        List<EpgProgram> past = EpgManager.get().programs(ch.epgId, ch.name);
        if (past != null) {
            int idx = past.indexOf(pg);
            if (idx < 0) return;
        }
        String url = buildLookbackUrl(ch, pg.start);
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, "该频道不支持回看(源未提供 tvg-rec 回看地址)",
                    Toast.LENGTH_LONG).show();
            return;
        }
        lookbackChannelName = ch.name;
        tvChannelName.setText(ch.name + " 【回看】");
        tvNow.setText("回看: " + pg.title);
        tvNext.setText("");
        // 回看走 Exo 主链路, 若当前处于 IJK 兜底态先退出
        if (ijkActive) releaseIjk();
        ijkFallback = false;
        Map<String, String> hdrs = new HashMap<>();
        hdrs.put("User-Agent", ch.httpUserAgent != null && !ch.httpUserAgent.isEmpty()
                ? ch.httpUserAgent : OkHttpUtil.UA);
        httpFactory.setDefaultRequestProperties(hdrs);
        playMedia(url);
    }

    /** 拼接回看地址: 优先 tvg-rec 模板(替换 {date}/{time}占位), 否则尝试频道地址推导 */
    private String buildLookbackUrl(LiveChannel ch, long startTime) {
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        String time = hm.format(new Date(startTime));
        String date = day.format(new Date(startTime));
        if (!ch.recUrls.isEmpty()) {
            String base = ch.recTemplate != null && !ch.recTemplate.isEmpty() ? ch.recTemplate : ch.recUrls.get(0);
            return base.replace("{date}", date).replace("{time}", time);
        }
        return "";
    }

    // ================= 多屏同看 =================

    /** 切换多屏(2x2 四路)与单屏 */
    private void toggleMulti() {
        if (multiMode) {
            exitMulti();
        } else {
            enterMulti();
        }
    }

    private void enterMulti() {
        if (currentChannels == null || currentChannels.isEmpty()) return;
        // 多屏统一使用 Exo, 若当前处于 IJK 兜底态先退出
        if (ijkActive) releaseIjk();
        multiContainer.setVisibility(View.VISIBLE);
        playerView.setVisibility(View.GONE);
        multiMode = true;
        hidePanel();
        for (int i = 0; i < 4; i++) {
            ExoPlayer p = new ExoPlayer.Builder(this)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(httpFactory))
                    .build();
            multiPlayers[i] = p;
            multiViews[i].setPlayer(p);
            multiViews[i].setUseController(false);
            multiViews[i].setVisibility(View.VISIBLE);
            if (i < currentChannels.size()) {
                LiveChannel ch = currentChannels.get(i);
                String url = ch.currentUrl();
                if (url != null && !url.isEmpty()) {
                    Map<String, String> hdrs = new HashMap<>();
                    hdrs.put("User-Agent", ch.httpUserAgent != null && !ch.httpUserAgent.isEmpty()
                            ? ch.httpUserAgent : OkHttpUtil.UA);
                    httpFactory.setDefaultRequestProperties(hdrs);
                    p.setMediaSource(liveMediaSource(url));
                    p.prepare();
                    p.setPlayWhenReady(true);
                } else {
                    multiViews[i].setVisibility(View.INVISIBLE);
                }
            } else {
                multiViews[i].setVisibility(View.INVISIBLE);
            }
        }
    }

    private void exitMulti() {
        multiMode = false;
        multiContainer.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        for (int i = 0; i < 4; i++) {
            if (multiPlayers[i] != null) {
                multiPlayers[i].release();
                multiPlayers[i] = null;
            }
            if (multiViews[i] != null) multiViews[i].setPlayer(null);
        }
        showPanel();
    }

    // ================= 播放器 =================

    private void initPlayer() {
        // 注意: 不要在这里 setUserAgent, 否则会覆盖频道声明的 http-user-agent
        // (部分直播源对错误 UA 直接返回 404, 例如 AptvPlayer-UA)
        httpFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true);
        defaultFactory = new DefaultMediaSourceFactory(httpFactory);
        hlsFactory = new HlsMediaSource.Factory(httpFactory);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(defaultFactory)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "play error: " + error.getErrorCodeName());
                // 一起看/斗鱼等分组为 FLV 流, Media3 不支持 FLV;
                // 先尝试 IJK(FFmpeg) 兜底, IJK 也失败才自动跳台
                if (!multiMode && !ijkFallback && tryIjkFallback()) return;
                autoSkip();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                // 出画面即认为该频道可用, 重置跳台计数
                if (state == Player.STATE_READY) autoSkipCount = 0;
            }
        });
        playerView.setPlayer(player);
        player.setPlayWhenReady(true);
        playerView.setUseController(false);
    }

    /**
     * 播放失败自动跳过: 先试同频道的备用地址, 再换下一个频道; 本分组跳完则切下一分组.
     * 公开直播源失效频道较多, 避免用户对着黑屏手动一个个试台.
     */
    private void autoSkip() {
        LiveChannel ch = currentChannelObj();
        if (ch != null && ch.urlIndex + 1 < ch.urls.size()) {
            ch.urlIndex++;
            playChannel(ch);
            return;
        }
        if (autoSkipCount >= MAX_AUTO_SKIP) {
            autoSkipCount = 0;
            Toast.makeText(this, "连续多个频道无法播放, 请换个分组试试", Toast.LENGTH_LONG).show();
            return;
        }
        autoSkipCount++;
        if (currentChannels != null && currentChannel + 1 < currentChannels.size()) {
            channelNext();
        } else {
            groupNext();
        }
    }

    private void togglePlayPause() {
        if (ijkActive) {
            if (ijkKernel == null) return;
            if (ijkKernel.isPlaying()) ijkKernel.pause();
            else ijkKernel.play();
            return;
        }
        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else player.play();
    }

    // ================= IJK 兜底(FLV 等 Media3 不支持的直播) =================

    /**
     * Exo 播放失败时降级 IJK. 每个频道只降级一次; 成功返回 true.
     */
    private boolean tryIjkFallback() {
        LiveChannel ch = currentChannelObj();
        if (ch == null) return false;
        String url = ch.currentUrl();
        if (url == null || url.isEmpty()) return false;
        ijkFallback = true;
        Log.i(TAG, "Exo 不支持, 降级 IJK: " + ch.name + " url=" + url);
        startIjk(url, ch);
        return true;
    }

    private void startIjk(String url, LiveChannel ch) {
        // Exo 让出画面与声音
        if (player != null) player.stop();
        if (ijkKernel == null) {
            ijkKernel = new IjkKernel(true);
            ijkKernel.setListener(new PlayerKernel.Listener() {
                @Override
                public void onStateChanged(int state) {
                    // 出画面即认为可用, 重置跳台计数
                    if (state == PlayerKernel.STATE_READY) autoSkipCount = 0;
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {}

                @Override
                public void onError(String message) {
                    Log.w(TAG, "IJK 失败, 转自动跳台: " + message);
                    releaseIjk();
                    autoSkip();
                }

                @Override
                public void onQualitiesChanged(List<PlayerKernel.Quality> qualities) {}

                @Override
                public void onVideoSizeChanged(int width, int height) {}
            });
            ijkKernel.init(this, playerContainer);
        }
        playerView.setVisibility(View.GONE);
        playerContainer.setVisibility(View.VISIBLE);
        ijkActive = true;
        // 部分直播源需要特定 UA, 与 Exo 链路保持一致
        String ua = ch.httpUserAgent != null && !ch.httpUserAgent.isEmpty()
                ? ch.httpUserAgent : OkHttpUtil.UA;
        Map<String, String> hdrs = new HashMap<>();
        hdrs.put("User-Agent", ua);
        ijkKernel.setDataSource(url, hdrs, 0);
        ijkKernel.play();
    }

    /** 释放 IJK 并把画面交还 Exo 的 playerView */
    private void releaseIjk() {
        if (ijkKernel != null) {
            ijkKernel.release();
            ijkKernel = null;
        }
        ijkActive = false;
        if (playerContainer != null) playerContainer.setVisibility(View.GONE);
        if (playerView != null) playerView.setVisibility(View.VISIBLE);
    }

    // ================= 切台/切组 =================

    private void channelPrev() { stepChannel(-1); }

    private void channelNext() { stepChannel(1); }

    private void stepChannel(int delta) {
        if (currentChannels == null || currentChannels.isEmpty()) return;
        int size = currentChannels.size();
        int next = currentChannel < 0 ? 0 : (currentChannel + delta + size) % size;
        playChannel(currentChannels.get(next));
        scrollChannelTo(next);
    }

    private void groupPrev() { stepGroup(-1); }

    private void groupNext() { stepGroup(1); }

    private void stepGroup(int delta) {
        if (groups.isEmpty()) return;
        int next = (currentGroup + delta + groups.size()) % groups.size();
        selectGroup(next);
    }

    /** 数字键直接选台: 频道号匹配(或按序号) */
    private void commitNumber() {
        hideNumberInput();
        if (numberBuf.length() == 0 || currentChannels == null) return;
        String num = numberBuf.toString();
        numberBuf.setLength(0);
        for (int i = 0; i < currentChannels.size(); i++) {
            LiveChannel c = currentChannels.get(i);
            if (num.equals(String.valueOf(c.number)) || num.equals(c.name)) {
                channelAdapter.setSelected(i);
                currentChannel = i;
                playChannel(c);
                scrollChannelTo(i);
                return;
            }
        }
        // 未命中: 给出提示, 避免静默无反馈
        Toast.makeText(this, "未找到频道号 " + num + ", 可换个分组再试", Toast.LENGTH_SHORT).show();
    }

    /** 数字键输入提示浮层 */
    private void showNumberInput(String digits) {
        if (tvNumberInput == null) return;
        tvNumberInput.setText("选台 " + digits);
        tvNumberInput.setVisibility(View.VISIBLE);
    }

    private void hideNumberInput() {
        if (tvNumberInput != null) tvNumberInput.setVisibility(View.GONE);
    }

    // ================= 面板显隐 =================

    /** 点击画面: 呼出/收起选台面板(触屏设备); 多屏模式下不弹选台 */
    private void togglePanel() {
        if (multiMode) return;
        if (panelVisible) {
            hidePanel();
        } else {
            showPanel();
        }
    }

    /** 列表项获得焦点即切台; 焦点落回当前频道时跳过, 避免重复起播/重连 */
    private void onChannelFocused(LiveChannel ch) {
        if (ch == null) return;
        int idx = currentChannels != null ? currentChannels.indexOf(ch) : -1;
        if (idx >= 0 && idx == currentChannel) return;
        playChannel(ch);
    }

    private void showPanel() {
        panelVisible = true;
        // 先取消动画再设可见, 避免上一次隐藏动画的收尾动作把面板又置为不可见
        channelPanel.animate().cancel();
        infoPanel.animate().cancel();
        channelPanel.setVisibility(View.VISIBLE);
        infoPanel.setVisibility(View.VISIBLE);
        channelPanel.setAlpha(0f);
        infoPanel.setAlpha(0f);
        channelPanel.animate().alpha(1f).setDuration(150).start();
        infoPanel.animate().alpha(1f).setDuration(150).start();
        // 焦点落到"当前频道"而不是列表首项, 避免呼出面板时跳到第一个台
        if (currentChannel >= 0) {
            scrollChannelTo(currentChannel);
        } else {
            rvChannels.post(() -> rvChannels.requestFocus());
        }
    }

    private void hidePanel() {
        panelVisible = false;
        // 立即置为不可见(不使用动画收尾回调), 保证显隐状态确定
        channelPanel.animate().cancel();
        infoPanel.animate().cancel();
        channelPanel.setVisibility(View.INVISIBLE);
        infoPanel.setVisibility(View.INVISIBLE);
        channelPanel.setAlpha(0f);
        infoPanel.setAlpha(0f);
    }

    /** 当前焦点是否在右侧面板内部 */
    private boolean focusInPanel() {
        View f = getCurrentFocus();
        return f != null && isDescendant(channelPanel, f);
    }

    private boolean isDescendant(View parent, View child) {
        View cur = child;
        while (cur != null) {
            if (cur == parent) return true;
            android.view.ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    // ================= 遥控按键 =================

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        int code = event.getKeyCode();

        // 数字键选台
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            numberBuf.append((char) ('0' + (code - KeyEvent.KEYCODE_0)));
            // 边输边提示, 避免用户以为按键没生效
            showNumberInput(numberBuf.toString());
            handler.removeCallbacks(numberCommit);
            handler.postDelayed(numberCommit, 1200L);
            return true;
        }

        // 菜单键: 打开当前频道节目单
        if (code == KeyEvent.KEYCODE_MENU) {
            showEpgDialog();
            return true;
        }

        // 面板可见且焦点在面板内: 上下交给系统做列表导航(焦点即切台),
        // 左右统一切换分组, OK 收起面板
        if (panelVisible && focusInPanel()) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                hidePanel();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                groupPrev();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                groupNext();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                channelPrev();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                channelNext();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                groupPrev();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                groupNext();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                showPanel();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                togglePlayPause();
                return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** 返回键: 先收面板, 再退出 */
    @Override
    public void onBackPressed() {
        if (panelVisible) {
            hidePanel();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 设置页改过直播源(增删/跟随接口自带)后回到直播页: 重新拉取频道列表
        if (ApiConfig.get().consumeLiveReloadPending()) {
            retryCount = 0;
            groups = ApiConfig.get().getLiveGroups();
            if (groups != null && !groups.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (LiveChannelGroup g : groups) names.add(g.name);
                groupAdapter.submit(names);
                selectGroup(currentGroup >= 0 && currentGroup < groups.size() ? currentGroup : 0);
            } else {
                loadGroups();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 收起数字键提示并清空输入缓冲, 避免残留
        hideNumberInput();
        numberBuf.setLength(0);
        if (player != null) player.pause();
        if (ijkActive && ijkKernel != null) ijkKernel.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (ijkKernel != null) {
            ijkKernel.release();
            ijkKernel = null;
        }
        if (player != null) player.release();
        for (int i = 0; i < 4; i++) {
            if (multiPlayers[i] != null) multiPlayers[i].release();
            multiPlayers[i] = null;
        }
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