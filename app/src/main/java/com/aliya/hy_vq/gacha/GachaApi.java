package com.aliya.hy_vq.gacha;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 米哈游祈愿记录 API 客户端。
 *
 * <p><b>原理</b>（实测自 webstatic 抽卡页 bundle.js，非推测）：</p>
 * <pre>
 *   游戏内打开「祈愿 → 历史记录」时，客户端申请临时 authkey 拼成网页地址，
 *   页面 JS 从中取参后直接请求：
 *
 *   GET {API_BASE}/gacha_info/api/getGachaLog
 *       ?{URL 里的全部参数}&gacha_type=..&page=..&size=20&end_id=..
 *
 *   官方 bundle 的关键实现（getCDNQuery）：
 *       var t = parse(location.search.slice(1));      // 解析全部查询参数
 *       return a({}, t, { region:.., gacha_id:.., timestamp:..,
 *                         lang: langMap[t.lang] || t.lang, prefix:.. });
 *   → 即<b>把整个查询串 t 原样展开</b>再补几个规范化字段。
 * </pre>
 *
 * <p><b>因此本类也照此实现：解析出 URL 的全部查询参数，原样透传给 API。</b>
 * 早先版本只手工拼 &nbsp;authkey_ver/sign_type/auth_appid/game_biz&nbsp; 几个参数，
 * 漏掉 <code>lang</code> 就会被服务端拒绝并返回
 * <code>retcode -108 "language error"</code> —— 透传即可根治，且日后新增参数也不会再漏。</p>
 *
 * <p><b>错误码含义</b>（实测）：服务端分两道校验 ——
 * 先验 authkey（失败 <code>-100 authkey error</code>），
 * 再验语言（失败 <code>-108 language error</code>）。
 * 所以收到 -108 反而说明 authkey 是有效的。</p>
 *
 * <p><b>凭证限制</b>：auth_appid=webview_gacha 决定它<b>只能读祈愿记录</b>，
 * 无法改密码/抽卡/交易；有效期通常 24 小时内。</p>
 */
public final class GachaApi {

    private GachaApi() {}

    /** API 基址（实测自 bundle.js：t.apiBase） */
    public static final String API_BASE = "https://public-operation-hk4e.mihoyo.com";
    private static final String PATH_LOG = "/gacha_info/api/getGachaLog";

    /** 每页固定 20 条（服务端硬上限） */
    private static final int PAGE_SIZE = 20;
    /** 单卡池最多翻页次数：20×500=10000 条，足够覆盖 6 个月留存期 */
    private static final int MAX_PAGES = 500;

    /** 全部卡池类型 */
    public static final String[] ALL_TYPES = {"100", "200", "301", "302", "500"};

    /** 这些参数由我们按翻页逻辑决定，不采用 URL 里的值 */
    private static final Set<String> OVERRIDE_KEYS = new LinkedHashSet<>();
    static {
        OVERRIDE_KEYS.add("gacha_type");
        OVERRIDE_KEYS.add("page");
        OVERRIDE_KEYS.add("size");
        OVERRIDE_KEYS.add("end_id");
        OVERRIDE_KEYS.add("begin_id");
        OVERRIDE_KEYS.add("prefix");   // 官方前端内部字段，API 不需要
    }

    /** 语言短码 → 标准码（照抄官方映射表中常用的几项） */
    private static String normLang(String s) {
        if (s == null || s.isEmpty()) return "zh-cn";
        switch (s) {
            case "sc": return "zh-cn";
            case "tc": return "zh-tw";
            case "en": return "en-us";
            case "jp": return "ja-jp";
            case "kr": return "ko-kr";
            default: return s;
        }
    }

    /** 从链接/文本中解析出的凭证信息 */
    public static class AuthInfo {
        public String authkey = "";
        public String region = "";
        public String gameBiz = "hk4e_cn";
        public String lang = "zh-cn";
        /** 原始链接（仅用于展示与排错） */
        public String rawUrl = "";
        /** URL 里的全部查询参数（已 URL 解码），发送时原样透传 */
        public final Map<String, String> query = new LinkedHashMap<>();

        public boolean valid() {
            return authkey != null && !authkey.isEmpty();
        }
    }

    /** 抓取结果 */
    public static class FetchResult {
        public final List<GachaRecord> records = new ArrayList<>();
        public String uid = "";
        public int pageCount = 0;
        /** 服务端返回的错误（null 表示成功） */
        public String error = null;
        /** 是否 authkey 失效 */
        public boolean authkeyExpired = false;
    }

    /**
     * 从用户粘贴的文本中解析出一条或多条凭证。
     * <p>支持：完整 URL、多行多个 URL、只含参数的片段、甚至整段聊天记录。
     * 同一 authkey 只保留一次。会把 URL 的全部查询参数存进 {@link AuthInfo#query}。</p>
     */
    public static List<AuthInfo> parseAuthInfos(String text) {
        List<AuthInfo> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        Set<String> seen = new LinkedHashSet<>();

        for (String line : text.split("[\\r\\n]+")) {
            String s = line.trim();
            if (s.isEmpty()) continue;

            // 取出查询串：优先取 '?' 之后，若无 '?' 则整行当作参数串
            String qs = s;
            int q = s.indexOf('?');
            if (q >= 0) qs = s.substring(q + 1);
            int hash = qs.indexOf('#');
            if (hash >= 0) qs = qs.substring(0, hash);

            // 一行里可能混有多段（如聊天记录），按 &authkey= 切出有效片段
            List<Map<String, String>> candidates = new ArrayList<>();
            Map<String, String> curMap = new LinkedHashMap<>();
            for (String pair : qs.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String k = pair.substring(0, eq).trim();
                String v = safeDecode(pair.substring(eq + 1).trim());
                if (!k.isEmpty()) curMap.put(k, v);
                // 遇到下一个 authkey 视为新的一段
                if ("authkey".equals(k) && curMap.size() > 1) {
                    candidates.add(curMap);
                    Map<String, String> next = new LinkedHashMap<>();
                    curMap = next;
                }
            }
            if (!curMap.isEmpty()) candidates.add(curMap);
            if (candidates.isEmpty()) candidates.add(curMap);
            // 兜底：整行里用正则再找一遍（应对非 & 分隔的粘贴格式）
            if (candidates.stream().noneMatch(m -> m.containsKey("authkey"))) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("authkey=([^&\\s\"'<>#]+)").matcher(s);
                while (m.find()) {
                    Map<String, String> one = new LinkedHashMap<>();
                    one.put("authkey", safeDecode(m.group(1)));
                    candidates.add(one);
                }
            }

            for (Map<String, String> map : candidates) {
                String key = map.get("authkey");
                if (key == null || key.isEmpty() || !seen.add(key)) continue;
                AuthInfo a = new AuthInfo();
                a.authkey = key;
                a.rawUrl = s;
                a.query.putAll(map);
                if (map.containsKey("region")) a.region = map.get("region");
                if (map.containsKey("game_biz")) a.gameBiz = map.get("game_biz");
                a.lang = normLang(map.get("lang"));
                a.query.put("lang", a.lang);   // 规范化后的语言回填，保证一定带上
                out.add(a);
            }
        }
        return out;
    }

    /**
     * 把服务端 retcode 翻成人话。
     * <p>实测两道校验顺序：先 authkey（-100），再语言（-108）。
     * 所以收到 -108 反而说明 authkey 本身是有效的，只是请求缺 lang。</p>
     */
    private static String friendlyError(int retcode, String msg) {
        switch (retcode) {
            case -100: case -101: case -111:
                return "authkey 已失效或过期 —— 请重新从游戏「祈愿 → 历史记录」复制新链接";
            case -108:
                return "请求缺少 lang 参数（链接不完整）—— 请粘贴完整链接，勿手工截取";
            case -110:
                return "authkey 与服务器区域不符 —— 请确认链接来自本账号所在服";
            default:
                return msg + "（retcode " + retcode + "）";
        }
    }

    private static String safeDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    public static String safeEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable t) {
            return s == null ? "" : s;
        }
    }

    /** 组装单次请求 URL：翻页参数由本方法决定，其余参数从凭证里原样透传 */
    private static String buildUrl(AuthInfo auth, String type, int pageNo, String endId) {
        StringBuilder sb = new StringBuilder(API_BASE).append(PATH_LOG).append('?');
        sb.append("gacha_type=").append(safeEncode(type))
          .append("&page=").append(pageNo)
          .append("&size=").append(PAGE_SIZE)
          .append("&end_id=").append(safeEncode(endId));
        // 透传 URL 里的全部参数（authkey / authkey_ver / sign_type / auth_appid /
        // game_biz / lang / region / gacha_id / timestamp / plat_type ...）
        for (Map.Entry<String, String> e : auth.query.entrySet()) {
            String k = e.getKey();
            if (k == null || k.isEmpty() || OVERRIDE_KEYS.contains(k)) continue;
            String v = e.getValue();
            if (v == null || v.isEmpty()) continue;
            sb.append('&').append(k).append('=').append(safeEncode(v));
        }
        // 兜底：确保 authkey 一定在
        if (!auth.query.containsKey("authkey") && !auth.authkey.isEmpty()) {
            sb.append("&authkey=").append(safeEncode(auth.authkey));
        }
        return sb.toString();
    }

    /**
     * 拉取某个卡池的全部记录（自动翻页）。
     *
     * @param auth   凭证
     * @param type   卡池类型（100/200/301/302/500）
     * @param existingIds 本地已存在的记录 id；用于提前去重与判断是否已追平历史
     * @param progress 进度回调（可空）
     */
    public static FetchResult fetchPool(AuthInfo auth, String type,
                                        Set<String> existingIds,
                                        Progress progress) {
        FetchResult res = new FetchResult();
        String endId = "0";
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                String url = buildUrl(auth, type, page + 1, endId);
                JSONObject root = getJson(url);
                if (root == null) {
                    res.error = "网络请求失败";
                    return res;
                }
                int retcode = root.optInt("retcode", 0);
                if (retcode != 0) {
                    String msg = root.optString("message", "未知错误");
                    res.error = friendlyError(retcode, msg);
                    // -100/-101/-111 表示凭证失效或过期
                    if (retcode == -100 || retcode == -101 || retcode == -111) {
                        res.authkeyExpired = true;
                    }
                    return res;
                }
                JSONObject data = root.optJSONObject("data");
                if (data == null) break;
                if (res.uid.isEmpty()) res.uid = data.optString("uid", "");
                JSONArray list = data.optJSONArray("list");
                if (list == null || list.length() == 0) break;

                int newInThisPage = 0;
                String lastId = null;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.optJSONObject(i);
                    if (o == null) continue;
                    GachaRecord r = GachaRecord.fromJson(o);
                    if (r.id.isEmpty()) continue;
                    lastId = r.id;
                    if (res.uid.isEmpty()) res.uid = r.uid;
                    if (existingIds != null && existingIds.contains(r.id)) continue;
                    res.records.add(r);
                    newInThisPage++;
                }
                res.pageCount++;
                if (progress != null) progress.onProgress(res.records.size(), true);

                // 本页全是旧数据 → 已追平历史
                if (newInThisPage == 0 && existingIds != null && !existingIds.isEmpty()) break;
                if (list.length() < PAGE_SIZE) break;
                if (lastId == null) break;
                endId = lastId;
                try { Thread.sleep(320); } catch (InterruptedException ignored) { }
            }
        } catch (Throwable t) {
            res.error = t.getMessage() == null ? t.toString() : t.getMessage();
        }
        if (progress != null) progress.onProgress(res.records.size(), false);
        return res;
    }

    /** 拉取全部卡池 */
    public static FetchResult fetchAll(AuthInfo auth, Set<String> existingIds, Progress progress) {
        FetchResult all = new FetchResult();
        for (String type : ALL_TYPES) {
            FetchResult one = fetchPool(auth, type, existingIds, progress);
            if (one.error != null) {
                all.error = one.error;
                all.authkeyExpired = one.authkeyExpired;
                if (one.authkeyExpired) return all;
                continue;
            }
            if (all.uid.isEmpty()) all.uid = one.uid;
            all.records.addAll(one.records);
            all.pageCount += one.pageCount;
        }
        return all;
    }

    private static JSONObject getJson(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            conn.setRequestProperty("Referer", "https://webstatic.mihoyo.com/");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            if (sb.length() == 0) return null;
            return new JSONObject(sb.toString());
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 进度回调 */
    public interface Progress {
        void onProgress(int fetched, boolean stillRunning);
    }
}
