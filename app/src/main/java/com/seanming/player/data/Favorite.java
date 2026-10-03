package com.seanming.player.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** 收藏 */
@Entity(tableName = "favorite")
public class Favorite {

    /** siteKey + "|" + vodId */
    @PrimaryKey
    @NonNull
    public String id = "";

    public String siteKey;
    public String vodId;
    public String vodName;
    public String vodPic;
    public String vodRemarks;
    public long createTime;
}
