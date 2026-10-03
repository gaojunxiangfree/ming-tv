package com.seanming.player.ui.play;

import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.okhttp.OkHttpDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * m3u8 广告过滤 DataSource (对齐影视仓 MyVideoView 的去广告能力).
 * 拦截 m3u8 播放列表请求, 过滤掉广告分片后再交给播放器:
 * 1. SCTE-35 标记(#EXT-X-CUE-OUT / #EXT-X-CUE-IN) 之间的分片;
 * 2. URI 含广告关键字的片段(advert/_ad_/...).
 * 只处理 .m3u8 播放列表, 不拦截 .ts/.m4s 分片, 保证正常内容不受影响.
 */
public class AdFilterDataSource implements DataSource {

    private final DataSource upstream;
    private byte[] filtered;
    private int pos;
    private boolean filtering = false;

    public AdFilterDataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        Uri uri = dataSpec.uri;
        String url = uri == null ? "" : uri.toString();
        if (isM3u8(url)) {
            long len = upstream.open(dataSpec);
            if (len <= 0) return len;
            // m3u8 播放列表通常很小(几十 KB), 一次性读全
            byte[] raw = new byte[(int) len];
            int off = 0;
            while (off < raw.length) {
                int n = upstream.read(raw, off, raw.length - off);
                if (n <= 0) break;
                off += n;
            }
            byte[] fb = filterM3u8(raw, off);
            if (fb != raw) {
                filtered = fb;
                pos = 0;
                filtering = true;
                android.util.Log.i("AdFilterDataSource",
                        "m3u8 去广告: " + url + " 原始=" + off + "B 过滤后=" + fb.length + "B");
                return fb.length;
            }
        }
        filtering = false;
        return upstream.open(dataSpec);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (filtering) {
            if (pos >= filtered.length) return -1; // C.RESULT_END_OF_INPUT
            int n = Math.min(length, filtered.length - pos);
            System.arraycopy(filtered, pos, buffer, offset, n);
            pos += n;
            return n;
        }
        return upstream.read(buffer, offset, length);
    }

    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @Override
    public void close() throws IOException {
        filtering = false;
        filtered = null;
        upstream.close();
    }

    /** 是否 m3u8 播放列表 */
    private static boolean isM3u8(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        String path = q > 0 ? u.substring(0, q) : u;
        return path.endsWith(".m3u8");
    }

    /** 过滤 m3u8 广告分片, 无广告时原样返回 */
    private static byte[] filterM3u8(byte[] raw, int len) {
        String content = new String(raw, 0, len, StandardCharsets.UTF_8);
        String[] lines = content.split("\\r?\\n");
        List<String> out = new ArrayList<>();
        boolean inAd = false;
        int removed = 0;
        for (String line : lines) {
            String t = line.trim();
            // SCTE-35 广告区间标记
            if (t.startsWith("#EXT-X-CUE-OUT")) {
                inAd = true;
                removed++;
                continue;
            }
            if (t.startsWith("#EXT-X-CUE-IN")) {
                inAd = false;
                removed++;
                continue;
            }
            if (inAd) {
                removed++;
                continue;
            }
            // 广告分片(非注释行且 URI 含广告关键字): 连同其 #EXTINF 一起移除
            if (!t.startsWith("#") && isAdUri(t)) {
                if (!out.isEmpty()) {
                    String prev = out.get(out.size() - 1).trim();
                    if (prev.startsWith("#EXTINF")) out.remove(out.size() - 1);
                }
                removed++;
                continue;
            }
            out.add(line);
        }
        if (removed == 0) return raw;
        StringBuilder sb = new StringBuilder();
        for (String l : out) sb.append(l).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 保守匹配广告分片 URI, 避免误伤 load/shadow/download 等正常文件名 */
    private static boolean isAdUri(String uri) {
        String u = uri.toLowerCase();
        return u.contains("advert") || u.contains("_ad_") || u.contains("/ad/")
                || u.contains("-ad-") || u.contains("广告");
    }

    /** 工厂: 包装 OkHttpDataSource 工厂 */
    public static class Factory implements DataSource.Factory {
        private final OkHttpDataSource.Factory upstream;

        public Factory(OkHttpDataSource.Factory upstream) {
            this.upstream = upstream;
        }

        @Override
        public DataSource createDataSource() {
            return new AdFilterDataSource(upstream.createDataSource());
        }
    }
}
