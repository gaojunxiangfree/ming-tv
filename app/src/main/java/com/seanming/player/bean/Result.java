package com.seanming.player.bean;

import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一返回结构, 同时兼容苹果cms采集接口与 spider 爬虫返回的 json.
 */
public class Result implements Serializable {

    /** spider 返回的首页分类数组, TVBox 标准字段名为 "class" */
    @SerializedName("class")
    public List<VodClass> classes;
    public List<Vod> list;
    /**
     * 筛选器: 各源结构差异很大, 用 Object 兜底避免 Gson 反序列化失败导致整个结果丢失.
     * 当前 UI 未使用筛选功能.
     */
    public Object filters;

    // 播放解析结果字段
    public int parse;      // 0=直连, 1=嗅探
    public String url;     // 播放地址
    public String jx;      // 解析接口
    public String header;  // 请求头 json 字符串
    public String playUrl; // 解析前缀
    public String msg;

    public int page;
    public int pagecount;
    public int total;

    public Result() {
        classes = new ArrayList<>();
        list = new ArrayList<>();
    }

    public static Result error(String msg) {
        Result r = new Result();
        // msg 为空时兜底, 否则 hasError() 会误判为"成功", 界面把加载失败显示成"无分类"
        r.msg = (msg == null || msg.isEmpty()) ? "加载失败" : msg;
        return r;
    }

    public boolean hasError() {
        return msg != null && !msg.isEmpty();
    }
}
