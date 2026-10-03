package com.seanming.player.server;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;

import com.google.gson.JsonObject;
import com.seanming.player.api.ApiConfig;
import com.seanming.player.ui.push.PushActivity;
import com.seanming.player.util.ThreadUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** 局域网 WiFi 配置服务，手机浏览器访问即可推送接口 */
public class WifiConfigServer {

    private static final int PORT = 9753;
    private static WifiConfigServer instance;
    private ServerSocket serverSocket;
    private boolean running;
    private Thread thread;
    private Context context;

    public static WifiConfigServer get() {
        if (instance == null) instance = new WifiConfigServer();
        return instance;
    }

    public void start(Context ctx) {
        if (running) return;
        context = ctx.getApplicationContext();
        running = true;
        thread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                while (running) {
                    Socket client = serverSocket.accept();
                    handle(client);
                }
            } catch (IOException ignored) {}
        });
        thread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
    }

    public boolean isRunning() {
        return running;
    }

    public String getAddress() {
        String ip = wifiIp();
        if (ip == null) ip = localIp();
        if (ip == null) ip = "127.0.0.1";
        return "http://" + ip + ":" + PORT;
    }

    /** WiFi 已连接时取无线网卡地址 */
    private String wifiIp() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip == 0) return null;
            return Formatter.formatIpAddress(ip);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 回退: 遍历网卡取站点本地 IPv4(投影仪常用有线网) */
    private String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address
                            && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void handle(Socket client) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(client.getOutputStream())) {

            String line = in.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";

            String body = null;
            int contentLength = 0;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.startsWith("Content-Length:")) {
                    contentLength = Integer.parseInt(line.split(":")[1].trim());
                }
            }
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                in.read(buf, 0, contentLength);
                body = new String(buf);
            }

            if ("/".equals(path) || "/index.html".equals(path)) {
                sendHtml(out);
            } else if ("/api/config".equals(path) && "POST".equals(method)) {
                handleConfig(body, out);
            } else if ("/api/push".equals(path) && "POST".equals(method)) {
                handlePush(body, out);
            } else if ("/api/search".equals(path) && "POST".equals(method)) {
                handleSearch(body, out);
            } else if ("/api/status".equals(path)) {
                sendStatus(out);
            } else {
                send404(out);
            }
            client.close();
        } catch (Exception ignored) {}
    }

    private void sendHtml(PrintWriter out) {
        String current = ApiConfig.get().getApiUrl();
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>SeanMing 配置推送</title><style>"
                + "body{background:#0d1117;color:#c9d1d9;font-family:system-ui;padding:20px;max-width:600px;margin:0 auto}"
                + "input,textarea{width:100%;padding:12px;margin:8px 0;background:#161b22;border:1px solid #30363d;color:#fff;border-radius:6px;box-sizing:border-box}"
                + "button{background:#ff6b6b;color:#fff;border:none;padding:12px 24px;border-radius:6px;font-size:16px;cursor:pointer;width:100%}"
                + "h1{color:#ff6b6b;font-size:20px}"
                + ".tip{color:#8b949e;font-size:13px;margin-top:8px}"
                + ".success{color:#3fb950;margin-top:12px}"
                + "</style></head><body>"
                + "<h1>📺 推送接口到 TV</h1>"
                + "<p class='tip'>当前接口: " + (current.isEmpty() ? "未配置" : current) + "</p>"
                + "<textarea id='url' rows='3' placeholder='粘贴 TVBox 接口地址，如 http://example.com/api.json'></textarea>"
                + "<button onclick='push()'>推送配置</button>"
                + "<div id='msg'></div>"
                + "<p class='tip'>支持单接口 JSON 或 多仓格式</p>"
                + "<h1 style='margin-top:28px'>🎬 推送视频到 TV</h1>"
                + "<input id='vname' placeholder='标题(可留空)' />"
                + "<input id='vurl' placeholder='视频直链, 如 http://xxx.com/a.m3u8 或 .mp4' />"
                + "<button onclick='pushVideo()'>推送播放</button>"
                + "<div id='vmsg'></div>"
                + "<h1 style='margin-top:28px'>🔍 远程搜索</h1>"
                + "<input id='skey' placeholder='输入片名, 电视立刻全源搜索' />"
                + "<button onclick='search()'>在电视上搜索</button>"
                + "<div id='smsg'></div>"
                + "<script>"
                + "function push(){var u=document.getElementById('url').value.trim();if(!u)return;"
                + "fetch('/api/config',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'url='+encodeURIComponent(u)})"
                + ".then(r=>r.text()).then(t=>{document.getElementById('msg').innerHTML='<p class=success>'+t+'</p>';})"
                + ".catch(e=>{document.getElementById('msg').innerHTML='<p class=success style=\"color:#f85149\">推送失败</p>';});}"
                + "function pushVideo(){var u=document.getElementById('vurl').value.trim();if(!u)return;"
                + "var n=document.getElementById('vname').value.trim();"
                + "fetch('/api/push',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'url='+encodeURIComponent(u)+'&name='+encodeURIComponent(n)})"
                + ".then(r=>r.text()).then(t=>{document.getElementById('vmsg').innerHTML='<p class=success>'+t+'</p>';})"
                + ".catch(e=>{document.getElementById('vmsg').innerHTML='<p class=success style=\"color:#f85149\">推送失败</p>';});}"
                + "function search(){var k=document.getElementById('skey').value.trim();if(!k)return;"
                + "fetch('/api/search',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'word='+encodeURIComponent(k)})"
                + ".then(r=>r.text()).then(t=>{document.getElementById('smsg').innerHTML='<p class=success>'+t+'</p>';})"
                + ".catch(e=>{document.getElementById('smsg').innerHTML='<p class=success style=\"color:#f85149\">推送失败</p>';});}"
                + "</script></body></html>";
        out.print("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n" + html);
        out.flush();
    }

    private void handleConfig(String body, PrintWriter out) {
        try {
            String decoded = URLDecoder.decode(body, StandardCharsets.UTF_8.name());
            String[] pairs = decoded.split("&");
            String url = "";
            for (String p : pairs) {
                if (p.startsWith("url=")) url = p.substring(4);
            }
            if (url.isEmpty()) {
                out.print("HTTP/1.1 400 Bad Request\r\n\r\n地址为空");
                out.flush();
                return;
            }
            final String fUrl = url;
            // 入库到多源列表并切换生效; 后续可在 TV 设置页切换/删除
            ApiConfig.get().addAndSelect(fUrl, new ThreadUtils.Callback<Boolean>() {
                @Override
                public void onResult(Boolean ok) {}
                @Override
                public void onError(Throwable t) {}
            });
            out.print("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n推送成功，TV 端正在加载…");
            out.flush();
        } catch (Exception e) {
            out.print("HTTP/1.1 500 Internal Server Error\r\n\r\n推送失败");
            out.flush();
        }
    }

    private void sendStatus(PrintWriter out) {
        JsonObject obj = new JsonObject();
        obj.addProperty("api", ApiConfig.get().getApiUrl());
        obj.addProperty("sites", ApiConfig.get().getSites().size());
        obj.addProperty("loaded", ApiConfig.get().isLoaded());
        out.print("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" + obj.toString());
        out.flush();
    }

    /** 手机推送视频直链: 直接在主线程拉起播放页 */
    private void handlePush(String body, PrintWriter out) {
        try {
            String decoded = body == null ? "" : URLDecoder.decode(body, StandardCharsets.UTF_8.name());
            String url = "";
            String name = "";
            for (String p : decoded.split("&")) {
                if (p.startsWith("url=")) url = p.substring(4);
                else if (p.startsWith("name=")) name = p.substring(5);
            }
            url = url.trim();
            if (url.isEmpty()) {
                out.print("HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n视频链接为空");
                out.flush();
                return;
            }
            final String fUrl = url;
            final String fName = name;
            ThreadUtils.main(() -> {
                try {
                    PushActivity.play(context, fUrl, fName);
                } catch (Throwable ignored) {}
            });
            out.print("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n已推送到电视，正在播放…");
            out.flush();
        } catch (Exception e) {
            out.print("HTTP/1.1 500 Internal Server Error\r\n\r\n推送失败");
            out.flush();
        }
    }

    /** 手机远程搜索(对齐影视仓搜索页"远程搜索"键): 手机输入关键词, 电视立刻全源秒搜 */
    private void handleSearch(String body, PrintWriter out) {
        try {
            String decoded = body == null ? "" : URLDecoder.decode(body, StandardCharsets.UTF_8.name());
            String word = "";
            for (String p : decoded.split("&")) {
                if (p.startsWith("word=")) word = p.substring(5);
            }
            word = word.trim();
            if (word.isEmpty()) {
                out.print("HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n搜索内容为空");
                out.flush();
                return;
            }
            final String fWord = word;
            ThreadUtils.main(() -> {
                try {
                    android.content.Intent i = new android.content.Intent(
                            context, com.seanming.player.ui.search.FastSearchActivity.class);
                    i.putExtra(com.seanming.player.ui.search.FastSearchActivity.EXTRA_KEYWORD, fWord);
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(i);
                } catch (Throwable ignored) {}
            });
            out.print("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n已推送到电视，正在搜索…");
            out.flush();
        } catch (Exception e) {
            out.print("HTTP/1.1 500 Internal Server Error\r\n\r\n搜索失败");
            out.flush();
        }
    }

    private void send404(PrintWriter out) {
        out.print("HTTP/1.1 404 Not Found\r\n\r\n404");
        out.flush();
    }
}
