package com.seanming.player.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** 播放历史(含进度) */
@Entity(tableName = "history")
public class HistoryRecord {

    /** siteKey + "|" + vodId */
    @PrimaryKey
    @NonNull
    public String id = "";

    public String siteKey;
    public String vodId;
    public String vodName;
    public String vodPic;

    /** 当前线路与剧集 */
    public String flag;
    public int episodeIndex;
    public String episodeName;

    /** 播放进度 */
    public long position;
    public long duration;

    public long updateTime;
}
