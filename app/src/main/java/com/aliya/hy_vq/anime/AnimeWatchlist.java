package com.aliya.hy_vq.anime;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 追剧清单（Watchlist）数据层。
 *
 * <p>只负责<b>数据的存储、筛选与状态流转</b>：不含任何 Android View / 动画 / Layout，
 * UI 层拿到 {@link Entry} 自行渲染即可。</p>
 *
 * <h3>为什么用 Gson 而不是 org.json</h3>
 * 本项目 android.jar 里的 {@code org.json} 是编译桩，运行时会抛 {@code Stub!}；
 * 因此序列化统一使用 {@code app/build.gradle.kts} 已声明的依赖
 * {@code com.google.code.gson:gson} —— 与同目录 {@link WatchHistory} 同款
 * （{@code JsonArray} / {@code JsonObject} / {@code JsonParser}），不新增任何依赖。
 *
 * <h3>持久化</h3>
 * 整个清单序列化为<b>一个 JSON 数组字符串</b>，存进 SharedPreferences 的
 * {@link #STORAGE_KEY}（{@code anime_watchlist_v1}，key 带版本号，方便日后升级 v2 时做迁移）。
 * 每次变更同步落盘（apply），进程内立即可见。
 *
 * <h3>向后兼容（绝不抛异常）</h3>
 * {@link #fromJson} 逐字段兜底：缺字段用默认值；字符串缺失给 {@code ""}、数字缺失给 {@code 0}；
 * 整体数据损坏时静默降级为空清单 —— 任何读取路径都不向调用方抛异常，
 * 未来新增字段时旧数据依然可读。
 *
 * <h3>唯一键</h3>
 * 条目以 {@code title + sourceId} 联合唯一（同名番剧可能来自不同源站）。
 *
 * <p>线程安全：所有公开方法均 {@code synchronized}，UI 线程与后台线程可混用。</p>
 */
public class AnimeWatchlist {

    // ══════════════ 5 态状态机（业界事实标准：在看/想看/看过/搁置/抛弃） ══════════════

    /** 在看 */
    public static final int STATUS_WATCHING = 0;
    /** 想看 */
    public static final int STATUS_PLANNED = 1;
    /** 看过 */
    public static final int STATUS_DONE = 2;
    /** 搁置 */
    public static final int STATUS_ON_HOLD = 3;
    /** 抛弃 */
    public static final int STATUS_DROPPED = 4;

    // ══════════════ 持久化位置 ══════════════

    /** SharedPreferences 文件名（独立文件，避免和 anime_prefs 混在一起） */
    public static final String PREFS_NAME = "anime_watchlist";

    /** 持久化 key：值为 JSON 数组字符串；带 {@code _v1} 便于日后迁移 */
    public static final String STORAGE_KEY = "anime_watchlist_v1";

    // ══════════════ 数据模型 ══════════════

    /**
     * 追剧清单条目（纯数据，字段全公开便于 UI 直接读取）。
     *
     * <p>写入请走 {@link AnimeWatchlist#put(Entry)}；直接改字段不会自动落盘。</p>
     */
    public static class Entry {

        /** 番剧标题（与 {@link #sourceId} 联合唯一） */
        public String title = "";

        /** 封面图地址（可能为空串） */
        public String coverUrl = "";

        /** 总集数；未知为 0 */
        public int totalEpisodes = 0;

        /** 已看到第几集（0 = 一集未看） */
        public int watchedEpisodes = 0;

        /** 来源站标识（源仓库里的 id），与 {@link #title} 联合唯一 */
        public String sourceId = "";

        /** 状态，取值见 {@code STATUS_*} 常量 */
        public int status = STATUS_WATCHING;

        /** 最后更新时间戳（毫秒，{@code System.currentTimeMillis()}） */
        public long updatedAt = 0;

        /** 中文状态标签，等价于 {@link AnimeWatchlist#statusLabel(int)} */
        public String statusLabel() {
            return AnimeWatchlist.statusLabel(status);
        }

        /** 进度文本，如 {@code "3/12"}；总集数未知（0）时只显示已看数 */
        public String progressText() {
            if (totalEpisodes > 0) {
                return watchedEpisodes + "/" + totalEpisodes;
            }
            return String.valueOf(watchedEpisodes);
        }
    }

    // ══════════════ 状态机辅助 ══════════════

    /**
     * 状态的中文标签（Tab / 列表分组标题直接用）。
     *
     * @param status {@code STATUS_*} 常量
     * @return 在看 / 想看 / 看过 / 搁置 / 抛弃；未知值返回「未知」
     */
    public static String statusLabel(int status) {
        switch (status) {
            case STATUS_WATCHING:
                return "在看";
            case STATUS_PLANNED:
                return "想看";
            case STATUS_DONE:
                return "看过";
            case STATUS_ON_HOLD:
                return "搁置";
            case STATUS_DROPPED:
                return "抛弃";
            default:
                return "未知";
        }
    }

    /** 是否为 5 态之一（写入前校验用；读取路径不做校验，保证兼容未来取值） */
    public static boolean isKnownStatus(int status) {
        return status == STATUS_WATCHING || status == STATUS_PLANNED
                || status == STATUS_DONE || status == STATUS_ON_HOLD
                || status == STATUS_DROPPED;
    }

    // ══════════════ 内部状态 ══════════════

    private final SharedPreferences prefs;
    private final List<Entry> entries = new ArrayList<>();

    /**
     * 构造并从 SharedPreferences 加载清单（内部用 applicationContext，避免泄漏 Activity）。
     *
     * @param ctx 任意 Context，不能为空
     * @throws IllegalArgumentException {@code ctx} 为 null 时
     */
    public AnimeWatchlist(Context ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("AnimeWatchlist: context 不能为 null");
        }
        Context app = ctx.getApplicationContext();
        Context base = app != null ? app : ctx;
        this.prefs = base.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
    }

    // ══════════════ 序列化（org.json 是桩，改用项目已依赖的 Gson） ══════════════

    /**
     * 序列化单条目为 JSON 对象。
     * 字符串字段统一兜底为非 null，避免把 null 写进持久化数据。
     */
    private static JsonObject toJson(Entry e) {
        JsonObject o = new JsonObject();
        o.addProperty("title", e.title != null ? e.title : "");
        o.addProperty("coverUrl", e.coverUrl != null ? e.coverUrl : "");
        o.addProperty("totalEpisodes", e.totalEpisodes);
        o.addProperty("watchedEpisodes", e.watchedEpisodes);
        o.addProperty("sourceId", e.sourceId != null ? e.sourceId : "");
        o.addProperty("status", e.status);
        o.addProperty("updatedAt", e.updatedAt);
        return o;
    }

    /**
     * 反序列化单条目 —— <b>缺字段用默认值兜底，绝不抛异常</b>（未来会加字段）。
     *
     * @param o JSON 对象；传 null 或结构异常时返回 null
     * @return 解析出的条目；标题为空视为脏数据返回 null
     */
    private static Entry fromJson(JsonObject o) {
        if (o == null) return null;
        try {
            Entry e = new Entry();
            e.title = str(o, "title");
            e.coverUrl = str(o, "coverUrl");
            e.totalEpisodes = clampEpisodes((int) num(o, "totalEpisodes"), 0);
            e.watchedEpisodes = (int) num(o, "watchedEpisodes");
            e.sourceId = str(o, "sourceId");
            e.status = (int) num(o, "status");           // 缺失默认 0 = 在看
            e.updatedAt = num(o, "updatedAt");
            e.watchedEpisodes = clampEpisodes(e.watchedEpisodes, e.totalEpisodes);
            return e.title.isEmpty() ? null : e;
        } catch (Throwable ignored) {
            return null;                                  // 单条坏数据只丢这一条
        }
    }

    /** 读字符串：缺失 / 类型不符一律给 "" */
    private static String str(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 读整数：缺失 / 类型不符一律给 0 */
    private static long num(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 集数下限 0；上限由 total 约束（total 为 0 = 未知，不设上限） */
    private static int clampEpisodes(int ep, int total) {
        if (ep < 0) ep = 0;
        if (total > 0 && ep > total) ep = total;
        return ep;
    }

    private static Entry copyOf(Entry src) {
        Entry e = new Entry();
        e.title = src.title;
        e.coverUrl = src.coverUrl;
        e.totalEpisodes = src.totalEpisodes;
        e.watchedEpisodes = src.watchedEpisodes;
        e.sourceId = src.sourceId;
        e.status = src.status;
        e.updatedAt = src.updatedAt;
        return e;
    }

    // ══════════════ 落盘 ══════════════

    /** 从 SharedPreferences 读入内存；数据损坏时降级为空清单，不抛异常 */
    private synchronized void load() {
        entries.clear();
        try {
            String raw = prefs.getString(STORAGE_KEY, null);
            if (raw == null || raw.isEmpty()) return;
            JsonElement el = JsonParser.parseString(raw);
            if (el == null || !el.isJsonArray()) return;
            for (JsonElement item : el.getAsJsonArray()) {
                if (item == null || !item.isJsonObject()) continue;
                Entry e = fromJson(item.getAsJsonObject());
                if (e != null) entries.add(e);
            }
        } catch (Throwable ignored) {
            // 整个文件坏掉 → 空清单，绝不把异常抛给调用方
        }
        sort();
    }

    /** 把内存清单整体序列化写回 SharedPreferences；失败静默（内存态仍可用） */
    private synchronized void save() {
        try {
            JsonArray arr = new JsonArray();
            for (Entry e : entries) {
                arr.add(toJson(e));
            }
            prefs.edit().putString(STORAGE_KEY, arr.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 按更新时间倒序（最新变动在最前） */
    private void sort() {
        Collections.sort(entries, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
    }

    /** 内部定位：title + sourceId 联合唯一；null 归一成 "" */
    private int indexOf(String title, String sourceId) {
        String t = title != null ? title : "";
        String s = sourceId != null ? sourceId : "";
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (t.equals(e.title) && s.equals(e.sourceId)) {
                return i;
            }
        }
        return -1;
    }

    private Entry internalGet(String title, String sourceId) {
        int i = indexOf(title, sourceId);
        return i >= 0 ? entries.get(i) : null;
    }

    // ══════════════ 公开 API ══════════════

    /**
     * 全部条目（按更新时间倒序的新副本，改它不影响已存数据）。
     */
    public synchronized List<Entry> all() {
        return new ArrayList<>(entries);
    }

    /**
     * 按状态筛选（保持 all() 的排序）。
     *
     * @param status {@code STATUS_*} 常量
     * @return 匹配条目的新副本；无匹配返回空列表（不为 null）
     */
    public synchronized List<Entry> byStatus(int status) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.status == status) out.add(e);
        }
        return out;
    }

    /**
     * 按唯一键取单条（返回<b>副本</b>，改完需再 {@link #put} 回写才会落盘）。
     *
     * @return 副本；不存在返回 null
     */
    public synchronized Entry get(String title, String sourceId) {
        Entry e = internalGet(title, sourceId);
        return e != null ? copyOf(e) : null;
    }

    /**
     * 新增或覆盖更新：按 {@code title + sourceId} 命中则整条覆盖，否则追加。
     *
     * <p>会把 {@link Entry#updatedAt} 置为当前时间并立即落盘。
     * {@code title} 为空、或 {@code e} 为 null 时视为无效输入，静默忽略。</p>
     *
     * @param e 待写入条目
     */
    public synchronized void put(Entry e) {
        if (e == null) return;
        if (e.title == null || e.title.isEmpty()) return;   // 标题是唯一键的一部分
        if (e.coverUrl == null) e.coverUrl = "";
        if (e.sourceId == null) e.sourceId = "";
        e.totalEpisodes = clampEpisodes(e.totalEpisodes, 0);
        e.watchedEpisodes = clampEpisodes(e.watchedEpisodes, e.totalEpisodes);
        e.updatedAt = System.currentTimeMillis();           // put 即视为「刚更新」
        int i = indexOf(e.title, e.sourceId);
        if (i >= 0) {
            entries.set(i, e);
        } else {
            entries.add(e);
        }
        sort();
        save();
    }

    /**
     * 按唯一键删除条目。
     *
     * @return true 表示确实删掉了一条；false 表示没找到
     */
    public synchronized boolean remove(String title, String sourceId) {
        int i = indexOf(title, sourceId);
        if (i < 0) return false;
        entries.remove(i);
        save();
        return true;
    }

    /**
     * 改状态（5 态状态机的唯一写入口）。
     *
     * @param status 必须是 {@code STATUS_*} 之一，非法值不落盘
     * @return true 表示已修改并落盘；false 表示条目不存在或状态非法
     */
    public synchronized boolean setStatus(String title, String sourceId, int status) {
        if (!isKnownStatus(status)) return false;
        Entry e = internalGet(title, sourceId);
        if (e == null) return false;
        e.status = status;
        e.updatedAt = System.currentTimeMillis();
        save();
        return true;
    }

    /**
     * 改「已看到第几集」，并刷新 {@link Entry#updatedAt}。
     *
     * <p>夹取规则：小于 0 按 0 算；总集数已知且超过总集数时按总集数算；
     * 总集数未知（0）只保底不封顶。<b>不自动改状态</b>（何时算「看过」由 UI 决定）。</p>
     *
     * @return true 表示已修改并落盘；false 表示条目不存在
     */
    public synchronized boolean setWatchedEpisodes(String title, String sourceId, int ep) {
        Entry e = internalGet(title, sourceId);
        if (e == null) return false;
        e.watchedEpisodes = clampEpisodes(ep, e.totalEpisodes);
        e.updatedAt = System.currentTimeMillis();
        save();
        return true;
    }

    /**
     * 各状态条目计数（Tab 角标用）。
     *
     * @param status {@code STATUS_*} 常量
     * @return 条数；无匹配返回 0
     */
    public synchronized int countByStatus(int status) {
        int n = 0;
        for (Entry e : entries) {
            if (e.status == status) n++;
        }
        return n;
    }

    /** 清空整个清单（同时移除持久化 key） */
    public synchronized void clear() {
        entries.clear();
        prefs.edit().remove(STORAGE_KEY).apply();
    }
}
