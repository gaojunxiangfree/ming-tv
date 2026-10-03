package com.seanming.player.bean;

import java.io.Serializable;

/**
 * 网盘目录/文件条目.
 * isUp=true 表示"返回上级"伪条目; drive=null 时表示当前是网盘列表(根页面).
 */
public class DriveFile implements Serializable {

    public String name;
    public boolean isDir;
    public long size;
    public long lastModified;
    /** 在网盘中的完整路径, 以 "/" 开头 */
    public String path;
    /** AList 列表直接给出的播放地址(可能为空) */
    public String fileUrl;
    /** 归属网盘, 根页面为 null */
    public StorageDrive drive;
    /** 上级条目伪节点 */
    public boolean isUp;

    public DriveFile() {}

    public DriveFile(String name, boolean isDir, String path) {
        this.name = name;
        this.isDir = isDir;
        this.path = path;
    }

    /** 返回上级伪条目 */
    public static DriveFile up() {
        DriveFile f = new DriveFile();
        f.isUp = true;
        f.isDir = true;
        f.name = "返回上级";
        return f;
    }

    /** 扩展名(小写, 无点) */
    public String ext() {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        if (i <= 0 || i >= name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase();
    }

    /** 是否为可播放的视频/字幕文件 */
    public boolean isMedia() {
        String e = ext();
        switch (e) {
            case "mp4": case "mkv": case "avi": case "mov": case "wmv": case "flv":
            case "ts": case "m2ts": case "m4v": case "rmvb": case "rm": case "webm":
            case "mpg": case "mpeg": case "3gp": case "vob": case "m3u8":
            case "srt": case "ass": case "ssa": case "vtt": case "sub":
                return true;
            default:
                return false;
        }
    }

    public String sizeText() {
        if (isDir || size <= 0) return "";
        double v = size;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return String.format(java.util.Locale.getDefault(), "%.1f%s", v, units[i]);
    }
}