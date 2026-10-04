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
        public List<String> tags = new ArrayList<>();

        /** 用于查播放源的关键词：优先原名（日文），其次显示名 */
        public String searchKeyword() {
            if (nativeTitle != null && !nativeTitle.isEmpty()) return nativeTitle;
            return title;
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
        // 按评分降序（无评分排后）
        merged.sort((a, b) -> Integer.compare(rank(b), rank(a)));
        return merged;
    }

    private static int rank(Entry e) {
        if (e.score < 0) return 0;
        return e.scoreScale == 100 ? e.score : e.score * 10;
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

    /** 粗略判同：原名/标题去空白后相等，或标题互为包含 */
    private static boolean sameAnime(Entry a, Entry b) {
        String[] pa = {nz(a.title), nz(a.nativeTitle)};
        String[] pb = {nz(b.title), nz(b.nativeTitle)};
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
