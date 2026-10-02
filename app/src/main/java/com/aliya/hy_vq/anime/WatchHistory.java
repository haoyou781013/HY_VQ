package com.aliya.hy_vq.anime;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 观看历史 / 继续观看（对齐 Kazumi 的功能）。
 *
 * <h3>为什么存的是"解析上下文"而不是直链</h3>
 * 源站直链有时效（token/防盗链），存下来过几小时就失效了。
 * 所以这里存**重新解析所需的一切**：
 * <pre>
 *   源配置 JSON（sourceJson） + 集页面地址（episodeUrl） + 线路名
 * </pre>
 * "继续观看"时用它们重跑一遍解析拿到新直链，再 seek 到上次的位置。
 *
 * <h3>进度是怎么回写的</h3>
 * {@code MediaPlayerActivity} 拿 Intent 里的 {@code historyKey}（就是 episodeUrl）
 * 定期调用 {@link #updateProgress}。这样播放器和动漫模块之间不需要直接耦合。
 */
public class WatchHistory {

    private static final String DIR = "anime";
    private static final String FILE = "history.json";
    /** 最多保留多少条（源配置 JSON 不小，别无限涨） */
    private static final int MAX_ENTRIES = 40;

    public static class Entry {
        public String subjectName = "";
        public String sourceName = "";
        public String sourceJson = "";
        public String channelName = "";
        public String episodeUrl = "";
        public String episodeTitle = "";
        public long positionMs = 0;
        public long durationMs = 0;
        public long updatedAt = 0;

        /** 已看百分比（0~1）；时长为 0 时返回 0 */
        public float progress() {
            if (durationMs <= 0) return 0f;
            return Math.max(0f, Math.min(1f, (float) positionMs / (float) durationMs));
        }

        /** 是否接近看完（>=95%），用于决定是否还显示"继续观看" */
        public boolean nearlyFinished() {
            return durationMs > 0 && progress() >= 0.95f;
        }

        public String positionText() {
            return fmt(positionMs) + " / " + fmt(durationMs);
        }

        private static String fmt(long ms) {
            if (ms <= 0) return "00:00";
            long s = ms / 1000;
            long h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
            if (h > 0) return String.format(java.util.Locale.CHINA, "%d:%02d:%02d", h, m, ss);
            return String.format(java.util.Locale.CHINA, "%02d:%02d", m, ss);
        }
    }

    private final Context ctx;
    private final List<Entry> entries = new ArrayList<>();

    public WatchHistory(Context context) {
        this.ctx = context.getApplicationContext();
        load();
    }

    private File file() {
        return new File(new File(ctx.getFilesDir(), DIR), FILE);
    }

    // ══════════════ 读写 ══════════════

    private synchronized void load() {
        entries.clear();
        File f = file();
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            JsonElement el = JsonParser.parseString(new String(buf, 0, Math.max(0, n), "UTF-8"));
            if (!el.isJsonArray()) return;
            for (JsonElement e : el.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                Entry en = new Entry();
                en.subjectName = str(o, "subjectName");
                en.sourceName = str(o, "sourceName");
                en.sourceJson = str(o, "sourceJson");
                en.channelName = str(o, "channelName");
                en.episodeUrl = str(o, "episodeUrl");
                en.episodeTitle = str(o, "episodeTitle");
                en.positionMs = num(o, "positionMs");
                en.durationMs = num(o, "durationMs");
                en.updatedAt = num(o, "updatedAt");
                if (!en.episodeUrl.isEmpty()) entries.add(en);
            }
        } catch (Throwable ignored) {
        }
        sort();
    }

    private synchronized void save() {
        try {
            File dir = new File(ctx.getFilesDir(), DIR);
            if (!dir.exists() && !dir.mkdirs()) return;
            JsonArray arr = new JsonArray();
            for (Entry en : entries) {
                JsonObject o = new JsonObject();
                o.addProperty("subjectName", en.subjectName);
                o.addProperty("sourceName", en.sourceName);
                o.addProperty("sourceJson", en.sourceJson);
                o.addProperty("channelName", en.channelName);
                o.addProperty("episodeUrl", en.episodeUrl);
                o.addProperty("episodeTitle", en.episodeTitle);
                o.addProperty("positionMs", en.positionMs);
                o.addProperty("durationMs", en.durationMs);
                o.addProperty("updatedAt", en.updatedAt);
                arr.add(o);
            }
            try (FileOutputStream out = new FileOutputStream(file())) {
                out.write(arr.toString().getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    private void sort() {
        Collections.sort(entries, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
    }

    private static String str(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static long num(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ══════════════ 对外 API ══════════════

    /** 最近观看（按时间倒序） */
    public synchronized List<Entry> recent() {
        return new ArrayList<>(entries);
    }

    /** 取最近一条未看完的（用于"继续观看"卡片） */
    public synchronized Entry latestUnfinished() {
        for (Entry e : entries) {
            if (!e.nearlyFinished() && e.positionMs > 0) return e;
        }
        return null;
    }

    public synchronized Entry findByEpisode(String episodeUrl) {
        if (episodeUrl == null) return null;
        for (Entry e : entries) {
            if (episodeUrl.equals(e.episodeUrl)) return e;
        }
        return null;
    }

    /**
     * 记录/更新一条观看记录（开始播放时调用）。
     * 已存在则更新元信息，**保留已有进度**。
     */
    public synchronized void record(Entry in) {
        if (in == null || in.episodeUrl == null || in.episodeUrl.isEmpty()) return;
        Entry existed = findByEpisode(in.episodeUrl);
        if (existed != null) {
            existed.subjectName = in.subjectName;
            existed.sourceName = in.sourceName;
            existed.sourceJson = in.sourceJson;
            existed.channelName = in.channelName;
            existed.episodeTitle = in.episodeTitle;
            existed.updatedAt = System.currentTimeMillis();
        } else {
            in.updatedAt = System.currentTimeMillis();
            entries.add(0, in);
        }
        trim();
        sort();
        save();
    }

    /** 播放器回写进度（按 episodeUrl 匹配） */
    public synchronized void updateProgress(String episodeUrl, long positionMs, long durationMs) {
        if (episodeUrl == null || episodeUrl.isEmpty()) return;
        Entry e = findByEpisode(episodeUrl);
        if (e == null) return;                       // 不是从动漫模块进来的播放，不记录
        e.positionMs = Math.max(0, positionMs);
        if (durationMs > 0) e.durationMs = durationMs;
        e.updatedAt = System.currentTimeMillis();
        sort();
        save();
    }

    /** 取上次看到的位置（继续观看用） */
    public synchronized long positionOf(String episodeUrl) {
        Entry e = findByEpisode(episodeUrl);
        if (e == null || e.nearlyFinished()) return 0L;
        return e.positionMs;
    }

    public synchronized void remove(String episodeUrl) {
        Entry e = findByEpisode(episodeUrl);
        if (e != null) {
            entries.remove(e);
            save();
        }
    }

    public synchronized void clear() {
        entries.clear();
        File f = file();
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    private void trim() {
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
    }
}
