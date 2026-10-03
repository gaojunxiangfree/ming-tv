package com.seanming.player.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.seanming.player.R;
import com.seanming.player.bean.Vod;
import com.seanming.player.util.ImageUtil;

import java.util.ArrayList;
import java.util.List;

/** 视频网格适配器 */
public class VodAdapter extends RecyclerView.Adapter<VodAdapter.VH> {

    public interface OnItemClickListener {
        void onClick(Vod vod);
    }

    private final List<Vod> items = new ArrayList<>();
    private OnItemClickListener listener;
    /** 当前分类名, 用于无标签数据时作为海报左上角占位(如"豆瓣热播") */
    private String currentTabName;

    public void setOnItemClickListener(OnItemClickListener l) { this.listener = l; }

    public void setCurrentTabName(String name) { this.currentTabName = name; }

    public void submitList(List<Vod> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    public void appendList(List<Vod> list) {
        if (list == null || list.isEmpty()) return;
        int start = items.size();
        items.addAll(list);
        notifyItemRangeInserted(start, list.size());
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_vod, parent, false);
        VH h = new VH(v);
        // 海报高度按屏幕可用高度动态计算, 保证投影仪/电视横屏下每行海报完整可见
        ViewGroup.LayoutParams lp = h.poster.getLayoutParams();
        lp.height = com.seanming.player.util.ScreenUtil.posterHeightPx(parent.getContext());
        h.poster.setLayoutParams(lp);
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Vod v = items.get(position);
        h.name.setText(v.vod_name == null ? "" : v.vod_name);
        // 年份 + 备注: 有备注时显示备注(如"更新至32集"), 否则显示年份
        String year = v.vod_year == null ? "" : v.vod_year;
        String remarks = v.vod_remarks == null ? "" : v.vod_remarks;
        if (!remarks.isEmpty()) {
            h.year.setText(remarks);
        } else if (!year.isEmpty()) {
            h.year.setText(year + "年");
        } else {
            h.year.setText("");
        }
        // 评分(黄底角标, 无评分隐藏)
        String score = v.vod_score == null ? "" : v.vod_score.trim();
        if (score.isEmpty()) {
            h.score.setVisibility(View.GONE);
        } else {
            h.score.setVisibility(View.VISIBLE);
            h.score.setText(score);
        }
        // 左上标签: 优先源返回的标签, 否则用当前分类名占位
        String tag = v.vod_tag == null ? "" : v.vod_tag.trim();
        if (tag.isEmpty()) tag = v.type_name == null ? "" : v.type_name.trim();
        if (tag.isEmpty()) tag = currentTabName == null ? "" : currentTabName;
        if (tag.isEmpty()) {
            h.tag.setVisibility(View.GONE);
        } else {
            h.tag.setVisibility(View.VISIBLE);
            h.tag.setText(tag);
        }
        ImageUtil.loadPoster(h.poster, v.vod_pic);
        h.itemView.setOnClickListener(view -> { if (listener != null) listener.onClick(v); });
        // 焦点变化时缩放动画, 让用户知道当前焦点位置
        h.itemView.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start();
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
            }
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView poster;
        TextView name, year, tag, score;
        VH(View v) {
            super(v);
            poster = v.findViewById(R.id.ivPoster);
            name = v.findViewById(R.id.tvName);
            year = v.findViewById(R.id.tvYear);
            tag = v.findViewById(R.id.tvTag);
            score = v.findViewById(R.id.tvScore);
        }
    }
}
