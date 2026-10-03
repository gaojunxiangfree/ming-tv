package com.seanming.player.ui.play;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Random;

/**
 * 轻量弹幕视图: 每条弹幕一个 TextView, 从右往左滚动, 随机纵向轨道.
 * 不依赖第三方弹幕库, 兼容现代 Android.
 */
public class DanmakuView extends FrameLayout {

    private static final String[] COLORS = {
            "#FFFFFF", "#FFEB3B", "#4FC3F7", "#FF8A80", "#A5D6A7", "#CE93D8",
    };
    private final Random random = new Random();
    private boolean enabled = true;
    /** 已占用的轨道(行号), 用于错开弹幕避免重叠 */
    private boolean[] trackUsed = new boolean[8];

    /** 可配置样式: 字号 sp */
    private float textSize = 24f;
    /** 弹幕透明度 0~1 */
    private float alpha = 1f;
    /** 滚动快慢系数(默认 1.0, 越大越快) */
    private float speed = 1f;
    /** 显示区域高度占比(0~1, 弹幕占用屏幕上部比例) */
    private float areaRatio = 0.45f;
    /** 纵向轨道数(根据 areaRatio 动态计算) */
    private int trackCount = 8;

    public DanmakuView(Context context) {
        super(context);
    }

    /** XML 布局反射膨胀所需构造器, 缺失会导致 InflateException */
    public DanmakuView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DanmakuView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** 设置弹幕样式并刷新已显示弹幕的高清参数 */
    public void applyStyle(float sizeSp, float alpha, float speed, float areaRatio) {
        this.textSize = sizeSp;
        this.alpha = Math.max(0.05f, Math.min(1f, alpha));
        this.speed = speed > 0.1f ? speed : 1f;
        this.areaRatio = Math.max(0.1f, Math.min(0.9f, areaRatio));
        this.trackCount = (int) (areaRatio * 16);
        if (trackCount < 4) trackCount = 4;
        if (trackCount > 16) trackCount = 16;
        // 按 trackCount 重建轨道数组大小, 保留原状态
        if (trackUsed.length != trackCount) {
            boolean[] next = new boolean[trackCount];
            System.arraycopy(trackUsed, 0, next, 0, Math.min(trackUsed.length, trackCount));
            trackUsed = next;
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            removeAllViews();
            for (int i = 0; i < trackUsed.length; i++) trackUsed[i] = false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 发送一条弹幕 */
    public void send(String text) {
        if (!enabled || text == null || text.trim().isEmpty()) return;
        TextView tv = new TextView(getContext());
        tv.setText(text.trim());
        tv.setTextSize(textSize);
        tv.setTextColor(Color.parseColor(COLORS[random.nextInt(COLORS.length)]));
        tv.setAlpha(alpha);
        tv.setSingleLine(true);
        tv.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        int tw = tv.getMeasuredWidth();
        if (tw <= 0) tw = 400;

        int track = pickTrack();
        int screenH = getHeight() > 0 ? getHeight() : 1080;
        int top = (int) (screenH * 0.05f) + track * (int) (screenH * areaRatio / trackCount);

        int startX = getWidth() > 0 ? getWidth() : 1920;
        tv.setX(startX);
        tv.setY(top);
        addView(tv, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        float duration = (5000f + random.nextInt(2000)) / speed;
        pauseView(tv);
        tv.animate()
                .x(-tw)
                .setDuration((long) duration)
                .withEndAction(() -> {
                    removeView(tv);
                    trackUsed[track] = false;
                })
                .start();
        resumeView(tv);
    }

    private void pauseView(TextView tv) {}

    private void resumeView(TextView tv) {}

    private int pickTrack() {
        // 优先选空闲轨道, 否则随机
        for (int i = 0; i < trackUsed.length; i++) {
            if (!trackUsed[i]) {
                trackUsed[i] = true;
                return i;
            }
        }
        return random.nextInt(trackUsed.length);
    }
}
