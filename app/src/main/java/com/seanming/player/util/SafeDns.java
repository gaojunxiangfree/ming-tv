package com.seanming.player.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;

/**
 * 安全 DNS (对齐影视仓的 safeDns 能力):
 * 1. 优先走 DoH (DNS over HTTPS) 解析, 避免运营商 DNS 劫持/污染导致的域名解析失败;
 * 2. 优先返回 IPv4, 规避部分 CDN 在 IPv6 路由不通时连接超时的问题(模拟器常见);
 * 3. DoH 失败自动回退系统 DNS, 保证兼容性.
 */
public class SafeDns implements Dns {

    /** DoH 解析服务器(与影视仓一致: 腾讯 DNSPod / 阿里) */
    private static final String[] DOH_URLS = {
            "https://doh.pub/dns-query",
            "https://dns.alidns.com/dns-query",
    };

    private final List<DnsOverHttps> dohList = new ArrayList<>();
    private final Dns fallback = Dns.SYSTEM;

    public SafeDns() {
        for (String url : DOH_URLS) {
            try {
                // bootstrap 客户端用系统 DNS 解析 DoH 服务器自身域名, 避免递归依赖
                OkHttpClient bootstrap = new OkHttpClient.Builder()
                        .dns(fallback)
                        .build();
                dohList.add(new DnsOverHttps.Builder()
                        .client(bootstrap)
                        .url(HttpUrl.get(url))
                        .build());
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        // 1. 依次尝试 DoH 服务器
        for (DnsOverHttps doh : dohList) {
            try {
                List<InetAddress> v4 = preferV4(doh.lookup(hostname));
                if (!v4.isEmpty()) return v4;
            } catch (Throwable ignored) {}
        }
        // 2. DoH 全失败: 回退系统 DNS, 仍优先 IPv4
        return preferV4(fallback.lookup(hostname));
    }

    /** 优先返回 IPv4 地址; 若域名只有 IPv6 则原样返回, 避免破坏 IPv6-only 域名 */
    private List<InetAddress> preferV4(List<InetAddress> addrs) {
        List<InetAddress> v4 = new ArrayList<>();
        for (InetAddress a : addrs) {
            if (a instanceof Inet4Address) v4.add(a);
        }
        return v4.isEmpty() ? addrs : v4;
    }
}
