package com.seanming.player.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.widget.TextView;

import com.seanming.player.R;

/**
 * 屏幕适配工具: 统一判定设备档位, 并据此给出方向策略、网格密度与字号.
 *
 * <p>档位判定是全局唯一口径(App 的方向守卫与这里共用), 规则:
 * <ul>
 *   <li><b>电视/投影仪</b>: 电视 UI 模式, 或设备无触摸屏, 或最小宽度 &gt;= 900dp</li>
 *   <li><b>平板</b>: 最小宽度 &gt;= 600dp</li>
 *   <li><b>手机</b>: 其余</li>
 * </ul>
 *
 * <p>注意不能只按最小宽度判断: 1080p 电视盒子 / Android TV 是 1920x1080 @320dpi,
 * 最小宽度只有 540dp, 会被误判成手机而锁竖屏、用手机字号. 因此补充"无触摸屏"这条
 * 硬件判据(电视/投影仪/盒子通常没有触摸屏), 宽度兜底则交给资源限定符(见 values-w840dp/)。
 */
public final class ScreenUtil {

    /** 手机档 */
    public static final int TIER_PHONE = 0;
    /** 平板档 */
    public static final int TIER_TABLET = 1;
    /** 电视/投影仪档 */
    public static final int TIER_TV = 2;

    private ScreenUtil() {}

    // ================= 档位判定 =================

    /** 当前设备档位 */
    public static int deviceTier(Context ctx) {
        if (isTelevision(ctx.getResources().getConfiguration())) return TIER_TV;
        if (!hasTouchScreen(ctx)) return TIER_TV;
        int sw = smallestWidthDp(ctx);
        if (sw >= 900) return TIER_TV;
        if (sw >= 600) return TIER_TABLET;
        // 1080p 电视盒子/投影: 横屏下可用宽度很大(960dp), 但最小宽度只有 540dp,
        // 命不中上面两条; 阈值与资源限定符 values-w840dp/ 保持一致, 避免出现
        // "字号走了大屏档、方向与网格却按手机算"的口径分裂.
        // 放在 sw>=600 之后, 所以平板(横屏宽度也很大但最小宽度>=600)不会被误判.
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int widthDp = (int) (dm.widthPixels / dm.density);
        int heightDp = (int) (dm.heightPixels / dm.density);
        if (widthDp >= 840 && heightDp <= 620) return TIER_TV;
        return TIER_PHONE;
    }

    /** 是否大屏设备(电视/投影仪/平板): 需要锁定横屏, 并共用一套大字号 */
    public static boolean isLargeScreen(Context ctx) {
        return deviceTier(ctx) != TIER_PHONE;
    }

    private static boolean isTelevision(Configuration c) {
        return (c.uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    /** 是否具备触摸屏: 电视 / 投影仪 / 盒子通常没有 */
    private static boolean hasTouchScreen(Context ctx) {
        return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    /** 最小宽度 dp; 个别设备未上报时用屏幕短边兜底 */
    private static int smallestWidthDp(Context ctx) {
        Configuration c = ctx.getResources().getConfiguration();
        int sw = c.smallestScreenWidthDp;
        if (sw > 0) return sw;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return (int) (Math.min(dm.widthPixels, dm.heightPixels) / dm.density);
    }

    // ================= 网格 =================

    /**
     * 计算网格列数.
     *
     * @param targetDp 目标每列宽度(dp), 越大则列越少、海报越大
     * @param minCols  最小列数
     * @param maxCols  最大列数
     */
    public static int calcGridColumns(Context ctx, int targetDp, int minCols, int maxCols) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int widthDp = (int) (ctx.getResources().getDisplayMetrics().widthPixels / density);
        int cols = Math.round((float) widthDp / targetDp);
        if (cols < minCols) cols = minCols;
        if (cols > maxCols) cols = maxCols;
        return cols;
    }

    /** 海报网格列数(首页/搜索结果): 手机更密, 平板居中, 电视海报更大更少 */
    public static int posterColumns(Context ctx) {
        switch (deviceTier(ctx)) {
            case TIER_PHONE:
                return calcGridColumns(ctx, 110, 2, 4);
            case TIER_TABLET:
                return calcGridColumns(ctx, 150, 3, 6);
            default:
                return calcGridColumns(ctx, 240, 3, 8);
        }
    }

    /** 选集网格列数(详情页): 单集按钮较小, 列数比海报更多 */
    public static int episodeColumns(Context ctx) {
        switch (deviceTier(ctx)) {
            case TIER_PHONE:
                return calcGridColumns(ctx, 90, 4, 7);
            case TIER_TABLET:
                return calcGridColumns(ctx, 110, 5, 9);
            default:
                return calcGridColumns(ctx, 130, 5, 12);
        }
    }

    /**
     * 海报高度(px).
     *
     * <p>手机/平板: 直接取 {@code sm_poster_h} —— 该资源已按档位分流, 避免"布局里声明一个值、
     * 代码里又写死另一个值"两处不同步(会出现图片被父容器裁切)。
     * <p>电视/投影仪: 按屏幕可用高度动态计算, 保证横屏下每行海报完整可见。
     */
    public static int posterHeightPx(Context ctx) {
        if (deviceTier(ctx) != TIER_TV) {
            return ctx.getResources().getDimensionPixelSize(R.dimen.sm_poster_h);
        }
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int heightDp = (int) (dm.heightPixels / dm.density);
        // 去掉顶栏/功能栏/分类栏 + 片名/备注文字后, 整行海报完整可见
        int poster = heightDp - 190 - 100;
        if (poster < 130) poster = 130;
        if (poster > 320) poster = 320;
        return Math.round(poster * dm.density);
    }

    // ================= 字号 =================

    /**
     * 用按档位分流的字号资源(如 {@code R.dimen.sm_text_label})设置字号.
     * 代码里动态创建的文本走这里, 保证与布局里的 {@code @dimen/sm_text_*} 同源。
     */
    public static void setTextSize(TextView tv, int dimenRes) {
        if (tv == null) return;
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, tv.getResources().getDimension(dimenRes));
    }
}
