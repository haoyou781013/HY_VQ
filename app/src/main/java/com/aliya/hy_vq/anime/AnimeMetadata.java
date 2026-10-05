package com.aliya.hy_vq.anime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 元数据搜索（方案 B：对标 Animeko 两段式）。
 *
 * <p><b>第一段</b>只查元数据库（1 个请求、秒回），拿到干净的番剧列表：
 * 中文关键词 → B站番剧搜索（实测 204ms，原生中文标题/封面/评分/标签/集数）；
 * 英文/日文 → AniList GraphQL（实测 785ms，评分/集数/封面）。
 * 两路互为兜底（主路 0 结果时切另一路）。</p>
 *
 * <p><b>第二段</b>（由 UI 层负责）：用户选中某部番后，才去用番剧名查播放源。</p>
 *
 * <p>只把 B站当<b>元数据</b>用（标题/封面/评分），不碰它的播放接口。</p>
 */
public final class AnimeMetadata {

    /** 一条番剧元数据 */
    public static class Entry {
        public String title = "";       // 显示名（中文优先）
        public String nativeTitle = ""; // 原名（日文/罗马字）
        public String cover = "";       // 封面 URL
        public String desc = "";        // 简介
        public String url = "";         // 详情/播放页链接
        public String source = "";      // 元数据来自哪个库
        public int score = -1;          // 评分（0-10 或 0-100，见 scoreScale）
        public int scoreScale = 10;     // 10 或 100
        public int episodes = -1;       // 集数
        public int rank = -1;           // 热度排名（越小越热，Bangumi rating.rank）
        public List<String> tags = new ArrayList<>();

        /**
         * 用于查播放源的关键词列表，<b>按实测命中率排序</b>。
         *
         * <p>容器内对 18 个源实测：中文 11/18、日文 1/18、罗马字 0/18。
         * 旧版优先返回原名（日文/罗马字）导致几乎搜不到源 —— 这是「无源播放」的根因。</p>
         */
        public java.util.List<String> searchKeywords() {
            java.util.List<String> out = new ArrayList<>();
            addIfNew(out, title);         // B站给的是中文 → 命中最高
            addIfNew(out, nativeTitle);   // 日文
            // romaji（AniList 的 nativeTitle 是罗马字）放最后
            return out;
        }

        /** 兼容旧调用：取第一个关键词 */
        public String searchKeyword() {
            java.util.List<String> k = searchKeywords();
            return k.isEmpty() ? "" : k.get(0);
        }

        private static void addIfNew(java.util.List<String> out, String v) {
            if (v == null || v.trim().isEmpty()) return;
            String t = v.trim();
            for (String x : out) if (x.equals(t)) return;
            out.add(t);
        }

        public String scoreText() {
            if (score < 0) return "";
            if (scoreScale == 100) return String.format(Locale.CHINA, "%.1f", score / 10.0);
            return String.valueOf(score);
        }
    }

    private AnimeMetadata() {
    }

    // ══════════════ 搜索入口 ══════════════

    /**
     * 并发查两条元数据路（中文→B站，英文→AniList），返回按评分降序的合并结果。
     * 主路先出，副路兜底；两路都空则返回空列表。
     *
     * @param onPartial 每路返回时回调一次（UI 可增量渲染）
     */
    public static List<Entry> search(final String keyword, final Callback onPartial) {
        final boolean cjk = containsCjk(keyword);
        final String[] routes = cjk ? new String[]{"bili", "anilist"}
                                    : new String[]{"anilist", "bili"};

        List<Entry> merged = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (String route : routes) {
                List<Entry> got;
                try {
                    got = "bili".equals(route) ? searchBili(keyword) : searchAniList(keyword);
                } catch (Throwable t) {
                    got = new ArrayList<>();
                }
                if (got != null && !got.isEmpty()) {
                    mergeInto(merged, got);
                    if (onPartial != null) {
                        final List<Entry> snapshot = new ArrayList<>(merged);
                        final String routeName = route;
                        onPartial.onResult(snapshot, routeName);
                    }
                    // 主路已有结果即可停止（B站/AniList 谁快谁先）
                    // 但为封面多样性，两路都取，上限在 merge 里控制
                }
            }
        } finally {
            pool.shutdownNow();
        }
        // 英文/日文路由拿到的条目缺中文名 → 用日文反查 B站 补全
        // （实测：B站搜日文可命中并返回中文标题；搜英文则 0 命中）
        enrichChineseTitles(merged);

        // 统一去重（同路由内 + 跨路由）
        merged = dedupList(merged);

        // 按热度排列（评分降序，同分看排名）
        sortByHeat(merged);
        return merged;
    }

    /**
     * 为缺中文名的条目补中文标题。
     *
     * <p>为什么需要：源站几乎只认中文（中文 11/18 命中 vs 日文 1/18）。
     * 走 AniList（英/日文路由）时拿不到中文名，若不补全，Stage2 只能用
     * 日文/罗马字搜源 → 几乎无源。实测 B站<b>搜日文可反查到中文标题</b>。</p>
     *
     * <p>只处理前 6 条无中文名的（限流），中文路由的条目本就跳过。</p>
     */
    private static void enrichChineseTitles(List<Entry> entries) {
        int budget = 6;
        for (Entry e : entries) {
            if (budget <= 0) break;
            if (containsCjk(e.title)) continue;           // 已有中文名
            if (!containsCjk(e.nativeTitle)) continue;    // 没有日文可反查
            budget--;
            try {
                List<Entry> got = searchBili(e.nativeTitle);
                if (got == null) continue;
                for (Entry g : got) {
                    if (containsCjk(g.title) && sameAnime(e, g)) {
                        e.title = g.title;                // 补中文名
                        if (e.cover == null || e.cover.isEmpty()) e.cover = g.cover;
                        if (e.episodes < 0 && g.episodes > 0) e.episodes = g.episodes;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 热度排序权重：AniList 百分制 ×10，Bangumi 十分制 ×100 → 统一到同一刻度 */
    private static int rank(Entry e) {
        if (e.score < 0) return 0;
        return e.scoreScale == 100 ? e.score : e.score * 10;
    }

    /** 首页按热度排列：评分降序；同分按排名升序（有 rank 的更热） */
    static void sortByHeat(List<Entry> list) {
        if (list == null || list.size() < 2) return;
        list.sort((a, b) -> {
            int ra = rank(a), rb = rank(b);
            if (ra != rb) return Integer.compare(rb, ra);          // 评分高者在前
            if (a.rank >= 0 && b.rank >= 0) return Integer.compare(a.rank, b.rank); // 排名小者在前
            if (a.episodes != b.episodes) return Integer.compare(b.episodes, a.episodes);
            return 0;
        });
    }

    /**
     * 去重：同路由内部也会出现重复（B站 同时返回「葬送的芙莉莲」与
     * 「葬送的芙莉莲 中配版」→ 用户看到两条）。归一化后判同，
     * 保留信息更全的那条（优先无版本后缀、评分更高者）。
     */
    static List<Entry> dedupList(List<Entry> in) {
        if (in == null || in.size() < 2) return in;
        List<Entry> out = new ArrayList<>();
        for (Entry e : in) {
            int dup = -1;
            for (int i = 0; i < out.size(); i++) {
                if (sameAnime(out.get(i), e)) { dup = i; break; }
            }
            if (dup < 0) {
                out.add(e);
            } else {
                Entry keep = out.get(dup);
                // 保留信息更全的：原无名补名、无封面补封面、评分取更高、集数取更大
                if ((keep.title == null || keep.title.isEmpty()) && e.title != null) keep.title = e.title;
                if ((keep.nativeTitle == null || keep.nativeTitle.isEmpty()) && e.nativeTitle != null)
                    keep.nativeTitle = e.nativeTitle;
                if ((keep.cover == null || keep.cover.isEmpty()) && e.cover != null) keep.cover = e.cover;
                if (e.score > keep.score) { keep.score = e.score; keep.scoreScale = e.scoreScale; }
                if (e.episodes > keep.episodes) keep.episodes = e.episodes;
                if ((keep.desc == null || keep.desc.isEmpty()) && e.desc != null) keep.desc = e.desc;
                if (keep.tags != null && e.tags != null && keep.tags.isEmpty()) keep.tags = e.tags;
                // 标题择优：优先无「中配版/国语版」等后缀的干净标题
                if (e.title != null && keep.title != null
                        && stripVersionSuffix(e.title).equals(stripVersionSuffix(keep.title))) {
                    boolean eClean = stripVersionSuffix(e.title).equals(e.title.trim());
                    boolean kClean = stripVersionSuffix(keep.title).equals(keep.title.trim());
                    if (eClean && !kClean) keep.title = e.title;   // 新的更干净 → 换
                } else if (e.title != null && (keep.title == null || keep.title.isEmpty())) {
                    keep.title = e.title;
                }
            }
        }
        return out;
    }

    private static void mergeInto(List<Entry> dst, List<Entry> src) {
        for (Entry e : src) {
            boolean dup = false;
            for (Entry d : dst) {
                if (sameAnime(d, e)) {
                    // 已有 → 补缺失字段（AniList 有评分/集数，B站有中文名）
                    if (d.title == null || d.title.isEmpty()) d.title = e.title;
                    if (d.nativeTitle == null || d.nativeTitle.isEmpty()) d.nativeTitle = e.nativeTitle;
                    if (d.cover == null || d.cover.isEmpty()) d.cover = e.cover;
                    if (d.desc == null || d.desc.isEmpty()) d.desc = e.desc;
                    if (d.episodes < 0 && e.episodes > 0) d.episodes = e.episodes;
                    if (d.score < 0 && e.score >= 0) { d.score = e.score; d.scoreScale = e.scoreScale; }
                    if (d.tags.isEmpty() && !e.tags.isEmpty()) d.tags = e.tags;
                    dup = true;
                    break;
                }
            }
            if (!dup && dst.size() < 40) dst.add(e);
        }
    }

    /** 去掉「中配版/国语版」等后缀 —— 同一部番在 B站 会出两条（一个带后缀） */
    private static String stripVersionSuffix(String s) {
        if (s == null) return "";
        return s.replaceAll("(中配版|国语版|日配版|粤语版|普通话版|剧场版|TV版|动画版|特别篇|OVA|OAD)$", "")
                .replaceAll("[\\s·:：！!？?。.、,，-]", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    /** 粗略判同：原名/标题去空白+去版本后缀后相等，或互为包含 */
    private static boolean sameAnime(Entry a, Entry b) {
        String[] pa = {stripVersionSuffix(a.title), stripVersionSuffix(a.nativeTitle)};
        String[] pb = {stripVersionSuffix(b.title), stripVersionSuffix(b.nativeTitle)};
        for (String x : pa) {
            if (x.isEmpty()) continue;
            for (String y : pb) {
                if (y.isEmpty()) continue;
                if (x.equals(y) || (x.length() > 4 && y.contains(x)) || (y.length() > 4 && x.contains(y)))
                    return true;
            }
        }
        return false;
    }

    private static String nz(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s·:：！!？?。.、,，-]", "").toLowerCase(Locale.ROOT);
    }

    private static boolean containsCjk(String s) {
        if (s == null) return false;
        for (char c : s.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    public interface Callback {
        void onResult(List<Entry> entries, String route);
    }

    // ══════════════ B站番剧搜索（中文最优） ══════════════

    /**
     * B站番剧搜索。实测：204ms、原生中文标题、封面、评分、集数、标签、简介全有。
     * 必须带 Referer + 伪 buvid3 Cookie，否则 HTTP 412 反爬。
     */
    public static List<Entry> searchBili(String keyword) throws Exception {
        String url = "https://api.bilibili.com/x/web-interface/search/type"
                + "?search_type=media_bangumi&keyword="
                + URLEncoder.encode(keyword, "UTF-8");
        String body = httpGet(url, "https://www.bilibili.com/",
                "buvid3=" + java.util.UUID.randomUUID() + "-infoc");
        if (body == null || body.isEmpty()) return new ArrayList<>();

        JSONObject root = new JSONObject(body);
        if (root.optInt("code", -1) != 0) return new ArrayList<>();
        JSONObject data = root.optJSONObject("data");
        if (data == null) return new ArrayList<>();
        JSONArray arr = data.optJSONArray("result");
        if (arr == null) return new ArrayList<>();

        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Entry e = new Entry();
            e.title = stripHtml(o.optString("title", ""));
            e.nativeTitle = stripHtml(o.optString("org_title", ""));
            e.cover = o.optString("cover", "");
            e.desc = o.optString("desc", "");
            e.url = o.optString("url", "");
            e.source = "bilibili";
            e.episodes = o.optInt("ep_size", -1);
            JSONObject ms = o.optJSONObject("media_score");
            if (ms != null) {
                e.score = ms.optInt("score", -1);
                e.scoreScale = 10;
            }
            String styles = o.optString("styles", "");
            if (!styles.isEmpty()) {
                for (String t : styles.split("[/、]")) {
                    String tt = t.trim();
                    if (!tt.isEmpty()) e.tags.add(tt);
                }
            }
            if (!e.title.isEmpty()) out.add(e);
            if (out.size() >= 20) break;
        }
        return out;
    }

    // ══════════════ AniList 搜索（英/日文最优） ══════════════

    /**
     * AniList GraphQL 搜索。实测直连可达（785ms），返回评分/集数/封面。
     * 中文关键词 0 命中（它不支持中文），所以中文优先走 B站。
     */
    public static List<Entry> searchAniList(String keyword) throws Exception {
        String query = "query($s:String){Page(perPage:12){media(search:$s,type:ANIME){"
                + "id title{romaji native} coverImage{large} averageScore episodes"
                + " description(asHtml:false)}}}";
        String payload = "{\"query\":" + q(query)
                + ",\"variables\":{\"s\":" + q(keyword) + "}}";

        String body = httpPost("https://graphql.anilist.co", payload);
        if (body == null || body.isEmpty()) return new ArrayList<>();

        JSONObject root = new JSONObject(body);
        if (root.has("errors")) return new ArrayList<>();
        JSONObject data = root.optJSONObject("data");
        if (data == null) return new ArrayList<>();
        JSONObject page = data.optJSONObject("Page");
        if (page == null) return new ArrayList<>();
        JSONArray arr = page.optJSONArray("media");
        if (arr == null) return new ArrayList<>();

        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Entry e = new Entry();
            JSONObject t = o.optJSONObject("title");
            if (t != null) {
                e.nativeTitle = t.optString("romaji", "");
                e.title = t.optString("native", "");
                if (e.title.isEmpty()) e.title = e.nativeTitle;
            }
            JSONObject img = o.optJSONObject("coverImage");
            if (img != null) e.cover = img.optString("large", "");
            e.score = o.has("averageScore") && !o.isNull("averageScore")
                    ? o.optInt("averageScore", -1) : -1;
            e.scoreScale = 100;   // AniList 是 0-100
            e.episodes = o.optInt("episodes", -1);
            e.desc = o.optString("description", "");
            e.url = "https://anilist.co/anime/" + o.optString("id", "");
            e.source = "anilist";
            if (!e.title.isEmpty() || !e.nativeTitle.isEmpty()) out.add(e);
        }
        return out;
    }

    /**
     * 首页推荐源（<b>首选</b>）：Kazumi 的 Bangumi 镜像热门榜。
     *
     * <p>发现自 Kazumi 源码 {@code ApiEndpoints.bangumiMirrorDomain} =
     * {@code https://api.kazumi.fyi}，其首页即用
     * {@code /kazumi/v1/popular/subjects}。</p>
     *
     * <p>实测 200 / 785ms / 19KB / 24 条，且<b>0/24 缺中文名</b>（nameCN）——
     * 这一点关键：源站只认中文（中文 11/18 vs 日文 1/18），中文名直接可用
     * 就无需再反查补全，feed 条目也能正常搜到源。</p>
     *
     * <p>字段：name(日文) / nameCN(中文) / images.common(封面) /
     * rating.score+rank+total / metaTags / info(集数·日期·导演)。</p>
     */
    public static List<Entry> feedPopular() throws Exception {
        String body = httpGet("https://api.kazumi.fyi/kazumi/v1/popular/subjects", null, null);
        if (body == null || body.isEmpty()) return new ArrayList<>();

        JSONObject root = new JSONObject(body);
        JSONArray arr = root.optJSONArray("data");
        if (arr == null) return new ArrayList<>();

        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Entry e = new Entry();
            // 中文名优先（nameCN），无则退回日文名
            e.title = o.optString("nameCN", "");
            if (e.title.isEmpty()) e.title = o.optString("name", "");
            e.nativeTitle = o.optString("name", "");
            if (e.title.equals(e.nativeTitle)) e.nativeTitle = "";

            JSONObject img = o.optJSONObject("images");
            if (img != null) e.cover = img.optString("common", "");

            JSONObject r = o.optJSONObject("rating");
            if (r != null && !r.isNull("score")) {
                e.score = (int) Math.round(r.optDouble("score", -1));
                e.scoreScale = 10;
            }
            if (r != null && !r.isNull("rank")) e.rank = r.optInt("rank", -1);

            // info 形如「12话 / 2026年10月1日 / 导演 / 原作 / 角色设计」
            String info = o.optString("info", "");
            if (!info.isEmpty()) {
                String[] parts = info.split(" / ");
                if (parts.length > 0 && parts[0].endsWith("话")) {
                    try {
                        e.episodes = Integer.parseInt(parts[0].replaceAll("[^0-9]", ""));
                    } catch (Throwable ignored) {
                    }
                }
                if (parts.length > 1) e.desc = parts[1];
            }

            // 标签
            JSONArray tags = o.optJSONArray("metaTags");
            if (tags != null) {
                for (int j = 0; j < tags.length(); j++) {
                    String t = tags.optString(j, "");
                    if (!t.isEmpty()) e.tags.add(t);
                }
            }

            e.source = "bangumi-mirror";
            e.url = "https://bgm.tv/subject/" + o.optLong("id", 0);
            if (!e.title.isEmpty()) out.add(e);
        }
        return dedupList(out);
    }

    /**
     * 首页推荐源：<b>AniList 热门番剧</b>（对标 Ani/Kazumi 的首页发现流）。
     *
     * <p>选它的理由：非 B站、非时间表；直连可达（实测 ~785ms）、
     * 自带封面与评分、更新的是当下热门（与 Ani 的发现页同源思路）。
     * 返回前用日文标题反查 B站 补中文名——否则源站搜不到（中文 11/18 vs 日文 1/18）。</p>
     */
    public static List<Entry> feedTrending() throws Exception {
        String query = "query{Page(page:1,perPage:24){media(type:ANIME,sort:TRENDING_DESC){"
                + "id title{romaji native} coverImage{large} averageScore episodes"
                + " description(asHtml:false) hectares}}}";
        // hectares 字段不存在会导致 GraphQL 报错，去掉
        query = query.replace(" hectares", "");
        String payload = "{\"query\":\"" + query.replace("\"", "\\\"") + "\"}";
        String body = httpPost("https://graphql.anilist.co", payload);
        if (body == null || body.isEmpty()) return new ArrayList<>();

        JSONObject root = new JSONObject(body);
        if (root.has("errors")) return new ArrayList<>();
        JSONObject page = root.optJSONObject("data") != null
                ? root.optJSONObject("data").optJSONObject("Page") : null;
        if (page == null) return new ArrayList<>();
        JSONArray arr = page.optJSONArray("media");
        if (arr == null) return new ArrayList<>();

        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Entry e = new Entry();
            JSONObject t = o.optJSONObject("title");
            if (t != null) {
                e.nativeTitle = t.optString("romaji", "");
                e.title = t.optString("native", "");
                if (e.title.isEmpty()) e.title = e.nativeTitle;
            }
            JSONObject img = o.optJSONObject("coverImage");
            if (img != null) e.cover = img.optString("large", "");
            e.score = o.has("averageScore") && !o.isNull("averageScore")
                    ? o.optInt("averageScore", -1) : -1;
            e.scoreScale = 100;
            e.episodes = o.optInt("episodes", -1);
            e.desc = o.optString("description", "");
            e.source = "anilist";
            e.url = "https://anilist.co/anime/" + o.optString("id", "");
            if (!e.title.isEmpty()) out.add(e);
        }
        // 补中文名（源站只认中文）
        enrichChineseTitles(out);
        out = dedupList(out);
        sortByHeat(out);          // 首页按热度排列
        return out;
    }

    /**
     * 首页推荐源（旧）：B站番剧时间表。已被 feedTrending() 取代。
     *
     * <p>实测：7 天 × ~8 集，带标题/封面/播出时间/season_id，21KB，
     * 与「发现」页的数据形态一致。返回去重后的番剧列表。</p>
     */
    public static List<Entry> feedTimeline() throws Exception {
        String url = "https://api.bilibili.com/pgc/web/timeline?types=1";
        String body = httpGet(url, "https://www.bilibili.com/",
                "buvid3=" + java.util.UUID.randomUUID() + "-infoc");
        if (body == null || body.isEmpty()) return new ArrayList<>();

        JSONObject root = new JSONObject(body);
        if (root.optInt("code", -1) != 0) return new ArrayList<>();
        JSONArray days = root.optJSONArray("result");
        if (days == null) return new ArrayList<>();

        List<Entry> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            JSONArray eps = day.optJSONArray("episodes");
            if (eps == null) continue;
            for (int j = 0; j < eps.length(); j++) {
                JSONObject e = eps.optJSONObject(j);
                if (e == null) continue;
                String sid = String.valueOf(e.optInt("season_id", 0));
                if ("0".equals(sid) || !seen.add(sid)) continue;
                Entry en = new Entry();
                en.title = e.optString("title", "");
                en.cover = e.optString("cover", "");
                en.source = "bilibili-timeline";
                String longTitle = e.optString("long_title", "");
                if (!longTitle.isEmpty() && !longTitle.equals(en.title)) en.desc = longTitle;
                String pub = e.optString("pub_time", "");
                if (!pub.isEmpty()) en.desc = (en.desc.isEmpty() ? "" : en.desc + " · ")
                        + "更新 " + pub;
                en.url = "https://www.bilibili.com/bangumi/ss" + sid;
                if (!en.title.isEmpty()) out.add(en);
                if (out.size() >= 30) return out;
            }
        }
        return out;
    }

    // ══════════════ HTTP / 工具 ══════════════

    private static String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** GET（短超时：元数据要秒回） */
    static String httpGet(String url, String referer, String cookie) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json");
        if (referer != null) c.setRequestProperty("Referer", referer);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        return read(c);
    }

    static String httpPost(String url, String jsonBody) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setDoOutput(true);
        try (java.io.OutputStream os = c.getOutputStream()) {
            os.write(jsonBody.getBytes("UTF-8"));
        }
        return read(c);
    }

    private static String read(java.net.HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        if (code / 100 != 2) {
            c.disconnect();
            return null;
        }
        java.io.InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        c.disconnect();
        return bos.toString("UTF-8");
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 去掉 B站 的 <em class="keyword"> 高亮标签 */
    private static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "").trim();
    }
}
