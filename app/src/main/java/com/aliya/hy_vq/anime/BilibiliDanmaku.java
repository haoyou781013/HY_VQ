package com.aliya.hy_vq.anime;

import com.aliya.hy_vq.player.DanmakuView.Danmaku;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B站弹幕源（实测可达，弹弹play 已 403 不可用）。
 *
 * <h3>链路</h3>
 * <pre>
 * ① 番剧 season API  →  https://api.bilibili.com/pgc/view/web/season?season_id=X
 *     得到 episodes[].cid（实测 200 / 37KB，含 28 集）
 * ② 弹幕接口          →  https://api.bilibili.com/x/v1/dm/list.so?oid=cid
 *     实测 200 / 394KB XML（未解压时 165KB，content-type: text/xml + deflate）
 * ③ 解析 &lt;d p="进度,模式,字号,颜色,时间,…"&gt;文本&lt;/d&gt;
 * </pre>
 *
 * <h3>压缩处理</h3>
 * 服务端无条件返回 {@code content-encoding: deflate}。本类先读字节，若不以 {@code <?xml}
 * 开头则用 {@link java.util.zip.InflaterInputStream} 解压兜底，保证拿到纯文本。
 */
public final class BilibiliDanmaku {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";
    /** <d p="progress,mode,size,color,timestamp,pool,hash,color2,id">text</d> */
    private static final Pattern DANMAKU = Pattern.compile(
            "<d p=\"([^\"]*)\">(.*?)</d>", Pattern.DOTALL);

    private BilibiliDanmaku() {
    }

    /**
     * 按番剧 season_id 与集序抓取弹幕。
     *
     * @param seasonId  B站番剧 season_id（AnimeMetadata.Entry.seasonId）
     * @param episodeNo 集序号，1 起；超出范围自动夹取
     */
    public static List<Danmaku> fetch(long seasonId, int episodeNo) throws Exception {
        List<Danmaku> out = new ArrayList<>();
        if (seasonId <= 0) return out;

        // ① 取 cid
        String seasonUrl = "https://api.bilibili.com/pgc/view/web/season?season_id=" + seasonId;
        String seasonXml = get(seasonUrl);
        if (seasonXml == null || seasonXml.isEmpty()) return out;
        long cid = pickCid(seasonXml, episodeNo);
        if (cid <= 0) return out;

        // ② 取弹幕
        String dmXml = get("https://api.bilibili.com/x/v1/dm/list.so?oid=" + cid);
        if (dmXml == null || dmXml.isEmpty()) return out;
        return parse(dmXml);
    }

    /** 从 season 接口结果中挑选第 N 集的 cid（支持两种返回形态） */
    private static long pickCid(String body, int episodeNo) {
        // 形态 A：{"result":{"episodes":[{"cid":123,"title":"1",...}]}}
        try {
            org.json.JSONObject root = new org.json.JSONObject(body);
            org.json.JSONObject result = root.optJSONObject("result");
            if (result == null) result = root.optJSONObject("data");
            if (result != null) {
                org.json.JSONArray eps = result.optJSONArray("episodes");
                if (eps != null && eps.length() > 0) {
                    int idx = Math.max(0, Math.min(episodeNo - 1, eps.length() - 1));
                    return eps.optJSONObject(idx).optLong("cid", -1);
                }
            }
        } catch (Throwable ignored) {
        }
        // 形态 B：文本兜底 —— 取第一个 cid
        Matcher m = Pattern.compile("\"cid\"\\s*:\\s*(\\d+)").matcher(body);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /** 解析弹幕 XML */
    public static List<Danmaku> parse(String xml) {
        List<Danmaku> out = new ArrayList<>();
        if (xml == null || xml.isEmpty()) return out;
        Matcher m = DANMAKU.matcher(xml);
        while (m.find()) {
            String p = m.group(1);
            String text = m.group(2);
            if (text == null || text.isEmpty()) continue;
            String[] attrs = p.split(",");
            if (attrs.length < 5) continue;
            try {
                // 首字段是**浮点秒**（实测 max=1552.4 vs 片长 1559s），larson 版是整数毫秒
                double sec = Double.parseDouble(attrs[0].trim());
                long pos = (long) (sec * 1000);
                int mode = Integer.parseInt(attrs[1].trim());   // 滚动=1/顶部=5/底部=6
                int color = Integer.parseInt(attrs[3].trim());  // 十进制颜色
                if (mode != 1 && mode != 5 && mode != 6) continue;
                Danmaku d = new Danmaku(text, pos);
                d.color = color | 0xFF000000;
                d.mode = mode;
                out.add(d);
            } catch (Throwable ignored) {
            }
        }
        out.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        return out;
    }

    // ══════════════ HTTP ══════════════

    private static String get(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(18000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://www.bilibili.com/");
        c.setRequestProperty("Accept-Encoding", "gzip, deflate");
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                c.disconnect();
                return null;
            }
            byte[] raw = readAll(c.getInputStream());
            c.disconnect();
            return decode(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    /** XML 或 deflate/gzip → 文本 */
    private static String decode(byte[] raw) throws Exception {
        if (raw == null || raw.length == 0) return null;
        String head = new String(raw, 0, Math.min(64, raw.length), Charset.forName("ISO-8859-1"));
        if (head.contains("<?xml") || head.contains("<i>") || head.contains("<d ")) {
            return new String(raw, "UTF-8");
        }
        // gzip
        if (raw.length > 2 && (raw[0] & 0xFF) == 0x1f && (raw[1] & 0xFF) == 0x8b) {
            try (InputStream in = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(raw))) {
                return toText(in);
            }
        }
        // deflate（含 zlib 头 / 裸 deflate 两种）
        try (InputStream in = new java.util.zip.InflaterInputStream(new ByteArrayInputStream(raw))) {
            String t = toText(in);
            if (t != null && (t.contains("<?xml") || t.contains("<d "))) return t;
        } catch (Throwable ignored) {
        }
        try (InputStream in = new java.util.zip.InflaterInputStream(
                new ByteArrayInputStream(raw), new java.util.zip.Inflater(true))) {
            String t = toText(in);
            if (t != null && (t.contains("<?xml") || t.contains("<d "))) return t;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String toText(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }
}
