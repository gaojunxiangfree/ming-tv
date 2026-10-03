package com.seanming.player.util;

import android.content.Context;

/**
 * 屏幕适配工具: 根据屏幕宽度动态计算网格列数, 适配电视 / 投影仪 / 手机等不同分辨率.
 * 投影仪/电视通常分辨率更高, 用更大的目标列宽(更少的列)让海报更大更易读.
 */
public final class ScreenUtil {

    private ScreenUtil() {}

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

    /** 海报网格列数(首页/搜索结果), 每列约 240dp 让投影仪上海报更大更易读 */
    public static int posterColumns(Context ctx) {
        return calcGridColumns(ctx, 240, 3, 8);
    }

    /** 选集网格列数(详情页), 单集按钮较小、列数更多 */
    public static int episodeColumns(Context ctx) {
        return calcGridColumns(ctx, 130, 5, 12);
    }

    /**
     * 海报区高度(px), 依据屏幕可用高度动态计算, 保证横屏电视/投影仪上
     * 至少能看到完整一行海报(大字号顶栏约占 190dp, 文字标签约占 44dp).
     * 这样 1080p 手机模拟器与 4K 投影仪都不会因海报过高把网格挤没.
     */
    public static int posterHeightPx(Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int heightDp = (int) (ctx.getResources().getDisplayMetrics().heightPixels / density);
        int poster = heightDp - 190 - 100; // 去掉顶栏/功能栏/分类栏 + 片名/备注文字后, 整行海报完整可见
        if (poster < 130) poster = 130;
        if (poster > 320) poster = 320;
        return Math.round(poster * density);
    }
}
