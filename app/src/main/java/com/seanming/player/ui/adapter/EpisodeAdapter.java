package com.seanming.player.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.seanming.player.R;
import com.seanming.player.bean.Vod;
import com.seanming.player.util.TextScaleUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 选集适配器.
 * 支持"页码范围收纳": 只展示 [rangeStart, rangeEnd) 内的剧集,
 * 但对外回调与 setSelected 一律使用"全局下标"(相对完整剧集列表), 调用方无需关心分页.
 */
public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.VH> {

    /** 回调使用全局下标 */
    public interface OnClick { void onClick(int index, Vod.Episode ep); }

    private final List<Vod.Episode> items = new ArrayList<>();
    /** 当前可见范围 [rangeStart, rangeEnd), 默认全部可见 */
    private int rangeStart = 0;
    private int rangeEnd = 0;
    private int selected = -1;
    private OnClick listener;

    public void setOnClick(OnClick l) { this.listener = l; }

    public void submit(List<Vod.Episode> list, int sel) {
        items.clear();
        if (list != null) items.addAll(list);
        rangeStart = 0;
        rangeEnd = items.size();
        selected = sel;
        notifyDataSetChanged();
    }

    /** 只展示 [start, end) 区间的剧集(收纳分页), 下标仍为全局下标 */
    public void setRange(int start, int end) {
        if (start < 0) start = 0;
        if (end > items.size()) end = items.size();
        if (end < start) end = start;
        rangeStart = start;
        rangeEnd = end;
        notifyDataSetChanged();
    }

    /** 选中某一集, 参数为全局下标(不在当前可见范围内则无视觉变化) */
    public void setSelected(int sel) {
        int old = selected;
        selected = sel;
        notifyPosition(old);
        notifyPosition(sel);
    }

    private void notifyPosition(int global) {
        int position = global - rangeStart;
        if (position >= 0 && position < getItemCount()) notifyItemChanged(position);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_episode, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final int global = rangeStart + position;
        if (global < 0 || global >= items.size()) return;
        Vod.Episode ep = items.get(global);
        h.tv.setText(ep.name);
        h.tv.setSelected(global == selected);
        // 动态文字大小: 选集文字随全局缩放
        TextScaleUtil.apply(h.tv, 26);
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(global, ep);
        });
        // 焦点变化时缩放动画
        h.itemView.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(120).start();
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
            }
        });
    }

    @Override
    public int getItemCount() { return Math.max(0, rangeEnd - rangeStart); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tv;
        VH(View v) {
            super(v);
            tv = v.findViewById(R.id.tvEpisode);
        }
    }
}
