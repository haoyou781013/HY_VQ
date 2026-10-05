package com.aliya.hy_vq.anime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ani 源配置模型 —— 对应 ani-subs（{@code sub.creamycake.org/v1/css1.json}）的 JSON 结构。
 *
 * <p>真实结构（实测）：</p>
 * <pre>
 * { "factoryId": "web-selector", "version": 2,
 *   "arguments": {
 *     "name": "...", "tier": 0,
 *     "channelTiers": { "简中": 0, "繁中": 1, "杜比": 6 },
 *     "searchConfig": {
 *       "searchUrl": "...{keyword}...",
 *       "subjectFormatId": "indexed",
 *       "selectorSubjectFormatIndexed": { "selectNames": "...", "selectLinks": "..." },
 *       "selectorSubjectFormatA":       { "selectLists": "..." },
 *       "channelFormatId": "index-grouped",
 *       "selectorChannelFormatFlattened": {
 *         "selectChannelNames": "...", "selectEpisodeLists": "...",
 *         "selectEpisodesFromList": "a", "matchEpisodeSortFromName": "第\\s*(?<ep>.+)\\s*[集话]" },
 *       "matchVideo": { "enableNestedUrl": true, "matchVideoUrl": "(...)", "cookies": "...",
 *                       "addHeadersToVideo": { "referer": "", "userAgent": "..." } } } } }
 * </pre>
 *
 * <p><b>tier 的两套概念不要混</b>（Animeko 规范特别强调）：</p>
 * <ul>
 *   <li>{@code arguments.tier} —— 源级静态优先级（订阅目录 t0~t4 表达）</li>
 *   <li>{@code channelTiers} —— <b>线路级实测能力</b>，键与解析出的 channel 名逐字一致，
 *       <b>覆盖源级</b>；未列出的回退源级</li>
 * </ul>
 */
public class AnimeSource {

    public String name = "";
    /** 源级 tier；-1 表示未评测（本工具产出的源默认如此） */
    public int tier = -1;
    /** 线路级 tier，覆盖源级 */
    public Map<String, Integer> channelTiers = new HashMap<>();
    public SearchConfig search = new SearchConfig();
    /** 原始 JSON，便于调试与重新导出 */
    public String rawJson = "";

    // ── 双引擎（决策 3：对齐 ani CSS selector，留口子）──
    /** 引擎类型：{@link #ENGINE_ANI}（CSS selector）或 {@link #ENGINE_KAZUMI}（XPath 规则） */
    public static final String ENGINE_ANI = "ani";
    public static final String ENGINE_KAZUMI = "kazumi";
    public String engine = ENGINE_ANI;
    /** Kazumi 规则原文（engine = kazumi 时非空） */
    public KazumiEngine.Rule kazumiRule;

    // ── 解析产物 ──

    /** 番剧条目 */
    public static class Subject {
        public String name;
        public String url;
        public String sourceName;

        public Subject() {
        }

        public Subject(String name, String url, String sourceName) {
            this.name = name;
            this.url = url;
            this.sourceName = sourceName;
        }

        @Override public String toString() {
            return name + " → " + url;
        }
    }

    /** 单集 */
    public static class Episode {
        public String sort;    // 集号（从标题正则提取）
        public String title;   // 原始标题
        public String url;

        public Episode() {
        }

        public Episode(String sort, String title, String url) {
            this.sort = sort;
            this.title = title;
            this.url = url;
        }

        @Override public String toString() {
            return "第" + sort + "集 " + url;
        }
    }

    /** 线路（同一部番的不同播放源/分组） */
    public static class Channel {
        public String name;
        public List<Episode> episodes = new ArrayList<>();

        public Channel() {
        }

        public Channel(String name) {
            this.name = name;
        }
    }

    /** 解析出的可播放地址 */
    public static class PlayLink {
        public String url;
        public String channelName;
        /** 需要带上的请求头（Referer / User-Agent / Cookie 等） */
        public Map<String, String> headers = new HashMap<>();
        /** 是否经过了一层嵌套页解析（对应 ani 的 enableNestedUrl） */
        public boolean nested;

        @Override public String toString() {
            return (nested ? "[嵌套] " : "") + url;
        }
    }

    /** 搜索与解析配置 */
    public static class SearchConfig {
        public String searchUrl = "";
        public boolean searchUseOnlyFirstWord = false;
        public boolean searchRemoveSpecial = false;
        public int requestInterval = 0;

        /** "indexed" 用 selectNames/selectLinks；"a" 用 selectLists */
        public String subjectFormatId = "a";
        public String selectLists = "";
        public String selectNames = "";
        public String selectLinks = "";
        public boolean preferShorterName = true;

        /** "index-grouped" 用 selectChannelNames + selectEpisodeLists；"no-channel" 只用 selectEpisodes */
        public String channelFormatId = "index-grouped";
        public String selectChannelNames = "";
        public String selectEpisodeLists = "";
        public String selectEpisodes = "";
        public String selectEpisodesFromList = "a";
        public String matchEpisodeSortFromName = "";

        public boolean filterByEpisodeSort = true;
        public boolean filterBySubjectName = true;

        // 播放地址提取
        public boolean enableNestedUrl = false;
        public String matchNestedUrl = "";
        public String matchVideoUrl = "";
        public boolean scanDomMediaUrls = false;

        public String cookies = "";
        public String referer = "";
        public String userAgent = "";
    }

    // ── 解析 ──

    /**
     * 解析单条源的 JSON。容错：字段缺失时保留默认值，不抛异常。
     *
     * @return 解析成功返回对象；结构完全不认识时返回 null
     */
    public static AnimeSource parse(String json) {
        try {
            // Kazumi 规则：有 api/type/searchURL + searchList（XPath）→ 走第二引擎
            if (json != null && json.contains("\"searchList\"") && json.contains("\"api\"")) {
                KazumiEngine.Rule rule = KazumiEngine.Rule.parse(json);
                if (rule != null && !rule.searchURL.isEmpty()) {
                    AnimeSource ks = new AnimeSource();
                    ks.engine = ENGINE_KAZUMI;
                    ks.kazumiRule = rule;
                    ks.name = rule.name;
                    ks.rawJson = json;
                    return ks;
                }
            }
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) return null;
            JsonObject root = el.getAsJsonObject();
            JsonObject args = root.has("arguments") && root.get("arguments").isJsonObject()
                    ? root.getAsJsonObject("arguments") : root;

            AnimeSource s = new AnimeSource();
            s.rawJson = json;
            s.name = str(args, "name", "");
            if (args.has("tier") && args.get("tier").isJsonPrimitive()) {
                try {
                    s.tier = args.get("tier").getAsInt();
                } catch (Throwable ignored) {
                }
            }
            if (args.has("channelTiers") && args.get("channelTiers").isJsonObject()) {
                JsonObject ct = args.getAsJsonObject("channelTiers");
                for (Map.Entry<String, JsonElement> e : ct.entrySet()) {
                    try {
                        s.channelTiers.put(e.getKey(), e.getValue().getAsInt());
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (args.has("searchConfig") && args.get("searchConfig").isJsonObject()) {
                s.search = parseSearch(args.getAsJsonObject("searchConfig"));
            }
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解析 whole 订阅文件：{ "exportedMediaSourceDataList": { "mediaSources": [...] } } 或直接数组 */
    public static List<AnimeSource> parseSubscription(String json) {
        List<AnimeSource> out = new ArrayList<>();
        try {
            JsonElement el = JsonParser.parseString(json);
            if (el.isJsonArray()) {
                for (JsonElement e : el.getAsJsonArray()) addIfOk(out, e);
            } else if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("exportedMediaSourceDataList")) {
                    JsonObject d = o.getAsJsonObject("exportedMediaSourceDataList");
                    if (d.has("mediaSources")) {
                        for (JsonElement e : d.getAsJsonArray("mediaSources")) addIfOk(out, e);
                    }
                } else if (o.has("packs") || o.has("sources")) {
                    String k = o.has("packs") ? "packs" : "sources";
                    for (JsonElement e : o.getAsJsonArray(k)) addIfOk(out, e);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void addIfOk(List<AnimeSource> out, JsonElement e) {
        if (!e.isJsonObject()) return;
        AnimeSource s = parse(e.toString());
        if (s != null && s.name != null && !s.name.isEmpty()) out.add(s);
    }

    private static SearchConfig parseSearch(JsonObject c) {
        SearchConfig sc = new SearchConfig();
        sc.searchUrl = str(c, "searchUrl", "");
        sc.searchUseOnlyFirstWord = bool(c, "searchUseOnlyFirstWord", false);
        sc.searchRemoveSpecial = bool(c, "searchRemoveSpecial", false);
        sc.requestInterval = num(c, "requestInterval", 0);

        sc.subjectFormatId = str(c, "subjectFormatId", "a");
        sc.preferShorterName = bool(c, "preferShorterName", true);
        if (c.has("selectorSubjectFormatA")) {
            JsonObject f = c.getAsJsonObject("selectorSubjectFormatA");
            sc.selectLists = str(f, "selectLists", "");
            sc.preferShorterName = bool(f, "preferShorterName", sc.preferShorterName);
        }
        if (c.has("selectorSubjectFormatIndexed")) {
            JsonObject f = c.getAsJsonObject("selectorSubjectFormatIndexed");
            sc.selectNames = str(f, "selectNames", "");
            sc.selectLinks = str(f, "selectLinks", "");
        }

        sc.channelFormatId = str(c, "channelFormatId", "index-grouped");
        if (c.has("selectorChannelFormatFlattened")) {
            JsonObject f = c.getAsJsonObject("selectorChannelFormatFlattened");
            sc.selectChannelNames = str(f, "selectChannelNames", "");
            sc.selectEpisodeLists = str(f, "selectEpisodeLists", "");
            sc.selectEpisodesFromList = str(f, "selectEpisodesFromList", "a");
            sc.matchEpisodeSortFromName = str(f, "matchEpisodeSortFromName", "");
        }
        if (c.has("selectorChannelFormatNoChannel")) {
            JsonObject f = c.getAsJsonObject("selectorChannelFormatNoChannel");
            sc.selectEpisodes = str(f, "selectEpisodes", "");
            if (sc.selectEpisodesFromList.isEmpty()) {
                sc.selectEpisodesFromList = str(f, "selectEpisodesFromList", "a");
            }
            if (sc.matchEpisodeSortFromName.isEmpty()) {
                sc.matchEpisodeSortFromName = str(f, "matchEpisodeSortFromName", "");
            }
        }

        sc.filterByEpisodeSort = bool(c, "filterByEpisodeSort", true);
        sc.filterBySubjectName = bool(c, "filterBySubjectName", true);

        if (c.has("matchVideo")) {
            JsonObject m = c.getAsJsonObject("matchVideo");
            sc.enableNestedUrl = bool(m, "enableNestedUrl", false);
            sc.matchNestedUrl = str(m, "matchNestedUrl", "");
            sc.matchVideoUrl = str(m, "matchVideoUrl", "");
            sc.scanDomMediaUrls = bool(m, "scanDomMediaUrls", false);
            sc.cookies = str(m, "cookies", "");
            if (m.has("addHeadersToVideo") && m.get("addHeadersToVideo").isJsonObject()) {
                JsonObject h = m.getAsJsonObject("addHeadersToVideo");
                sc.referer = str(h, "referer", "");
                sc.userAgent = str(h, "userAgent", "");
            }
        }
        return sc;
    }

    private static String str(JsonObject o, String k, String def) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    private static int num(JsonObject o, String k, int def) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : def;
        } catch (Throwable t) {
            return def;
        }
    }

    // ── tier ──

    /**
     * 取某条线路的 tier：<b>线路级优先，回退源级</b>（与 Animeko 语义一致）。
     * 返回值 -1 表示未评测。
     */
    public int tierFor(String channelName) {
        if (channelName != null) {
            Integer t = channelTiers.get(channelName);
            if (t != null) return t;
        }
        return tier;
    }

    /** 是否已评测（决定能否参与自动选源排序） */
    public boolean isRated() {
        return tier >= 0 || !channelTiers.isEmpty();
    }

    @Override public String toString() {
        return "AnimeSource{" + name + ", tier=" + tier + ", 线路tier=" + channelTiers.size() + "}";
    }
}
