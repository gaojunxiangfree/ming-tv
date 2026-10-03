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

/** 选集适配器 */
public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.VH> {

    public interface OnClick { void onClick(int index, Vod.Episode ep); }

    private final List<Vod.Episode> items = new ArrayList<>();
    private int selected = -1;
    private OnClick listener;

    public void setOnClick(OnClick l) { this.listener = l; }

    public void submit(List<Vod.Episode> list, int sel) {
        items.clear();
        if (list != null) items.addAll(list);
        selected = sel;
        notifyDataSetChanged();
    }

    public void setSelected(int sel) {
        int old = selected;
        selected = sel;
        if (old >= 0) notifyItemChanged(old);
        if (sel >= 0) notifyItemChanged(sel);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_episode, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Vod.Episode ep = items.get(position);
        h.tv.setText(ep.name);
        h.tv.setSelected(position == selected);
        // 动态文字大小: 选集文字随全局缩放
        TextScaleUtil.apply(h.tv, 26);
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(position, ep);
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
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tv;
        VH(View v) {
            super(v);
            tv = v.findViewById(R.id.tvEpisode);
        }
    }
}
