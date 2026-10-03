package com.seanming.player.bean;

/** EPG 节目单条目 */
public class EpgProgram {
    public String channelId;
    /** 开始时间(毫秒) */
    public long start;
    /** 结束时间(毫秒) */
    public long stop;
    public String title;
    public String desc;

    public EpgProgram(String channelId, long start, long stop, String title, String desc) {
        this.channelId = channelId;
        this.start = start;
        this.stop = stop;
        this.title = title;
        this.desc = desc;
    }

    /** 是否正在播出 */
    public boolean isPlaying(long now) {
        return now >= start && now < stop;
    }
}