package com.seanming.player.ui.play;

import android.content.Context;
import android.view.ViewGroup;

import java.util.List;
import java.util.Map;

/**
 * 播放器内核统一接口.
 * Exo / IJK 各自实现, PlayActivity 通过本接口调用, 屏蔽底层差异.
 */
public interface PlayerKernel {

    /** 播放状态常量, 与 ExoPlayer 对齐, 便于上层复用判断逻辑 */
    int STATE_IDLE = 1;
    int STATE_BUFFERING = 2;
    int STATE_READY = 3;
    int STATE_ENDED = 4;

    /** 清晰度档位(按视频分辨率识别) */
    class Quality {
        public final String label; // 1080p / 720p / 360p / 4K
        public final int width;
        public final int height;

        public Quality(String label, int width, int height) {
            this.label = label;
            this.width = width;
            this.height = height;
        }

        @Override
        public String toString() { return label + " (" + width + "x" + height + ")"; }
    }

    /** 字幕轨道(内嵌字幕, 来自 HLS/DASH 等多轨字幕流) */
    class SubtitleTrack {
        public final String label;      // 中文 / English / 无字幕
        public final String language;   // zh / en / null
        public final int groupIndex;    // Media3 Tracks.Group 索引
        public final int trackIndex;    // 组内轨道索引

        public SubtitleTrack(String label, String language, int groupIndex, int trackIndex) {
            this.label = label;
            this.language = language;
            this.groupIndex = groupIndex;
            this.trackIndex = trackIndex;
        }

        @Override
        public String toString() { return label; }
    }

    /** 音轨(多音轨源: 国语/粤语/原声 等) */
    class AudioTrack {
        public final String label;      // 国语 / 粤语 / 原声
        public final String language;   // zh / yue / null
        public final int groupIndex;    // Media3 Tracks.Group 索引
        public final int trackIndex;    // 组内轨道索引

        public AudioTrack(String label, String language, int groupIndex, int trackIndex) {
            this.label = label;
            this.language = language;
            this.groupIndex = groupIndex;
            this.trackIndex = trackIndex;
        }

        @Override
        public String toString() { return label; }
    }

    /** 播放状态/错误回调 */
    interface Listener {
        void onStateChanged(int state);
        void onIsPlayingChanged(boolean isPlaying);
        void onError(String message);
        /** 可切换的清晰度列表变化(多码率源才有多个档位) */
        void onQualitiesChanged(List<Quality> qualities);
        /** 当前视频实际分辨率变化 */
        void onVideoSizeChanged(int width, int height);
    }

    /**
     * 初始化内核, 并将渲染视图 attach 到 container.
     * Exo 内核会创建 PlayerView, IJK 内核会创建 SurfaceView.
     */
    void init(Context ctx, ViewGroup container);

    /** 设置数据源并准备播放, startPosMs 为起始位置(毫秒) */
    void setDataSource(String url, Map<String, String> headers, long startPosMs);

    /** 设置倍速 (0.5 ~ 2.0) */
    void setSpeed(float speed);

    /** 跳转到指定位置 ms */
    void seekTo(long positionMs);

    /** 开始播放 */
    void play();

    /** 暂停 */
    void pause();

    /** 获取当前位置 ms */
    long getPosition();

    /** 获取总时长 ms */
    long getDuration();

    /** 是否就绪可播放 (STATE_READY) */
    boolean isReady();

    /** 是否在播放中 */
    boolean isPlaying();

    /** 获取可用清晰度列表(单码率源为空或仅一项) */
    List<Quality> getQualities();

    /** 切换到指定清晰度 */
    void selectQuality(Quality quality);

    /** 获取当前实际清晰度(未知返回 null) */
    Quality getCurrentQuality();

    /** 获取可用的字幕轨道列表(无内嵌字幕返回空列表) */
    List<SubtitleTrack> getSubtitleTracks();

    /** 选择字幕轨道(传 null 表示关闭字幕) */
    void selectSubtitle(SubtitleTrack track);

    /** 加载外部字幕文件(.srt/.ass/.vtt), 传 null/空表示移除外部字幕 */
    void setSubtitleUrl(String url);

    /** 获取可用的音轨列表(无多音轨返回空列表) */
    List<AudioTrack> getAudioTracks();

    /** 选择音轨(传 null 表示恢复默认音轨) */
    void selectAudioTrack(AudioTrack track);

    /** 恢复默认轨道选择(清除用户对音轨/字幕的覆盖) */
    void resetTracks();

    /** 设置监听 */
    void setListener(Listener listener);

    /** 释放资源 */
    void release();
}
