package com.seanming.player.util;

import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;

import java.net.URLDecoder;

/**
 * 海报加载工具. 兼容 TVBox 爬虫返回的 pic 地址格式:
 *   https://xxx.jpg@Referer=https://movie.douban.com/@User-Agent=Mozilla/5.0...
 * 其中 @Referer= / @User-Agent= 是需要附加到图片请求的头信息, 需拆分后用 Glide 携带头加载,
 * 否则豆瓣等图床会返回 418 拒绝加载.
 */
public final class ImageUtil {

    private ImageUtil() {}

    public static void loadPoster(ImageView iv, String pic) {
        if (pic == null || pic.isEmpty()) return;

        String url = pic;
        String referer = null;
        String ua = null;

        int at = pic.indexOf('@');
        if (at > 0) {
            url = pic.substring(0, at);
            String[] parts = pic.substring(at + 1).split("@");
            for (String p : parts) {
                int eq = p.indexOf('=');
                if (eq <= 0) continue;
                String k = p.substring(0, eq).trim();
                String v = decode(p.substring(eq + 1));
                if ("Referer".equalsIgnoreCase(k)) referer = v;
                else if ("User-Agent".equalsIgnoreCase(k)) ua = v;
            }
        }

        Object model;
        LazyHeaders.Builder hb = new LazyHeaders.Builder();
        boolean hasHeader = false;
        if (referer != null && !referer.isEmpty()) { hb.addHeader("Referer", referer); hasHeader = true; }
        if (ua != null && !ua.isEmpty()) { hb.addHeader("User-Agent", ua); hasHeader = true; }
        model = hasHeader ? new GlideUrl(url, hb.build()) : new GlideUrl(url);

        Glide.with(iv.getContext())
                .load(model)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(iv);
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }
}
