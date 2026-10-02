package com.aliya.hy_vq.anime;

import com.aliya.hy_vq.anime.AnimeSource.Channel;
import com.aliya.hy_vq.anime.AnimeSource.Episode;
import com.aliya.hy_vq.anime.AnimeSource.PlayLink;
import com.aliya.hy_vq.anime.AnimeSource.SearchConfig;
import com.aliya.hy_vq.anime.AnimeSource.Subject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ani 源解析引擎 —— 把「搜索结果 → 番剧条目 → 线路 → 单集 → 可播直链」跑通。
 *
 * <p>流程（对标 Animeko 的 Selector 六步，裁剪到本工具需要的四步）：
 * {@code search → channels → resolve}，其中 resolve 内部含
 * <b>解密</b>与<b>嵌套页二次解析</b>。</p>
 *
 * <h3>移植自 k2a 工具的实测结论（见 08-转换工具/README.md）</h3>
 * <ul>
 *   <li><b>encrypt 有三种形态</b>：0 明文 / 1 URL 百分号编码（可能多层）/ 2 base64 后再 URL 编码。
 *       不能只信站点声明的值，要按"能解出 http 开头"来判定。</li>
 *   <li><b>取 player 数据必须先定位完整 JSON</b>：直接正则找 {@code "url"} 会匹配到站点配置
 *       （实测踩过：取到 {@code www.moonci.com} 这种垃圾值）。这里用括号计数法配对。</li>
 *   <li><b>解出的地址可能还是页面</b>（嵌套 URL，如 {@code /play/xxx.html}），必须再解析一层。</li>
 *   <li><b>可用性判定不能用 HEAD</b>（会 403），要用 GET + Range —— 该逻辑在验证侧，不在此处。</li>
 * </ul>
 */
public class SourceEngine {

    private static final Pattern PLAYER_JSON = Pattern.compile("player_aaaa\\s*=\\s*\\{");

    private final OkHttpClient http;

    /**
     * 共享连接池 —— 原实现在构造函数里 new 一个 client，而 AnimeSearchManager
     * 对**每个源**都 new SourceEngine()，等于每源一个连接池，复用率为零。
     * 改为静态单例后，多源并发共用同一池，省掉重复的对象与线程开销。
     */
    private static volatile OkHttpClient SHARED_HTTP;

    private static OkHttpClient shared() {
        OkHttpClient c = SHARED_HTTP;
        if (c == null) {
            synchronized (SourceEngine.class) {
                c = SHARED_HTTP;
                if (c == null) {
                    c = new OkHttpClient.Builder()
                            // 超时收短：解析场景宁可快速失败，也不要让一个慢源拖住整体
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(12, TimeUnit.SECONDS)
                            .connectionPool(new okhttp3.ConnectionPool(24, 5, TimeUnit.MINUTES))
                            .retryOnConnectionFailure(true)
                            .followRedirects(true)
                            .build();
                    SHARED_HTTP = c;
                }
            }
        }
        return c;
    }

    public SourceEngine() {
        this.http = shared();
    }

    // ══════════════════════════ 1. 搜索 ══════════════════════════

    /** 在单个源上搜索关键词。失败抛 IOException，由调用方决定是否降级。 */
    public List<Subject> search(AnimeSource src, String keyword) throws IOException {
        SearchConfig c = src.search;
        String kw = prepareKeyword(keyword, c);
        String url = c.searchUrl.replace("{keyword}", URLEncoder.encode(kw, "UTF-8"));
        String html = get(url, null, headersOf(src, null));
        Document doc = Jsoup.parse(html, url);
        List<Subject> out = new ArrayList<>();

        if ("indexed".equalsIgnoreCase(c.subjectFormatId)
                && !c.selectNames.isEmpty() && !c.selectLinks.isEmpty()) {
            Elements names = doc.select(c.selectNames);
            Elements links = doc.select(c.selectLinks);
            int n = Math.min(names.size(), links.size());
            for (int i = 0; i < n; i++) {
                String nm = clean(names.get(i).text());
                String href = abs(links.get(i), "href");
                if (okSubject(nm, href, keyword, c)) {
                    out.add(new Subject(nm, href, src.name));
                }
            }
        } else if (!c.selectLists.isEmpty()) {
            for (Element el : doc.select(c.selectLists)) {
                Element a = el.tagName().equals("a") ? el : el.selectFirst("a");
                if (a == null) continue;
                String nm = clean(a.text());
                if (nm.isEmpty()) nm = clean(a.attr("title"));
                String href = abs(a, "href");
                if (okSubject(nm, href, keyword, c)) {
                    out.add(new Subject(nm, href, src.name));
                }
            }
        }
        return out;
    }

    private boolean okSubject(String name, String href, String keyword, SearchConfig c) {
        if (name == null || name.isEmpty() || href == null || href.isEmpty()) return false;
        if (href.startsWith("javascript")) return false;
        if (c.filterBySubjectName && keyword != null && !keyword.isEmpty()) {
            // 宽松包含判断：把关键词去掉空白后看是否出现在标题里
            String k = keyword.replaceAll("\\s+", "");
            String nm = name.replaceAll("\\s+", "");
            if (!nm.contains(k) && !k.contains(nm)) {
                return false;
            }
        }
        return true;
    }

    private String prepareKeyword(String keyword, SearchConfig c) {
        String kw = keyword == null ? "" : keyword.trim();
        if (c.searchRemoveSpecial) {
            kw = kw.replaceAll("[^\\p{IsHan}A-Za-z0-9\\s]", " ").trim();
        }
        if (c.searchUseOnlyFirstWord) {
            String[] parts = kw.split("\\s+");
            if (parts.length > 1) kw = parts[0];
        }
        return kw;
    }

    // ══════════════════════════ 2. 线路与单集 ══════════════════════════

    /** 解析详情页，得到线路列表（每条线路含其单集）。 */
    public List<Channel> channels(AnimeSource src, Subject subject) throws IOException {
        SearchConfig c = src.search;
        String html = get(subject.url, subject.url, headersOf(src, null));
        Document doc = Jsoup.parse(html, subject.url);
        List<Channel> out = new ArrayList<>();

        Pattern sortPat = compileSortPattern(c.matchEpisodeSortFromName);

        if (!c.selectEpisodeLists.isEmpty()) {
            Elements names = c.selectChannelNames.isEmpty()
                    ? new Elements() : doc.select(c.selectChannelNames);
            Elements lists = doc.select(c.selectEpisodeLists);
            for (int i = 0; i < lists.size(); i++) {
                String chName = i < names.size() ? clean(names.get(i).text()) : ("线路" + (i + 1));
                Channel ch = new Channel(chName.isEmpty() ? ("线路" + (i + 1)) : chName);
                addEpisodes(ch, lists.get(i), c, sortPat, subject.url);
                if (!ch.episodes.isEmpty()) out.add(ch);
            }
        } else if (!c.selectEpisodes.isEmpty()) {
            // no-channel：所有集数在同一组
            Channel ch = new Channel("默认");
            for (Element a : doc.select(c.selectEpisodes)) {
                addEpisode(ch, a, c, sortPat, subject.url);
            }
            if (!ch.episodes.isEmpty()) out.add(ch);
        }
        return out;
    }

    private void addEpisodes(Channel ch, Element container, SearchConfig c,
                             Pattern sortPat, String pageUrl) {
        String sel = c.selectEpisodesFromList == null || c.selectEpisodesFromList.isEmpty()
                ? "a" : c.selectEpisodesFromList;
        for (Element a : container.select(sel)) {
            addEpisode(ch, a, c, sortPat, pageUrl);
        }
    }

    private void addEpisode(Channel ch, Element a, SearchConfig c, Pattern sortPat, String pageUrl) {
        String title = clean(a.text());
        if (title.isEmpty()) title = clean(a.attr("title"));
        String href = abs(a, "href");
        if (href == null || href.isEmpty() || href.startsWith("javascript")) return;
        String sort = "";
        if (sortPat != null) {
            Matcher m = sortPat.matcher(title);
            if (m.find()) {
                try {
                    sort = m.group("ep");
                } catch (Throwable t) {
                    sort = m.group(1);
                }
            }
        }
        if (sort == null) sort = "";
        // filterByEpisodeSort：提不出集号的丢弃（避免把"预告""PV"当正片）
        if (c.filterByEpisodeSort && sort.isEmpty()) return;
        ch.episodes.add(new Episode(sort.trim(), title, href));
    }

    /** 把 ani 的 {@code (?<ep>...)} 语法编译成 Java 正则（语法相同，仅做容错）。 */
    private Pattern compileSortPattern(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return Pattern.compile(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    // ══════════════════════════ 3. 直链解析 ══════════════════════════

    /**
     * 从单集页面解析出可播放地址（含请求头）。
     * 流程：抓播放页 → 取 player 数据 → 解密 → 若是嵌套页则再解析一层。
     */
    public PlayLink resolve(AnimeSource src, Episode ep, String channelName) throws IOException {
        SearchConfig c = src.search;
        Map<String, String> headers = headersOf(src, c);
        String html = get(ep.url, ep.url, headers);

        PlayLink link = new PlayLink();
        link.channelName = channelName;

        // ① 页面内直接有媒体地址
        String direct = findMediaUrl(html, c);
        if (direct != null) {
            link.url = direct;
            link.headers = headers;
            return link;
        }

        // ② player_aaaa 数据 → 解密
        Map<String, String> player = extractPlayer(html);
        if (player != null) {
            String raw = player.get("url");
            String dec = decryptUrl(raw, player.get("encrypt"));
            if (dec != null) {
                if (isMediaUrl(dec)) {
                    link.url = dec;
                    link.headers = headers;
                    return link;
                }
                // ③ 嵌套页：解出来还是页面，再抓一层
                String nestedHtml = get(dec, ep.url, headers);
                String nestedDirect = findMediaUrl(nestedHtml, c);
                if (nestedDirect == null) {
                    Map<String, String> p2 = extractPlayer(nestedHtml);
                    if (p2 != null) {
                        nestedDirect = decryptUrl(p2.get("url"), p2.get("encrypt"));
                    }
                }
                if (nestedDirect != null && nestedDirect.startsWith("http")) {
                    link.url = nestedDirect;
                    link.nested = true;
                    link.headers = headers;
                    return link;
                }
                throw new IOException("嵌套页未找到媒体地址");
            }
        }
        throw new IOException("未识别播放数据（可能是需 WebView 的第三方解析源）");
    }

    /** 请求头：Referer 为空串时**不要**添加该头（实测某些源带 Referer 反而 400）。 */
    public static Map<String, String> headersOf(AnimeSource src, SearchConfig c) {
        Map<String, String> h = new HashMap<>();
        SearchConfig sc = c != null ? c : src.search;
        if (sc != null) {
            if (sc.userAgent != null && !sc.userAgent.isEmpty()) h.put("User-Agent", sc.userAgent);
            if (sc.referer != null && !sc.referer.isEmpty()) h.put("Referer", sc.referer);
            if (sc.cookies != null && !sc.cookies.isEmpty()) h.put("Cookie", sc.cookies);
        }
        return h;
    }

    /** 从页面里找直接的媒体地址（含被转义的 \/ 形式）。 */
    public static String findMediaUrl(String html, SearchConfig c) {
        if (html == null) return null;
        Pattern p = Pattern.compile(
                "https?:(?:\\\\?/){2}[^\"'\\s\\\\<>]{0,200}?\\.(?:m3u8|mp4|flv|mkv)[^\"'\\s\\\\<>]{0,80}");
        Matcher m = p.matcher(html);
        while (m.find()) {
            String u = m.group().replace("\\/", "/");
            // 若规则给了 matchVideoUrl，优先用规则筛一遍
            if (c != null && c.matchVideoUrl != null && !c.matchVideoUrl.isEmpty()) {
                try {
                    if (!Pattern.compile(c.matchVideoUrl).matcher(u).find()) continue;
                } catch (Throwable ignored) {
                }
            }
            return u;
        }
        return null;
    }

    public static boolean isMediaUrl(String url) {
        if (url == null) return false;
        String u = url.split("\\?")[0].toLowerCase();
        return u.endsWith(".m3u8") || u.endsWith(".mp4") || u.endsWith(".flv")
                || u.endsWith(".mkv") || u.endsWith(".ts") || u.endsWith(".mpd");
    }

    // ── player 数据提取与解密（移植自 k2a）──

    /** 提取 player_aaaa 各字段。返回 null 表示页面没有该数据。 */
    public static Map<String, String> extractPlayer(String html) {
        if (html == null) return null;
        Matcher m = PLAYER_JSON.matcher(html);
        while (m.find()) {
            int start = html.indexOf('{', m.start());
            String blob = matchBraces(html, start);
            if (blob == null) continue;
            Map<String, String> out = new HashMap<>();
            out.put("url", jsonStr(blob, "url"));
            out.put("encrypt", jsonNum(blob, "encrypt"));
            out.put("from", jsonStr(blob, "from"));
            if (out.get("url") != null && !out.get("url").isEmpty()) return out;
        }
        return null;
    }

    /** 括号计数法配对完整的 JSON 对象（正确处理字符串与转义）—— 避免正则切错。 */
    private static String matchBraces(String text, int start) {
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') inStr = false;
                continue;
            }
            if (ch == '"') inStr = true;
            else if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }

    private static String jsonStr(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .matcher(json);
        if (m.find()) {
            return m.group(1).replace("\\/", "/").replace("\\\"", "\"");
        }
        return null;
    }

    private static String jsonNum(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"?(-?\\d+)\"?")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 解密播放地址。encrypt 0/1/2 三种形态，且不迷信声明值——按"能解出 http 开头"判定。
     * 兼容「base64 后再 URL 编码」（两层）。
     */
    public static String decryptUrl(String raw, String encrypt) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("http")) return s;

        String b64 = null;
        try {
            byte[] dec = Base64.getDecoder().decode(padBase64(s));
            b64 = new String(dec, "UTF-8");
        } catch (Throwable ignored) {
        }

        if ("2".equals(encrypt)) {
            String v = b64 == null ? null : urlDecode(b64);
            if (v != null && v.startsWith("http")) return v;
        }
        if ("1".equals(encrypt) || encrypt == null) {
            String v = urlDecode(s);
            if (v != null && v.startsWith("http")) return v;
        }
        // 兜底：两种都试，再试多层 url 解码
        if (b64 != null) {
            String v = urlDecode(b64);
            if (v != null && v.startsWith("http")) return v;
        }
        String v = s;
        for (int i = 0; i < 3; i++) {
            v = urlDecode(v);
            if (v != null && v.startsWith("http")) return v;
            if (v == null) break;
        }
        return null;
    }

    private static String padBase64(String s) {
        int r = s.length() % 4;
        if (r == 0) return s;
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < 4 - r; i++) sb.append('=');
        return sb.toString();
    }

    /** 宽容的 URL 解码：src 非法百分号序列时原样返回。 */
    private static String urlDecode(String s) {
        if (s == null) return null;
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    // ══════════════════════════ HTTP / 工具 ══════════════════════════

    /** 简单 GET（同步）。失败抛 IOException。 */
    public String get(String url, String referer, Map<String, String> extraHeaders) throws IOException {
        if (url == null || url.isEmpty()) throw new IOException("空 URL");
        String fixed = normalizeUrl(url);
        Request.Builder b = new Request.Builder().url(fixed);
        b.header("User-Agent", DEFAULT_UA);
        if (referer != null && !referer.isEmpty()) b.header("Referer", referer);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
                    b.header(e.getKey(), e.getValue());
                }
            }
        }
        try (Response resp = http.newCall(b.build()).execute()) {
            ResponseBody body = resp.body();
            return body == null ? "" : body.string();
        }
    }

    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 站点常返回 http:// 链接但只支持 https（实测 AGE）。 */
    public static String normalizeUrl(String u) {
        if (u != null && u.startsWith("http://")) {
            return "https://" + u.substring("http://".length());
        }
        return u;
    }

    private static String abs(Element a, String attr) {
        String v = a.attr("abs:" + attr);
        if (v != null && !v.isEmpty()) return v;
        v = a.attr(attr);
        if (v == null || v.isEmpty()) return v;
        return normalizeUrl(v);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
