package com.seanming.player.ui.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.seanming.player.R;
import com.seanming.player.api.ApiConfig;
import com.seanming.player.data.AppDatabase;
import com.seanming.player.server.WifiConfigServer;
import com.seanming.player.util.PrefUtils;
import com.seanming.player.util.TextScaleUtil;
import com.seanming.player.util.ThreadUtils;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/** 设置页: 接口配置(多源管理) + 站点切换 + 扫码推送 + 播放器内核 */
public class SettingsActivity extends AppCompatActivity {

    private EditText etApiUrl;
    private TextView tvApiStatus;
    private TextView tvPushUrl;
    private LinearLayout playerContainer;
    private LinearLayout ijkDecoderContainer;
    private LinearLayout danmuStyleContainer;
    private LinearLayout subtitleStyleContainer;
    private LinearLayout playlistContainer;
    private LinearLayout textScaleContainer;
    private TextView tvTextScaleSample;
    private LinearLayout splashContainer;
    private LinearLayout apiSourcesContainer;
    private LinearLayout renderContainer;
    private LinearLayout danmuToggleContainer;
    private LinearLayout loopContainer;
    private LinearLayout p2pContainer;
    private LinearLayout dataContainer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etApiUrl = findViewById(R.id.etApiUrl);
        tvApiStatus = findViewById(R.id.tvApiStatus);
        tvPushUrl = findViewById(R.id.tvPushUrl);
        apiSourcesContainer = findViewById(R.id.apiSourcesContainer);
        playerContainer = findViewById(R.id.playerContainer);
        ijkDecoderContainer = findViewById(R.id.ijkDecoderContainer);
        danmuStyleContainer = findViewById(R.id.danmuStyleContainer);
        subtitleStyleContainer = findViewById(R.id.subtitleStyleContainer);
        playlistContainer = findViewById(R.id.playlistContainer);
        textScaleContainer = findViewById(R.id.textScaleContainer);
        tvTextScaleSample = findViewById(R.id.tvTextScaleSample);
        splashContainer = findViewById(R.id.splashContainer);
        renderContainer = findViewById(R.id.renderContainer);
        danmuToggleContainer = findViewById(R.id.danmuToggleContainer);
        loopContainer = findViewById(R.id.loopContainer);
        p2pContainer = findViewById(R.id.p2pContainer);
        dataContainer = findViewById(R.id.dataContainer);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnLoadApi).setOnClickListener(v -> loadApi());

        etApiUrl.setText(PrefUtils.get(PrefUtils.K_API_URL, ""));
        refreshStatus();
        renderApiSources();
        renderPlayerSection();
        renderPlayToggles();
        renderDataSection();
        renderStyleSections();
        renderTextScale();
        renderSplashSettings();

        // 启动局域网配置服务
        WifiConfigServer.get().start(this);
        tvPushUrl.setText("手机浏览器访问: " + WifiConfigServer.get().getAddress());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        WifiConfigServer.get().stop();
    }

    private void loadApi() {
        String url = etApiUrl.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入接口地址", Toast.LENGTH_SHORT).show();
            return;
        }
        tvApiStatus.setText("加载中…");
        ApiConfig.get().addAndSelect(url, new ThreadUtils.Callback<Boolean>() {
            @Override
            public void onResult(Boolean ok) {
                Toast.makeText(SettingsActivity.this, "接口加载成功", Toast.LENGTH_SHORT).show();
                refreshStatus();
                renderApiSources();
            }

            @Override
            public void onError(Throwable t) {
                tvApiStatus.setText("加载失败: " + t.getMessage());
            }
        });
    }

    /** 渲染已推送/保存的接口源列表: 点击切换生效, 长按删除 */
    private void renderApiSources() {
        if (apiSourcesContainer == null) return;
        apiSourcesContainer.removeAllViews();
        List<String> sources = ApiConfig.get().getSources();
        if (sources.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无推送的接口源, 可在上方输入或手机扫码推送多个接口");
            empty.setTextSize(22);
            empty.setTextColor(getColor(R.color.sm_text_dim));
            empty.setPadding(0, 6, 0, 6);
            apiSourcesContainer.addView(empty);
            return;
        }
        String active = ApiConfig.get().getApiUrl();
        for (final String url : sources) {
            final boolean isActive = url.equals(active);
            TextView tv = (TextView) LayoutInflater.from(this).inflate(R.layout.item_tab, apiSourcesContainer, false);
            // 单行横排: 省去协议前缀压缩宽度, ★ 标记当前生效源
            tv.setText((isActive ? "★ " : "") + url.replaceFirst("^https?://", ""));
            tv.setSelected(isActive);
            addFocus(tv);
            // 点击切换生效
            tv.setOnClickListener(v -> switchSource(url));
            // 长按删除
            tv.setOnLongClickListener(v -> {
                ApiConfig.get().removeSource(url);
                renderApiSources();
                Toast.makeText(SettingsActivity.this, "已删除接口源", Toast.LENGTH_SHORT).show();
                return true;
            });
            apiSourcesContainer.addView(tv);
        }
    }

    /** 切换到指定接口源并重新加载 */
    private void switchSource(String url) {
        tvApiStatus.setText("切换中: " + url);
        ApiConfig.get().load(url, new ThreadUtils.Callback<Boolean>() {
            @Override
            public void onResult(Boolean ok) {
                Toast.makeText(SettingsActivity.this, "接口切换成功", Toast.LENGTH_SHORT).show();
                refreshStatus();
                renderApiSources();
            }

            @Override
            public void onError(Throwable t) {
                tvApiStatus.setText("切换失败: " + t.getMessage());
            }
        });
    }

    private void refreshStatus() {
        String url = ApiConfig.get().getApiUrl();
        int count = ApiConfig.get().getSites().size();
        tvApiStatus.setText("当前接口: " + (url.isEmpty() ? "未配置" : url) + "  |  站点数: " + count);
    }

    /** 渲染播放器内核选择 (Exo/IJK) + IJK 解码模式 (硬解/软解) */
    private void renderPlayerSection() {
        playerContainer.removeAllViews();
        ijkDecoderContainer.removeAllViews();
        String current = PrefUtils.get(PrefUtils.K_PLAYER, PrefUtils.PLAYER_EXO);

        // Exo 按钮
        TextView tvExo = (TextView) LayoutInflater.from(this)
                .inflate(R.layout.item_tab, playerContainer, false);
        tvExo.setText("Exo" + (PrefUtils.PLAYER_EXO.equals(current) ? " (当前)" : ""));
        tvExo.setSelected(PrefUtils.PLAYER_EXO.equals(current));
        tvExo.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start();
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
            }
        });
        tvExo.setOnClickListener(v -> {
            PrefUtils.put(PrefUtils.K_PLAYER, PrefUtils.PLAYER_EXO);
            renderPlayerSection();
        });
        playerContainer.addView(tvExo);

        // IJK 按钮
        TextView tvIjk = (TextView) LayoutInflater.from(this)
                .inflate(R.layout.item_tab, playerContainer, false);
        tvIjk.setText("IJK" + (PrefUtils.PLAYER_IJK.equals(current) ? " (当前)" : ""));
        tvIjk.setSelected(PrefUtils.PLAYER_IJK.equals(current));
        tvIjk.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start();
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
            }
        });
        tvIjk.setOnClickListener(v -> {
            PrefUtils.put(PrefUtils.K_PLAYER, PrefUtils.PLAYER_IJK);
            renderPlayerSection();
        });
        playerContainer.addView(tvIjk);

        // IJK 解码模式子选项, 仅 IJK 选中时显示
        if (PrefUtils.PLAYER_IJK.equals(current)) {
            ijkDecoderContainer.setVisibility(View.VISIBLE);
            TextView label = new TextView(this);
            label.setText("IJK 解码模式");
            label.setTextSize(24);
            label.setTextColor(getColor(R.color.sm_text));
            label.setTypeface(label.getTypeface());
            label.setPadding(0, 8, 0, 4);
            label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            ijkDecoderContainer.addView(label);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            ijkDecoderContainer.addView(row);

            String mode = PrefUtils.get(PrefUtils.K_IJK_DECODER, PrefUtils.IJK_HARDWARE);
            TextView tvHw = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_tab, row, false);
            tvHw.setText("硬解" + (PrefUtils.IJK_HARDWARE.equals(mode) ? " (当前)" : ""));
            tvHw.setSelected(PrefUtils.IJK_HARDWARE.equals(mode));
            tvHw.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus) {
                    view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start();
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                }
            });
            tvHw.setOnClickListener(v -> {
                PrefUtils.put(PrefUtils.K_IJK_DECODER, PrefUtils.IJK_HARDWARE);
                renderPlayerSection();
            });
            row.addView(tvHw);

            TextView tvSw = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_tab, row, false);
            tvSw.setText("软解" + (PrefUtils.IJK_SOFTWARE.equals(mode) ? " (当前)" : ""));
            tvSw.setSelected(PrefUtils.IJK_SOFTWARE.equals(mode));
            tvSw.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus) {
                    view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start();
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                }
            });
            tvSw.setOnClickListener(v -> {
                PrefUtils.put(PrefUtils.K_IJK_DECODER, PrefUtils.IJK_SOFTWARE);
                renderPlayerSection();
            });
            row.addView(tvSw);
        } else {
            ijkDecoderContainer.setVisibility(View.GONE);
        }
    }

    // ================= 播放设置 / 数据设置 =================

    /** 渲染播放设置四组开关: 渲染方式 / 弹幕开关 / 循环播放 / P2P加速 */
    private void renderPlayToggles() {
        renderOpts(renderContainer, PrefUtils.K_RENDER, "texture",
                new String[][]{{"texture", "纹理渲染"}, {"surface", "表面渲染"}}, null);
        renderOpts(danmuToggleContainer, PrefUtils.K_DANMU_ON, "1",
                new String[][]{{"1", "弹幕开启"}, {"0", "弹幕关闭"}}, null);
        renderOpts(loopContainer, PrefUtils.K_LOOP, "0",
                new String[][]{{"1", "循环开启"}, {"0", "循环关闭"}}, null);
        renderOpts(p2pContainer, PrefUtils.K_P2P, "0",
                new String[][]{{"1", "P2P开启"}, {"0", "P2P关闭"}}, null);
    }

    /** 渲染一组开关按钮(对齐影视仓设置项): 选中高亮, 点击持久化并重绘 */
    private void renderOpts(LinearLayout container, String key, String defVal,
                            String[][] opts, Runnable after) {
        container.removeAllViews();
        String current = PrefUtils.get(key, defVal);
        for (String[] p : opts) {
            TextView tv = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_tab, container, false);
            boolean sel = current.equals(p[0]);
            tv.setText(p[1] + (sel ? " (当前)" : ""));
            tv.setSelected(sel);
            addFocus(tv);
            tv.setOnClickListener(v -> {
                PrefUtils.put(key, p[0]);
                if (after != null) after.run();
                renderOpts(container, key, defVal, opts, after);
            });
            container.addView(tv);
        }
    }

    /** 渲染数据设置: 清空搜索记录 / 播放历史 / 收藏, 均带确认弹窗 */
    private void renderDataSection() {
        dataContainer.removeAllViews();
        addDataButton("清空搜索记录", () -> {
            PrefUtils.put(PrefUtils.K_SEARCH_HISTORY, "");
        });
        addDataButton("清空播放历史", () -> {
            AppDatabase.get(this).historyDao().clear();
        });
        addDataButton("清空收藏", () -> {
            AppDatabase.get(this).favoriteDao().clear();
        });
    }

    /** 生成一个数据清理按钮(确认后执行) */
    private void addDataButton(String text, Runnable action) {
        TextView tv = (TextView) LayoutInflater.from(this)
                .inflate(R.layout.item_tab, dataContainer, false);
        tv.setText(text);
        addFocus(tv);
        tv.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("确认")
                    .setMessage("确定要" + text + "吗?")
                    .setPositiveButton("确定", (d, w) -> {
                        action.run();
                        Toast.makeText(this, text + "完成", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        dataContainer.addView(tv);
    }

    // ================= 播放样式设置 =================

    /** 渲染弹幕默认样式 / 字幕样式 / 播放列表布局 三组选项 */
    private void renderStyleSections() {
        renderDanmuStyle();
        renderSubtitleStyle();
        renderPlaylistLayout();
    }

    /** 弹幕默认样式: 字号 / 速度两档快捷预设 */
    private void renderDanmuStyle() {
        danmuStyleContainer.removeAllViews();
        final String[][] presets = {
                {"小/稍快", "20", "1.2"},
                {"标准", "24", "1.0"},
                {"大字/舒缓", "30", "0.8"},
        };
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        danmuStyleContainer.addView(row);
        for (String[] p : presets) {
            TextView tv = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_tab, row, false);
            final String size = p[1], spd = p[2];
            boolean current = PrefUtils.get(PrefUtils.K_DANMU_SIZE, "24").equals(size)
                    && PrefUtils.get(PrefUtils.K_DANMU_SPEED, "1.0").equals(spd);
            tv.setText(p[0] + (current ? " (当前)" : ""));
            tv.setSelected(current);
            tv.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus) {
                    view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start();
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                }
            });
            tv.setOnClickListener(v -> {
                PrefUtils.put(PrefUtils.K_DANMU_SIZE, size);
                PrefUtils.put(PrefUtils.K_DANMU_SPEED, spd);
                renderDanmuStyle();
            });
            row.addView(tv);
        }
    }

    /** 字幕样式: 字号(小/标准/大) + 颜色(白/黄/青) */
    private void renderSubtitleStyle() {
        subtitleStyleContainer.removeAllViews();

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        subtitleStyleContainer.addView(sizeRow);
        final String[][] sizes = {{"小", "28"}, {"标准", "34"}, {"大", "40"}};
        for (String[] p : sizes) {
            TextView tv = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_tab, sizeRow, false);
            final String v = p[1];
            boolean current = PrefUtils.get(PrefUtils.K_SUBTITLE_SIZE, "34").equals(v);
            tv.setText(p[0] + (current ? " (当前)" : ""));
            tv.setSelected(current);
            addFocus(tv);
            tv.setOnClickListener(v2 -> {
                PrefUtils.put(PrefUtils.K_SUBTITLE_SIZE, v);
                renderSubtitleStyle();
            });
            sizeRow.addView(tv);
        }

        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        subtitleStyleContainer.addView(colorRow);
        final String[][] colors = {{"白色", "#FFFFFF"}, {"亮黄", "#FFEB3B"}, {"青色", "#4FC3F7"}};
        for (String[] p : colors) {
            TextView tv = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_tab, colorRow, false);
            final String v = p[1];
            boolean current = PrefUtils.get(PrefUtils.K_SUBTITLE_COLOR, "#FFFFFF").equalsIgnoreCase(v);
            tv.setText(p[0] + (current ? " (当前)" : ""));
            tv.setSelected(current);
            addFocus(tv);
            tv.setOnClickListener(v2 -> {
                PrefUtils.put(PrefUtils.K_SUBTITLE_COLOR, v);
                renderSubtitleStyle();
            });
            colorRow.addView(tv);
        }
    }

    /** 全局文字大小: 标准 / 偏大 / 超大 */
    private void renderTextScale() {
        if (textScaleContainer == null) return;
        textScaleContainer.removeAllViews();
        final String[][] opts = {{"0", "标准"}, {"1", "偏大"}, {"2", "超大"}};
        String current = PrefUtils.get(PrefUtils.K_TEXT_SCALE, "0");
        for (String[] p : opts) {
            TextView tv = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_tab, textScaleContainer, false);
            boolean sel = current.equals(p[0]);
            tv.setText(p[1] + (sel ? " (当前)" : ""));
            tv.setSelected(sel);
            addFocus(tv);
            tv.setOnClickListener(v -> {
                PrefUtils.put(PrefUtils.K_TEXT_SCALE, p[0]);
                renderTextScale();
            });
            textScaleContainer.addView(tv);
        }
        // 预览取样文字随缩放即时变化
        if (tvTextScaleSample != null) {
            TextScaleUtil.apply(tvTextScaleSample, 24);
        }
    }

    /** 开屏页: 情话开关 / 自定义情话 / 背景图 URL */
    private void renderSplashSettings() {
        if (splashContainer == null) return;
        splashContainer.removeAllViews();
        boolean poemOn = !"0".equals(PrefUtils.get(PrefUtils.K_SPLASH_POEM_ON, "1"));

        // ---- 情话开关 ----
        TextView label = new TextView(this);
        label.setText("情话展示");
        label.setTextSize(24);
        label.setTextColor(getColor(R.color.sm_text));
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setPadding(0, 8, 0, 4);
        splashContainer.addView(label);

        LinearLayout onRow = new LinearLayout(this);
        onRow.setOrientation(LinearLayout.HORIZONTAL);
        splashContainer.addView(onRow);
        final String[][] toggles = {{"1", "开启"}, {"0", "关闭"}};
        for (String[] p : toggles) {
            TextView tv = (TextView) LayoutInflater.from(this).inflate(R.layout.item_tab, onRow, false);
            boolean sel = poemOn == "1".equals(p[0]);
            tv.setText(p[1] + (sel ? " (当前)" : ""));
            tv.setSelected(sel);
            addFocus(tv);
            tv.setOnClickListener(v -> {
                PrefUtils.put(PrefUtils.K_SPLASH_POEM_ON, p[0]);
                renderSplashSettings();
            });
            onRow.addView(tv);
        }

        // ---- 自定义情话 ----
        if (poemOn) {
            TextView label2 = new TextView(this);
            label2.setText("自定义情话 (每行一句, 留空为默认)");
            label2.setTextSize(24);
            label2.setTextColor(getColor(R.color.sm_text));
            label2.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            label2.setPadding(0, 16, 0, 4);
            splashContainer.addView(label2);

            android.widget.EditText etPoem = new android.widget.EditText(this);
            etPoem.setText(PrefUtils.get(PrefUtils.K_SPLASH_POEM, ""));
            etPoem.setHint("示例: 山野万里\n藏在我心里的温柔➟");
            etPoem.setTextSize(24);
            etPoem.setTextColor(getColor(R.color.sm_text));
            etPoem.setHintTextColor(getColor(R.color.sm_text_dim));
            etPoem.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
            etPoem.setLines(5);
            etPoem.setBackground(getDrawable(R.drawable.bg_search_box));
            etPoem.setPadding(24, 24, 24, 24);
            splashContainer.addView(etPoem);

            LinearLayout row2 = new LinearLayout(this);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            row2.setPadding(0, 12, 0, 0);
            splashContainer.addView(row2);
            TextView btnSavePoem = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_tab, row2, false);
            btnSavePoem.setText("保存情话");
            addFocus(btnSavePoem);
            btnSavePoem.setOnClickListener(v -> {
                PrefUtils.put(PrefUtils.K_SPLASH_POEM, etPoem.getText().toString().trim());
                Toast.makeText(this, "情话已保存", Toast.LENGTH_SHORT).show();
            });
            row2.addView(btnSavePoem);

            TextView btnResetPoem = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_tab, row2, false);
            btnResetPoem.setText("恢复默认");
            addFocus(btnResetPoem);
            btnResetPoem.setOnClickListener(v -> {
                PrefUtils.put(PrefUtils.K_SPLASH_POEM, "");
                etPoem.setText("");
                Toast.makeText(this, "已恢复默认情话", Toast.LENGTH_SHORT).show();
            });
            row2.addView(btnResetPoem);
        }

        // ---- 语音播报开关 ----
        boolean ttsOn = !"0".equals(PrefUtils.get(PrefUtils.K_SPLASH_TTS_ON, "1"));

        TextView labelTts = new TextView(this);
        labelTts.setText("开屏语音播报 (进入 App 时朗读一句)");
        labelTts.setTextSize(24);
        labelTts.setTextColor(getColor(R.color.sm_text));
        labelTts.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        labelTts.setPadding(0, 16, 0, 4);
        splashContainer.addView(labelTts);

        LinearLayout ttsRow = new LinearLayout(this);
        ttsRow.setOrientation(LinearLayout.HORIZONTAL);
        splashContainer.addView(ttsRow);
        for (String[] p : toggles) {
            TextView tv = (TextView) LayoutInflater.from(this).inflate(R.layout.item_tab, ttsRow, false);
            boolean sel = ttsOn == "1".equals(p[0]);
            tv.setText(p[1] + (sel ? " (当前)" : ""));
            tv.setSelected(sel);
            addFocus(tv);
            tv.setOnClickListener(v -> {
                PrefUtils.put(PrefUtils.K_SPLASH_TTS_ON, p[0]);
                renderSplashSettings();
            });
            ttsRow.addView(tv);
        }

        // ---- 自定义播报内容 ----
        if (ttsOn) {
            TextView labelTtsText = new TextView(this);
            labelTtsText.setText("自定义播报内容 (留空则播报: " + getString(R.string.tts_greeting) + ")");
            labelTtsText.setTextSize(24);
            labelTtsText.setTextColor(getColor(R.color.sm_text));
            labelTtsText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            labelTtsText.setPadding(0, 16, 0, 4);
            splashContainer.addView(labelTtsText);

            android.widget.EditText etTts = new android.widget.EditText(this);
            etTts.setText(PrefUtils.get(PrefUtils.K_SPLASH_TTS_TEXT, ""));
            etTts.setHint("示例: 欢迎回家, 茗雅");
            etTts.setTextSize(24);
            etTts.setTextColor(getColor(R.color.sm_text));
            etTts.setHintTextColor(getColor(R.color.sm_text_dim));
            etTts.setSingleLine(true);
            etTts.setBackground(getDrawable(R.drawable.bg_search_box));
            etTts.setPadding(24, 24, 24, 24);
            splashContainer.addView(etTts);

            LinearLayout rowTts = new LinearLayout(this);
            rowTts.setOrientation(LinearLayout.HORIZONTAL);
            rowTts.setPadding(0, 12, 0, 0);
            splashContainer.addView(rowTts);
            TextView btnSaveTts = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_tab, rowTts, false);
            btnSaveTts.setText("保存播报内容");
            addFocus(btnSaveTts);
            btnSaveTts.setOnClickListener(v -> {
                PrefUtils.put(PrefUtils.K_SPLASH_TTS_TEXT, etTts.getText().toString().trim());
                Toast.makeText(this, "播报内容已保存, 下次打开 App 生效", Toast.LENGTH_SHORT).show();
            });
            rowTts.addView(btnSaveTts);

            TextView btnResetTts = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_tab, rowTts, false);
            btnResetTts.setText("恢复默认");
            addFocus(btnResetTts);
            btnResetTts.setOnClickListener(v -> {
                PrefUtils.put(PrefUtils.K_SPLASH_TTS_TEXT, "");
                etTts.setText("");
                Toast.makeText(this, "已恢复默认播报内容", Toast.LENGTH_SHORT).show();
            });
            rowTts.addView(btnResetTts);
        }

        // ---- 背景图 ----
        TextView label3 = new TextView(this);
        label3.setText("背景图 URL (留空为默认渐变)");
        label3.setTextSize(24);
        label3.setTextColor(getColor(R.color.sm_text));
        label3.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label3.setPadding(0, 16, 0, 4);
        splashContainer.addView(label3);

        android.widget.EditText etBg = new android.widget.EditText(this);
        etBg.setText(PrefUtils.get(PrefUtils.K_SPLASH_BG, ""));
        etBg.setHint("如 https://xxxx/photo.jpg");
        etBg.setTextSize(24);
        etBg.setTextColor(getColor(R.color.sm_text));
        etBg.setHintTextColor(getColor(R.color.sm_text_dim));
        etBg.setSingleLine(true);
        etBg.setBackground(getDrawable(R.drawable.bg_search_box));
        etBg.setPadding(24, 24, 24, 24);
        splashContainer.addView(etBg);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setPadding(0, 12, 0, 0);
        splashContainer.addView(row3);
        TextView btnBg = (TextView) LayoutInflater.from(this).inflate(R.layout.item_tab, row3, false);
        btnBg.setText("保存背景图");
        addFocus(btnBg);
        btnBg.setOnClickListener(v -> {
            PrefUtils.put(PrefUtils.K_SPLASH_BG, etBg.getText().toString().trim());
            Toast.makeText(this, "背景图已保存", Toast.LENGTH_SHORT).show();
        });
        row3.addView(btnBg);

        TextView btnClearBg = (TextView) LayoutInflater.from(this).inflate(R.layout.item_tab, row3, false);
        btnClearBg.setText("清除背景");
        addFocus(btnClearBg);
        btnClearBg.setOnClickListener(v -> {
            PrefUtils.put(PrefUtils.K_SPLASH_BG, "");
            etBg.setText("");
            Toast.makeText(this, "已清除背景图", Toast.LENGTH_SHORT).show();
        });
        row3.addView(btnClearBg);
    }

    /** 播放列表布局: 网格(平铺) / 垂直列表 */
    private void renderPlaylistLayout() {
        playlistContainer.removeAllViews();
        String current = PrefUtils.get(PrefUtils.K_PLAYLIST_LAYOUT, "grid");
        final String[][] opts = {{"grid", "网格(平铺)"}, {"column", "垂直列表"}};
        for (String[] p : opts) {
            TextView tv = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.item_tab, playlistContainer, false);
            boolean sel = current.equals(p[0]);
            tv.setText(p[1] + (sel ? " (当前)" : ""));
            tv.setSelected(sel);
            addFocus(tv);
            tv.setOnClickListener(v -> {
                PrefUtils.put(PrefUtils.K_PLAYLIST_LAYOUT, p[0]);
                renderPlaylistLayout();
            });
            playlistContainer.addView(tv);
        }
    }

    /** 统一焦点缩放动画 */
    private void addFocus(View v) {
        v.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start();
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
            }
        });
    }
}
