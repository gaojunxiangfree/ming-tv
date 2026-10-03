package com.seanming.player.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.seanming.player.R;

import java.util.ArrayList;
import java.util.List;

/** 直播分组 Tab(横向) */
public class LiveGroupAdapter extends RecyclerView.Adapter<LiveGroupAdapter.VH> {

    public interface OnClick { void onClick(int position); }

    private final List<String> items = new ArrayList<>();
    private int selected = -1;
    private OnClick listener;

    public void setOnClick(OnClick l) { this.listener = l; }

    public void submit(List<String> list) {
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
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_live_group, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        h.name.setText(items.get(position));
        h.itemView.setSelected(position == selected);
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(h.getBindingAdapterPosition());
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView name;
        VH(View v) {
            super(v);
            name = v.findViewById(R.id.tvGroupName);
        }
    }
}