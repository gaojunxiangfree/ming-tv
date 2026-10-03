package com.seanming.player.util;

import android.content.Context;
import android.content.SharedPreferences;

/** 配置持久化(SharedPreferences) */
public class PrefUtils {

    private static final String FILE = "sean_ming_pref";
    private static SharedPreferences sp;

    public static void init(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static void put(String key, String value) { sp.edit().putString(key, value).apply(); }
    public static String get(String key, String def) { return sp.getString(key, def); }
    public static void putBool(String key, boolean v) { sp.edit().putBoolean(key, v).apply(); }
    public static boolean getBool(String key, boolean def) { return sp.getBoolean(key, def); }
    public static void putLong(String key, long v) { sp.edit().putLong(key, v).apply(); }
    public static long getLong(String key, long def) { return sp.getLong(key, def); }
    public static void putInt(String key, int v) { sp.edit().putInt(key, v).apply(); }
    public static int getInt(String key, int def) { return sp.getInt(key, def); }

    // ---- 常用键 ----
    public static final String K_API_URL = "api_url";
    /** 已推送的接口源列表(JSON 数组), 多源管理时可选其一生效 */
    public static final String K_API_SOURCES = "api_sources";
    public static final String K_HOME_SITE = "home_site";
    public static final String K_ANIMATION = "animation_enabled";
    public static final String K_BG_EFFECT = "bg_effect_enabled";
    public static final String K_SPEED = "play_speed";
    public static final String K_SKIP_HEAD = "skip_head_sec";
    public static final String K_SKIP_TAIL = "skip_tail_sec";
    /** 播放器内核选择: "exo" 或 "ijk" */
    public static final String K_PLAYER = "player_kernel";
    /** IJK 解码模式: "hardware" 硬解 / "software" 软解, 仅 K_PLAYER=ijk 时生效 */
    public static final String K_IJK_DECODER = "ijk_decoder";
    /** 搜索历史记录(JSON 数组字符串) */
    public static final String K_SEARCH_HISTORY = "search_history";
    /** 指定搜索源(JSON 数组字符串, 站点 key); 为空表示全部可搜索站点 */
    public static final String K_SEARCH_SITES = "search_sites";

    // ---- 弹幕样式 ----
    public static final String K_DANMU_ON = "danmaku_on";
    public static final String K_DANMU_SIZE = "danmaku_size";
    public static final String K_DANMU_ALPHA = "danmaku_alpha";
    public static final String K_DANMU_SPEED = "danmaku_speed";
    public static final String K_DANMU_AREA = "danmaku_area";
    /** 字幕样式 */
    public static final String K_SUBTITLE_SIZE = "subtitle_size";
    public static final String K_SUBTITLE_COLOR = "subtitle_color";
    /** 播放列表布局: "column" 垂直列表 / "grid" 网格 */
    public static final String K_PLAYLIST_LAYOUT = "playlist_layout";
    /** P2P 加速开关 */
    public static final String K_P2P = "p2p_enabled";
    /** 渲染方式: "texture" TextureView / "surface" SurfaceView, 仅 Exo 内核生效 */
    public static final String K_RENDER = "render_mode";
    /** 循环播放开关: "1"开 / "0"关, 播完自动重播本集 */
    public static final String K_LOOP = "loop_enabled";
    /** 直播频道记忆: 上次停留的分组序号 / 频道序号 */
    public static final String K_LIVE_GROUP = "live_group_index";
    public static final String K_LIVE_CHANNEL = "live_channel_index";
    /** 动态文字大小档位: "0"标准 / "1"偏大 / "2"超大 */
    public static final String K_TEXT_SCALE = "text_scale";

    // ---- 开屏页 ----
    /** 情话展示开关: "1"开 / "0"关 */
    public static final String K_SPLASH_POEM_ON = "splash_poem_on";
    /** 自定义情话内容(多行, 以换行分隔; 为空则使用默认) */
    public static final String K_SPLASH_POEM = "splash_poem";
    /** 开屏背景图 URL(为空则使用默认渐变背景) */
    public static final String K_SPLASH_BG = "splash_bg";

    /** 默认播放器内核 */
    public static final String PLAYER_EXO = "exo";
    public static final String PLAYER_IJK = "ijk";
    public static final String IJK_HARDWARE = "hardware";
    public static final String IJK_SOFTWARE = "software";

    /** 片头跳过持久化键(按 视频ID+线路+集数 区分), 存秒数 */
    public static String introKey(String vodId, String flag, int index) {
        return "intro_" + vodId + "_" + flag + "_" + index;
    }

    /** 片尾跳过持久化键(按 视频ID+线路+集数 区分), 存秒数 */
    public static String outroKey(String vodId, String flag, int index) {
        return "outro_" + vodId + "_" + flag + "_" + index;
    }
}
