package com.seanming.player.ui.play;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.seanming.player.R;
import com.seanming.player.api.ApiConfig;
import com.seanming.player.bean.Parse;
import com.seanming.player.bean.Result;
import com.seanming.player.bean.Site;
import com.seanming.player.bean.Vod;
import com.seanming.player.data.AppDatabase;
import com.seanming.player.data.HistoryRecord;
import com.seanming.player.spider.SpiderManager;
import com.seanming.player.ui.adapter.EpisodeAdapter;
import com.seanming.player.util.OkHttpUtil;
import com.seanming.player.util.PrefUtils;
import com.seanming.player.util.ScreenUtil;
import com.seanming.player.util.ThreadUtils;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 播放页: 多内核 (Exo/IJK) + 选集 */
public class PlayActivity extends AppCompatActivity {

    /** 供详情页小窗预览使用: 全屏播放结束时的最后位置(ms).
     *  用户在全屏里快进/续播后返回, 详情页小窗由此位置续播. */
    public static long lastPlayPosition = 0;

    private FrameLayout playerContainer;
    private PlayerKernel kernel;
    /** 当前内核类型(exo/ijk), 用于失败自动回退 */
    private String currentKernel = PrefUtils.PLAYER_EXO;
    /** 最近一次播放信息, 用于内核失败时切换内核重试 */
    private String pendingUrl;
    private Map<String, String> pendingHeaders;
    /** 网盘直连等场景由外部传入的请求头(如 WebDAV Basic 认证) */
    private Map<String, String> directHeaders;
    private long pendingPos;
    private boolean kernelFellBack = false;
    private Vod vod;
    private Site site;
    private String flag;
    private int index;
    private List<Vod.Episode> episodes;
    private EpisodeAdapter episodeAdapter;
    private LinearLayout episodePanel;
    /** 选集平铺网格(三行高, 列数按屏宽自适应) */
    private RecyclerView rvEpisodes;
    private View btnBack;
    private View controlBar;
    private TextView tvTitle;
    private TextView tvPosition;
    private TextView btnPlayPause;
    private TextView btnSpeed;
    private TextView btnQuality;
    private TextView btnSubtitle;
    private TextView btnDanmaku;
    private TextView btnP2P;
    private TextView btnRefresh;
    private TextView btnReplay;
    private TextView btnLoop;
    private TextView btnKernel;
    private TextView btnDecoder;
    private TextView btnRender;
    private TextView btnIntro;
    private TextView btnOutro;
    private TextView btnDefault;
    private TextView btnAudio;
    private TextView tvTopInfo;
    private DanmakuView danmakuView;
    /** 循环播放开关(持久化): 播完自动重播本集 */
    private boolean loopOn = "1".equals(PrefUtils.get(PrefUtils.K_LOOP, "0"));
    /** 当前集片头/片尾跳过秒数(按 vod+flag+index 持久化) */
    private int introSkipSec = 0;
    private int outroSkipSec = 0;
    /** 网速统计: 上次取样时间/字节数, 及计算出的网速(bytes/s) */
    private long lastNetTime = 0;
    private long lastNetBytes = 0;
    private long netSpeed = 0;
    /** P2P 加速开关(持久化): 通常用于磁力/迅雷等 P2P 分流地址 */
    private boolean p2pEnabled = "1".equals(PrefUtils.get(PrefUtils.K_P2P, "0"));
    /** 弹幕开关(持久化) */
    private boolean danmakuOn = true;
    /** 弹幕持久化键 */
    private static final String K_DANMAKU = "k_danmaku";
    private TextView tvQuality;
    private android.widget.SeekBar seekBar;
    /** 是否正在拖动进度条(拖动中暂停刷新, 避免跳动) */
    private boolean seeking = false;
    private boolean playWhenReady = true;
    /** 当前可切换的清晰度列表 */
    private List<PlayerKernel.Quality> qualities = new ArrayList<>();
    private List<PlayerKernel.SubtitleTrack> subtitles = new ArrayList<>();
    /** 控制层(返回/标题/底部控制条/进度)是否可见 */
    private boolean controlsVisible = true;

    /** 手势控制: 亮度/音量/进度 */
    private TextView tvGesture;
    private android.media.AudioManager audioManager;
    private int maxVolume;
    private int gestureStartX, gestureStartY;
    private long gestureStartPos;
    private float gestureStartBrightness;
    private int gestureStartVolume;
    /** 0=未定 1=进度 2=亮度 3=音量 */
    private int gestureMode = 0;

    /** 播放时无操作 5 秒后自动隐藏控制层 */
    private static final long AUTO_HIDE_DELAY = 5000L;
    private final Handler hideHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::hideControls;
    /** 快进/快退浮层自动消失 */
    private final Runnable hideGestureRunnable = this::hideGesture;

    /** 倍速档位, 0.5x ~ 2.0x */
    private static final float[] SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    private int speedIndex = 2; // 默认 1.0x

    /** 定时刷新播放进度显示 */
    private final Handler posHandler = new Handler(Looper.getMainLooper());
    private final Runnable posTicker = new Runnable() {
        @Override
        public void run() {
            if (kernel != null && tvPosition != null) {
                long cur = kernel.getPosition();
                long dur = kernel.getDuration();
                // 片头跳过: 播放中且停留在片头区间则跳到片头结束
                if (introSkipSec > 0 && !seeking && dur > 0 && cur < introSkipSec * 1000L) {
                    kernel.seekTo(introSkipSec * 1000L);
                }
                // 片尾跳过: 接近结尾则直接跳到末尾(触发播完/下一集)
                if (outroSkipSec > 0 && dur > 0 && dur - cur < outroSkipSec * 1000L && cur < dur) {
                    kernel.seekTo(dur);
                }
                // 拖动进度条时不刷新, 避免与用户拖动冲突
                if (!seeking) {
                    tvPosition.setText(formatMs(cur) + (dur > 0 ? " / " + formatMs(dur) : ""));
                    if (seekBar != null && dur > 0) {
                        seekBar.setProgress((int) (cur * 1000 / dur));
                    }
                }
            }
            // 顶栏信息: 分辨率 | 网速 | 时间 (每秒更新一次网速)
            if (tvTopInfo != null) {
                long now = System.currentTimeMillis();
                long bytes = OkHttpUtil.totalBytes();
                if (lastNetTime == 0 || now - lastNetTime >= 1000) {
                    if (lastNetTime != 0) {
                        long dt = Math.max(now - lastNetTime, 1);
                        netSpeed = (bytes - lastNetBytes) * 1000 / dt;
                    }
                    lastNetBytes = bytes;
                    lastNetTime = now;
                }
                String q = tvQuality != null ? tvQuality.getText().toString() : "";
                String time = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(new java.util.Date());
                tvTopInfo.setText((q.isEmpty() ? "" : q + " | ") + formatSpeedBytes(netSpeed) + " | " + time);
            }
            posHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_play);

        vod = (Vod) getIntent().getSerializableExtra("vod");
        flag = getIntent().getStringExtra("flag");
        index = getIntent().getIntExtra("index", 0);
        if (vod == null) { finish(); return; }
        // siteKey 是 transient 字段, vod 经 Intent 序列化后会丢失,
        // 优先使用 DetailActivity 单独传递的 siteKey extra
        String siteKey = getIntent().getStringExtra("siteKey");
        site = findSite(siteKey != null ? siteKey : vod.siteKey);
        // 网盘直连: 接收外部传入的请求头(WebDAV Basic 等)
        String headersJson = getIntent().getStringExtra("headers");
        if (headersJson != null && !headersJson.isEmpty()) {
            try { directHeaders = new Gson().fromJson(headersJson, Map.class); } catch (Throwable ignored) {}
        }
        // 恢复 vod.siteKey, 供历史记录查找/保存使用
        vod.siteKey = site.getKey();
        android.util.Log.i("PlayActivity", "onCreate siteKey=" + siteKey
                + " vod.siteKey=" + vod.siteKey + " => site=" + site.getKey()
                + " isSpider=" + site.isSpider());
        // transient playFlags 在 Intent 反序列化后丢失, 重新解析
        if (vod.playFlags == null || vod.playFlags.isEmpty()) {
            vod.parsePlayFrom();
        }
        if (vod.playFlags == null || vod.playFlags.isEmpty()) {
            Toast.makeText(this, "播放信息缺失", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (flag == null || !vod.playFlags.containsKey(flag)) {
            flag = vod.playFlags.keySet().iterator().next();
        }
        episodes = vod.playFlags.get(flag);
        if (episodes == null || episodes.isEmpty()) { finish(); return; }

        playerContainer = findViewById(R.id.playerContainer);
        tvTitle = findViewById(R.id.tvTitle);
        episodePanel = findViewById(R.id.episodePanel);
        tvPosition = findViewById(R.id.tvPosition);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnSpeed = findViewById(R.id.btnSpeed);
        btnQuality = findViewById(R.id.btnQuality);
        btnSubtitle = findViewById(R.id.btnSubtitle);
        btnDanmaku = findViewById(R.id.btnDanmaku);
        btnP2P = findViewById(R.id.btnP2P);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnReplay = findViewById(R.id.btnReplay);
        btnLoop = findViewById(R.id.btnLoop);
        btnKernel = findViewById(R.id.btnKernel);
        btnDecoder = findViewById(R.id.btnDecoder);
        btnRender = findViewById(R.id.btnRender);
        btnIntro = findViewById(R.id.btnIntro);
        btnOutro = findViewById(R.id.btnOutro);
        btnDefault = findViewById(R.id.btnDefault);
        btnAudio = findViewById(R.id.btnAudio);
        tvTopInfo = findViewById(R.id.tvTopInfo);
        danmakuView = findViewById(R.id.danmakuView);
        tvQuality = findViewById(R.id.tvQuality);
        tvGesture = findViewById(R.id.tvGesture);
        seekBar = findViewById(R.id.seekBar);
        btnBack = findViewById(R.id.btnBack);
        controlBar = findViewById(R.id.controlBar);
        rvEpisodes = findViewById(R.id.rvEpisodes);

        // 手势控制初始化
        audioManager = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        maxVolume = audioManager != null ? audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) : 0;

        btnBack.setOnClickListener(v -> finish());
        // 点击播放器区域切换控制层显隐(不遮挡视频)
        playerContainer.setOnClickListener(v -> toggleControls());
        playerContainer.setOnTouchListener((v, e) -> handleGesture(e));
        findViewById(R.id.btnEpisodes).setOnClickListener(v -> toggleEpisodePanel());
        // 快进: 打开跳转面板, 拖动选择目标时间并实时显示「目标 / 总时长」
        findViewById(R.id.btnSeek).setOnClickListener(v -> showSeekDialog());
        // 上一集/下一集
        findViewById(R.id.btnPrev).setOnClickListener(v -> playPrev());
        findViewById(R.id.btnNext).setOnClickListener(v -> playNext());
        // 播放/暂停
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        // 倍速: 点击弹出选择
        btnSpeed.setOnClickListener(v -> showSpeedDialog());
        // 清晰度: 点击弹出选择
        btnQuality.setOnClickListener(v -> showQualityDialog());
        // 字幕: 点击弹出选择
        btnSubtitle.setOnClickListener(v -> showSubtitleDialog());
        // 弹幕: 点击弹出开关/发送选项
        btnDanmaku.setOnClickListener(v -> showDanmakuDialog());
        // P2P: 点击弹出加速开关/降级选项
        btnP2P.setOnClickListener(v -> showP2PDialog());
        applyP2PState();
        // 刷新: 重新解析并重放当前集(从当前位置)
        btnRefresh.setOnClickListener(v -> { pendingPos = kernel != null ? kernel.getPosition() : 0; playCurrent(); });
        // 重播: 从 0 重新播放当前集
        btnReplay.setOnClickListener(v -> {
            if (kernel != null) kernel.seekTo(0);
            if (!kernel.isPlaying()) kernel.play();
            Toast.makeText(this, "重新播放", Toast.LENGTH_SHORT).show();
            resetAutoHide();
        });
        // 循环: 播完自动重播本集
        btnLoop.setOnClickListener(v -> toggleLoop());
        // 内核: Exo <-> IJK 持久化切换并重放
        btnKernel.setOnClickListener(v -> toggleKernel());
        // 解码: IJK 硬解/软解切换(仅 IJK 生效)
        btnDecoder.setOnClickListener(v -> toggleDecoder());
        // 渲染: Exo 的 TextureView/SurfaceView 切换(仅 Exo 生效)
        btnRender.setOnClickListener(v -> toggleRender());
        // 片头跳过: 设置当前集跳过秒数
        btnIntro.setOnClickListener(v -> showIntroOutroDialog(true));
        // 片尾跳过: 设置当前集跳过秒数
        btnOutro.setOnClickListener(v -> showIntroOutroDialog(false));
        // 默认: 恢复默认音轨/字幕/倍速
        btnDefault.setOnClickListener(v -> resetDefault());
        // 音轨: 多音轨切换
        btnAudio.setOnClickListener(v -> showAudioDialog());
        updateKernelButton();
        updateDecoderButton();
        updateRenderButton();
        updateLoopButton();
        updateIntroOutroButtons();
        // 恢复上次弹幕开关设置 (兼容旧键 k_danmaku)
        String st = PrefUtils.get(PrefUtils.K_DANMU_ON, null);
        if (st == null) st = PrefUtils.get(K_DANMAKU, "1");
        danmakuOn = "1".equals(st);
        applyDanmakuState();
        // 应用弹幕样式(字号/透明度/速度/显示区域)
        danmakuView.applyStyle(
                Float.parseFloat(PrefUtils.get(PrefUtils.K_DANMU_SIZE, "24")),
                Float.parseFloat(PrefUtils.get(PrefUtils.K_DANMU_ALPHA, "1.0")),
                Float.parseFloat(PrefUtils.get(PrefUtils.K_DANMU_SPEED, "1.0")),
                Float.parseFloat(PrefUtils.get(PrefUtils.K_DANMU_AREA, "0.45")));

        // 进度条拖动: 拖动时预览时间, 松手后 seek
        seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && kernel != null) {
                    long dur = kernel.getDuration();
                    if (dur > 0) {
                        long target = dur * progress / 1000;
                        tvPosition.setText(formatMs(target) + " / " + formatMs(dur));
                        // 拖动时中央大字浮层显示「目标 / 总时长」, 一眼看清快进到哪里
                        showGesture("⏩ " + formatMs(target) + "  /  " + formatMs(dur));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
                seeking = true;
                hideHandler.removeCallbacks(hideRunnable);
                hideHandler.removeCallbacks(hideGestureRunnable);
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
                seeking = false;
                hideGesture();
                if (kernel != null) {
                    long dur = kernel.getDuration();
                    if (dur > 0) {
                        long target = dur * sb.getProgress() / 1000;
                        kernel.seekTo(target);
                    }
                }
                resetAutoHide();
            }
        });

        // 焦点缩放动画
        setupFocusAnim(btnBack);
        setupFocusAnim(findViewById(R.id.btnSeek));
        setupFocusAnim(findViewById(R.id.btnPrev));
        setupFocusAnim(findViewById(R.id.btnNext));
        setupFocusAnim(btnPlayPause);
        setupFocusAnim(btnSpeed);
        setupFocusAnim(btnQuality);
        setupFocusAnim(btnSubtitle);
        setupFocusAnim(btnDanmaku);
        setupFocusAnim(btnP2P);
        setupFocusAnim(btnRefresh);
        setupFocusAnim(btnReplay);
        setupFocusAnim(btnLoop);
        setupFocusAnim(btnKernel);
        setupFocusAnim(btnDecoder);
        setupFocusAnim(btnRender);
        setupFocusAnim(btnIntro);
        setupFocusAnim(btnOutro);
        setupFocusAnim(btnDefault);
        setupFocusAnim(btnAudio);
        setupFocusAnim(findViewById(R.id.btnEpisodes));

        // 选集平铺网格: 列数按屏宽自适应(投影仪/电视更宽则更多列), 约三行铺满便于遥控器选择
        rvEpisodes.setLayoutManager(new GridLayoutManager(this, ScreenUtil.episodeColumns(this)));
        episodeAdapter = new EpisodeAdapter();
        rvEpisodes.setAdapter(episodeAdapter);
        episodeAdapter.submit(episodes, index);
        episodeAdapter.setOnClick((i, ep) -> {
            index = i;
            episodeAdapter.setSelected(i);
            // 选完自动收起面板, 回到播放画面
            hideEpisodePanel();
            playCurrent();
        });

        initPlayer();
        updatePlayPauseLabel();
        playCurrent();
        // 初始显示控制层, 播放开始后自动隐藏
        showControls();
    }

    private Site findSite(String key) {
        // 网盘/推送直连: 使用非爬虫的合成站点, ep.url 即真实直链
        if ("_drive".equals(key) || "_push".equals(key)) {
            Site s = new Site();
            s.setKey(key);
            s.setName("_drive".equals(key) ? "网盘" : "推送");
            s.setType(0);
            return s;
        }
        for (Site s : ApiConfig.get().getSites()) {
            if (s.getKey().equals(key)) return s;
        }
        return ApiConfig.get().getHomeSite();
    }

    private void initPlayer() {
        // 根据 PrefUtils 选择内核: exo / ijk (硬解/软解)
        String playerType = PrefUtils.get(PrefUtils.K_PLAYER, PrefUtils.PLAYER_EXO);
        currentKernel = playerType;
        kernel = createKernel(PrefUtils.PLAYER_IJK.equals(playerType));
        kernel.init(this, playerContainer);
        // 恢复上次倍速
        float saved = loadSpeed();
        speedIndex = 2;
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - saved) < 0.01f) { speedIndex = i; break; }
        }
        kernel.setSpeed(SPEEDS[speedIndex]);
        btnSpeed.setText("倍速 " + formatSpeed(SPEEDS[speedIndex]));
        setupKernelListener();
        updateKernelButton();
        updateDecoderButton();
        updateRenderButton();
    }

    /** 按当前内核类型 + 解码/渲染配置创建内核 */
    private PlayerKernel createKernel(boolean ijk) {
        if (ijk) {
            String mode = PrefUtils.get(PrefUtils.K_IJK_DECODER, PrefUtils.IJK_HARDWARE);
            boolean hw = PrefUtils.IJK_HARDWARE.equals(mode);
            return new IjkKernel(hw);
        }
        return new ExoKernel();
    }

    /** 释放当前内核并按当前配置重建, 重放当前地址 */
    private void rebuildKernel() {
        if (kernel != null) {
            kernel.release();
            kernel = null;
        }
        kernel = createKernel(PrefUtils.PLAYER_IJK.equals(currentKernel));
        kernel.init(this, playerContainer);
        kernel.setSpeed(SPEEDS[speedIndex]);
        setupKernelListener();
        updateKernelButton();
        updateDecoderButton();
        updateRenderButton();
        if (pendingUrl != null && !pendingUrl.isEmpty()) {
            kernel.setDataSource(pendingUrl, pendingHeaders, pendingPos);
        }
    }

    /** 绑定内核回调(初始化与内核回退切换后共用) */
    private void setupKernelListener() {
        kernel.setListener(new PlayerKernel.Listener() {
            @Override
            public void onStateChanged(int state) {
                if (state == PlayerKernel.STATE_ENDED) {
                    if (loopOn) {
                        // 循环播放: 回到本集开头重播
                        if (kernel != null) {
                            kernel.seekTo(0);
                            kernel.play();
                        }
                    } else if (index < episodes.size() - 1) {
                        index++;
                        episodeAdapter.setSelected(index);
                        playCurrent();
                    } else {
                        finish();
                    }
                }
                updatePlayPauseLabel();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseLabel();
                if (isPlaying) {
                    // 播放中: 若控制层可见, 启动自动隐藏
                    resetAutoHide();
                } else {
                    // 暂停: 取消自动隐藏, 保持控制层可见方便操作
                    hideHandler.removeCallbacks(hideRunnable);
                }
            }

            @Override
            public void onError(String message) {
                // 内核自动回退: 首选内核失败时切换到另一内核重试一次
                if (!kernelFellBack && pendingUrl != null && !pendingUrl.isEmpty()) {
                    kernelFellBack = true;
                    switchKernel();
                    return;
                }
                Toast.makeText(PlayActivity.this, "播放失败: " + message, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onQualitiesChanged(List<PlayerKernel.Quality> list) {
                qualities = list != null ? list : new ArrayList<>();
                // 更新清晰度按钮文案: 单档位隐藏按钮, 多档位显示档位数
                if (btnQuality != null) {
                    if (qualities.size() > 1) {
                        btnQuality.setVisibility(View.VISIBLE);
                        btnQuality.setText("清晰度");
                    } else {
                        btnQuality.setVisibility(View.GONE);
                    }
                }
                // 同步字幕轨道: "字幕"按钮始终可见(支持内嵌切换 + 外部字幕加载)
                subtitles = kernel != null ? kernel.getSubtitleTracks() : new ArrayList<>();
                if (btnSubtitle != null) {
                    btnSubtitle.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onVideoSizeChanged(int width, int height) {
                if (tvQuality != null) {
                    tvQuality.setText(width + "x" + height);
                }
            }
        });
    }

    /** 释放当前内核并切换到另一内核, 重放当前地址(内核失败自动回退用, 不持久化) */
    private void switchKernel() {
        boolean toIjk = !PrefUtils.PLAYER_IJK.equals(currentKernel);
        currentKernel = toIjk ? PrefUtils.PLAYER_IJK : PrefUtils.PLAYER_EXO;
        rebuildKernel();
        Toast.makeText(this, "已切换到" + (toIjk ? "IJK" : "Exo") + "内核重试", Toast.LENGTH_SHORT).show();
    }

    /** 播放页「内核」按钮: 持久化切换 Exo <-> IJK 并重放 */
    private void toggleKernel() {
        boolean toIjk = !PrefUtils.PLAYER_IJK.equals(currentKernel);
        currentKernel = toIjk ? PrefUtils.PLAYER_IJK : PrefUtils.PLAYER_EXO;
        PrefUtils.put(PrefUtils.K_PLAYER, currentKernel);
        rebuildKernel();
        Toast.makeText(this, "已切换为 " + (toIjk ? "IJK 内核" : "Exo 内核") + ", 持久化保存", Toast.LENGTH_SHORT).show();
    }

    /** 解码按钮: IJK 硬解/软解切换(仅 IJK 生效) */
    private void toggleDecoder() {
        if (PrefUtils.PLAYER_EXO.equals(currentKernel)) {
            Toast.makeText(this, "解码切换仅 IJK 内核生效, 请先切换到 IJK", Toast.LENGTH_SHORT).show();
            return;
        }
        String cur = PrefUtils.get(PrefUtils.K_IJK_DECODER, PrefUtils.IJK_HARDWARE);
        String next = PrefUtils.IJK_HARDWARE.equals(cur) ? PrefUtils.IJK_SOFTWARE : PrefUtils.IJK_HARDWARE;
        PrefUtils.put(PrefUtils.K_IJK_DECODER, next);
        pendingPos = kernel != null ? kernel.getPosition() : 0;
        rebuildKernel();
        Toast.makeText(this, "已切换为 " + (PrefUtils.IJK_HARDWARE.equals(next) ? "硬解" : "软解"), Toast.LENGTH_SHORT).show();
    }

    /** 渲染按钮: Exo 的 TextureView/SurfaceView 切换(仅 Exo 生效) */
    private void toggleRender() {
        if (PrefUtils.PLAYER_IJK.equals(currentKernel)) {
            Toast.makeText(this, "渲染切换仅 Exo 内核生效, 请先切换到 Exo", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean surface = "surface".equals(PrefUtils.get(PrefUtils.K_RENDER, "texture"));
        PrefUtils.put(PrefUtils.K_RENDER, surface ? "texture" : "surface");
        pendingPos = kernel != null ? kernel.getPosition() : 0;
        rebuildKernel();
        Toast.makeText(this, "渲染方式: " + (surface ? "TextureView" : "SurfaceView"), Toast.LENGTH_SHORT).show();
    }

    /** 循环开关: 播完自动重播本集(持久化) */
    private void toggleLoop() {
        loopOn = !loopOn;
        PrefUtils.put(PrefUtils.K_LOOP, loopOn ? "1" : "0");
        updateLoopButton();
        Toast.makeText(this, loopOn ? "循环播放已开启" : "循环播放已关闭", Toast.LENGTH_SHORT).show();
    }

    /** 恢复默认: 音轨/字幕/倍速 1.0x */
    private void resetDefault() {
        if (kernel != null) {
            kernel.resetTracks();
            kernel.setSpeed(1.0f);
        }
        speedIndex = 2;
        btnSpeed.setText("倍速 1.0x");
        saveSpeed(1.0f);
        Toast.makeText(this, "已恢复默认(音轨/字幕/倍速 1.0x)", Toast.LENGTH_SHORT).show();
        resetAutoHide();
    }

    /** 音轨选择对话框 */
    private void showAudioDialog() {
        List<PlayerKernel.AudioTrack> tracks = kernel != null ? kernel.getAudioTracks() : new ArrayList<>();
        if (tracks.isEmpty()) {
            Toast.makeText(this, "当前视频无多音轨", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[tracks.size() + 1];
        labels[0] = "默认音轨";
        for (int i = 0; i < tracks.size(); i++) labels[i + 1] = tracks.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle("选择音轨")
                .setItems(labels, (d, which) -> {
                    if (which == 0) {
                        kernel.selectAudioTrack(null);
                    } else {
                        kernel.selectAudioTrack(tracks.get(which - 1));
                    }
                    resetAutoHide();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 片头/片尾跳过对话框(秒数持久化到当前集) */
    private void showIntroOutroDialog(final boolean isIntro) {
        final int[] opts = {0, 2, 5, 10, 15, 30, 60};
        String[] labels = new String[opts.length];
        int cur = isIntro ? introSkipSec : outroSkipSec;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = opts[i] == 0 ? "不跳过" : opts[i] + " 秒" + (opts[i] == cur ? "  ✓" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle(isIntro ? "片头跳过" : "片尾跳过")
                .setItems(labels, (d, which) -> {
                    int sec = opts[which];
                    String key = isIntro
                            ? PrefUtils.introKey(vod.vod_id, flag, index)
                            : PrefUtils.outroKey(vod.vod_id, flag, index);
                    PrefUtils.putInt(key, sec);
                    if (isIntro) introSkipSec = sec; else outroSkipSec = sec;
                    updateIntroOutroButtons();
                    Toast.makeText(this, (isIntro ? "片头" : "片尾") + "跳过已设为 "
                            + (sec == 0 ? "无" : sec + " 秒"), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateKernelButton() {
        if (btnKernel != null) {
            btnKernel.setText("内核 " + (PrefUtils.PLAYER_IJK.equals(currentKernel) ? "IJK" : "Exo"));
        }
    }

    private void updateDecoderButton() {
        if (btnDecoder != null) {
            boolean hw = PrefUtils.IJK_HARDWARE.equals(PrefUtils.get(PrefUtils.K_IJK_DECODER, PrefUtils.IJK_HARDWARE));
            btnDecoder.setText("解码 " + (hw ? "硬解" : "软解"));
        }
    }

    private void updateRenderButton() {
        if (btnRender != null) {
            boolean surface = "surface".equals(PrefUtils.get(PrefUtils.K_RENDER, "texture"));
            btnRender.setText("渲染 " + (surface ? "S" : "T"));
        }
    }

    private void updateLoopButton() {
        if (btnLoop != null) {
            btnLoop.setText(loopOn ? "循环 开" : "循环 关");
            btnLoop.setTextColor(getResources().getColor(loopOn ? R.color.sm_primary : R.color.sm_text_dim));
        }
    }

    private void updateIntroOutroButtons() {
        if (btnIntro != null) btnIntro.setText(introSkipSec > 0 ? "片头 跳" + introSkipSec + "s" : "片头 跳过");
        if (btnOutro != null) btnOutro.setText(outroSkipSec > 0 ? "片尾 跳" + outroSkipSec + "s" : "片尾 跳过");
    }

    /** bytes/s -> KB/s / MB/s 显示 */
    private static String formatSpeedBytes(long bytesPerSec) {
        if (bytesPerSec <= 0) return "0 B/s";
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1024 * 1024) return String.format("%.1f KB/s", bytesPerSec / 1024.0);
        return String.format("%.1f MB/s", bytesPerSec / 1024.0 / 1024.0);
    }

    /** 「选集」按钮: 展开/收起选集平铺网格 */
    private void toggleEpisodePanel() {
        if (episodePanel == null) return;
        if (episodePanel.getVisibility() == View.VISIBLE) {
            hideEpisodePanel();
        } else {
            showEpisodePanel();
        }
    }

    /** 展开选集平铺网格: 收起底部控制条/进度, 焦点落到当前集 */
    private void showEpisodePanel() {
        if (episodePanel == null) return;
        episodePanel.setVisibility(View.VISIBLE);
        // 面板覆盖底部区域, 暂时收起控制条与进度, 避免重叠
        if (controlBar != null) controlBar.setVisibility(View.GONE);
        if (seekBar != null) seekBar.setVisibility(View.GONE);
        if (tvPosition != null) tvPosition.setVisibility(View.GONE);
        if (tvQuality != null) tvQuality.setVisibility(View.GONE);
        if (tvTopInfo != null) tvTopInfo.setVisibility(View.GONE);
        hideHandler.removeCallbacks(hideRunnable);
        // 滚动并聚焦当前集, 方便遥控器继续选择
        if (rvEpisodes != null) {
            rvEpisodes.post(() -> {
                rvEpisodes.scrollToPosition(Math.max(index, 0));
                RecyclerView.ViewHolder vh = rvEpisodes.findViewHolderForAdapterPosition(index);
                if (vh != null) vh.itemView.requestFocus();
            });
        }
    }

    /** 收起选集网格并恢复底部控制条 */
    private void hideEpisodePanel() {
        if (episodePanel == null) return;
        episodePanel.setVisibility(View.GONE);
        setControlsVisibility(View.VISIBLE);
        controlsVisible = true;
        resetAutoHide();
    }

    /** 切换控制层显隐: 播放时点击视频区域显示/隐藏控制条 */
    private void toggleControls() {
        if (controlsVisible) {
            hideControls();
        } else {
            showControls();
        }
    }

    /** 显示控制层, 播放状态下启动自动隐藏计时 */
    private void showControls() {
        controlsVisible = true;
        setControlsVisibility(View.VISIBLE);
        resetAutoHide();
        // 焦点落到播放/暂停按钮, 方便遥控器继续用方向键导航
        if (btnPlayPause != null) btnPlayPause.requestFocus();
    }

    /** 隐藏控制层(不遮挡视频) */
    private void hideControls() {
        controlsVisible = false;
        setControlsVisibility(View.GONE);
        // 选集面板一并隐藏, 避免遮挡视频
        if (episodePanel != null) episodePanel.setVisibility(View.GONE);
        hideHandler.removeCallbacks(hideRunnable);
    }

    /** 统一设置控制层各 View 的可见性 */
    private void setControlsVisibility(int visibility) {
        if (btnBack != null) btnBack.setVisibility(visibility);
        if (tvTitle != null) tvTitle.setVisibility(visibility);
        if (controlBar != null) controlBar.setVisibility(visibility);
        if (seekBar != null) seekBar.setVisibility(visibility);
        if (tvPosition != null) tvPosition.setVisibility(visibility);
        if (tvQuality != null) tvQuality.setVisibility(visibility);
        if (tvTopInfo != null) tvTopInfo.setVisibility(visibility);
    }

    /** 重置自动隐藏计时: 仅在播放中且控制层可见时启动倒计时 */
    private void resetAutoHide() {
        hideHandler.removeCallbacks(hideRunnable);
        if (controlsVisible && kernel != null && kernel.isPlaying()) {
            hideHandler.postDelayed(hideRunnable, AUTO_HIDE_DELAY);
        }
    }

    /** 快退/快进(遥控器媒体键/方向键): 中央浮层显示「目标 / 总时长」 */
    private void seekBy(long deltaMs) {
        if (kernel == null) return;
        long dur = kernel.getDuration();
        long target = kernel.getPosition() + deltaMs;
        if (target < 0) target = 0;
        if (dur > 0 && target > dur) target = dur;
        kernel.seekTo(target);
        // 无需固定 ±10s 按钮: 直接显示跳到的时间与总时长
        showGesture((deltaMs > 0 ? "⏩ 快进  " : "⏪ 快退  ") + formatMs(target)
                + (dur > 0 ? "  /  " + formatMs(dur) : ""));
        hideHandler.removeCallbacks(hideGestureRunnable);
        hideHandler.postDelayed(hideGestureRunnable, 1200);
        resetAutoHide();
    }

    /**
     * 快进/跳转面板: 拖动选择目标时间, 大字实时显示「目标时间 / 总时长」,
     * 这样一眼就知道快进到了哪里, 不再需要 ±10s 固定步进按钮.
     */
    private void showSeekDialog() {
        if (kernel == null) return;
        final long dur = kernel.getDuration();
        if (dur <= 0) {
            Toast.makeText(this, "时长未知, 请稍后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        final long cur = kernel.getPosition();

        final TextView tvTime = new TextView(this);
        tvTime.setGravity(android.view.Gravity.CENTER);
        tvTime.setTextSize(30f);
        tvTime.setTextColor(getResources().getColor(R.color.sm_text));
        tvTime.setText(formatMs(cur) + "  /  " + formatMs(dur));

        final TextView tvHint = new TextView(this);
        tvHint.setGravity(android.view.Gravity.CENTER);
        tvHint.setTextSize(14f);
        tvHint.setTextColor(getResources().getColor(R.color.sm_text_dim));
        tvHint.setText("当前 " + formatMs(cur) + "  ·  拖动选择目标时间");

        final android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(1000);
        bar.setProgress((int) (cur * 1000 / dur));
        bar.setPadding(30, 40, 30, 40);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 32, 48, 16);
        box.addView(tvTime);
        box.addView(tvHint);
        box.addView(bar);

        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                long target = dur * progress / 1000;
                tvTime.setText(formatMs(target) + "  /  " + formatMs(dur));
                tvHint.setText("当前 " + formatMs(kernel != null ? kernel.getPosition() : cur)
                        + "  ·  跳转到 " + formatMs(target));
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        });

        new AlertDialog.Builder(this)
                .setTitle("快进 / 跳转时间")
                .setView(box)
                .setPositiveButton("跳转", (d, w) -> {
                    long target = dur * bar.getProgress() / 1000;
                    if (kernel != null) kernel.seekTo(target);
                    showGesture("⏩ 已跳转  " + formatMs(target) + "  /  " + formatMs(dur));
                    hideHandler.removeCallbacks(hideGestureRunnable);
                    hideHandler.postDelayed(hideGestureRunnable, 1500);
                    resetAutoHide();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 手势控制: 横向拖动=进度, 左侧纵向=亮度, 右侧纵向=音量 */
    private boolean handleGesture(MotionEvent e) {
        int width = playerContainer.getWidth();
        int height = playerContainer.getHeight();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                gestureStartX = (int) e.getX();
                gestureStartY = (int) e.getY();
                gestureStartPos = kernel != null ? kernel.getPosition() : 0;
                gestureStartBrightness = getWindow().getAttributes().screenBrightness;
                if (gestureStartBrightness < 0.01f) gestureStartBrightness = 0.5f;
                gestureStartVolume = audioManager != null
                        ? audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) : 0;
                gestureMode = 0;
                return true;
            case MotionEvent.ACTION_MOVE: {
                int dx = (int) e.getX() - gestureStartX;
                int dy = (int) e.getY() - gestureStartY;
                if (gestureMode == 0) {
                    if (Math.abs(dx) > 20 && Math.abs(dx) > Math.abs(dy)) {
                        gestureMode = 1; // 进度
                    } else if (Math.abs(dy) > 20) {
                        gestureMode = gestureStartX < width / 2 ? 2 : 3; // 亮度/音量
                    } else {
                        return true;
                    }
                }
                if (gestureMode == 1) {
                    long dur = kernel != null ? kernel.getDuration() : 0;
                    if (dur <= 0) return true;
                    long target = gestureStartPos + (long) (dx * 1.0 / width * dur);
                    if (target < 0) target = 0;
                    if (target > dur) target = dur;
                    kernel.seekTo(target);
                    showGesture("进度  " + formatMs(target) + " / " + formatMs(dur));
                } else if (gestureMode == 2) {
                    float b = gestureStartBrightness + (gestureStartY - (int) e.getY()) * 1.0f / height;
                    b = Math.max(0.01f, Math.min(1.0f, b));
                    setBrightness(b);
                    showGesture("亮度  " + (int) (b * 100) + "%");
                } else if (gestureMode == 3) {
                    int vol = gestureStartVolume + (int) ((gestureStartY - (int) e.getY()) * 1.0f / height * maxVolume);
                    vol = Math.max(0, Math.min(maxVolume, vol));
                    setVolume(vol);
                    showGesture("音量  " + (maxVolume > 0 ? (int) (vol * 100.0 / maxVolume) : 0) + "%");
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                // 无拖动(=短按点击)时切换控制条显示, 让触屏设备也能点屏呼出控制层
                boolean tap = (gestureMode == 0);
                gestureMode = 0;
                hideGesture();
                resetAutoHide();
                if (tap) toggleControls();
                return true;
            case MotionEvent.ACTION_CANCEL:
                gestureMode = 0;
                hideGesture();
                resetAutoHide();
                return true;
        }
        return false;
    }

    private void showGesture(String text) {
        if (tvGesture == null) return;
        tvGesture.setText(text);
        tvGesture.setVisibility(View.VISIBLE);
    }

    private void hideGesture() {
        if (tvGesture == null) return;
        tvGesture.setVisibility(View.GONE);
    }

    private void setBrightness(float b) {
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b;
        getWindow().setAttributes(lp);
    }

    private void setVolume(int v) {
        if (audioManager != null) {
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0);
        }
    }

    private void togglePlayPause() {
        if (kernel == null) return;
        if (kernel.isPlaying()) {
            kernel.pause();
            playWhenReady = false;
        } else {
            kernel.play();
            playWhenReady = true;
        }
        updatePlayPauseLabel();
    }

    /** 下一集 */
    private void playNext() {
        if (episodes == null || episodes.isEmpty()) return;
        if (index < episodes.size() - 1) {
            index++;
            episodeAdapter.setSelected(index);
            playCurrent();
        }
    }

    /** 上一集 */
    private void playPrev() {
        if (episodes == null || episodes.isEmpty()) return;
        if (index > 0) {
            index--;
            episodeAdapter.setSelected(index);
            playCurrent();
        }
    }

    /**
     * 遥控器按键拦截: 支持投影仪/电视遥控器的方向键与媒体键控制播放.
     * 控制层隐藏(全屏播放)时, 方向键直接控制播放; 控制层显示时交给焦点系统导航.
     */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case android.view.KeyEvent.KEYCODE_HEADSETHOOK:
                case android.view.KeyEvent.KEYCODE_MEDIA_PLAY:
                case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE:
                    togglePlayPause();
                    return true;
                case android.view.KeyEvent.KEYCODE_MEDIA_NEXT:
                    playNext();
                    return true;
                case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    playPrev();
                    return true;
                case android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    seekBy(10000);
                    return true;
                case android.view.KeyEvent.KEYCODE_MEDIA_REWIND:
                    seekBy(-10000);
                    return true;
                case android.view.KeyEvent.KEYCODE_BACK:
                    // 优先收起选集面板/控制层, 全部收起后才退出
                    if (episodePanel != null && episodePanel.getVisibility() == View.VISIBLE) {
                        hideEpisodePanel();
                        return true;
                    }
                    if (controlsVisible) {
                        hideControls();
                        return true;
                    }
                    break;
            }
            // 全屏播放(控制层隐藏)时, 遥控器方向键直接控制播放
            if (!controlsVisible) {
                switch (event.getKeyCode()) {
                    case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                        seekBy(-10000);
                        return true;
                    case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                        seekBy(10000);
                        return true;
                    case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                    case android.view.KeyEvent.KEYCODE_ENTER:
                    case android.view.KeyEvent.KEYCODE_NUMPAD_ENTER:
                    case android.view.KeyEvent.KEYCODE_BUTTON_A:
                        togglePlayPause();
                        return true;
                    case android.view.KeyEvent.KEYCODE_DPAD_UP:
                    case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                    case android.view.KeyEvent.KEYCODE_MENU:
                        showControls();
                        return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void updatePlayPauseLabel() {
        if (kernel == null || btnPlayPause == null) return;
        boolean playing = kernel.isPlaying();
        btnPlayPause.setText(playing ? "⏸ 暂停" : "▶ 播放");
    }

    /** 倍速选择对话框 */
    private void showSpeedDialog() {
        String[] labels = new String[SPEEDS.length];
        for (int i = 0; i < SPEEDS.length; i++) {
            labels[i] = formatSpeed(SPEEDS[i]) + "x" + (i == speedIndex ? "  ✓" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("播放倍速")
                .setItems(labels, (d, which) -> {
                    if (which < 0 || which >= SPEEDS.length) return;
                    speedIndex = which;
                    float speed = SPEEDS[which];
                    if (kernel != null) kernel.setSpeed(speed);
                    btnSpeed.setText("倍速 " + formatSpeed(speed));
                    saveSpeed(speed);
                })
                .show();
    }

    /** 清晰度选择对话框: 列出可切换的清晰度档位 */
    private void showQualityDialog() {
        if (qualities == null || qualities.isEmpty()) {
            Toast.makeText(this, "当前源不支持清晰度切换", Toast.LENGTH_SHORT).show();
            return;
        }
        // 按分辨率去重(同一分辨率可能对应多个码率 track)
        List<PlayerKernel.Quality> unique = new ArrayList<>();
        for (PlayerKernel.Quality q : qualities) {
            boolean exists = false;
            for (PlayerKernel.Quality u : unique) {
                if (u.width == q.width && u.height == q.height) { exists = true; break; }
            }
            if (!exists) unique.add(q);
        }
        String[] labels = new String[unique.size()];
        for (int i = 0; i < unique.size(); i++) {
            labels[i] = unique.get(i).label + " (" + unique.get(i).width + "x" + unique.get(i).height + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("选择清晰度")
                .setItems(labels, (d, which) -> {
                    if (which < 0 || which >= unique.size()) return;
                    kernel.selectQuality(unique.get(which));
                    resetAutoHide();
                })
                .show();
    }

    /** 字幕选择对话框: 内嵌字幕轨道 + 关闭 + 加载外部字幕 */
    private void showSubtitleDialog() {
        List<String> labels = new ArrayList<>();
        labels.add("关闭字幕");
        for (PlayerKernel.SubtitleTrack t : subtitles) labels.add(t.label);
        labels.add("加载外部字幕(URL)");
        String[] arr = labels.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("选择字幕")
                .setItems(arr, (d, which) -> {
                    if (which == 0) {
                        kernel.selectSubtitle(null);
                        kernel.setSubtitleUrl(null);
                    } else if (which <= subtitles.size()) {
                        kernel.selectSubtitle(subtitles.get(which - 1));
                    } else {
                        showSubtitleUrlInput();
                    }
                    resetAutoHide();
                })
                .show();
    }

    /** 输入外部字幕 URL 并加载 */
    private void showSubtitleUrlInput() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("字幕 URL (.srt/.ass/.vtt)");
        input.setTextColor(getResources().getColor(R.color.sm_text));
        input.setHintTextColor(getResources().getColor(R.color.sm_text_dim));
        new AlertDialog.Builder(this)
                .setTitle("加载外部字幕")
                .setView(input)
                .setPositiveButton("加载", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    kernel.selectSubtitle(null);  // 清内嵌字幕
                    kernel.setSubtitleUrl(url);
                    Toast.makeText(this, "已加载外部字幕", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 弹幕对话框: 开关弹幕 / 发送弹幕 / 样式设置 (遥控器方向键可选中) */
    private void showDanmakuDialog() {
        final String[] items = {
                danmakuOn ? "关闭弹幕" : "开启弹幕",
                "发送弹幕",
                "弹幕样式设置",
        };
        new AlertDialog.Builder(this)
                .setTitle("弹幕")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        danmakuOn = !danmakuOn;
                        PrefUtils.put(PrefUtils.K_DANMU_ON, danmakuOn ? "1" : "0");
                        PrefUtils.put(K_DANMAKU, danmakuOn ? "1" : "0");
                        applyDanmakuState();
                        Toast.makeText(this, danmakuOn ? "弹幕已开启" : "弹幕已关闭", Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        showSendDanmakuInput();
                    } else {
                        showDanmakuStyleDialog();
                    }
                    resetAutoHide();
                })
                .show();
    }

    /** 弹幕样式设置: 字号 / 透明度 / 速度 / 显示区域 */
    private void showDanmakuStyleDialog() {
        final String size = PrefUtils.get(PrefUtils.K_DANMU_SIZE, "24");
        final String alpha = PrefUtils.get(PrefUtils.K_DANMU_ALPHA, "1.0");
        final String speed = PrefUtils.get(PrefUtils.K_DANMU_SPEED, "1.0");
        final String area = PrefUtils.get(PrefUtils.K_DANMU_AREA, "0.45");
        final String[] items = {
                "字号: " + size + " sp",
                "透明度: " + alpha,
                "速度: " + speed,
                "显示区域: " + (int) (Float.parseFloat(area) * 100) + "%",
        };
        // 各选项的可选值
        final String[][] opts = {
                {"18", "24", "30", "36", "42"},
                {"0.3", "0.5", "0.8", "1.0"},
                {"0.5", "1.0", "1.5", "2.0"},
                {"0.3", "0.45", "0.6", "0.8"},
        };
        final String[] keys = {
                PrefUtils.K_DANMU_SIZE, PrefUtils.K_DANMU_ALPHA,
                PrefUtils.K_DANMU_SPEED, PrefUtils.K_DANMU_AREA,
        };
        new AlertDialog.Builder(this)
                .setTitle("弹幕样式设置")
                .setItems(items, (d, index) -> {
                    new AlertDialog.Builder(this)
                            .setTitle(items[index])
                            .setItems(opts[index], (dd, oi) -> {
                                PrefUtils.put(keys[index], opts[index][oi]);
                                reapplyDanmakuStyle();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 重新读取样式并应用到弹幕层 */
    private void reapplyDanmakuStyle() {
        danmakuView.applyStyle(
                Float.parseFloat(PrefUtils.get(PrefUtils.K_DANMU_SIZE, "24")),
                Float.parseFloat(PrefUtils.get(PrefUtils.K_DANMU_ALPHA, "1.0")),
                Float.parseFloat(PrefUtils.get(PrefUtils.K_DANMU_SPEED, "1.0")),
                Float.parseFloat(PrefUtils.get(PrefUtils.K_DANMU_AREA, "0.45")));
        Toast.makeText(this, "弹幕样式已更新", Toast.LENGTH_SHORT).show();
        resetAutoHide();
    }

    /** 应用弹幕开关状态: 同步弹幕层可见性与按钮文案 */
    private void applyDanmakuState() {
        if (danmakuView != null) {
            danmakuView.setEnabled(danmakuOn);
            danmakuView.setVisibility(danmakuOn ? View.VISIBLE : View.GONE);
        }
        if (btnDanmaku != null) {
            btnDanmaku.setText(danmakuOn ? "弹幕 开" : "弹幕 关");
            btnDanmaku.setTextColor(getResources().getColor(
                    danmakuOn ? R.color.sm_primary : R.color.sm_text_dim));
        }
    }

    // ---------------- P2P 加速 ----------------
    /** 应用 P2P 开关状态: 同步按钮文案与颜色 */
    private void applyP2PState() {
        if (btnP2P == null) return;
        btnP2P.setText(p2pEnabled ? "P2P 加速 开" : "P2P 加速");
        btnP2P.setTextColor(getResources().getColor(
                p2pEnabled ? R.color.sm_primary : R.color.sm_text_dim));
    }

    /** P2P 对话框: 开关 + 说明 */
    private void showP2PDialog() {
        final String[] items = {
                p2pEnabled ? "关闭 P2P 加速" : "开启 P2P 加速",
                "P2P 说明",
        };
        new AlertDialog.Builder(this)
                .setTitle("P2P 加速")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        p2pEnabled = !p2pEnabled;
                        PrefUtils.put(PrefUtils.K_P2P, p2pEnabled ? "1" : "0");
                        applyP2PState();
                        Toast.makeText(this, p2pEnabled ? "P2P 加速已开启" : "P2P 加速已关闭",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("P2P 加速说明")
                                .setMessage("影视仓的 P2P 加速依赖专有 forcetech 内核(cdnp/peer 等磁力·迅雷·BT 分流)。\n\n"
                                        + "当前版本未集成该 SDK，遇磁力/迅雷/ed2k 等 P2P 地址时会降级为「复制链接」交由支持工具播放。\n\n"
                                        + "量子源中部分站点可能直接返回秒播直链(非 P2P)，此类可正常在线播放。")
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                    resetAutoHide();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 判断某地址是否为 P2P/磁力/下载协议, 无法直接解码播放 */
    private boolean isP2PScheme(String url) {
        if (url == null || url.isEmpty()) return false;
        return url.startsWith("magnet:") || url.startsWith("thunder:")
                || url.startsWith("thunderx:") || url.startsWith("xunlei://")
                || url.startsWith("ed2k://") || url.startsWith("bth://")
                || url.startsWith("ftpbg://") || url.startsWith("cdnp:")
                || url.contains("forcetech");
    }

    /** 复制文本到系统剪贴板 */
    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("p2p", text));
        } catch (Throwable ignored) {}
    }

    /** 输入并发送一条弹幕 */
    private void showSendDanmakuInput() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("输入弹幕内容");
        input.setTextColor(getResources().getColor(R.color.sm_text));
        input.setHintTextColor(getResources().getColor(R.color.sm_text_dim));
        new AlertDialog.Builder(this)
                .setTitle("发送弹幕")
                .setView(input)
                .setPositiveButton("发送", (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty() && danmakuView != null) danmakuView.send(text);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String formatSpeed(float s) {
        if (s == (int) s) return String.valueOf((int) s) + ".0";
        return String.valueOf(s);
    }

    private float loadSpeed() {
        try {
            String s = PrefUtils.get(PrefUtils.K_SPEED, "1.0");
            return Float.parseFloat(s);
        } catch (Throwable t) {
            return 1.0f;
        }
    }

    private void saveSpeed(float speed) {
        PrefUtils.put(PrefUtils.K_SPEED, String.valueOf(speed));
    }

    /** 毫秒 -> mm:ss 或 h:mm:ss */
    private static String formatMs(long ms) {
        if (ms <= 0) return "00:00";
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }

    private void playCurrent() {
        Vod.Episode ep = episodes.get(index);
        tvTitle.setText(vod.vod_name + "  " + ep.name);

        // 加载当前集的片头/片尾跳过设置(按 vod+flag+index 持久化)
        introSkipSec = PrefUtils.getInt(PrefUtils.introKey(vod.vod_id, flag, index), 0);
        outroSkipSec = PrefUtils.getInt(PrefUtils.outroKey(vod.vod_id, flag, index), 0);
        updateIntroOutroButtons();

        // 恢复历史进度; 若外部(小窗预览进全屏)传入 startPos 则优先从中续播
        long startPos = getIntent().getLongExtra("startPos", 0);
        if (startPos <= 0) {
            HistoryRecord history = AppDatabase.get(this).historyDao().find(vod.siteKey + "|" + vod.vod_id);
            if (history != null && history.flag.equals(flag) && history.episodeIndex == index) {
                startPos = history.position;
            }
        }
        final long fStart = startPos;

        // 爬虫需二次解析真实地址
        ThreadUtils.io(() -> {
            String url = null;
            Map<String, String> headers = null;
            int needParse = 0;
            if (site != null && site.isSpider()) {
                // spider 站点的 ep.url 是编码后的 ID, 不是可播放地址,
                // 必须通过 playerContent 解析; 失败则不播放
                Result r = SpiderManager.playerContent(site, flag, ep.url, null);
                android.util.Log.i("PlayActivity", "playerContent flag=" + flag + " ep.url=" + ep.url
                        + " => url=" + r.url + " parse=" + r.parse + " header=" + r.header
                        + " err=" + r.msg);
                if (!r.hasError() && r.url != null && !r.url.isEmpty()) {
                    url = r.url;
                    needParse = r.parse;
                    if (r.header != null && !r.header.isEmpty()) {
                        try {
                            headers = new Gson().fromJson(r.header, Map.class);
                        } catch (Throwable ignored) {}
                    }
                }
            } else {
                // 非 spider 站点, ep.url 即为直链; 网盘场景附带认证请求头
                url = ep.url;
                headers = directHeaders;
            }
            // 回退: spider 解析失败(如加密原生库加载失败)时, 若 ep.url 本身已是直链(mp4/m3u8等), 直接播放
            if (url == null || url.isEmpty()) {
                if (isDirectUrl(ep.url)) {
                    android.util.Log.i("PlayActivity", "fallback to direct ep.url: " + ep.url);
                    url = ep.url;
                }
            }
            android.util.Log.i("PlayActivity", "playCurrent final url=" + url + " needParse=" + needParse
                    + " headers=" + (headers == null ? "null" : headers.size()));
            // 若爬虫要求二次解析 (parse=1), 调用接口配置中的 parses 链路
            if (needParse == 1) {
                url = resolveWithParses(url);
            }
            final String fUrl = url;
            final Map<String, String> fHeaders = headers;
            final long fPos = fStart;
            ThreadUtils.main(() -> {
                if (fUrl == null || fUrl.isEmpty()) {
                    // 网盘类源(百度/夸克/115/阿里/迅雷等)需要账号登录才能解析出真实播放地址
                    String fl = flag == null ? "" : flag;
                    boolean netdisk = fl.contains("百度") || fl.contains("夸克") || fl.contains("115")
                            || fl.contains("阿里") || fl.contains("迅雷") || fl.contains("uc")
                            || fl.contains("原画");
                    Toast.makeText(PlayActivity.this,
                            netdisk ? "该资源需网盘账号登录(扫码)后才能播放，请切换到秒播/直链源或登录网盘"
                                    : "无法获取播放地址，请切换其他线路或站点",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                // P2P/磁力/迅雷 等非流式直链地址: 无法用 Exo/IJK 直接解码, 降级为复制链接
                if (isP2PScheme(fUrl)) {
                    final String p2pUrl = fUrl;
                    copyToClipboard(p2pUrl);
                    new AlertDialog.Builder(PlayActivity.this)
                            .setTitle("P2P 链接，无法直接在线播放")
                            .setMessage("该线路返回的是 P2P/磁力/迅雷 分流地址，当前未集成 forcetech P2P 内核，无法在线解码。\n\n"
                                    + "链接已复制到剪贴板，可粘贴到支持磁力/迅雷的工具下载后播放；或切换其他线路/站点。\n\n"
                                    + "开启「P2P 加速」仅为标识，其原生解码仍需专有 SDK。")
                            .setPositiveButton("知道了", null)
                            .setNegativeButton("再复制一次", (d, w) -> copyToClipboard(p2pUrl))
                            .show();
                    return;
                }
                if (kernel == null) return;
                // 记录本次播放信息, 供内核失败时自动回退重试
                pendingUrl = fUrl;
                pendingHeaders = fHeaders;
                pendingPos = fPos;
                kernelFellBack = false;
                kernel.setDataSource(fUrl, fHeaders, fPos);
            });
        });
    }

    /**
     * 按接口配置中的 parses 列表依次尝试解析视频地址.
     * 各 parse 通常为 http://xxx/api/?key=...&url=<encoded>
     * 成功返回真实播放地址, 全部失败则返回原 url.
     */
    private String resolveWithParses(String rawUrl) {
        List<Parse> parses = ApiConfig.get().getParses();
        if (parses.isEmpty()) return rawUrl;
        for (Parse p : parses) {
            if (p.type != 1) continue;
            try {
                String full = p.url + URLEncoder.encode(rawUrl, "UTF-8");
                String resp = OkHttpUtil.get(full);
                if (resp == null || resp.isEmpty()) continue;
                String resolved = extractPlayableUrl(resp);
                if (resolved != null && !resolved.isEmpty()) return resolved;
            } catch (Throwable ignored) {}
        }
        return rawUrl;
    }

    /** 从解析接口的 JSON 响应中提取真实播放地址 */
    private String extractPlayableUrl(String resp) {
        try {
            JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
            // 常见字段: url / playUrl / alyUrl / durl / src
            String[] keys = {"url", "playUrl", "alyUrl", "src"};
            for (String k : keys) {
                if (o.has(k) && o.get(k).isJsonPrimitive()) {
                    String v = o.get(k).getAsString();
                    if (v.startsWith("http")) return v;
                }
            }
            // data.url / data.durl 数组(取第一项)
            if (o.has("data") && o.get("data").isJsonObject()) {
                JsonObject d = o.getAsJsonObject("data");
                for (String k : keys) {
                    if (d.has(k) && d.get(k).isJsonPrimitive()) {
                        String v = d.get(k).getAsString();
                        if (v.startsWith("http")) return v;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 判断 ep.url 是否本身已是可直接播放的直链(带视频扩展名的 http 地址) */
    private boolean isDirectUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        return lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".ts")
                || lower.contains(".flv") || lower.contains(".mkv") || lower.contains(".avi")
                || lower.contains(".mov") || lower.contains(".webm") || lower.contains(".mp3");
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 全屏结束位置回传给详情页小窗(供返回后续播)
        if (kernel != null) {
            lastPlayPosition = kernel.getPosition();
        }
        saveHistory();
        if (kernel != null) kernel.pause();
        posHandler.removeCallbacks(posTicker);
        hideHandler.removeCallbacks(hideRunnable);
        hideHandler.removeCallbacks(hideGestureRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        posHandler.postDelayed(posTicker, 200);
    }

    private void setupFocusAnim(android.view.View view) {
        if (view == null) return;
        view.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start();
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hideHandler.removeCallbacks(hideRunnable);
        hideHandler.removeCallbacks(hideGestureRunnable);
        if (kernel != null) kernel.release();
    }

    private void saveHistory() {
        if (kernel == null) return;
        HistoryRecord r = new HistoryRecord();
        r.id = vod.siteKey + "|" + vod.vod_id;
        r.siteKey = vod.siteKey;
        r.vodId = vod.vod_id;
        r.vodName = vod.vod_name;
        r.vodPic = vod.vod_pic;
        r.flag = flag;
        r.episodeIndex = index;
        r.episodeName = episodes.get(index).name;
        r.position = kernel.getPosition();
        r.duration = kernel.getDuration();
        r.updateTime = System.currentTimeMillis();
        AppDatabase.get(this).historyDao().upsert(r);
    }
}
