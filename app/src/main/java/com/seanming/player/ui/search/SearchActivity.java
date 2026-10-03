package com.seanming.player.ui.search;

import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.seanming.player.R;
import com.seanming.player.api.SearchApi;
import com.seanming.player.bean.Site;
import com.seanming.player.server.WifiConfigServer;
import com.seanming.player.spider.SpiderManager;
import com.seanming.player.util.PrefUtils;
import com.seanming.player.util.QRCodeUtil;
import com.seanming.player.util.ThreadUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/**
 * 搜索页(对齐影视仓 SearchActivity).
 *
 * 影视仓搜索"秒出结果"的关键: 这个页面本身**不做任何站点搜索**,
 * 点击"搜索"只是保存历史并立刻跳转到全源秒搜页(FastSearchActivity),
 * 由那边用 8 线程并发把结果流式回填. 因此输入页永远零等待.
 *
 * 页面结构也与影视仓一致: 左=搜索历史, 中=输入框+虚拟键盘, 右=热门搜索/联想词.
 *  - 热门搜索: 360kan 实时热榜(电视榜/电影榜/综艺榜/儿童榜), 与影视仓同一接口;
 *  - 输入时: 用爱奇艺联想接口实时给出候选词(替换右侧热榜);
 *  - 键盘键位: 与影视仓一致(远程搜索 / 删除 + A-Z + 0-9), 6 列网格;
 *  - 指定搜索源: 勾选参与秒搜的站点(对齐影视仓"指定搜索源").
 */
public class SearchActivity extends AppCompatActivity {

    /** 键盘键位(与影视仓 SearchKeyboard 完全一致的顺序) */
    private static final String KEY_REMOTE = "远程搜索";
    private static final String KEY_DEL = "⌫ 删除";
    private static final String[] KEY_SEQ = {
            KEY_REMOTE, KEY_DEL,
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0"
    };
    /** 键盘列数(影视仓为 6 列) */
    private static final int KEY_COLS = 6;
    /** 搜索历史最多保留条数(影视仓为 20) */
    private static final int MAX_HISTORY = 20;
    /** 联想词防抖间隔 */
    private static final long SUGGEST_DEBOUNCE_MS = 250L;

    private EditText etKeyword;
    private LinearLayout historyContainer;
    private View historyTitleBar;
    private LinearLayout hotContainer;
    private LinearLayout hotTabRow;
    private View hotTabScroll;
    private TextView tvHotTitle;

    /** 热榜: 榜名 -> 热搜词(保持接口返回顺序) */
    private final LinkedHashMap<String, List<String>> hotBoards = new LinkedHashMap<>();
    private final List<String> hotTabNames = new ArrayList<>();
    private int hotBoardIndex = 0;
    private final List<TextView> hotTabs = new ArrayList<>();
    /** true = 右侧展示的是联想词(输入中), false = 展示热榜 */
    private boolean suggestMode = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable suggestTask;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        etKeyword = findViewById(R.id.etKeyword);
        historyContainer = findViewById(R.id.historyContainer);
        historyTitleBar = findViewById(R.id.historyTitleBar);
        hotContainer = findViewById(R.id.hotContainer);
        hotTabRow = findViewById(R.id.hotTabRow);
        hotTabScroll = findViewById(R.id.hotTabScroll);
        tvHotTitle = findViewById(R.id.tvHotTitle);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnClearHistory).setOnClickListener(v -> clearHistory());
        findViewById(R.id.btnSearch).setOnClickListener(v -> doSearch(currentKeyword()));
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            etKeyword.setText("");
            etKeyword.requestFocus();
        });
        findViewById(R.id.btnSelectSource).setOnClickListener(v -> showSourcePicker());

        // 键盘: 影视仓 6 列网格
        int[] rowIds = {R.id.keyRow1, R.id.keyRow2, R.id.keyRow3, R.id.keyRow4,
                R.id.keyRow5, R.id.keyRow6, R.id.keyRow7};
        buildKeyboard(rowIds);

        etKeyword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { scheduleSuggest(); }
        });
        etKeyword.setOnEditorActionListener((v, actionId, e) -> {
            if (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                doSearch(currentKeyword());
                return true;
            }
            return false;
        });

        renderHistory();
        loadHotRank();
        // 后台预热爬虫: 用户挑词/输入的这几秒里把 20 多个爬虫提前加载好,
        // 点搜索时就能直接并发开搜(这是"秒出结果"的关键之一)
        SpiderManager.preloadAsync(FastSearchActivity.searchableSites());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (suggestTask != null) ui.removeCallbacks(suggestTask);
    }

    private String currentKeyword() {
        return etKeyword.getText().toString().trim();
    }

    // ==================== 搜索(对齐影视仓: 零等待跳转) ====================

    /**
     * 影视仓的做法: 本页不搜索, 存历史后立即跳全源秒搜页,
     * 让"搜索"这个动作瞬间完成, 结果由秒搜页并发流式返回.
     */
    private void doSearch(String key) {
        if (key == null || key.isEmpty()) {
            Toast.makeText(this, "请输入搜索内容", Toast.LENGTH_SHORT).show();
            return;
        }
        saveHistory(key);
        renderHistory();
        openFastSearch(key);
    }

    private void openFastSearch(String key) {
        Intent i = new Intent(this, FastSearchActivity.class);
        i.putExtra(FastSearchActivity.EXTRA_KEYWORD, key);
        startActivity(i);
    }

    // ==================== 虚拟键盘 ====================

    /** 按 KEY_SEQ 铺满 6 列网格; 末行不足 6 个时按键加宽铺满, 保证列对齐 */
    private void buildKeyboard(int[] rowIds) {
        int idx = 0;
        for (int rowId : rowIds) {
            LinearLayout row = findViewById(rowId);
            int remaining = KEY_SEQ.length - idx;
            if (remaining <= 0) break;
            int inRow = Math.min(KEY_COLS, remaining);
            float weight = KEY_COLS / (float) inRow;
            for (int c = 0; c < inRow; c++) {
                row.addView(buildKey(idx++, weight));
            }
        }
    }

    /** 生成单个键盘键(按 weight 均分该行, 左右留间距) */
    private TextView buildKey(final int keyIndex, float weight) {
        final String label = KEY_SEQ[keyIndex];
        TextView key = new TextView(this);
        key.setText(label);
        // 动作键文字较长("远程搜索"/"⌫ 删除"), 用小字号避免挤压
        key.setTextSize(label.length() > 1 ? 15 : 24);
        key.setTextColor(getResources().getColor(
                keyIndex == 0 ? R.color.sm_primary : R.color.sm_text));
        key.setBackgroundResource(R.drawable.bg_button_selector);
        key.setPadding(0, 14, 0, 14);
        key.setGravity(Gravity.CENTER);
        key.setFocusable(true);
        key.setClickable(true);
        key.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        lp.setMargins(3, 0, 3, 0);
        key.setLayoutParams(lp);
        setupFocusAnim(key);
        key.setOnClickListener(v -> onKeyClicked(keyIndex));
        return key;
    }

    /** 键盘点击: 0=远程搜索(扫码), 1=删除(退格), 其余=追加字符 */
    private void onKeyClicked(int keyIndex) {
        if (keyIndex == 0) {
            showRemoteSearchDialog();
        } else if (keyIndex == 1) {
            backspace();
        } else {
            etKeyword.append(KEY_SEQ[keyIndex]);
            etKeyword.setSelection(etKeyword.getText().length());
        }
    }

    private void backspace() {
        Editable e = etKeyword.getText();
        if (e.length() > 0) {
            e.delete(e.length() - 1, e.length());
            etKeyword.setSelection(e.length());
        }
    }

    // ==================== 搜索历史 ====================

    private void saveHistory(String key) {
        List<String> list = loadHistoryList();
        list.remove(key);
        list.add(0, key);
        if (list.size() > MAX_HISTORY) list = new ArrayList<>(list.subList(0, MAX_HISTORY));
        PrefUtils.put(PrefUtils.K_SEARCH_HISTORY, new Gson().toJson(list));
    }

    private List<String> loadHistoryList() {
        String json = PrefUtils.get(PrefUtils.K_SEARCH_HISTORY, "");
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            List<String> list = new Gson().fromJson(json, new TypeToken<List<String>>() {}.getType());
            return list != null ? list : new ArrayList<>();
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    private void clearHistory() {
        PrefUtils.put(PrefUtils.K_SEARCH_HISTORY, "");
        renderHistory();
    }

    private void renderHistory() {
        List<String> history = loadHistoryList();
        historyContainer.removeAllViews();
        historyTitleBar.setVisibility(history.isEmpty() ? View.GONE : View.VISIBLE);
        for (String word : history) {
            historyContainer.addView(buildWordItem(word, v -> doSearch(word)));
        }
    }

    // ==================== 热门搜索(360kan 实时热榜) ====================

    /** 拉取 360kan 热榜, 失败则静默(右侧只剩标题) */
    private void loadHotRank() {
        ThreadUtils.io(() -> {
            LinkedHashMap<String, List<String>> boards = null;
            try {
                boards = SearchApi.hotRank();
            } catch (Throwable t) {
                android.util.Log.w("SearchActivity", "hotRank fail: " + t.getMessage());
            }
            final LinkedHashMap<String, List<String>> data = boards;
            ThreadUtils.main(() -> {
                if (isFinishing() || suggestMode) return;
                if (data == null || data.isEmpty()) {
                    tvHotTitle.setText("热门搜索");
                    return;
                }
                hotBoards.clear();
                hotBoards.putAll(data);
                hotTabNames.clear();
                hotTabNames.addAll(data.keySet());
                hotBoardIndex = 0;
                buildHotTabs();
                renderHotBoard();
            });
        });
    }

    /** 热榜分类 Tab(电视榜/电影榜/综艺榜/儿童榜) */
    private void buildHotTabs() {
        hotTabRow.removeAllViews();
        hotTabs.clear();
        for (int i = 0; i < hotTabNames.size(); i++) {
            final int index = i;
            TextView tab = new TextView(this);
            tab.setText(hotTabNames.get(i));
            tab.setTextSize(16);
            tab.setPadding(10, 8, 10, 8);
            tab.setFocusable(true);
            tab.setClickable(true);
            tab.setBackgroundResource(R.drawable.bg_button_selector);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 6, 0);
            tab.setLayoutParams(lp);
            setupFocusAnim(tab);
            tab.setOnClickListener(v -> {
                hotBoardIndex = index;
                renderHotBoard();
            });
            hotTabRow.addView(tab);
            hotTabs.add(tab);
        }
        highlightHotTabs();
    }

    private void highlightHotTabs() {
        for (int i = 0; i < hotTabs.size(); i++) {
            boolean active = i == hotBoardIndex;
            hotTabs.get(i).setTextColor(getResources().getColor(
                    active ? R.color.sm_primary : R.color.sm_text));
            hotTabs.get(i).setSelected(active);
        }
    }

    /** 展示热榜(退出联想模式) */
    private void renderHotBoard() {
        suggestMode = false;
        tvHotTitle.setText("热门搜索");
        hotTabScroll.setVisibility(hotTabNames.isEmpty() ? View.GONE : View.VISIBLE);
        highlightHotTabs();
        hotContainer.removeAllViews();
        if (hotTabNames.isEmpty()) return;
        List<String> words = hotBoards.get(hotTabNames.get(hotBoardIndex));
        if (words == null) return;
        for (int i = 0; i < words.size(); i++) {
            final String word = words.get(i);
            hotContainer.addView(buildWordItem((i + 1) + ". " + word, v -> doSearch(word)));
        }
    }

    // ==================== 联想词(爱奇艺接口) ====================

    /** 输入变化 → 防抖后拉联想词; 输入为空则回到热榜 */
    private void scheduleSuggest() {
        if (suggestTask != null) ui.removeCallbacks(suggestTask);
        final String key = currentKeyword();
        if (key.isEmpty()) {
            renderHotBoard();
            return;
        }
        suggestTask = () -> loadSuggest(key);
        ui.postDelayed(suggestTask, SUGGEST_DEBOUNCE_MS);
    }

    private void loadSuggest(final String key) {
        ThreadUtils.io(() -> {
            List<String> words = null;
            try {
                words = SearchApi.suggest(key);
            } catch (Throwable t) {
                android.util.Log.w("SearchActivity", "suggest fail: " + t.getMessage());
            }
            final List<String> data = words;
            ThreadUtils.main(() -> {
                if (isFinishing()) return;
                // 输入已变化则丢弃过期结果
                if (!key.equals(currentKeyword())) return;
                renderSuggest(data);
            });
        });
    }

    /** 展示联想词(替换右侧热榜) */
    private void renderSuggest(List<String> words) {
        suggestMode = true;
        tvHotTitle.setText("猜你想搜");
        hotTabScroll.setVisibility(View.GONE);
        hotContainer.removeAllViews();
        if (words == null || words.isEmpty()) return;
        for (String word : words) {
            hotContainer.addView(buildWordItem(word, v -> doSearch(word)));
        }
    }

    // ==================== 指定搜索源(对齐影视仓"指定搜索源") ====================

    /** 多选参与秒搜的站点; 全不选/全选 = 使用全部可搜索站点 */
    private void showSourcePicker() {
        // 与秒搜页用同一套过滤, 保证"弹窗里能勾的"就是"实际会搜的"
        final List<Site> candidates = FastSearchActivity.searchableSites();
        if (candidates.isEmpty()) {
            Toast.makeText(this, "当前接口没有可搜索的站点", Toast.LENGTH_SHORT).show();
            return;
        }
        final Set<String> selected = new HashSet<>(loadSelectedSites());
        // 从未指定过时, 默认全部勾选
        if (selected.isEmpty()) {
            for (Site s : candidates) selected.add(s.getKey());
        }
        final String[] names = new String[candidates.size()];
        final boolean[] checked = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            names[i] = candidates.get(i).getName() == null
                    ? candidates.get(i).getKey() : candidates.get(i).getName();
            checked[i] = selected.contains(candidates.get(i).getKey());
        }
        new AlertDialog.Builder(this)
                .setTitle("指定搜索源")
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("保存", (d, w) -> {
                    List<String> picked = new ArrayList<>();
                    for (int i = 0; i < candidates.size(); i++) {
                        if (checked[i]) picked.add(candidates.get(i).getKey());
                    }
                    // 全选/全不选都等价于"不指定", 存空串走全部站点
                    boolean all = picked.isEmpty() || picked.size() == candidates.size();
                    PrefUtils.put(PrefUtils.K_SEARCH_SITES, all ? "" : new Gson().toJson(picked));
                    Toast.makeText(this, all ? "已恢复搜索全部源" : "已指定 " + picked.size() + " 个搜索源",
                            Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("全选", (d, w) -> {
                    PrefUtils.put(PrefUtils.K_SEARCH_SITES, "");
                    Toast.makeText(this, "已恢复搜索全部源", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private Set<String> loadSelectedSites() {
        Set<String> set = new HashSet<>();
        try {
            String json = PrefUtils.get(PrefUtils.K_SEARCH_SITES, "");
            if (json != null && !json.isEmpty()) {
                List<String> list = new Gson().fromJson(json, new TypeToken<List<String>>() {}.getType());
                if (list != null) set.addAll(list);
            }
        } catch (Throwable ignored) {}
        return set;
    }

    // ==================== 远程搜索(手机扫码输入关键词) ====================

    /** 对齐影视仓键盘首键"远程搜索": 手机扫码后输入关键词即在本机搜索 */
    private void showRemoteSearchDialog() {
        if (!WifiConfigServer.get().isRunning()) WifiConfigServer.get().start(this);
        String addr = WifiConfigServer.get().getAddress();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(40, 30, 40, 20);

        ImageView qr = new ImageView(this);
        Bitmap bmp = QRCodeUtil.generate(addr, 420);
        if (bmp != null) qr.setImageBitmap(bmp);
        box.addView(qr);

        TextView info = new TextView(this);
        info.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        info.setGravity(Gravity.CENTER);
        info.setTextSize(20);
        info.setTextColor(getResources().getColor(R.color.sm_text));
        info.setPadding(0, 24, 0, 0);
        info.setText("手机与电视连接同一 WiFi\n扫码打开 " + addr + "\n输入关键词即可在电视上搜索");
        box.addView(info);

        new AlertDialog.Builder(this)
                .setTitle("远程搜索")
                .setView(box)
                .setPositiveButton("关闭", (DialogInterface d, int w) -> d.dismiss())
                .show();
    }

    // ==================== 通用 ====================

    /** 生成一个可点击的词条(历史/热榜/联想共用) */
    private TextView buildWordItem(String text, View.OnClickListener onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(24);
        tv.setTextColor(getResources().getColor(R.color.sm_text));
        tv.setBackgroundResource(R.drawable.bg_button_selector);
        tv.setPadding(24, 14, 24, 14);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setFocusable(true);
        tv.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 10);
        tv.setLayoutParams(lp);
        setupFocusAnim(tv);
        tv.setOnClickListener(onClick);
        return tv;
    }

    private void setupFocusAnim(View view) {
        if (view == null) return;
        view.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start();
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
            }
        });
    }
}
