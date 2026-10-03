package com.seanming.player.ui.splash;

import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.seanming.player.R;
import com.seanming.player.ui.home.HomeActivity;
import com.seanming.player.util.ImageUtil;
import com.seanming.player.util.PrefUtils;

import java.util.Locale;

/** 开屏页: 可配置的情话 + 背景图.
 *  情话可开启/关闭、内容可自定义; 背景图可设置 URL; 进入时语音播报一句问候. */
public class SplashActivity extends AppCompatActivity {

    private static final String TAG = "SplashActivity";
    private static final String TTS_GREETING_ID = "splash_greeting";

    /** 开屏语音播报的 TTS 实例, 系统无 TTS 引擎时为 null */
    private TextToSpeech tts;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        LinearLayout poemBox = findViewById(R.id.poemBox);
        boolean poemOn = !"0".equals(PrefUtils.get(PrefUtils.K_SPLASH_POEM_ON, "1"));

        // 情话关闭: 隐藏情话区域, 仅保留爱心/标题/署名
        if (!poemOn) {
            poemBox.setVisibility(View.GONE);
        } else {
            applyPoem(poemBox);
        }

        // 背景图: 设置了 URL 则加载; 有照片时用半透明黑遮罩代替渐变光晕, 保证文字可读
        String bg = PrefUtils.get(PrefUtils.K_SPLASH_BG, "");
        View overlay = findViewById(R.id.overlay);
        View scrim = findViewById(R.id.vScrim);
        ImageView ivBg = (ImageView) findViewById(R.id.ivSplashBg);
        if (bg != null && !bg.isEmpty()) {
            ivBg.setVisibility(View.VISIBLE);
            if (overlay != null) overlay.setVisibility(View.GONE);
            if (scrim != null) scrim.setVisibility(View.VISIBLE);
            ImageUtil.loadPoster(ivBg, bg);
        }

        // 逐行淡入
        if (poemOn) {
            fadeIn(R.id.tvHeart, 200, 800);
            fadeIn(R.id.tvSplashTitle, 700, 1000);
            poemBox.setAlpha(0f);
            poemBox.animate().alpha(1f).setStartDelay(1500).setDuration(1500);
            fadeIn(R.id.tvSplashSub, 4200, 1000);
        } else {
            // 无情话时加快进入
            fadeIn(R.id.tvHeart, 100, 600);
            fadeIn(R.id.tvSplashTitle, 400, 800);
            fadeIn(R.id.tvSplashSub, 800, 800);
        }

        // 打开 App 时播报一句问候
        speakGreeting();

        // 到达停留时长后进入首页
        new Handler(Looper.getMainLooper()).postDelayed(this::goHome, 5500);
    }

    @Override
    protected void onDestroy() {
        releaseTts();
        super.onDestroy();
    }

    /** 打开 App 时用系统 TTS 播报问候语.
     *  部分电视盒子/投影仪未内置 TTS 引擎, 此时静默跳过, 不能影响开屏与进入首页. */
    private void speakGreeting() {
        try {
            tts = new TextToSpeech(getApplicationContext(), status -> {
                if (tts == null) return;
                if (status != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "TTS 初始化失败, 跳过语音播报: status=" + status);
                    releaseTts();
                    return;
                }
                // 问候语是英文: 优先美式英语, 引擎不支持时退回系统默认语言(尽力朗读)
                int lang = tts.setLanguage(Locale.US);
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "TTS 不支持美式英语, 退回系统默认语言: result=" + lang);
                    tts.setLanguage(Locale.getDefault());
                }
                // 强制走媒体音量通道: 电视/投影仪上默认通道可能被静音或路由异常
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {
                        Log.i(TAG, "TTS 开始播报: " + utteranceId);
                    }

                    @Override public void onDone(String utteranceId) {
                        Log.i(TAG, "TTS 播报完成: " + utteranceId);
                    }

                    @Override public void onError(String utteranceId) {
                        Log.w(TAG, "TTS 播报失败: " + utteranceId);
                    }
                });
                String greeting = getString(R.string.tts_greeting);
                int ret = tts.speak(greeting, TextToSpeech.QUEUE_FLUSH, null, TTS_GREETING_ID);
                Log.i(TAG, "TTS speak(\"" + greeting + "\") -> " + ret);
            });
        } catch (Throwable t) {
            // 无 TTS 引擎等异常情况: 降级为静默, 不影响开屏流程
            Log.w(TAG, "TTS 不可用, 跳过语音播报", t);
            releaseTts();
        }
    }

    private void releaseTts() {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Throwable ignored) {
            }
            tts = null;
        }
    }

    private void goHome() {
        if (isFinishing()) return;
        startActivity(new Intent(SplashActivity.this, HomeActivity.class));
        finish();
    }

    /** 把默认/自定义情话逐行填写到 poemBox(每行一个 TextView) */
    private void applyPoem(LinearLayout poemBox) {
        String[] lines;
        String custom = PrefUtils.get(PrefUtils.K_SPLASH_POEM, "");
        if (custom != null && !custom.trim().isEmpty()) {
            // 自定义内容: 按行拆分
            lines = custom.trim().split("\\n");
        } else {
            lines = DEFAULT_POEM;
        }
        // 复用已定义的诗句 View 来填行, 行数不足的隐藏
        TextView[] views = {
                findViewById(R.id.tvSplashPoem1),
                findViewById(R.id.tvSplashPoem2),
                findViewById(R.id.tvSplashPoem3),
                findViewById(R.id.tvSplashPoem4),
                findViewById(R.id.tvSplashPoem5),
                findViewById(R.id.tvSplashPoem6),
        };
        for (int i = 0; i < views.length; i++) {
            if (i < lines.length) {
                String line = lines[i];
                int at = line.indexOf('@');
                // 支持 "文本@字号" 自定义字号(如 "你好@40")
                float size = 26f;
                String text = line;
                if (at > 0) {
                    try { size = Float.parseFloat(line.substring(at + 1)); } catch (Throwable ignored) {}
                    text = line.substring(0, at);
                }
                views[i].setText(text.trim());
                views[i].setTextSize(size);
                // 柔和文字阴影: 在照片背景上也能清晰分离, 对默认渐变背景无副作用
                views[i].setShadowLayer(8f, 0f, 2f, 0xCC000000);
                views[i].setVisibility(View.VISIBLE);
            } else {
                views[i].setVisibility(View.GONE);
            }
        }
    }

    /** 在 delay 毫秒后开始淡入, 持续 duration 毫秒 */
    private void fadeIn(int viewId, long delay, long duration) {
        View v = findViewById(viewId);
        if (v == null) return;
        v.setAlpha(0f);
        v.animate().alpha(1f).setStartDelay(delay).setDuration(duration).start();
    }

    private static final String[] DEFAULT_POEM = {
            "世间所有的浪漫",
            "都不及你在我身旁",
            "这方小小的屏幕",
            "装不下我满心的欢喜",
            "只好把每个夜晚",
            "都点亮成你的模样",
    };
}