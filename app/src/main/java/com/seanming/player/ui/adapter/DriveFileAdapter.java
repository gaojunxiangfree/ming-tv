package com.seanming.player.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.seanming.player.R;
import com.seanming.player.bean.DriveFile;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 网盘文件列表 */
public class DriveFileAdapter extends RecyclerView.Adapter<DriveFileAdapter.VH> {

    public interface OnClick {
        void onClick(DriveFile f);
        void onLongClick(DriveFile f);
    }

    private final List<DriveFile> items = new ArrayList<>();
    private OnClick listener;

    public void setOnClick(OnClick l) { this.listener = l; }

    public void submit(List<DriveFile> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_drive, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DriveFile f = items.get(position);
        h.name.setText(f.name);
        if (f.isUp) {
            h.icon.setText("↩");
            h.meta.setText("");
        } else if (f.drive != null && f.path == null) {
            // 网盘根条目(列表第一层)
            h.icon.setText(f.drive.type == 2 ? "🅰" : "☁");
            h.meta.setText(f.drive.typeName());
        } else if (f.isDir) {
            h.icon.setText("📁");
            h.meta.setText(mtime(f));
        } else {
            h.icon.setText(f.isMedia() ? "🎬" : "📄");
            String s = f.sizeText();
            h.meta.setText(s.isEmpty() ? f.ext().toUpperCase(Locale.ROOT) : s);
        }
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(f); });
        h.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onLongClick(f);
            return true;
        });
        h.itemView.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) view.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start();
            else view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
        });
    }

    private static String mtime(DriveFile f) {
        if (f.lastModified <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(f.lastModified));
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView icon, name, meta;
        VH(View v) {
            super(v);
            icon = v.findViewById(R.id.tvIcon);
            name = v.findViewById(R.id.tvName);
            meta = v.findViewById(R.id.tvMeta);
        }
    }
}