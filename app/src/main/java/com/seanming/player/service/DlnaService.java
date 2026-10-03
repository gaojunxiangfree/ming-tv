package com.seanming.player.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.text.format.Formatter;

import com.seanming.player.ui.push.PushActivity;
import com.seanming.player.util.ThreadUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Random;
import java.util.UUID;

/**
 * DLNA 投屏接收 (MediaRender service/DMR) 骨架。
 *
 * 完整 DMR 需真机局域网验证。本服务提供：
 *  1) 真实 SSDP 响应：监听 UDP 1900 组播，响应 M-SEARCH 并周期性发送 ssdp:alive，
 *     使本设备以 MediaRenderer 身份出现在局域网 DLNA 控制端(手机投屏 App) 中;
 *  2) HTTP 控制端：提供 device description.xml 与 SOAP AVTransport
 *     SetAVTransportURI/Play, 收到投屏地址后用现有推送链路拉起播放;
 *  3) 浏览器回退: GET /dlna/push?url= (模拟器可通过 curl 验证"推入→播放"链路)。
 */
public class DlnaService extends Service {

    public static final int HTTP_PORT = 9754;
    private static final String MULTICAST_GROUP = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    private static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String UDN = "uuid:" + UUID.nameUUIDFromBytes(
            "seanming-player-dmr".getBytes(StandardCharsets.UTF_8));

    private static final Random RND = new Random();
    private static volatile boolean running;
    private static DlnaService instance;

    private ServerSocket httpServer;
    private MulticastSocket ssdpSocket;
    private Thread httpThread;
    private Thread ssdpThread;
    private Thread notifyThread;
    private String localIp = "127.0.0.1";

    public static synchronized void start(Context ctx) {
        if (running) return;
        Intent i = new Intent(ctx, DlnaService.class);
        ctx.startService(i);
    }

    public static synchronized void stop(Context ctx) {
        if (!running) return;
        ctx.stopService(new Intent(ctx, DlnaService.class));
    }

    public static boolean isRunning() { return running; }

    public static String getHttpBase() {
        String ip = instance != null ? instance.localIp : "127.0.0.1";
        return "http://" + ip + ":" + HTTP_PORT;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        localIp = resolveIp();
        running = true;
        startHttpServer();
        startSsdp();
        startNotify();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return new Binder(); }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        try { if (httpServer != null) httpServer.close(); } catch (IOException ignored) {}
        if (ssdpSocket != null) { try { ssdpSocket.close(); } catch (Exception ignored) {} }
        super.onDestroy();
    }

    // ---------------- 本地 IP ----------------
    private String resolveIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) return Formatter.formatIpAddress(ip);
            }
        } catch (Throwable ignored) {}
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "127.0.0.1";
    }

    // ---------------- HTTP 控制端 ----------------
    private void startHttpServer() {
        httpThread = new Thread(() -> {
            try {
                httpServer = new ServerSocket(HTTP_PORT);
                while (running) {
                    try {
                        Socket c = httpServer.accept();
                        handle(c);
                    } catch (IOException ignored) {}
                }
            } catch (IOException ignored) {}
        }, "dlna-http");
        httpThread.start();
    }

    private void handle(Socket client) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = client.getOutputStream()) {
            String line = in.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];

            int contentLength = 0;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(line.split(":")[1].trim()); } catch (Exception ignored) {}
                }
            }
            String body = null;
            if (contentLength > 0) {
                int len = 0;
                StringBuilder sb = new StringBuilder(contentLength);
                char[] buf = new char[Math.min(contentLength, 8192)];
                while (len < contentLength) {
                    int r = in.read(buf, 0, Math.min(buf.length, contentLength - len));
                    if (r < 0) break;
                    sb.append(buf, 0, r);
                    len += r;
                }
                body = sb.toString();
            }

            if (path.contains("description.xml")) {
                sendDescription(out);
            } else if (path.contains("AVTransport") && "POST".equals(method)) {
                handleAvTransport(body, out);
            } else if (path.contains("/dlna/push")) {
                handlePushQuery(path, method, body, out);
            } else {
                out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception ignored) {}
    }

    private void sendDescription(OutputStream out) throws IOException {
        String desc = "<?xml version=\"1.0\"?>"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<device>"
                + "<deviceType>" + DEVICE_TYPE + "</deviceType>"
                + "<friendlyName>SeanMing TV 投屏接收</friendlyName>"
                + "<manufacturer>SeanMing</manufacturer>"
                + "<modelName>SeanMing DMR</modelName>"
                + "<serialNumber>sm-dmr-1</serialNumber>"
                + "<UDN>" + UDN + "</UDN>"
                + "<serviceList>"
                + "<service>"
                + "<serviceType>" + AV_TRANSPORT + "</serviceType>"
                + "<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>"
                + "<controlURL>/upnp/control/AVTransport</controlURL>"
                + "<eventSubURL>/upnp/event/AVTransport</eventSubURL>"
                + "<SCPDURL>/scpd/AVTransport.xml</SCPDURL>"
                + "</service>"
                + "</serviceList>"
                + "</device></root>";
        byte[] data = desc.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/xml; charset=\"utf-8\"\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private void handleAvTransport(String body, OutputStream out) throws IOException {
        if (body == null) body = "";
        String title = null;
        String uri = extractTag(body, "CurrentURI");
        String meta = extractTag(body, "CurrentURIMetaData");
        if (meta != null) {
            int s = meta.toLowerCase().indexOf(":title>");
            if (s >= 0) {
                int start = meta.indexOf('>', s);
                int end = meta.indexOf('<', start);
                if (start >= 0 && end > start) title = meta.substring(start + 1, end).trim();
            }
        }

        boolean hasUri = uri != null && !uri.isEmpty();
        if (hasUri) {
            final String u = uri;
            final String t = title;
            ThreadUtils.main(() -> {
                try { PushActivity.play(getApplicationContext(), u, t); } catch (Throwable ignored) {}
            });
        }

        String resp = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body>"
                + "<u:SetAVTransportURIResponse xmlns:u=\"" + AV_TRANSPORT + "\">"
                + "<InstanceID>0</InstanceID>"
                + "</u:SetAVTransportURIResponse>"
                + "</s:Body></s:Envelope>";
        byte[] data = resp.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\n"
                + "Ext: \r\n"
                + "Content-Type: text/xml; charset=\"utf-8\"\r\n"
                + "Content-Length: " + data.length + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private void handlePushQuery(String path, String method, String body, OutputStream out) throws IOException {
        String url = null, name = null;
        if ("POST".equals(method) && body != null) {
            String[] pairs = body.split("&");
            for (String p : pairs) {
                if (p.startsWith("url=")) url = URLDecoder.decode(p.substring(4), StandardCharsets.UTF_8.name());
                else if (p.startsWith("name=")) name = URLDecoder.decode(p.substring(5), StandardCharsets.UTF_8.name());
            }
        } else {
            int qi = path.indexOf('?');
            if (qi >= 0) {
                String[] pairs = path.substring(qi + 1).split("&");
                for (String p : pairs) {
                    if (p.startsWith("url=")) url = URLDecoder.decode(p.substring(4), StandardCharsets.UTF_8.name());
                    else if (p.startsWith("name=")) name = URLDecoder.decode(p.substring(5), StandardCharsets.UTF_8.name());
                }
            }
        }
        if (url == null || url.isEmpty()) {
            byte[] d = "HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nmissing url".getBytes(StandardCharsets.UTF_8);
            out.write(d); out.flush(); return;
        }
        final String u = url.trim();
        final String n = name;
        ThreadUtils.main(() -> {
            try { PushActivity.play(getApplicationContext(), u, n); } catch (Throwable ignored) {}
        });
        byte[] d = ("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n"
                + "已推送: " + u).getBytes(StandardCharsets.UTF_8);
        out.write(d); out.flush();
    }

    private static String extractTag(String body, String tag) {
        String lower = body.toLowerCase();
        String lt = "<" + tag.toLowerCase();
        int s = lower.indexOf(lt);
        if (s < 0) return null;
        int close = body.indexOf('>', s);
        int end = lower.indexOf("</" + tag.toLowerCase(), s);
        if (close < 0 || end <= close) return null;
        return body.substring(close + 1, end).trim();
    }

    // ---------------- SSDP DMR 发现 ----------------
    private void startSsdp() {
        ssdpThread = new Thread(() -> {
            try {
                MulticastSocket ms = new MulticastSocket(null);
                ms.setReuseAddress(true);
                ms.setLoopbackMode(false);
                ms.bind(new InetSocketAddress(SSDP_PORT));
                InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
                NetworkInterface ni = findMulticastInterface();
                if (ni != null) ms.joinGroup(new InetSocketAddress(group, SSDP_PORT), ni);
                else ms.joinGroup(group);
                ssdpSocket = ms;
                byte[] buf = new byte[2048];
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try { ms.receive(p); } catch (IOException ignored) { break; }
                    String req = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
                    if (req.contains("M-SEARCH")) {
                        String reply = "HTTP/1.1 200 OK\r\n"
                                + "CACHE-CONTROL: max-age=1800\r\n"
                                + "ST: " + DEVICE_TYPE + "\r\n"
                                + "USN: " + UDN + "::" + DEVICE_TYPE + "\r\n"
                                + "LOCATION: " + getHttpBase() + "/description.xml\r\n"
                                + "SERVER: Android/13 UPnP/1.0 SeanMing-DMR/1.0\r\n"
                                + "\r\n";
                        try {
                            DatagramSocket send = new DatagramSocket();
                            send.send(new DatagramPacket(reply.getBytes(StandardCharsets.UTF_8),
                                    reply.getBytes(StandardCharsets.UTF_8).length, p.getAddress(), p.getPort()));
                            send.close();
                        } catch (IOException ignored) {}
                    }
                }
            } catch (IOException ignored) {}
        }, "dlna-ssdp");
        ssdpThread.start();
    }

    private NetworkInterface findMulticastInterface() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isUp() && !ni.isLoopback() && !ni.getName().startsWith("rmnet")) {
                    return ni;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void startNotify() {
        notifyThread = new Thread(() -> {
            try (DatagramSocket s = new DatagramSocket()) {
                InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
                long boot = System.currentTimeMillis();
                while (running) {
                    String notify = "NOTIFY * HTTP/1.1\r\n"
                            + "HOST: " + MULTICAST_GROUP + ":" + SSDP_PORT + "\r\n"
                            + "CACHE-CONTROL: max-age=1800\r\n"
                            + "LOCATION: " + getHttpBase() + "/description.xml\r\n"
                            + "NT: " + DEVICE_TYPE + "\r\n"
                            + "NTS: ssdp:alive\r\n"
                            + "SERVER: Android/13 UPnP/1.0 SeanMing-DMR/1.0\r\n"
                            + "USN: " + UDN + "\r\n"
                            + "BOOTID.UPNP.ORG: " + boot + "\r\n"
                            + "\r\n";
                    byte[] data = notify.getBytes(StandardCharsets.UTF_8);
                    try {
                        s.send(new DatagramPacket(data, data.length, group, SSDP_PORT));
                    } catch (IOException ignored) {}
                    try { Thread.sleep(30000 + RND.nextInt(3000)); } catch (InterruptedException ignored) { break; }
                }
            } catch (IOException ignored) {}
        }, "dlna-notify");
        notifyThread.start();
    }
}