package com.seanming.player.util;

import android.util.TypedValue;
import android.widget.TextView;

import com.seanming.player.App;

/**
 * 动态文字大小: 依据设置页"文字大小"全局缩放系数对 UI 文字进行缩放.
 * 档位: "0" 标准(1.0) / "1" 偏大(1.15) / "2" 超大(1.3).
 */
public class TextScaleUtil {

    public static float scale() {
        float s;
        String v = PrefUtils.get(PrefUtils.K_TEXT_SCALE, "0");
        switch (v) {
            case "1": s = 1.15f; break;
            case "2": s = 1.3f; break;
            default: s = 1.0f;
        }
        // 电视/投影仪档额外加大(投影距离远), 覆盖代码里以基准 sp 创建的视图
        if (App.get() != null && ScreenUtil.deviceTier(App.get()) == ScreenUtil.TIER_TV) {
            s *= 1.15f;
        }
        return s;
    }

    /** 把基准 sp 字号按全局缩放后设置到 TextView */
    public static void apply(TextView tv, float baseSp) {
        if (tv == null) return;
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp * scale());
    }
}
