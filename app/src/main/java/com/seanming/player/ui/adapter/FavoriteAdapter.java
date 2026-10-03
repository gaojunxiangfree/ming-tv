package com.seanming.player.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.seanming.player.R;
import com.seanming.player.data.Favorite;
import com.seanming.player.util.ImageUtil;

import java.util.ArrayList;
import java.util.List;

/** 收藏列表适配器 */
public class FavoriteAdapter extends RecyclerView.Adapter<FavoriteAdapter.VH> {

    public interface OnItemClickListener {
        void onClick(Favorite favorite);
        void onDelete(Favorite favorite);
    }

    private final List<Favorite> items = new ArrayList<>();
    private OnItemClickListener listener;

    public void setOnItemClickListener(OnItemClickListener l) { this.listener = l; }

    public void submit(List<Favorite> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_collect, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Favorite f = items.get(position);
        h.name.setText(f.vodName == null ? "" : f.vodName);
        h.remarks.setText(f.vodRemarks == null ? "" : f.vodRemarks);
        ImageUtil.loadPoster(h.poster, f.vodPic);
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(f); });
        h.btnDelete.setOnClickListener(v -> { if (listener != null) listener.onDelete(f); });
        // 焦点缩放动画
        h.itemView.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                view.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start();
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
            }
        });
        h.btnDelete.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start();
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
            }
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView poster;
        TextView name, remarks;
        View btnDelete;
        VH(View v) {
            super(v);
            poster = v.findViewById(R.id.ivPoster);
            name = v.findViewById(R.id.tvName);
            remarks = v.findViewById(R.id.tvRemarks);
            btnDelete = v.findViewById(R.id.btnDelete);
        }
    }
}
