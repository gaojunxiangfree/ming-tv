package com.seanming.player.server;

import android.content.Context;
import android.util.Log;

import com.seanming.player.spider.SpiderManager;
import com.seanming.player.util.ThreadUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地代理服务(对齐影视仓常驻在 9978 端口的 JServer).
 *
 * 影视仓用 GET /proxy?do=&lt;站点key&gt;&amp;... 承载爬虫本地代理
 * (见 oo0oO0.proxyLocal -&gt; jarLoader.proxyInvoke -&gt; Proxy.proxy(map)).
 * wex 加密源会生成指向 http://127.0.0.1:9978/proxy 的 URL 来中转取数/取流,
 * 没有这个常驻服务就会拿不到数据, 爬虫内部随即抛异常.
 */
public class LocalProxyServer {

    public static final int PORT = 9978;
    private static final String TAG = "LocalProxy";

    private static LocalProxyServer instance;

    public static LocalProxyServer get() {
        if (instance == null) instance = new LocalProxyServer();
        return instance;
    }

    private ServerSocket serverSocket;
    private volatile boolean running;
    private int port = PORT;

    public int getPort() { return port; }

    public boolean isRunning() { return running; }

    public void start(Context ctx) {
        if (running) return;
        running = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"));
                port = PORT;
                Log.i(TAG, "local proxy listening on 127.0.0.1:" + port);
            } catch (Throwable t) {
                // 端口被占用(例如真机上同时开着影视仓)时无法提供服务
                Log.e(TAG, "bind 127.0.0.1:" + PORT + " failed: " + t.getMessage());
                running = false;
                return;
            }
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    ThreadUtils.io(() -> handle(client));
                } catch (Throwable t) {
                    if (!running) break;
                }
            }
        }, "local-proxy").start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
    }

    private void handle(Socket client) {
        try (Socket c = client;
             BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = c.getOutputStream()) {

            String requestLine = in.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !"GET".equalsIgnoreCase(parts[0])) {
                write(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            String uri = parts[1];
            int q = uri.indexOf('?');
            String path = q >= 0 ? uri.substring(0, q) : uri;
            Map<String, String> params = q >= 0 ? parseQuery(uri.substring(q + 1)) : new HashMap<>();

            // 请求头一并带上(影视仓同样把 headers 合并进 parms)
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    params.putIfAbsent(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                }
            }

            if (!"/proxy".equals(path)) {
                write(out, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), null);
                return;
            }

            Object[] r = SpiderManager.proxyLocal(params);
            if (r == null || r.length < 3) {
                write(out, 500, "text/plain", "500".getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            int status = r[0] instanceof Number ? ((Number) r[0]).intValue() : 200;
            String mime = r[1] == null ? "application/octet-stream" : r[1].toString();
            byte[] body = toBytes(r[2]);
            Map<String, String> headers = null;
            if (r.length > 3 && r[3] instanceof Map) {
                headers = new HashMap<>();
                for (Object k : ((Map<?, ?>) r[3]).keySet()) {
                    Object v = ((Map<?, ?>) r[3]).get(k);
                    if (k != null && v != null) headers.put(k.toString(), v.toString());
                }
            }
            write(out, status, mime, body, headers);
        } catch (Throwable t) {
            Log.w(TAG, "handle error: " + t);
        }
    }

    /** 爬虫返回的第三段可能是 InputStream / byte[] / String */
    private static byte[] toBytes(Object o) throws IOException {
        if (o == null) return new byte[0];
        if (o instanceof byte[]) return (byte[]) o;
        if (o instanceof InputStream) {
            try (InputStream is = (InputStream) o) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            }
        }
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void write(OutputStream out, int status, String mime, byte[] body,
                              Map<String, String> headers) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n");
        sb.append("Content-Type: ").append(mime).append("\r\n");
        sb.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        if (body != null && body.length > 0) out.write(body);
        out.flush();
    }

    private static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String kv : query.split("&")) {
            int idx = kv.indexOf('=');
            try {
                if (idx > 0) {
                    map.put(URLDecoder.decode(kv.substring(0, idx), "UTF-8"),
                            URLDecoder.decode(kv.substring(idx + 1), "UTF-8"));
                } else if (!kv.isEmpty()) {
                    map.put(URLDecoder.decode(kv, "UTF-8"), "");
                }
            } catch (Throwable ignored) {}
        }
        return map;
    }
}
