package com.aliya.hy_vq.gacha;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 米游社账号登录 → 生成抽卡 authkey。
 *
 * <p><b>为什么用 WebView 而不是自己实现登录接口</b>：
 * 米哈游密码登录需要 DS 动态签名（salt 随米游社版本变化，会持续失效）、
 * 设备指纹、以及新设备短信验证。而网页登录页本身就是官方实现，
 * 让 WebView 承载它可以省掉全部这些，我们只从 CookieManager 取结果。</p>
 *
 * <p><b>关键实测结论</b>：{@code POST api-takumi.mihoyo.com/binding/api/genAuthKey}
 * 在无 Cookie 时返回
 * {@code retcode:-100 "登录状态失效，请重新登录"} ——
 * <b>而不是"签名错误"</b>，说明该接口<b>不校验 DS</b>，
 * 只要带上有效的登录 Cookie 即可。这大幅降低了实现复杂度。</p>
 *
 * <p><b>隐私</b>：账号密码只提交给官方网页，本应用不接触；
 * Cookie 仅保存在本机，仅用于向米哈游官方接口换取 authkey。</p>
 */
public final class MiyousheAuth {

    private MiyousheAuth() {}

    /** 登录页（米游社通行证） */
    public static final String LOGIN_URL = "https://user.mihoyo.com/";

    /** 取 Cookie 的目标域（与 genAuthKey 同域） */
    private static final String API_HOST = "https://api-takumi.mihoyo.com";

    /** 生成 authkey 的接口（实测不需要 DS 签名） */
    private static final String GEN_AUTHKEY_URL =
            "https://api-takumi.mihoyo.com/binding/api/genAuthKey";

    /** 获取游戏角色列表 */
    private static final String ROLES_URL =
            "https://api-takumi.mihoyo.com/binding/api/getUserGameRolesByCookie";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; M2101K9C Build/TKQ1.220829.002; wv) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/108.0.5359.128 "
            + "Mobile Safari/537.36 miHoYoBBS/2.111.0";

    // ══════════════════════════════════════════════
    //  WebView 登录
    // ══════════════════════════════════════════════

    /** 登录状态回调（主线程） */
    public interface LoginCallback {
        /** @param cookie 登录后的完整 Cookie；失败时为 null */
        void onFinished(String cookie, String error);
    }

    /**
     * 配置用于登录的 WebView。
     *
     * @param onLoginSuccess 检测到登录态后回调（把 WebView 交给调用方关闭）
     */
    @SuppressLint("SetJavaScriptEnabled")
    public static void setupWebView(final WebView web, final Activity host,
                                    final LoginCallback cb) {
        commonSetup(web);

        web.setWebViewClient(new WebViewClient() {
            private boolean done = false;

            @Override
            public void onPageFinished(WebView view, String url) {
                String cookie = readCookie();
                if (hasLoginCookie(cookie)) {
                    if (watcher != null) watcher.onLoginDetected(cookie);
                    if (!done) {
                        done = true;
                        cb.onFinished(cookie, null);
                    }
                }
            }
        });
        web.loadUrl(LOGIN_URL);
    }

    /**
     * 仅加载登录页，<b>不做自动嗅探</b>。
     * <p>因为 WebView 里可能残留旧的无效 Cookie，自动触发会立刻拿它换 key
     * 并报「登录状态失效」，让用户误以为登录失败。改为由界面上的按钮手动触发。</p>
     */
    public static void setupWebViewQuiet(final WebView web, final Activity host) {
        commonSetup(web);
        web.setWebViewClient(new WebViewClient());
        web.loadUrl(LOGIN_URL);
    }

    /** 登录态观察者（仅用于界面提示，不自动触发换 key） */
    public interface LoginWatcher {
        void onLoginDetected(String cookie);
    }

    private static LoginWatcher watcher;

    public static void setLoginWatcher(LoginWatcher w) {
        watcher = w;
    }

    /**
     * 旧版自动嗅探（保留但不再作为主流程）。
     * <p>仅当确实需要「登录后自动继续」时使用；注意残留 Cookie 会导致误判。</p>
     */
    public static void setupWebViewLegacy(final WebView web, final Activity host,
                                          final LoginCallback cb) {
        setupWebView(web, host, cb);
    }

    private static void commonSetup(WebView web) {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(UA);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);
    }

    /** 读取 api 域的 Cookie（登录票据通常在这些域下） */
    public static String readCookie() {
        try {
            CookieManager cm = CookieManager.getInstance();
            StringBuilder sb = new StringBuilder();
            for (String host : new String[]{
                    API_HOST,
                    "https://passport-api.mihoyo.com",
                    "https://user.mihoyo.com",
                    "https://bbs.mihoyo.com",
                    "https://webstatic.mihoyo.com"}) {
                String c = cm.getCookie(host);
                if (c != null && !c.isEmpty()) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(c);
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 判断 Cookie 是否已含登录态 */
    public static boolean hasLoginCookie(String cookie) {
        if (cookie == null || cookie.isEmpty()) return false;
        for (String k : new String[]{"ltoken", "ltoken_v2", "stoken", "cookie_token",
                "login_ticket", "account_id", "ltuid"}) {
            if (cookie.contains(k + "=")) return true;
        }
        return false;
    }

    /** Cookie 摘要（脱敏，用于界面显示与排错） */
    public static String summarize(String cookie) {
        if (cookie == null || cookie.isEmpty()) return "（空）";
        StringBuilder sb = new StringBuilder();
        for (String kv : cookie.split(";")) {
            String s = kv.trim();
            if (s.isEmpty()) continue;
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            String k = s.substring(0, eq).trim();
            String v = s.substring(eq + 1).trim();
            if (k.isEmpty()) continue;
            // 只显示存在的键名与长度，不泄露值
            if (sb.length() > 0) sb.append("、");
            sb.append(k).append("(").append(v.length()).append(")");
        }
        return sb.length() == 0 ? "（无有效字段）" : sb.toString();
    }

    // ══════════════════════════════════════════════
    //  用 Cookie 换取 authkey
    // ══════════════════════════════════════════════

    /** authkey 获取结果 */
    public static class AuthKeyResult {
        public String authKey = null;
        public String error = null;
        /** 服务端原始返回，便于排错 */
        public String raw = "";
    }

    /**
     * 生成抽卡用 authkey。
     *
     * <p>实测该接口不校验 DS，只需有效 Cookie；requestBody 只需 auth_appid 与 game_biz。</p>
     */
    public static AuthKeyResult genAuthKey(String cookie, String gameBiz) {
        AuthKeyResult r = new AuthKeyResult();
        try {
            JSONObject body = new JSONObject();
            body.put("auth_appid", "webview_gacha");
            body.put("game_biz", gameBiz == null || gameBiz.isEmpty() ? "hk4e_cn" : gameBiz);
            String resp = post(GEN_AUTHKEY_URL, body.toString(), cookie);
            r.raw = resp;
            if (resp == null) {
                r.error = "网络请求失败";
                return r;
            }
            JSONObject o = new JSONObject(resp);
            int code = o.optInt("retcode", 0);
            if (code != 0) {
                r.error = friendly(code, o.optString("message", ""));
                return r;
            }
            JSONObject data = o.optJSONObject("data");
            if (data != null) {
                // 不同版本字段名可能是 authkey 或 auth_key
                String k = data.optString("authkey", "");
                if (k.isEmpty()) k = data.optString("auth_key", "");
                if (!k.isEmpty()) r.authKey = k;
            }
            if (r.authKey == null) r.error = "服务端未返回 authkey（字段为空）";
        } catch (Throwable t) {
            r.error = t.getMessage() == null ? t.toString() : t.getMessage();
        }
        return r;
    }

    /**
     * 查询该账号下的游戏角色（用于让用户选择要分析哪个号）。
     * 同样只需 Cookie。
     */
    public static String queryRoles(String cookie) {
        try {
            return get(ROLES_URL, cookie);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String friendly(int code, String msg) {
        switch (code) {
            case -100:
                return "登录状态已失效 —— 请重新登录米游社账号";
            case -101:
                return "登录票据过期 —— 请重新登录";
            case -110:
                return "账号与服务器区域不符";
            case -111:
                return "需要重新验证 —— 请在米游社完成设备验证后再试";
            default:
                return (msg == null || msg.isEmpty() ? "未知错误" : msg) + "（retcode " + code + "）";
        }
    }

    // ══════════════════════════════════════════════
    //  HTTP
    // ══════════════════════════════════════════════

    private static String post(String url, String body, String cookie) throws Exception {
        HttpURLConnection c = open(url, "POST", cookie);
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(c);
    }

    private static String get(String url, String cookie) throws Exception {
        return read(open(url, "GET", cookie));
    }

    private static HttpURLConnection open(String url, String method, String cookie) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("x-rpc-app_version", "2.111.0");
        c.setRequestProperty("x-rpc-client_type", "5");
        c.setRequestProperty("X-Requested-With", "com.mihoyo.hyperion");
        c.setRequestProperty("Referer", "https://webstatic.mihoyo.com");
        c.setRequestProperty("Origin", "https://webstatic.mihoyo.com");
        c.setRequestProperty("Accept", "application/json");
        if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        return c;
    }

    private static String read(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        if (in == null) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } finally {
            c.disconnect();
        }
        return sb.toString();
    }
}
