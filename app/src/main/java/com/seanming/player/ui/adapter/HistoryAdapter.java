package com.seanming.player.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.seanming.player.R;
import com.seanming.player.data.HistoryRecord;
import com.seanming.player.util.ImageUtil;

import java.util.ArrayList;
import java.util.List;

/** 历史记录列表适配器 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    public interface OnItemClickListener {
        /** 点击整行 - 继续播放 */
        void onClick(HistoryRecord record);
        /** 点击删除 */
        void onDelete(HistoryRecord record);
    }

    private final List<HistoryRecord> items = new ArrayList<>();
    private OnItemClickListener listener;

    public void setOnItemClickListener(OnItemClickListener l) { this.listener = l; }

    public void submit(List<HistoryRecord> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_history, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        HistoryRecord r = items.get(position);
        h.name.setText(r.vodName == null ? "" : r.vodName);
        h.episode.setText(r.episodeName == null ? "" : r.episodeName);
        // 进度百分比与文本
        int percent = 0;
        String posText = formatMs(r.position);
        if (r.duration > 0) {
            percent = (int) (r.position * 100 / r.duration);
            posText += " / " + formatMs(r.duration);
        }
        h.progress.setText("已观看 " + percent + "%  " + posText);
        h.pb.setProgress(percent);
        h.time.setText(formatTimeAgo(r.updateTime));
        ImageUtil.loadPoster(h.poster, r.vodPic);
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(r); });
        h.btnDelete.setOnClickListener(v -> { if (listener != null) listener.onDelete(r); });
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
        TextView name, episode, progress, time;
        ProgressBar pb;
        View btnDelete;
        VH(View v) {
            super(v);
            poster = v.findViewById(R.id.ivPoster);
            name = v.findViewById(R.id.tvName);
            episode = v.findViewById(R.id.tvEpisode);
            progress = v.findViewById(R.id.tvProgress);
            pb = v.findViewById(R.id.pbProgress);
            time = v.findViewById(R.id.tvTime);
            btnDelete = v.findViewById(R.id.btnDelete);
        }
    }

    /** 毫秒 -> mm:ss 或 h:mm:ss */
    private static String formatMs(long ms) {
        if (ms <= 0) return "00:00";
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }

    /** 时间戳 -> "刚刚 / N分钟前 / N小时前 / MM-dd" */
    private static String formatTimeAgo(long ts) {
        if (ts <= 0) return "";
        long diff = System.currentTimeMillis() - ts;
        long min = diff / 60000;
        if (min < 1) return "刚刚";
        if (min < 60) return min + "分钟前";
        long hour = min / 60;
        if (hour < 24) return hour + "小时前";
        long day = hour / 24;
        if (day < 7) return day + "天前";
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault());
        return sdf.format(new java.util.Date(ts));
    }
}
