package com.aliya.hy_vq.github;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * GitHub OAuth Device Flow 登录。
 *
 * <p><b>为什么用 Device Flow 而不是网页登录表单：</b>调研结论（2026-10-12，
 * AOSP 源码 + 真实大陆网络实测交叉验证）——
 * <ul>
 *   <li>走网页表单必须访问 {@code github.com}，实测成功率仅 10%；</li>
 *   <li>登录表单 POST 是绝对 URL 打到 {@code github.com}，经公共代理会跨域失败；</li>
 *   <li>即使登录成功，Cookie 归属由<b>响应 host</b> 决定，经代理登录则 Cookie
 *       绑到代理域名，{@code github.com} 永远拿不到会话；</li>
 *   <li>跨 App 共享 Cookie（如从 Edge 导入）在 Android 上<b>无任何 API 可行</b>
 *       ——{@code CookieManager} 只管本 App，{@code CookieManagerCompat}
 *       也只有只读方法。</li>
 * </ul>
 *
 * <p>Device Flow 只需访问 {@code api.github.com}（实测 100% 通畅）与
 * {@code github.com/login/device}（用户在浏览器输入一次性码），
 * 换取 token 后一切请求走 Bearer，<b>完全不依赖 Cookie</b>。
 * 这正是 GitHub 官方 App 在国内稳定的根本原因。
 *
 * <p><b>安全说明：</b>token 只存在本机 SharedPreferences，不随 APK 分发，
 * 也不会出现在源码里（用户自己粘贴或自己输入）。
 */
public class GhDeviceFlow {

    /**
     * OAuth App 的 client_id —— Device Flow 属公开客户端，无需 client_secret。
     *
     * <p><b>留空是有意的</b>：未注册 OAuth App 时 GitHub 对 Device Flow 返回
     * {@code {"error":"Not Found"}}（2026-10-12 实测），功能不可用。
     * 借用他人（如 gh CLI）的 client_id 技术上能跑通，但那是把请求记到别人
     * 名下、且对方随时可吊销，故不采用。
     *
     * <p><b>启用步骤</b>（一次性，约 1 分钟）：
     * <ol>
     *   <li>登录 github.com → Settings → Developer settings → OAuth Apps
     *       → New OAuth App</li>
     *   <li>Application name 填 {@code HY_VQ}；Homepage URL 与
     *       Authorization callback URL <b>都填</b> {@code https://github.com}</li>
     *       （Device Flow 用不到回调，但 GitHub 表单要求非空）</li>
     *   <li>创建后页面显示 <b>Client ID</b>，填到下面这个常量</li>
     * </ol>
     * 填好后 Device Flow 登录即可用；未填时仍可走
     * {@link #savePat} 粘贴 PAT 的方式登录，功能不受影响。
     */
    private static final String CLIENT_ID = "";

    private static final String DEVICE_CODE_URL =
            "https://github.com/login/device/code";
    private static final String ACCESS_TOKEN_URL =
            "https://github.com/login/oauth/access_token";
    private static final String VERIFY_PAGE =
            "https://github.com/login/device";

    private static final String PREFS = "hyvq_github";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_LOGIN = "login";

    private final Context ctx;

    public GhDeviceFlow(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    // ── token 存取 ──────────────────────────────────────

    /** 读取已保存的 token；空串表示未登录 */
    public String token() {
        return prefs().getString(KEY_TOKEN, "");
    }

    public boolean isLoggedIn() {
        return !token().isEmpty();
    }

    /** OAuth App Client ID 是否已配置（决定 Device Flow 能否使用） */
    public static boolean deviceFlowConfigured() {
        return !CLIENT_ID.isEmpty();
    }

    /** 已登录用户的登录名（缓存，避免每次都调 API） */
    public String loginName() {
        return prefs().getString(KEY_LOGIN, "");
    }

    public void saveToken(String token, String login) {
        prefs().edit()
                .putString(KEY_TOKEN, token == null ? "" : token.trim())
                .putString(KEY_LOGIN, login == null ? "" : login)
                .apply();
    }

    public void logout() {
        prefs().edit().remove(KEY_TOKEN).remove(KEY_LOGIN).apply();
    }

    /** 用户手动粘贴 PAT（Personal Access Token）*/
    public void savePat(String pat, String login) {
        saveToken(pat, login);
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ── Device Flow 三步 ────────────────────────────────

    /** 第 1 步的结果 */
    public static class Start {
        public boolean ok;
        public String message;
        public String deviceCode;    // App 侧轮询用（不要展示给用户）
        public String userCode;      // 要用户输入的短码，如 "WDJB-MJHT"
        public String verifyUrl;
        public int interval;         // 建议轮询间隔（秒）
        public int expiresIn;        // 有效期（秒）
    }

    /**
     * 第 1 步：向 GitHub 申请设备码。
     *
     * @param scope 逗号分隔的权限。默认 {@code repo} —— 读私有仓库/管理 Issue 与 Release。
     *              若只读公开内容，用 {@code public_repo} 或空串。
     */
    public Start start(String scope) {
        Start st = new Start();
        if (CLIENT_ID.isEmpty()) {
            st.message = "尚未配置 OAuth App（需填入 Client ID），"
                    + "请改用「粘贴 Token」方式登录";
            return st;
        }
        java.net.HttpURLConnection conn = null;
        try {
            conn = post(DEVICE_CODE_URL,
                    "client_id=" + CLIENT_ID
                            + "&scope=" + GhApi.urlEnc(scope == null ? "repo" : scope));
            if (conn.getResponseCode() != 200) {
                st.message = "申请失败（HTTP " + conn.getResponseCode() + "）";
                return st;
            }
            String body = readAll(conn.getInputStream());
            JSONObject j = new JSONObject(body);
            st.deviceCode = j.optString("device_code", "");
            st.userCode = j.optString("user_code", "");
            st.verifyUrl = j.optString("verification_uri", VERIFY_PAGE);
            st.interval = j.optInt("interval", 5);
            st.expiresIn = j.optInt("expires_in", 900);
            st.ok = !st.deviceCode.isEmpty() && !st.userCode.isEmpty();
            if (!st.ok) st.message = "GitHub 未返回设备码：" + body;
        } catch (Exception e) {
            st.message = "网络不可达：" + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return st;
    }

    /** 第 2 步的单次轮询结果 */
    public static class Poll {
        public boolean done;        // true = 已拿到 token
        public boolean pending;     // true = 用户还没确认，继续等
        public boolean slowDown;    // true = 轮询太快，需要放慢
        public boolean expired;     // true = 设备码已过期
        public boolean denied;      // true = 用户拒绝了授权
        public String token;
        public String message;
    }

    /**
     * 第 2 步：轮询换取 token。需按 {@code interval} 秒间隔反复调用。
     *
     * <p>GitHub 的响应体是 {@code application/x-www-form-urlencoded} 而非 JSON，
     * 这里手工解析 {@code key=value&...}（也兼容 JSON 形态）。
     */
    public Poll poll(String deviceCode) {
        Poll p = new Poll();
        java.net.HttpURLConnection conn = null;
        try {
            conn = post(ACCESS_TOKEN_URL,
                    "client_id=" + CLIENT_ID
                            + "&device_code=" + GhApi.urlEnc(deviceCode)
                            + "&grant_type=urn:ietf:params:oauth:grant-type:device_code");
            String body = readAll(conn.getInputStream());
            String error = "", accessToken = "";
            if (body.trim().startsWith("{")) {
                JSONObject j = new JSONObject(body);
                error = j.optString("error", "");
                accessToken = j.optString("access_token", "");
            } else {
                for (String kv : body.split("&")) {
                    int i = kv.indexOf('=');
                    if (i <= 0) continue;
                    String k = kv.substring(0, i);
                    String v = urlDec(kv.substring(i + 1));
                    if (k.equals("error")) error = v;
                    else if (k.equals("access_token")) accessToken = v;
                }
            }
            if (!accessToken.isEmpty()) {
                p.done = true;
                p.token = accessToken;
            } else if ("authorization_pending".equals(error)) {
                p.pending = true;
            } else if ("slow_down".equals(error)) {
                p.slowDown = true;
            } else if ("expired_token".equals(error)) {
                p.expired = true;
                p.message = "设备码已过期，请重新发起";
            } else if ("access_denied".equals(error)) {
                p.denied = true;
                p.message = "你拒绝了授权";
            } else {
                p.message = error.isEmpty() ? body : error;
            }
        } catch (Exception e) {
            p.message = "网络不可达：" + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return p;
    }

    /** 第 3 步：用 token 换登录名，验证 token 有效 */
    public String verifyAndGetLogin(String token) {
        GhApi api = new GhApi(ctx, token);
        JSONObject me = api.me();
        if (me == null) return "";
        return me.optString("login", "");
    }

    // ── 工具 ──────────────────────────────────────────

    private static java.net.HttpURLConnection post(String url, String body) throws Exception {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", "HY_VQ-Android");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (java.io.OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes("UTF-8"));
            out.flush();
        }
        return conn;
    }

    private static String readAll(java.io.InputStream in) {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String urlDec(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}