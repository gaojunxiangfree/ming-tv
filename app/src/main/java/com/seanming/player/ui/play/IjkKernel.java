package com.seanming.player.ui.play;

import android.content.Context;
import android.net.Uri;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * IJK 内核实现.
 * 包装 IjkMediaPlayer, 渲染视图用 SurfaceView.
 * 支持硬解码 (MediaCodec) 和软解码 (FFmpeg) 切换.
 */
public class IjkKernel implements PlayerKernel {

    private IjkMediaPlayer player;
    private SurfaceView surfaceView;
    private Listener listener;
    private boolean prepared = false;
    private boolean playWhenReady = true;
    private String pendingUrl;
    private Map<String, String> pendingHeaders;
    /** 从请求头里摘出的 UA, 单独走 ffmpeg 的 user_agent 选项下发(影视仓同款做法) */
    private String pendingUserAgent;
    private long pendingStartMs;
    private float pendingSpeed = 1.0f;

    /** 硬解: 1, 软解: 0, 由构造传入 */
    private final boolean hardwareDecode;

    public IjkKernel(boolean hardwareDecode) {
        this.hardwareDecode = hardwareDecode;
    }

    @Override
    public void init(Context ctx, ViewGroup container) {
        // 创建 SurfaceView 并加入容器
        surfaceView = new SurfaceView(ctx);
        surfaceView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        container.removeAllViews();
        container.addView(surfaceView);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Surface s = holder.getSurface();
                if (player != null) {
                    player.setDisplay(holder);
                    player.setSurface(s);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (player != null) {
                    try { player.setDisplay(null); } catch (Throwable ignored) {}
                }
            }
        });

        // 创建 IjkMediaPlayer 并配置解码选项
        player = new IjkMediaPlayer();
        applyOptions();

        // 状态监听
        player.setOnPreparedListener(mp -> {
            prepared = true;
            // 设置倍速 (需在 prepare 之后调用)
            try { player.setSpeed(pendingSpeed); } catch (Throwable ignored) {}
            if (playWhenReady) player.start();
            if (listener != null) {
                listener.onStateChanged(STATE_READY);
                listener.onIsPlayingChanged(playWhenReady);
            }
        });
        player.setOnBufferingUpdateListener((mp, percent) -> {
            // 缓冲更新, 不映射状态 (IJK 的缓冲信息不够稳定)
        });
        player.setOnInfoListener((mp, what, extra) -> {
            if (listener == null) return false;
            switch (what) {
                case IMediaPlayer.MEDIA_INFO_BUFFERING_START:
                    listener.onStateChanged(STATE_BUFFERING);
                    listener.onIsPlayingChanged(false);
                    return true;
                case IMediaPlayer.MEDIA_INFO_BUFFERING_END:
                    listener.onStateChanged(STATE_READY);
                    listener.onIsPlayingChanged(playWhenReady);
                    return true;
            }
            return false;
        });
        player.setOnCompletionListener(mp -> {
            if (listener != null) {
                listener.onStateChanged(STATE_ENDED);
                listener.onIsPlayingChanged(false);
            }
        });
        player.setOnErrorListener((mp, framework, impl) -> {
            if (listener != null) {
                listener.onError("IJK 播放错误: framework=" + framework + " impl=" + impl);
            }
            return false;
        });

        // SurfaceView 立即 attach, 等 surfaceCreated 回调中再 setDisplay
        // 若已有 pendingUrl (setDataSource 先于 surfaceCreated 调用), 在 surfaceCreated 中会处理
        if (pendingUrl != null) {
            applyDataSource();
        }
    }

    /**
     * 下发 IJK 播放选项(对齐影视仓 com.androidx.zx.OooOo0O 的配置).
     *
     * 注意: IjkMediaPlayer.reset() 会连带清空此前 setOption 下发的全部选项,
     * 所以每次 prepare 前(即 reset() 之后)都必须重新调用本方法,
     * 否则硬解/HLS 相关选项全部失效.
     */
    private void applyOptions() {
        if (player == null) return;
        // 硬解: 1 开启 mediacodec, 软解: 0 走 FFmpeg
        int mc = hardwareDecode ? 1 : 0;
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "subtitle", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_timeout", -1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48);
        // 启动阶段不分片预读, 加快首帧
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "fastseek");
        // 探测 range 支持会拖慢部分 CDN
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 0);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 15 * 1024 * 1024);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0);
        // SDL_FCC_RV32 = 842225234
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", 842225234L);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "reconnect", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", mc);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", mc);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", mc);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", mc);
        // UA 单独下发(影视仓同款): ffmpeg 的 http 从 user_agent 选项取 UA,
        // 若再在 headers 里塞一个 User-Agent 会与之冲突, 因此 setDataSource 里已把它摘出来
        if (pendingUserAgent != null && !pendingUserAgent.isEmpty()) {
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", pendingUserAgent);
        }
        // HLS 的 AES-128 加密分片会被 ffmpeg 以 crypto+https://... 打开,
        // 协议白名单里必须包含 crypto(以及依赖的 http/https/tcp/tls), 否则分片全部打不开;
        // allowed_extensions 放开扩展名限制, 避免带查询串的 .ts 分片被拒.
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist",
                "async,cache,crypto,file,http,https,ijkhttphook,ijkinject,ijklivehook,"
                        + "ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data");
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "allowed_extensions", "ALL");
    }

    @Override
    public void setDataSource(String url, Map<String, String> headers, long startPosMs) {
        this.pendingUrl = url;
        this.pendingHeaders = headers;
        this.pendingStartMs = startPosMs;
        // 影视仓做法: 把 User-Agent 从 headers 里摘出来, 单独走 ffmpeg 的 user_agent 选项,
        // 避免两个 UA 来源冲突导致部分 CDN 拒绝请求.
        // 注意: 这里复制一份, 不改动调用方(PlayActivity)传入的 map,
        // 否则内核回退到 Exo 时请求头会缺 UA.
        this.pendingUserAgent = null;
        if (headers != null && !headers.isEmpty()) {
            Map<String, String> copy = new java.util.HashMap<>(headers);
            for (String k : copy.keySet()) {
                if (k != null && k.equalsIgnoreCase("User-Agent")) {
                    pendingUserAgent = copy.get(k);
                    break;
                }
            }
            if (pendingUserAgent != null) copy.remove("User-Agent");
            this.pendingHeaders = copy;
        }
        if (player != null) {
            applyDataSource();
        }
    }

    /** 实际调用 IjkMediaPlayer.setDataSource 并 prepareAsync */
    private void applyDataSource() {
        try {
            prepared = false;
            player.reset();
            // reset() 会清空所有 option, 必须在 setDataSource 之前重新下发
            applyOptions();
            // 重置后需要重新设置 Surface, 否则黑屏
            if (surfaceView != null) {
                SurfaceHolder h = surfaceView.getHolder();
                Surface s = h.getSurface();
                if (s != null && s.isValid()) {
                    player.setDisplay(h);
                    player.setSurface(s);
                }
            }
            // 设置 headers (IJK 通过 Uri+headers 重载支持)
            if (pendingHeaders != null && !pendingHeaders.isEmpty()) {
                try {
                    player.setDataSource(pendingUrl, pendingHeaders);
                } catch (Throwable ignored) {
                    // 某些 IJK 版本不支持 headers 重载, 退到无 headers
                    player.setDataSource(pendingUrl);
                }
            } else {
                player.setDataSource(pendingUrl);
            }
            // 起始位置: 先 prepare, 再在 onPrepared 中 seekTo
            final long startPos = pendingStartMs;
            player.setOnPreparedListener(mp -> {
                prepared = true;
                try { player.setSpeed(pendingSpeed); } catch (Throwable ignored) {}
                if (startPos > 0) player.seekTo(startPos);
                if (playWhenReady) player.start();
                if (listener != null) {
                    listener.onStateChanged(STATE_READY);
                    listener.onIsPlayingChanged(playWhenReady);
                }
            });
            player.prepareAsync();
            if (listener != null) {
                listener.onStateChanged(STATE_BUFFERING);
                listener.onIsPlayingChanged(false);
            }
        } catch (Throwable t) {
            if (listener != null) listener.onError("IJK setDataSource 失败: " + t.getMessage());
        }
    }

    @Override
    public void setSpeed(float speed) {
        this.pendingSpeed = speed;
        if (player != null && prepared) {
            try { player.setSpeed(speed); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void seekTo(long positionMs) {
        if (player != null && prepared) {
            try { player.seekTo(positionMs); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void play() {
        playWhenReady = true;
        if (player != null && prepared) {
            try { player.start(); } catch (Throwable ignored) {}
            if (listener != null) listener.onIsPlayingChanged(true);
        }
    }

    @Override
    public void pause() {
        playWhenReady = false;
        if (player != null && prepared) {
            try { player.pause(); } catch (Throwable ignored) {}
            if (listener != null) listener.onIsPlayingChanged(false);
        }
    }

    @Override
    public long getPosition() {
        if (player != null && prepared) {
            try { return player.getCurrentPosition(); } catch (Throwable ignored) {}
        }
        return 0;
    }

    @Override
    public long getDuration() {
        if (player != null && prepared) {
            try { return player.getDuration(); } catch (Throwable ignored) {}
        }
        return 0;
    }

    @Override
    public boolean isReady() {
        return prepared;
    }

    @Override
    public boolean isPlaying() {
        if (player != null && prepared) {
            try { return player.isPlaying(); } catch (Throwable ignored) {}
        }
        return false;
    }

    @Override
    public List<Quality> getQualities() {
        // IJK 多码率切换支持有限, 当前返回空列表(不支持清晰度切换)
        return new ArrayList<>();
    }

    @Override
    public void selectQuality(Quality quality) {
        // IJK 内核暂不实现清晰度切换
    }

    @Override
    public Quality getCurrentQuality() {
        return null;
    }

    @Override
    public List<SubtitleTrack> getSubtitleTracks() {
        // IJK 内核暂不提供字幕轨道列表
        return new ArrayList<>();
    }

    @Override
    public void selectSubtitle(SubtitleTrack track) {
        // IJK 内核暂不支持字幕轨道切换
    }

    @Override
    public void setSubtitleUrl(String url) {
        // IJK 内核暂不支持外部字幕
    }

    @Override
    public List<AudioTrack> getAudioTracks() {
        // IJK 内核暂不提供音轨列表
        return new ArrayList<>();
    }

    @Override
    public void selectAudioTrack(AudioTrack track) {
        // IJK 内核暂不支持音轨切换
    }

    @Override
    public void resetTracks() {
        // IJK 内核暂不支持轨道重置
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void release() {
        if (player != null) {
            try {
                player.stop();
                player.release();
            } catch (Throwable ignored) {}
            player = null;
        }
        if (surfaceView != null && surfaceView.getParent() instanceof ViewGroup) {
            ((ViewGroup) surfaceView.getParent()).removeView(surfaceView);
            surfaceView = null;
        }
    }
}
