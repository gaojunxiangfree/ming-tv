package com.seanming.player.bean;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 直播频道分组 */
public class LiveChannelGroup implements Serializable {
    public String name;
    public final List<LiveChannel> channels = new ArrayList<>();

    public LiveChannelGroup(String name) {
        this.name = name;
    }
}
