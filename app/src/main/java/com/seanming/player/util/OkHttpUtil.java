package com.seanming.player.util;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 全局 OkHttp 封装.
 * UA 使用 "okhttp/3.15" 兼容大部分 TVBox 接口的 UA 防爬:
 * 部分接口(如 tvbox.xn--4kq62z5rby2qupq9ub.top)对非 okhttp/* UA 会返回 302 重定向或 403.
 */
public class OkHttpUtil {

    private static final AtomicLong TOTAL_BYTES = new AtomicLong();

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .dns(new SafeDns())
            // 统计下载字节数, 供播放页顶栏实时显示网速
            .addInterceptor(chain -> {
                Response resp = chain.proceed(chain.request());
                okhttp3.ResponseBody body = resp.body();
                if (body == null) return resp;
                okio.BufferedSource source = okio.Okio.buffer(new CountingSource(body.source(), TOTAL_BYTES));
                return resp.newBuilder()
                        .body(okhttp3.ResponseBody.create(body.contentType(), body.contentLength(), source))
                        .build();
            })
            .build();

    /** 累计下载字节数(全网所有 OkHttp 请求), 用于计算网速 */
    public static long totalBytes() { return TOTAL_BYTES.get(); }

    /** 包装 Source 统计读取字节数 */
    private static final class CountingSource extends okio.ForwardingSource {
        private final AtomicLong total;

        CountingSource(okio.Source delegate, AtomicLong total) {
            super(delegate);
            this.total = total;
        }

        @Override
        public long read(okio.Buffer sink, long byteCount) throws IOException {
            long read = super.read(sink, byteCount);
            if (read > 0) total.addAndGet(read);
            return read;
        }
    }

    /** TVBox 标准 UA, 部分接口有 UA 检测 */
    public static final String UA = "okhttp/3.15";

    public static OkHttpClient client() { return CLIENT; }

    /** 同步 GET, 返回字符串; 失败抛 IOException */
    public static String get(String url) throws IOException {
        return get(url, null);
    }

    public static String get(String url, Map<String, String> headers) throws IOException {
        Request.Builder b = new Request.Builder().url(url).header("User-Agent", UA);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                b.header(e.getKey(), e.getValue());
            }
        }
        IOException lastErr = null;
        // 最多重试 2 次, 适配不稳定接口
        for (int attempt = 0; attempt < 2; attempt++) {
            android.util.Log.i("OkHttpUtil", "GET attempt=" + attempt + " url=" + url);
            try (Response resp = CLIENT.newCall(b.build()).execute()) {
                int code = resp.code();
                String ct = resp.header("Content-Type");
                long len = resp.body() != null ? resp.body().contentLength() : -1;
                android.util.Log.i("OkHttpUtil", "RESP code=" + code + " ct=" + ct + " len=" + len + " url=" + url);
                if (!resp.isSuccessful() || resp.body() == null) {
                    throw new IOException("http " + code + " for " + url);
                }
                return resp.body().string();
            } catch (IOException ioe) {
                android.util.Log.w("OkHttpUtil", "ERR attempt=" + attempt + " url=" + url + " : " + ioe);
                lastErr = ioe;
                if (attempt == 0) {
                    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                }
            }
        }
        throw lastErr;
    }

    /** 下载二进制(jar 等) */
    public static byte[] getBytes(String url) throws IOException {
        IOException lastErr = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Request req = new Request.Builder().url(url).header("User-Agent", UA).build();
                try (Response resp = CLIENT.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        throw new IOException("http " + resp.code() + " for " + url);
                    }
                    return resp.body().bytes();
                }
            } catch (IOException ioe) {
                lastErr = ioe;
                if (attempt == 0) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            }
        }
        throw lastErr;
    }
}
