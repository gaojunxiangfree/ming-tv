package com.seanming.player.ui.play;

import android.content.Context;
import android.view.ViewGroup;

import com.seanming.player.R;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;

import com.seanming.player.util.OkHttpUtil;
import com.seanming.player.util.PrefUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ExoPlayer 内核实现.
 * 包装 Media3 ExoPlayer, 渲染视图用 PlayerView.
 * 支持多码率源的清晰度切换 + 分辨率展示 + 音轨切换.
 * 渲染方式由设置页/播放页「渲染」按钮决定: TextureView(默认) / SurfaceView.
 */
public class ExoKernel implements PlayerKernel {

    private ExoPlayer player;
    private PlayerView playerView;
    private Listener listener;
    private boolean playWhenReady = true;
    private DefaultTrackSelector trackSelector;
    private final List<Quality> qualities = new ArrayList<>();
    private final List<SubtitleTrack> subtitleTracks = new ArrayList<>();
    private final List<TrackGroup> subtitleTrackGroups = new ArrayList<>();
    private final List<AudioTrack> audioTracks = new ArrayList<>();
    private final List<TrackGroup> audioTrackGroups = new ArrayList<>();
    private Quality currentQuality;
    private String currentUrl;
    private OkHttpDataSource.Factory dataSourceFactory;

    @Override
    public void init(Context ctx, ViewGroup container) {
        // 渲染方式: 设置页/播放页 K_RENDER 决定 TextureView / SurfaceView
        boolean surface = "surface".equals(PrefUtils.get(PrefUtils.K_RENDER, "texture"));
        playerView = (PlayerView) android.view.LayoutInflater.from(ctx)
                .inflate(surface ? R.layout.player_surface : R.layout.player_texture, container, false);
        playerView.setUseController(false);
        container.removeAllViews();
        container.addView(playerView);

        // 创建 ExoPlayer, 使用 OkHttp DataSource 支持自定义 headers 和 UA,
        // 使用 DefaultTrackSelector 支持多码率清晰度切换
        trackSelector = new DefaultTrackSelector(ctx);
        dataSourceFactory = new OkHttpDataSource.Factory(OkHttpUtil.client())
                .setUserAgent(OkHttpUtil.UA);
        player = new ExoPlayer.Builder(ctx)
                .setTrackSelector(trackSelector)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(ctx)
                        .setDataSourceFactory(new AdFilterDataSource.Factory(dataSourceFactory)))
                .build();
        playerView.setPlayer(player);
        player.setPlayWhenReady(playWhenReady);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                int mapped;
                switch (state) {
                    case Player.STATE_IDLE: mapped = STATE_IDLE; break;
                    case Player.STATE_BUFFERING: mapped = STATE_BUFFERING; break;
                    case Player.STATE_READY: mapped = STATE_READY; break;
                    case Player.STATE_ENDED: mapped = STATE_ENDED; break;
                    default: mapped = state;
                }
                if (listener != null) listener.onStateChanged(mapped);
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (listener != null) listener.onIsPlayingChanged(isPlaying);
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                if (listener != null) listener.onError(error.getMessage());
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                // 收集所有视频轨道的分辨率作为可切换清晰度
                qualities.clear();
                subtitleTracks.clear();
                subtitleTrackGroups.clear();
                audioTracks.clear();
                audioTrackGroups.clear();
                for (Tracks.Group group : tracks.getGroups()) {
                    if (group.getType() == C.TRACK_TYPE_VIDEO) {
                        for (int i = 0; i < group.length; i++) {
                            Format f = group.getTrackFormat(i);
                            if (f.width > 0 && f.height > 0) {
                                qualities.add(new Quality(labelFor(f.width, f.height), f.width, f.height));
                            }
                        }
                    } else if (group.getType() == C.TRACK_TYPE_TEXT) {
                        // 收集字幕轨道(记录 TrackGroup 供选择时使用)
                        TrackGroup tg = group.getMediaTrackGroup();
                        if (tg == null) continue;
                        int gi = subtitleTrackGroups.size();
                        subtitleTrackGroups.add(tg);
                        for (int i = 0; i < group.length; i++) {
                            Format f = group.getTrackFormat(i);
                            String lang = f.language == null ? "" : f.language;
                            subtitleTracks.add(new SubtitleTrack(
                                    labelForLang(lang) + (i > 0 ? " " + (i + 1) : ""),
                                    lang, gi, i));
                        }
                    } else if (group.getType() == C.TRACK_TYPE_AUDIO) {
                        // 收集音轨(多音轨源: 国语/粤语/原声 等)
                        TrackGroup tg = group.getMediaTrackGroup();
                        if (tg == null) continue;
                        int gi = audioTrackGroups.size();
                        audioTrackGroups.add(tg);
                        for (int i = 0; i < group.length; i++) {
                            Format f = group.getTrackFormat(i);
                            String lang = f.language == null ? "" : f.language;
                            String label = labelForAudioLang(lang, i);
                            audioTracks.add(new AudioTrack(label, lang, gi, i));
                        }
                    }
                }
                android.util.Log.i("ExoKernel", "onTracksChanged video=" + qualities.size()
                        + " subtitle=" + subtitleTracks.size() + " audio=" + audioTracks.size());
                if (listener != null) listener.onQualitiesChanged(new ArrayList<>(qualities));
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    currentQuality = new Quality(labelFor(videoSize.width, videoSize.height),
                            videoSize.width, videoSize.height);
                    android.util.Log.i("ExoKernel", "onVideoSizeChanged " + videoSize.width + "x" + videoSize.height);
                    if (listener != null) listener.onVideoSizeChanged(videoSize.width, videoSize.height);
                }
            }
        });
    }

    @Override
    public void setDataSource(String url, Map<String, String> headers, long startPosMs) {
        if (player == null) return;
        currentUrl = url;
        // 下发源解析返回的请求头(如 Referer / Cookie / UA 等),
        // 部分源缺 Referer 等请求头会加载失败或黑屏, 必须在建立数据源前设置
        if (headers != null && !headers.isEmpty() && dataSourceFactory != null) {
            try {
                dataSourceFactory.setDefaultRequestProperties(headers);
            } catch (Throwable ignored) {}
        }
        MediaItem item = new MediaItem.Builder().setUri(url).build();
        player.setMediaItem(item, startPosMs);
        player.prepare();
    }

    @Override
    public void setSpeed(float speed) {
        if (player != null) player.setPlaybackSpeed(speed);
    }

    @Override
    public void seekTo(long positionMs) {
        if (player != null) player.seekTo(positionMs);
    }

    @Override
    public void play() {
        if (player != null) {
            playWhenReady = true;
            player.setPlayWhenReady(true);
        }
    }

    @Override
    public void pause() {
        if (player != null) {
            playWhenReady = false;
            player.setPlayWhenReady(false);
        }
    }

    @Override
    public long getPosition() {
        return player != null ? player.getCurrentPosition() : 0;
    }

    @Override
    public long getDuration() {
        return player != null ? player.getDuration() : 0;
    }

    @Override
    public boolean isReady() {
        return player != null && player.getPlaybackState() == Player.STATE_READY;
    }

    @Override
    public boolean isPlaying() {
        return player != null && player.getPlayWhenReady()
                && player.getPlaybackState() == Player.STATE_READY;
    }

    @Override
    public List<Quality> getQualities() {
        return new ArrayList<>(qualities);
    }

    @Override
    public void selectQuality(Quality quality) {
        if (player == null || quality == null) return;
        // 通过 min/max 尺寸约束, 让 ExoPlayer 选择指定分辨率的轨道
        TrackSelectionParameters params = player.getTrackSelectionParameters()
                .buildUpon()
                .setMaxVideoSize(quality.width, quality.height)
                .setMinVideoSize(quality.width, quality.height)
                .build();
        player.setTrackSelectionParameters(params);
    }

    @Override
    public Quality getCurrentQuality() {
        return currentQuality;
    }

    @Override
    public List<SubtitleTrack> getSubtitleTracks() {
        return new ArrayList<>(subtitleTracks);
    }

    @Override
    public void selectSubtitle(SubtitleTrack track) {
        if (player == null) return;
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
        if (track == null) {
            // 关闭字幕: 清除文本轨道的 override
            b.clearOverridesOfType(C.TRACK_TYPE_TEXT);
        } else {
            TrackGroup tg = subtitleTrackGroups.get(track.groupIndex);
            b.setOverrideForType(new TrackSelectionOverride(tg, track.trackIndex));
        }
        player.setTrackSelectionParameters(b.build());
    }

    @Override
    public void setSubtitleUrl(String url) {
        if (player == null || currentUrl == null) return;
        long pos = player.getCurrentPosition();
        MediaItem.Builder b = new MediaItem.Builder().setUri(currentUrl);
        if (url != null && !url.isEmpty()) {
            MediaItem.SubtitleConfiguration sub = new MediaItem.SubtitleConfiguration.Builder(
                    android.net.Uri.parse(url))
                    .setMimeType(mimeFor(url))
                    .setLanguage("zh")
                    .build();
            b.setSubtitleConfigurations(java.util.Collections.singletonList(sub));
        }
        player.setMediaItem(b.build(), pos);
        player.prepare();
        android.util.Log.i("ExoKernel", "setSubtitleUrl: " + (url == null ? "clear" : url));
    }

    /** 根据扩展名推断字幕 MIME 类型 */
    private static String mimeFor(String url) {
        String u = url.toLowerCase();
        if (u.contains(".ass") || u.contains(".ssa")) return MimeTypes.TEXT_SSA;
        if (u.contains(".vtt")) return MimeTypes.TEXT_VTT;
        return MimeTypes.APPLICATION_SUBRIP; // .srt 默认
    }

    /** 按分辨率高度映射常见清晰度标签 */
    private static String labelFor(int width, int height) {
        if (height >= 2160 || width >= 3840) return "4K";
        if (height >= 1080 || width >= 1920) return "1080p";
        if (height >= 720 || width >= 1280) return "720p";
        if (height >= 480 || width >= 640) return "480p";
        if (height >= 360) return "360p";
        return height + "p";
    }

    /** 按语言码映射字幕标签 */
    private static String labelForLang(String lang) {
        if (lang == null || lang.isEmpty()) return "字幕";
        switch (lang.toLowerCase()) {
            case "zh": case "chi": case "zho": case "cmn": return "中文";
            case "en": case "eng": return "English";
            case "ja": case "jpn": return "日语";
            case "ko": case "kor": return "韩语";
            case "zh-hans": return "简体中文";
            case "zh-hant": return "繁体中文";
            default: return lang;
        }
    }

    /** 按语言码映射音轨标签: 国语/粤语/日语/韩语/原声 等 */
    private static String labelForAudioLang(String lang, int index) {
        if (lang == null || lang.isEmpty()) {
            // 无语言码: 组内第 0 轨通常为默认音轨
            return index == 0 ? "原声" : "音轨 " + (index + 1);
        }
        switch (lang.toLowerCase()) {
            case "zh": case "chi": case "zho": case "cmn": return "国语";
            case "yue": return "粤语";
            case "en": case "eng": return "English";
            case "ja": case "jpn": return "日语";
            case "ko": case "kor": return "韩语";
            case "zh-hans": return "国语";
            case "zh-hant": return "粤语";
            default: return lang;
        }
    }

    @Override
    public List<AudioTrack> getAudioTracks() {
        return new ArrayList<>(audioTracks);
    }

    @Override
    public void selectAudioTrack(AudioTrack track) {
        if (player == null) return;
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
        if (track == null) {
            // 恢复默认音轨
            b.clearOverridesOfType(C.TRACK_TYPE_AUDIO);
        } else {
            TrackGroup tg = audioTrackGroups.get(track.groupIndex);
            b.setOverrideForType(new TrackSelectionOverride(tg, track.trackIndex));
        }
        player.setTrackSelectionParameters(b.build());
    }

    @Override
    public void resetTracks() {
        if (player == null) return;
        // 清除用户对音轨/字幕的覆盖, 恢复默认选择
        TrackSelectionParameters params = player.getTrackSelectionParameters()
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build();
        player.setTrackSelectionParameters(params);
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void release() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (playerView != null) {
            playerView.setPlayer(null);
            if (playerView.getParent() instanceof ViewGroup) {
                ((ViewGroup) playerView.getParent()).removeView(playerView);
            }
            playerView = null;
        }
    }
}
