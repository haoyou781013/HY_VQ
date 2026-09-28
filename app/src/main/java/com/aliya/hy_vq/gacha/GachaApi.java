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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 米哈游祈愿记录 API 客户端。
 *
 * <p><b>原理</b>（来自 webstatic 抽卡页 bundle.js 的实测结论）：</p>
 * <pre>
 *   游戏内打开「祈愿 → 历史记录」时，客户端会申请一个临时 authkey，
 *   拼成 https://webstatic.mihoyo.com/hk4e/event/.../index.html?authkey=...
 *   在 WebView 里打开。页面 JS 从 URL 读出 authkey 后直接请求：
 *
 *   GET {API_BASE}/gacha_info/api/getGachaLog
 *       ?authkey_ver=1&sign_type=2&auth_appid=webview_gacha
 *       &game_biz=hk4e_cn&gacha_type={池}&page=1&size=20&end_id={游标}
 *       &authkey={凭证}
 *
 *   本类做的事就是复刻第 3 步：<b>只要拿到 authkey，就不需要 WebView</b>，
 *   可以直接翻页拉全量记录。
 * </pre>
 *
 * <p><b>凭证限制</b>：auth_appid=webview_gacha 决定了它<b>只能读祈愿记录</b>，
 * 无法改密码/抽卡/交易；且有效期短（通常 24 小时内）。</p>
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

    /** 从链接/文本中解析出的凭证信息 */
    public static class AuthInfo {
        public String authkey = "";
        public String region = "";
        public String gameBiz = "hk4e_cn";
        /** 原始链接（仅用于展示与排错） */
        public String rawUrl = "";

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

    // ── authkey 提取 ──
    // 形如  authkey=xxxx%2b%2f...&   或整段文本里任意位置
    private static final Pattern P_AUTHKEY = Pattern.compile("authkey=([^&\\s\"'<>#]+)");
    private static final Pattern P_REGION = Pattern.compile("region=([^&\\s\"'<>#]+)");
    private static final Pattern P_BIZ = Pattern.compile("game_biz=([^&\\s\"'<>#]+)");

    /**
     * 从用户粘贴的文本中解析出一条或多条凭证。
     * <p>支持：完整 URL、多行多个 URL、只含参数的片段、甚至整段聊天记录。
     * 同一 authkey 只保留一次。</p>
     */
    public static List<AuthInfo> parseAuthInfos(String text) {
        List<AuthInfo> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        Set<String> seen = new LinkedHashSet<>();

        // 按行切分，逐行提取（一行可能含多个链接，用 find 循环兜底）
        for (String line : text.split("[\\r\\n]+")) {
            if (line.trim().isEmpty()) continue;
            Matcher m = P_AUTHKEY.matcher(line);
            while (m.find()) {
                String key = safeDecode(m.group(1));
                if (key.isEmpty() || !seen.add(key)) continue;
                AuthInfo a = new AuthInfo();
                a.authkey = key;
                a.rawUrl = line.trim();
                Matcher r = P_REGION.matcher(line);
                if (r.find()) a.region = safeDecode(r.group(1));
                Matcher b = P_BIZ.matcher(line);
                if (b.find()) a.gameBiz = safeDecode(b.group(1));
                out.add(a);
            }
        }
        return out;
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

    /**
     * 拉取某个卡池的全部记录（自动翻页）。
     *
     * @param auth   凭证
     * @param type   卡池类型（100/200/301/302/500）
     * @param existingIds 本地已存在的记录 id 集合；用于在拉取过程中提前去重与判断是否已到旧数据
     * @param progress 进度回调（可空），参数为 (当前池已拉条数, 是否仍在继续)
     */
    public static FetchResult fetchPool(AuthInfo auth, String type,
                                        Set<String> existingIds,
                                        Progress progress) {
        FetchResult res = new FetchResult();
        String endId = "0";
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                String url = API_BASE + PATH_LOG
                        + "?authkey_ver=1&sign_type=2&auth_appid=webview_gacha"
                        + "&game_biz=" + safeEncode(auth.gameBiz)
                        + "&gacha_type=" + type
                        + "&page=" + (page + 1)
                        + "&size=" + PAGE_SIZE
                        + "&end_id=" + safeEncode(endId)
                        + "&authkey=" + safeEncode(auth.authkey);
                JSONObject root = getJson(url);
                if (root == null) {
                    res.error = "网络请求失败";
                    return res;
                }
                int retcode = root.optInt("retcode", 0);
                if (retcode != 0) {
                    String msg = root.optString("message", "未知错误");
                    res.error = msg + "（retcode " + retcode + "）";
                    // -100 / -101 通常表示 authkey 失效或过期
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

                // 本页全是旧数据 → 说明已追平历史，无需继续翻页
                if (newInThisPage == 0 && existingIds != null && !existingIds.isEmpty()) break;
                if (list.length() < PAGE_SIZE) break;      // 最后一页
                if (lastId == null) break;
                endId = lastId;
                // 轻微限速，避免触发风控
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
                if (one.authkeyExpired) return all;   // 凭证失效，后续池无需再试
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
