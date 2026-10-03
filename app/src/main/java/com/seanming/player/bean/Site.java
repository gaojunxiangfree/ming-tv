package com.seanming.player.bean;

import java.io.Serializable;
import java.util.List;

/**
 * 站点源定义, 兼容 TVBox 接口 JSON 中的 sites 节点.
 * type: 0=xml采集, 1=json采集(苹果cms), 3=jar爬虫(spider), 4=drpy js爬虫
 */
public class Site implements Serializable {

    private String key;
    private String name;
    private int type;
    private String api;
    private int searchable;   // 1 支持搜索
    private int quickSearch;  // 1 支持快速搜索
    private int filterable;   // 1 支持筛选
    private String ext;       // 爬虫扩展参数(可为字符串或json字符串)
    private String jar;       // jar 爬虫地址
    private int playerType;   // 1=IJK 2=Exo, 默认按全局
    private int changeable;   // 是否允许切换源
    private List<String> categories; // 首页展示的分类名白名单

    private boolean activated; // 运行时: 当前选中站点

    public String getKey() { return key == null || key.isEmpty() ? name : key; }
    public void setKey(String key) { this.key = key; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getType() { return type; }
    public void setType(int type) { this.type = type; }
    public String getApi() { return api; }
    public void setApi(String api) { this.api = api; }
    public int getSearchable() { return searchable; }
    public void setSearchable(int s) { this.searchable = s; }
    public int getQuickSearch() { return quickSearch; }
    public void setQuickSearch(int q) { this.quickSearch = q; }
    public int getFilterable() { return filterable; }
    public void setFilterable(int f) { this.filterable = f; }
    public String getExt() { return ext; }
    public void setExt(String ext) { this.ext = ext; }
    public String getJar() { return jar; }
    public void setJar(String jar) { this.jar = jar; }
    public int getPlayerType() { return playerType; }
    public void setPlayerType(int p) { this.playerType = p; }
    public int getChangeable() { return changeable; }
    public void setChangeable(int c) { this.changeable = c; }
    public List<String> getCategories() { return categories; }
    public void setCategories(List<String> c) { this.categories = c; }
    public boolean isActivated() { return activated; }
    public void setActivated(boolean a) { this.activated = a; }

    public boolean isSpider() { return type == 3 || type == 4; }
}
