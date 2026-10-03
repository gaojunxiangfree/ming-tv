package com.seanming.player.bean;

import java.io.Serializable;

/**
 * 视频解析接口定义, 兼容 TVBox 接口 JSON 中的 parses 节点.
 * type: 0=未使用, 1=json 解析, 2=聚合解析
 * url 末尾会拼接真实视频地址 (按 TVBox 规范 url + encoded(videoUrl))
 */
public class Parse implements Serializable {

    public String name;
    public int type;
    public String url;
    public String ext;

    public String getName() { return name; }
    public int getType() { return type; }
    public String getUrl() { return url; }
    public String getExt() { return ext; }
}
