package com.aliya.hy_vq.gacha;

import android.content.Context;

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
 * <p><b>登录方式</b>：扫码。调用 createQRLogin 拿到官方二维码页面地址，
 * 用 WebView 直接展示（因此不需要二维码生成库），用户用米游社 App 扫码确认，
 * 再轮询 queryQRLoginStatus 取得凭证。</p>
 *
 * <p>相比自建密码登录：不需要 DS 动态签名（salt 随版本失效）、设备指纹与短信验证。</p>
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

    // ══════════════════════════════════════════════
    //  ★ 扫码登录（实测可行，且不需要 DS 签名）
    // ══════════════════════════════════════════════

    /** 米游社通行证 app_id：必须以请求头 x-rpc-app_id 发送，否则 createQRLogin 报 -3005 */
    public static final String MIYOUSHE_APP_ID = "bll8iq97cem8";

    private static final String CREATE_QR_URL =
            "https://passport-api.mihoyo.com/account/ma-cn-passport/app/createQRLogin";
    private static final String QUERY_QR_URL =
            "https://passport-api.mihoyo.com/account/ma-cn-passport/app/queryQRLoginStatus";

    private static final String PREF = "app_settings";
    private static final String KEY_DEVICE_ID = "miyoushe_device_id";

    /**
     * 生成并持久化设备 ID —— <b>这是能否绕开风控的关键</b>。
     *
     * <p>实测：用随机 UUID 时 createQRLogin 能成功，但紧接着查扫码状态返回
     * {@code -3503「当前设备或网络环境存在风险」}；
     * 换成由 ANDROID_ID 经 {@code UUID.nameUUIDFromBytes}（MD5 派生 UUID v3）
     * 生成的规范值后，风控即解除（retcode 0）。</p>
     *
     * <p>必须持久化：每次生成新值同样会被判为异常设备。</p>
     */
    public static String deviceId(Context ctx) {
        try {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String v = sp.getString(KEY_DEVICE_ID, null);
            if (v != null && !v.isEmpty()) return v;
            String androidId = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            if (androidId == null || androidId.isEmpty()) {
                androidId = java.util.UUID.randomUUID().toString().replace("-", "");
            }
            String uuid = java.util.UUID.nameUUIDFromBytes(
                    androidId.getBytes(StandardCharsets.UTF_8)).toString();
            sp.edit().putString(KEY_DEVICE_ID, uuid).apply();
            return uuid;
        } catch (Throwable t) {
            return java.util.UUID.randomUUID().toString();
        }
    }

    /** 扫码会话 */
    public static class QrSession {
        /** 官方二维码页面地址（WebView 直接加载即可，无需二维码库） */
        public String url = "";
        public String ticket = "";
        /** Created（待扫码）/ Scanned（已扫码待确认）/ Confirmed（已确认） */
        public String status = "";
        public String stoken = "";
        public String ltokenV2 = "";
        public String mid = "";
        public String accountId = "";
        public String error = null;
        public boolean confirmed() { return "Confirmed".equalsIgnoreCase(status); }
    }

    /** 创建扫码会话 */
    public static QrSession createQrLogin(Context ctx) {
        QrSession s = new QrSession();
        try {
            JSONObject body = new JSONObject();
            body.put("app_id", MIYOUSHE_APP_ID);
            String resp = postWithAppId(CREATE_QR_URL, body.toString(), deviceId(ctx));
            if (resp == null) { s.error = "网络请求失败"; return s; }
            JSONObject o = new JSONObject(resp);
            if (o.optInt("retcode", 0) != 0) {
                s.error = friendly(o.optInt("retcode"), o.optString("message", ""));
                return s;
            }
            JSONObject d = o.optJSONObject("data");
            if (d == null) { s.error = "服务端未返回数据"; return s; }
            s.url = d.optString("url", "");
            s.ticket = d.optString("ticket", "");
            s.status = "Created";
            if (s.url.isEmpty() || s.ticket.isEmpty()) s.error = "二维码地址或票据为空";
        } catch (Throwable t) {
            s.error = t.getMessage() == null ? t.toString() : t.getMessage();
        }
        return s;
    }

    /** 查询扫码状态（供轮询） */
    public static QrSession queryQrStatus(Context ctx, String ticket) {
        QrSession s = new QrSession();
        s.ticket = ticket;
        try {
            JSONObject body = new JSONObject();
            body.put("app_id", MIYOUSHE_APP_ID);
            body.put("ticket", ticket);
            body.put("token_types", new org.json.JSONArray().put(1));
            String resp = postWithAppId(QUERY_QR_URL, body.toString(), deviceId(ctx));
            if (resp == null) { s.error = "网络请求失败"; return s; }
            JSONObject o = new JSONObject(resp);
            if (o.optInt("retcode", 0) != 0) {
                s.error = friendly(o.optInt("retcode"), o.optString("message", ""));
                return s;
            }
            JSONObject d = o.optJSONObject("data");
            if (d == null) { s.error = "服务端未返回数据"; return s; }
            s.status = d.optString("status", "");
            org.json.JSONArray tokens = d.optJSONArray("tokens");
            if (tokens != null) {
                for (int i = 0; i < tokens.length(); i++) {
                    JSONObject t = tokens.optJSONObject(i);
                    if (t == null) continue;
                    String tk = t.optString("token", "");
                    String type = t.optString("token_type", "");
                    if ("1".equals(type)) s.stoken = tk;
                    else if ("2".equals(type)) s.ltokenV2 = tk;
                    else if (s.stoken.isEmpty()) s.stoken = tk;
                }
            }
            JSONObject ui = d.optJSONObject("user_info");
            if (ui != null) {
                s.mid = ui.optString("mid", "");
                s.accountId = ui.optString("account_id", "");
            }
        } catch (Throwable t) {
            s.error = t.getMessage() == null ? t.toString() : t.getMessage();
        }
        return s;
    }

    /** 把扫码得到的凭证拼成 Cookie */
    public static String cookieFrom(QrSession s) {
        StringBuilder sb = new StringBuilder();
        if (!s.stoken.isEmpty()) sb.append("stoken=").append(s.stoken).append("; ");
        if (!s.mid.isEmpty()) sb.append("mid=").append(s.mid).append("; ");
        if (!s.ltokenV2.isEmpty()) sb.append("ltoken_v2=").append(s.ltokenV2).append("; ");
        if (!s.accountId.isEmpty()) {
            sb.append("account_id=").append(s.accountId).append("; ");
            sb.append("ltuid=").append(s.accountId).append("; ");
        }
        return sb.toString();
    }

    /** 带 x-rpc-app_id 的 POST（扫码接口必需此头） */
    private static String postWithAppId(String url, String body, String deviceId) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("x-rpc-app_version", "2.111.0");
        c.setRequestProperty("x-rpc-client_type", "5");
        c.setRequestProperty("x-rpc-app_id", MIYOUSHE_APP_ID);
        c.setRequestProperty("x-rpc-device_id", deviceId);
        c.setRequestProperty("X-Requested-With", "com.mihoyo.hyperion");
        c.setRequestProperty("Referer", "https://webstatic.mihoyo.com");
        c.setRequestProperty("Origin", "https://webstatic.mihoyo.com");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(c);
    }

    /**
     * Cookie 摘要（<b>脱敏</b>）：只显示字段名与其值长度，不输出值本身。
     * <p>用于界面提示与排错 —— 既能看出「有哪些凭证」，又不会泄露。</p>
     */
    public static String summarize(String cookie) {
        if (cookie == null || cookie.isEmpty()) return "（空）";
        StringBuilder sb = new StringBuilder();
        for (String kv : cookie.split(";")) {
            String t = kv.trim();
            if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String k = t.substring(0, eq).trim();
            String v = t.substring(eq + 1).trim();
            if (k.isEmpty()) continue;
            if (sb.length() > 0) sb.append("、");
            sb.append(k).append("(").append(v.length()).append(")");
        }
        return sb.length() == 0 ? "（无有效字段）" : sb.toString();
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
