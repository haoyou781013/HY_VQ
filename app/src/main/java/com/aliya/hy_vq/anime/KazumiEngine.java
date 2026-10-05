package com.aliya.hy_vq.anime;

import com.aliya.hy_vq.anime.AnimeSource.Channel;
import com.aliya.hy_vq.anime.AnimeSource.Episode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kazumi 规则引擎（第二引擎，对齐 {@code Predidit/KazumiRules} 的 XPath 规则）。
 *
 * <p>当初架构决策即「对齐 ani CSS selector，<b>给双引擎留口子</b>」——本类就是那个口子。</p>
 *
 * <h3>规则格式（实测 KazumiRules）</h3>
 * <pre>
 * { "api":"1", "type":"anime", "name":"7sefun", "baseURL":"https://...",
 *   "searchURL":".../search?wd=@keyword",
 *   "searchList":"//div[2]/div/div/div",      ← 根级绝对 XPath
 *   "searchName":"//div[2]/text()",           ← 相对节点 XPath
 *   "searchResult":"//a",                     ← 相对节点 XPath
 *   "chapterRoads":"//div[@class='tab']/div",  ← 根级
 *   "chapterResult":"//a" }                    ← 相对
 * </pre>
 *
 * <h3>XPath → CSS 转换</h3>
 * jsoup 不支持 XPath，但实测规则只用三类选择器，可等价转换：
 * <pre>
 *  //tag            → tag              （根级后代，CSS 同义）
 *  tag[N]           → tag:nth-of-type(N)   （与 XPath「第 N 个同名兄弟」等价）
 *  tag[@a='v']      → tag[a="v"]
 *  tag[contains(@a,'v')] → tag[a*="v"]
 *  /                → " > "（直接子代）    //  → " "（后代）
 *  text()           → 取元素 text()
 * </pre>
 */
public final class KazumiEngine {

    private KazumiEngine() {
    }

    // ══════════════ XPath → CSS ══════════════

    /**
     * 把 Kazumi 规则里的 XPath 转成 jsoup 可用的 CSS 选择器。
     * 只覆盖规则实际使用的子集（位置/属性/包含/子代），无法转换时返回 null。
     */
    public static String xpathToCss(String xpath) {
        if (xpath == null || xpath.trim().isEmpty()) return null;
        String x = xpath.trim();
        // 文本节点：//div[2]/text() → 取 div 的文本
        x = x.replaceAll("/text\\(\\)\\s*$", "");
        x = x.replaceAll("/node\\(\\)\\s*$", "");
        // 去掉开头的 // （根级后代在 CSS 里等价于直接选择器）
        boolean leadingDesc = x.startsWith("//");
        if (leadingDesc) x = x.substring(2);

        StringBuilder css = new StringBuilder();
        boolean first = true;
        // 逐段处理：tag[...], tag
        String[] parts = x.split("/");
        for (int i = 0; i < parts.length; i++) {
            String raw = parts[i];
            boolean doubleSlash = raw.startsWith("/") && i > 0;   // 由 "//" 拆出的空段
            if (raw.isEmpty()) continue;
            String seg = raw;
            String connector;
            if (i == 0) {
                connector = "";
            } else {
                connector = doubleSlash ? " " : " > ";
            }
            StringBuilder out = new StringBuilder();
            // 段内可能形如 div[2] / div[@class='x'] / div
            Matcher m = Pattern.compile("^(\\*|[A-Za-z][\\w-]*|\\*)"
                    + "(?:\\[(.+)\\])?$").matcher(seg.trim());
            if (!m.matches()) {
                // 含属性在标签前等复杂形态 → 放弃（保守失败）
                return null;
            }
            String tag = m.group(1);
            String pred = m.group(2);
            out.append(tag);
            if (pred != null && !pred.isEmpty()) {
                out.append(convertPredicate(pred));
            }
            css.append(connector).append(out);
            first = false;
        }
        String result = css.toString().trim();
        if (result.isEmpty()) return null;
        // 前置 // 在 CSS 中等价于后代，直接返回即可
        return result;
    }

    /** 谓词转换：[2] → :nth-of-type(2)；[@a='v'] → [a="v"]；[contains(@a,'v')] → [a*="v"] */
    private static String convertPredicate(String pred) {
        String p = pred.trim();
        // 数字位置
        if (p.matches("\\d+")) return ":nth-of-type(" + p + ")";
        // contains
        Matcher ct = Pattern.compile("^contains\\(\\s*@([\\w:-]+)\\s*,\\s*'([^']*)'\\s*\\)$")
                .matcher(p);
        if (ct.matches()) return "[" + ct.group(1) + "*=\"" + ct.group(2) + "\"]";
        Matcher ct2 = Pattern.compile("^contains\\(\\s*@([\\w:-]+)\\s*,\\s*\"([^\"]*)\"\\s*\\)$")
                .matcher(p);
        if (ct2.matches()) return "[" + ct2.group(1) + "*=\"" + ct2.group(2) + "\"]";
        // 等值
        Matcher eq = Pattern.compile("^@([\\w:-]+)\\s*=\\s*'([^']*)'$").matcher(p);
        if (eq.matches()) return "[" + eq.group(1) + "=\"" + eq.group(2) + "\"]";
        Matcher eq2 = Pattern.compile("^@([\\w:-]+)\\s*=\\s*\"([^\"]*)\"$").matcher(p);
        if (eq2.matches()) return "[" + eq2.group(1) + "=\"" + eq2.group(2) + "\"]";
        return "";   // 无法识别的谓词：忽略（保守）
    }

    // ══════════════ 规则解析 ══════════════

    /** 一条 Kazumi 规则 */
    public static class Rule {
        public String name = "";
        public String baseURL = "";
        public String searchURL = "";
        public String searchList = "";
        public String searchName = "";
        public String searchResult = "";
        public String chapterRoads = "";
        public String chapterResult = "";
        public String userAgent = "";
        public boolean useWebview = false;
        public String raw = "";

        public static Rule parse(String json) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(json);
                Rule r = new Rule();
                r.raw = json;
                r.name = o.optString("name", "");
                r.baseURL = o.optString("baseURL", "");
                r.searchURL = o.optString("searchURL", "");
                r.searchList = o.optString("searchList", "");
                r.searchName = o.optString("searchName", "");
                r.searchResult = o.optString("searchResult", "");
                r.chapterRoads = o.optString("chapterRoads", "");
                r.chapterResult = o.optString("chapterResult", "");
                r.userAgent = o.optString("userAgent", "");
                r.useWebview = o.optBoolean("useWebview", false);
                return r.name.isEmpty() ? null : r;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    // ══════════════ 检索 ══════════════

    /** 搜索：按 searchURL 换词取页面，用 searchList/Name/Result 取条目 */
    public static List<AnimeSource.Subject> search(Rule r, String keyword) throws Exception {
        List<AnimeSource.Subject> out = new ArrayList<>();
        String cssList = xpathToCss(r.searchList);
        if (cssList == null) return out;
        String url = r.searchURL.replace("@keyword",
                java.net.URLEncoder.encode(keyword, "UTF-8"));
        String html = get(url, r.userAgent);
        // Kazumi 规则普遍标 useWebview=true：纯 HTTP 常拿到反爬挑战页/JS 空壳
        // （实测 6/6 规则 useWebview=true；acgpost 返回 Cloudflare 挑战页）
        boolean rendered = false;
        if (html == null || html.isEmpty() || !html.contains("芙") && keyword != null
                && keyword.length() >= 3 && !html.contains(keyword.substring(0, Math.min(3, keyword.length())))) {
            String viaWv = WebViewPageFetcher.fetchBlocking(url, 14000);
            if (viaWv != null && viaWv.length() > 2000) {
                html = viaWv;
                rendered = true;
            }
        }
        if (html == null || html.isEmpty()) return out;
        Document doc = Jsoup.parse(html, r.baseURL);
        Elements list = doc.select(cssList);
        // WebView 渲染后仍无结果且未渲染过 → 再试一次渲染
        if (list.isEmpty() && !rendered) {
            String viaWv = WebViewPageFetcher.fetchBlocking(url, 14000);
            if (viaWv != null && viaWv.length() > 2000) {
                doc = Jsoup.parse(viaWv, r.baseURL);
                list = doc.select(cssList);
            }
        }

        String cssName = xpathToCss(r.searchName);
        String cssLink = xpathToCss(r.searchResult);
        for (Element el : list) {
            String name = "", href = "";
            if (cssName != null) {
                Elements ns = el.select(cssName);
                if (!ns.isEmpty()) name = ns.first().text();
                else name = el.text();
            } else {
                name = el.text();
            }
            if (cssLink != null) {
                Elements ls = el.select(cssLink);
                if (!ls.isEmpty()) href = abs(ls.first(), "href");
            }
            if (href.isEmpty()) href = abs(el, "href");
            name = name == null ? "" : name.trim();
            if (name.length() > 80) name = name.substring(0, 80);
            if (!name.isEmpty() && !href.isEmpty() && !href.startsWith("javascript")) {
                out.add(new AnimeSource.Subject(name, href, r.name));
            }
            if (out.size() >= 20) break;
        }
        return out;
    }

    /** 取线路与单集：chapterRoads 为根级选择，chapterResult 为相对取链接 */
    public static List<AnimeSource.Channel> channels(Rule r, AnimeSource.Subject sub)
            throws Exception {
        List<AnimeSource.Channel> out = new ArrayList<>();
        String cssRoads = xpathToCss(r.chapterRoads);
        if (cssRoads == null) return out;
        String html = get(sub.url, r.userAgent);
        if (html == null || html.isEmpty() || r.useWebview) {
            String viaWv = WebViewPageFetcher.fetchBlocking(sub.url, 14000);
            if (viaWv != null && viaWv.length() > 2000) html = viaWv;
        }
        if (html == null || html.isEmpty()) return out;
        Document doc = Jsoup.parse(html, sub.url == null ? r.baseURL : sub.url);

        String cssEp = xpathToCss(r.chapterResult);
        Elements roads = doc.select(cssRoads);
        int idx = 0;
        for (Element road : roads) {
            Channel c = new Channel();
            c.name = "线路" + (++idx);
            Elements eps = cssEp != null ? road.select(cssEp) : road.select("a");
            for (Element a : eps) {
                String href = abs(a, "href");
                String txt = a.text() == null ? "" : a.text().trim();
                if (txt.isEmpty()) txt = a.attr("title");
                if (href.isEmpty() || href.startsWith("javascript")) continue;
                if (txt.isEmpty()) txt = "第" + (c.episodes.size() + 1) + "集";
                c.episodes.add(new AnimeSource.Episode(String.valueOf(c.episodes.size() + 1),
                        txt, href));
            }
            if (!c.episodes.isEmpty()) out.add(c);
        }
        // 只有一条线路时也返回（Kazumi 单线路源常见）
        if (out.isEmpty()) {
            // 退化：整页取所有集链接，归为一条线路
            Channel c = new Channel();
            c.name = "默认";
            if (cssEp != null) {
                for (Element a : doc.select(cssEp)) {
                    String href = abs(a, "href");
                    if (href.isEmpty() || href.startsWith("javascript")) continue;
                    c.episodes.add(new AnimeSource.Episode(
                            String.valueOf(c.episodes.size() + 1), a.text(), href));
                }
            }
            if (!c.episodes.isEmpty()) out.add(c);
        }
        return out;
    }

    /** Kazumi 规则的「播放地址」通常在集页里用 WebView 播（useWebview），此处返回集页地址 */
    public static AnimeSource.PlayLink resolve(Rule r, AnimeSource.Subject sub,
                                              AnimeSource.Episode ep) {
        AnimeSource.PlayLink link = new AnimeSource.PlayLink();
        link.url = ep.url;                 // 交给 WebView 或上级解析
        link.channelName = "";
        link.nested = true;                // 标记：需二次解析
        if (r.userAgent != null && !r.userAgent.isEmpty()) {
            link.headers.put("User-Agent", r.userAgent);
        }
        return link;
    }

    // ══════════════ HTTP ══════════════

    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    private static String get(String url, String ua) throws Exception {
        if (url == null || url.isEmpty()) return null;
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(18000);
        c.setRequestProperty("User-Agent", (ua == null || ua.isEmpty()) ? DEFAULT_UA : ua);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                // 302 手动跟随一次
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc != null && loc.startsWith("http")) {
                        c.disconnect();
                        return get(loc, ua);
                    }
                }
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
            String body = bos.toString("UTF-8");
            if (body.isEmpty()) return null;
            return body;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String abs(Element e, String attr) {
        String v = e.attr("abs:" + attr);
        if (v == null || v.isEmpty()) v = e.attr(attr);
        return v == null ? "" : v;
    }
}
