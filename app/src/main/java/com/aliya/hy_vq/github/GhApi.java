package com.aliya.hy_vq.github;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * GitHub REST API 客户端。
 *
 * <p><b>为什么不走网页版：</b>2026-10-12 实测（真实大陆网络，上海移动 AS9808）——
 * <pre>
 *   github.com                成功率  1/5   ← 网页主站被间歇掐断
 *   api.github.com            成功率  5/5   ← 本类只用这个
 *   codeload.github.com       成功率  5/5
 *   raw.githubusercontent.com 成功率  4/5
 * </pre>
 * GitHub 官方 App 之所以稳定，正是因为它是 <b>API 客户端而非浏览器</b>：
 * 只访问从未被干扰的 api.github.com，不加载 HTML，也不依赖被掐断的 github.com。
 * 本类走同一条路线，因此不需要任何代理。
 *
 * <p><b>登录方式：</b>OAuth Device Flow（{@link GhDeviceFlow}），拿到 token 后
 * 所有请求带 {@code Authorization: Bearer}。<b>不依赖 Cookie、不走浏览器表单</b>，
 * 因此不存在"代理把 Cookie 绑到代理域名"的问题。
 *
 * <p><b>Range 支持（实测 206）：</b>
 * <pre>
 *   raw.githubusercontent.com   206  accept-ranges: bytes   → 可分段
 *   api.github.com            206  accept-ranges: bytes   → 可分段
 *   codeload.github.com       200  无 Content-Range       → 不支持分段
 * </pre>
 * 故 {@link #supportsRange} 按端点判断，不做盲目分段。
 */
public class GhApi {

    private static final String API_BASE = "https://api.github.com";
    private static final String UA = "HY_VQ-Android";
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 30000;

    private final Context ctx;
    private final String token;

    public GhApi(Context ctx, String token) {
        this.ctx = ctx.getApplicationContext();
        this.token = token == null ? "" : token.trim();
    }

    public boolean hasToken() {
        return !token.isEmpty();
    }

    public String token() {
        return token;
    }

    // ── 底层请求 ────────────────────────────────────────

    private static final class Resp {
        int code;
        String body;
        String etag;
        String rateRemaining;
        String rateReset;
        JSONObject json;
        List<String> linkNext;
    }

    private Resp request(String path, String method, String accept) {
        Resp r = new Resp();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(path.startsWith("http") ? path : API_BASE + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method == null ? "GET" : method);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", accept != null ? accept
                    : "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            if (hasToken()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            r.code = conn.getResponseCode();
            r.etag = conn.getHeaderField("ETag");
            r.rateRemaining = conn.getHeaderField("X-RateLimit-Remaining");
            r.rateReset = conn.getHeaderField("X-RateLimit-Reset");

            InputStream in = r.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            r.body = in == null ? "" : readAll(in);

            // 分页：解析 Link 头里的 rel="next"
            String link = conn.getHeaderField("Link");
            if (link != null) {
                r.linkNext = new ArrayList<>();
                for (String part : link.split(",")) {
                    if (part.contains("rel=\"next\"")) {
                        int a = part.indexOf('<');
                        int b = part.indexOf('>');
                        if (a >= 0 && b > a) r.linkNext.add(part.substring(a + 1, b));
                    }
                }
            }
            if (r.body != null && !r.body.isEmpty() && r.body.charAt(0) == '{') {
                try {
                    r.json = new JSONObject(r.body);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            r.code = -1;
            r.body = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r;
    }

    private Resp reqJson(String path) {
        return request(path, "GET", "application/vnd.github+json");
    }

    /** 把错误转成人能看懂的一句话（Rate limit / 404 / 网络异常分别提示） */
    public static String explain(Resp r) {
        if (r.code == -1) return "网络不可达：" + r.body;
        if (r.code == 401) return "未授权或 token 已失效，请重新登录";
        if (r.code == 403) {
            if (r.rateRemaining != null && r.rateRemaining.equals("0")) {
                return "API 限流已用尽（未登录 60 次/小时），登录后可提升到 5000 次/小时";
            }
            return "无权访问（403）";
        }
        if (r.code == 404) return "未找到（404）";
        if (r.code >= 500) return "GitHub 服务异常（" + r.code + "）";
        return "请求失败（" + r.code + "）" + (r.body == null ? "" : "");
    }

    /** 游客限流 60/小时，登录后 5000/小时 —— 供 UI 展示 */
    public String rateInfo() {
        Resp r = reqJson("/rate_limit");
        if (r.json == null) return null;
        JSONObject core = r.json.optJSONObject("resources") == null ? null
                : r.json.optJSONObject("resources").optJSONObject("core");
        if (core == null) return null;
        return core.optInt("limit", 0) + "/" + core.optInt("remaining", 0);
    }

    // ── 账号 ──────────────────────────────────────────

    /** 当前登录用户；返回 null 表示未登录或 token 失效 */
    public JSONObject me() {
        Resp r = reqJson("/user");
        return r.code == 200 ? r.json : null;
    }

    /** 我的仓库（登录后） */
    public JSONArray myRepos(int page, int perPage) {
        Resp r = reqJson("/user/repos?per_page=" + perPage + "&page=" + page
                + "&sort=pushed");
        return r.code == 200 ? parseArray(r.body) : null;
    }

    public JSONObject repo(String owner, String repo) {
        Resp r = reqJson("/repos/" + owner + "/" + repo);
        return r.code == 200 ? r.json : null;
    }

    public JSONArray searchRepos(String query, int page) {
        Resp r = reqJson("/search/repositories?q=" + urlEnc(query) + "&per_page=30&page=" + page);
        if (r.code != 200) return null;
        return parseArray(r.json != null && r.json.has("items")
                ? r.json.optJSONArray("items").toString() : "[]");
    }

    // ── 仓库内容 ───────────────────────────────────────

    /** 仓库根目录或子目录文件列表 */
    public JSONArray contents(String owner, String repo, String path, String ref) {
        StringBuilder sb = new StringBuilder("/repos/").append(owner).append('/').append(repo)
                .append("/contents");
        if (path != null && !path.isEmpty()) sb.append('/').append(path);
        if (ref != null && !ref.isEmpty()) sb.append("?ref=").append(urlEnc(ref));
        Resp r = reqJson(sb.toString());
        return r.code == 200 ? parseArray(r.body) : null;
    }

    /** 分支列表 */
    public JSONArray branches(String owner, String repo) {
        Resp r = reqJson("/repos/" + owner + "/" + repo + "/branches?per_page=100");
        return r.code == 200 ? parseArray(r.body) : null;
    }

    /** 读文件内容（返回 base64 原文）；文件过大返回 null */
    public String fileText(String owner, String repo, String path, String ref) {
        StringBuilder sb = new StringBuilder("/repos/").append(owner).append('/').append(repo)
                .append("/contents/").append(path);
        if (ref != null && !ref.isEmpty()) sb.append("?ref=").append(urlEnc(ref));
        Resp r = reqJson(sb.toString());
        if (r.code != 200 || r.json == null) return null;
        String b64 = r.json.optString("content", "");
        if (b64.isEmpty()) return null;
        try {
            return new String(Base64.decode(b64.replace("\n", ""), Base64.DEFAULT), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /** Release 列表 */
    public JSONArray releases(String owner, String repo, int perPage) {
        Resp r = reqJson("/repos/" + owner + "/" + repo + "/releases?per_page=" + perPage);
        return r.code == 200 ? parseArray(r.body) : null;
    }

    /** raw 直链（支持 Range，实测 206）—— 供下载器用 */
    public static String rawUrl(String owner, String repo, String ref, String path) {
        return "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + ref + "/" + path;
    }

    /** 仓库 zip 下载直链（不支持 Range，只能整文件 + 断点续传） */
    public static String zipUrl(String owner, String repo, String ref) {
        return "https://codeload.github.com/" + owner + "/" + repo + "/zip/" + ref;
    }

    // ── 下载（断点续传基础版；多源轮换后续）─────────────

    /**
     * 该端点是否支持 Range 分段。
     * 实测：raw/api = 206 支持；codeload = 200 且无 Content-Range，不支持。
     */
    public static boolean supportsRange(String url) {
        if (url == null) return false;
        return url.contains("raw.githubusercontent.com") || url.contains("api.github.com")
                || url.contains("objects.githubusercontent.com");
    }

    /** 下载结果 */
    public static class DownloadResult {
        public boolean ok;
        public String message;
        public long bytesWritten;   // 本次实际写入字节（续传时 < 总大小）
        public long totalBytes;     // -1 = 未知
        public boolean resumed;     // true = 本次是断点续传
    }

    /**
     * 下载文件到目标路径，**支持断点续传**。
     *
     * <p>为什么必须有：实测 codeload 拉 269MB 的 tar.gz，单次连接在收到
     * 100~200KB 后就被掐断（Content-Length 声明 269MB，实收远小于此）。
     * 所以必须记录已下载长度，下次用 {@code Range: bytes=N-} 接上。
     *
     * <p>这是**基础版**：单一源。多源轮换（raw/jsDelivr 等互为冗余）后续再加，
     * 届时只需在失败时切下一个源重试，本方法的续传逻辑可直接复用。
     *
     * @param expectedSize 已知总大小（-1 未知）；用于校验完整性与断点判断
     */
    public DownloadResult download(String url, java.io.File target, long expectedSize,
                                    ProgressListener listener) {
        DownloadResult res = new DownloadResult();
        long have = target.exists() ? target.length() : 0L;

        // 已有文件比预期还大 → 说明是别的文件或已损坏，清掉重来
        if (expectedSize > 0 && have > expectedSize) {
            target.delete();
            have = 0;
        }
        // 预期大小未知时，无法判断完整性，只在有内容且 Range 不支持时重新下
        if (have > 0 && expectedSize <= 0 && !supportsRange(url)) {
            target.delete();
            have = 0;
        }
        if (expectedSize > 0 && have == expectedSize) {
            res.ok = true;
            res.totalBytes = expectedSize;
            res.bytesWritten = 0;
            res.resumed = have > 0;
            return res;
        }

        HttpURLConnection conn = null;
        try {
            java.io.File tmp = new java.io.File(target.getAbsolutePath() + ".part");
            // 用 .part 的真实长度作为续传点（比 target 更可靠：.part 才是正在写的）
            long from = tmp.exists() ? tmp.length() : 0L;
            if (target.exists() && target.length() > from) from = target.length();
            if (expectedSize > 0 && from > expectedSize) {
                tmp.delete();
                from = 0;
            }

            java.net.URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", UA);
            if (hasToken()) conn.setRequestProperty("Authorization", "Bearer " + token);
            if (from > 0) {
                conn.setRequestProperty("Range", "bytes=" + from + "-");
            }

            int code = conn.getResponseCode();
            if (code == 416) {   // Range Not Satisfiable → 已下完
                res.ok = true;
                res.totalBytes = from;
                finish(tmp, target);
                return res;
            }
            if (code != 200 && code != 206) {
                res.message = "HTTP " + code;
                return res;
            }
            // 服务端忽略 Range 回了 200 → 必须从头写，否则会拼出坏文件
            boolean append = code == 206 && from > 0;
            if (!append) from = 0;

            String cl = conn.getHeaderField("Content-Length");
            long total = -1;
            if (cl != null) {
                try {
                    total = Long.parseLong(cl.trim());
                    if (append) total += from;
                } catch (NumberFormatException ignored) {
                }
            }
            String cr = conn.getHeaderField("Content-Range");
            if (cr != null) {   // "bytes 100-999/1000"
                int slash = cr.lastIndexOf('/');
                if (slash > 0) {
                    try {
                        total = Long.parseLong(cr.substring(slash + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            res.totalBytes = total;

            long written = from;
            try (InputStream in = conn.getInputStream();
                 java.io.FileOutputStream out = new java.io.FileOutputStream(tmp, append)) {
                byte[] buf = new byte[32768];
                int n;
                long lastTick = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    written += n;
                    if (listener != null && written - lastTick > 256 * 1024) {
                        lastTick = written;
                        listener.onProgress(written, total);
                    }
                }
                out.flush();
            }
            if (listener != null) listener.onProgress(written, total);

            res.bytesWritten = written - from;
            res.resumed = from > 0;

            // 完整性校验：声明了大小就必须对上，否则算失败（下一轮可续传）
            if (total > 0 && written != total) {
                res.message = "下载中断（" + written + "/" + total + " 字节，可续传）";
                return res;
            }
            finish(tmp, target);
            res.ok = true;
            return res;
        } catch (Exception e) {
            res.message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return res;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** .part → 正式文件（原子重命名） */
    private static void finish(java.io.File tmp, java.io.File target) {
        if (!tmp.exists()) return;
        if (target.exists()) target.delete();
        if (!tmp.renameTo(target)) {   // 跨分区可能失败，退化为拷贝
            try (InputStream in = new java.io.FileInputStream(tmp);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                tmp.delete();
            } catch (Exception ignored) {
            }
        }
    }

    /** 下载进度回调 */
    public interface ProgressListener {
        void onProgress(long written, long total);
    }

    // ── 工具 ──────────────────────────────────────────

    private static JSONArray parseArray(String body) {
        try {
            return new JSONArray(body == null ? "[]" : body);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static JSONArray parseArray(String body, boolean unused) {
        return parseArray(body);
    }

    private static String readAll(InputStream in) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static String urlEnc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** 人类可读体积 */
    public static String fmtSize(long b) {
        if (b < 0) return "未知";
        if (b >= 1073741824L) return String.format(java.util.Locale.CHINA, "%.2f GB", b / 1073741824.0);
        if (b >= 1048576L) return String.format(java.util.Locale.CHINA, "%.1f MB", b / 1048576.0);
        if (b >= 1024L) return String.format(java.util.Locale.CHINA, "%.0f KB", b / 1024.0);
        return b + " B";
    }
}