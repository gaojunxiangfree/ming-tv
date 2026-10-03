package com.seanming.player.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.seanming.player.R;
import com.seanming.player.bean.LiveChannel;

import java.util.ArrayList;
import java.util.List;

/** 直播频道列表 */
public class LiveChannelAdapter extends RecyclerView.Adapter<LiveChannelAdapter.VH> {

    public interface OnClick { void onClick(LiveChannel ch); }

    private final List<LiveChannel> items = new ArrayList<>();
    private int selected = -1;
    private OnClick listener;
    private OnClick focusListener;

    public void setOnClick(OnClick l) { this.listener = l; }

    /** 列表项获得焦点时回调(影视仓: 焦点移动即切台预览) */
    public void setOnFocus(OnClick l) { this.focusListener = l; }

    public void submit(List<LiveChannel> list) {
        items.clear();
        if (list != null) items.addAll(list);
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
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_live_channel, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LiveChannel c = items.get(position);
        h.num.setText(String.valueOf(c.number));
        h.name.setText(c.name);
        h.itemView.setSelected(position == selected);
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(c); });
        // 焦点缩放动画 + 焦点即切台
        h.itemView.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                view.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start();
                if (focusListener != null) focusListener.onClick(c);
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
            }
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView num, name;
        VH(View v) {
            super(v);
            num = v.findViewById(R.id.tvNumber);
            name = v.findViewById(R.id.tvName);
        }
    }
}
