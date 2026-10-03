package com.seanming.player.bean;

import android.util.Base64;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;

/**
 * 网盘存储配置(对标影视仓 StorageDrive).
 * type: 1=WebDAV, 2=AList(OpenList).
 * config 字段直接展开为 url/username/password/initPath, 便于持久化与读取.
 */
public class StorageDrive implements Serializable {

    public static final int TYPE_WEBDAV = 1;
    public static final int TYPE_ALIST = 2;

    public int id;
    public String name;
    public int type;
    public String url;
    public String username;
    public String password;
    /** 初始进入路径, 可为空 */
    public String initPath;

    /** AList 登录 token 运行时缓存, 不持久化 */
    public transient String token;

    public StorageDrive() {}

    public StorageDrive(int id, String name, int type, String url,
                        String username, String password, String initPath) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.url = url;
        this.username = username;
        this.password = password;
        this.initPath = initPath;
    }

    /** 规范化 base url: 补全协议 + 末尾斜杠 */
    public String baseUrl() {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return u;
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        if (!u.endsWith("/")) u += "/";
        return u;
    }

    /** WebDAV Basic 认证头值(无账号返回 null) */
    public String basicAuth() {
        if (username == null || username.isEmpty()) return null;
        String raw = username + ":" + (password == null ? "" : password);
        return "Basic " + Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    public String typeName() {
        return type == TYPE_ALIST ? "AList" : "WebDAV";
    }
}