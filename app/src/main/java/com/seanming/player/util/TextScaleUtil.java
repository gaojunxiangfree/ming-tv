package com.seanming.player.util;

import android.util.TypedValue;
import android.widget.TextView;

/**
 * 动态文字大小: 依据设置页"文字大小"全局缩放系数对 UI 文字进行缩放.
 * 档位: "0" 标准(1.0) / "1" 偏大(1.15) / "2" 超大(1.3).
 */
public class TextScaleUtil {

    public static float scale() {
        String v = PrefUtils.get(PrefUtils.K_TEXT_SCALE, "0");
        switch (v) {
            case "1": return 1.15f;
            case "2": return 1.3f;
            default: return 1.0f;
        }
    }

    /** 把基准 sp 字号按全局缩放后设置到 TextView */
    public static void apply(TextView tv, float baseSp) {
        if (tv == null) return;
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp * scale());
    }
}
