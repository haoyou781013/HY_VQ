package com.aliya.hy_vq.gacha;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * 抽卡链接自动获取（通过 Shizuku 以 adb/shell 权限读取系统日志）。
 *
 * <p><b>原理</b>：游戏打开「祈愿 → 历史记录」时，会把含 {@code authkey} 的网址
 * 写进系统日志。以 shell 权限执行 {@code logcat} 即可读到，再正则提取该网址。</p>
 *
 * <p><b>为什么用 Shizuku 而不是 root</b>：Shizuku 通过用户自行启动的 adb 服务授予
 * 应用 shell(uid 2000) 权限，足以读取 {@code logcat}，但<b>不涉及 root</b>，
 * 权限边界清晰、可随时在 Shizuku 里撤销。这也是社区同类工具采用的方案。</p>
 *
 * <p><b>为什么不用 VPN 抓包</b>：那需要用户安装 CA 证书做中间人解密；
 * 而日志里本来就有明文网址，读日志更简单、无侵入。</p>
 */
public final class GachaLinkFetcher {

    /** Shizuku 可用状态 */
    public enum State {
        /** 未安装 Shizuku */
        NOT_INSTALLED,
        /** 已安装但服务未运行（用户没启动过 Shizuku） */
        NOT_RUNNING,
        /** 服务在运行但本应用未获授权 */
        NO_PERMISSION,
        /** 就绪，可以读取日志 */
        READY
    }

    /** Shizuku 权限请求码 */
    public static final int REQ_SHIZUKU_PERMISSION = 2001;

    private GachaLinkFetcher() {}

    // ── 匹配含 authkey 的抽卡网页地址 ──
    // 国服 webstatic.mihoyo.com / 国际服 webstatic.hoyoverse.com 都覆盖
    private static final Pattern P_URL = Pattern.compile(
            "(https://webstatic\\.(?:mihoyo|hoyoverse)\\.com/[^\\s\"'<>\\\\]*)");
    private static final Pattern P_AUTHKEY = Pattern.compile("authkey=");

    private static final Handler UI = new Handler(Looper.getMainLooper());

    // ══════════════════════════════════════════════
    //  状态检查
    // ══════════════════════════════════════════════

    /** 判断 Shizuku 是否已安装（未安装时引导用户去装） */
    public static boolean isShizukuInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 综合判断当前状态 */
    public static State check(Context ctx) {
        if (!isShizukuInstalled(ctx)) return State.NOT_INSTALLED;
        try {
            if (!Shizuku.pingBinder()) return State.NOT_RUNNING;
            // Android 11+ 需要显式授权；旧版本用 Shizuku 直接可用
            if (Shizuku.isPreV11()) return State.READY;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return State.READY;
            return State.NO_PERMISSION;
        } catch (Throwable t) {
            return State.NOT_RUNNING;
        }
    }

    /** 申请 Shizuku 权限（结果通过 addPermissionListener 回调） */
    public static void requestPermission(int requestCode) {
        try {
            Shizuku.requestPermission(requestCode);
        } catch (Throwable ignored) {
        }
    }

    /** 注册权限结果监听（调用方需在适当时机 remove） */
    public static void addPermissionListener(
            Shizuku.OnRequestPermissionResultListener l) {
        try {
            Shizuku.addRequestPermissionResultListener(l);
        } catch (Throwable ignored) {
        }
    }

    public static void removePermissionListener(
            Shizuku.OnRequestPermissionResultListener l) {
        try {
            Shizuku.removeRequestPermissionResultListener(l);
        } catch (Throwable ignored) {
        }
    }

    /** 状态对应的中文说明与操作建议 */
    public static String describe(State st) {
        switch (st) {
            case NOT_INSTALLED:
                return "未检测到 Shizuku。请先安装 Shizuku 应用（开源，可从应用商店或官网获取），"
                        + "再回到这里。";
            case NOT_RUNNING:
                return "Shizuku 尚未启动。请打开 Shizuku 应用，按它提示启动服务"
                        + "（通常经由无线调试或电脑授权），然后回到这里。";
            case NO_PERMISSION:
                return "Shizuku 已就绪，但本应用还没有获得授权。点下方按钮授权后即可自动读取。";
            case READY:
            default:
                return "Shizuku 已就绪，可以直接读取系统日志提取抽卡链接。";
        }
    }

    // ══════════════════════════════════════════════
    //  扫描
    // ══════════════════════════════════════════════

    /** 扫描结果回调（均在主线程） */
    public interface Callback {
        /**
         * @param url 找到的链接（可能多个，取最新的一个）；找不到时为 null
         * @param all 本次找到的全部链接（去重）
         * @param error 出错信息；成功时为 null
         */
        void onResult(String url, Set<String> all, String error);
    }

    /**
     * 扫描系统日志提取抽卡链接。
     *
     * <p>在子线程执行 {@code logcat -d -t N}，避免全量 dump 导致卡顿/超时；
     * 只解析含 authkey 的抽卡网页地址。</p>
     *
     * @param lines 读取的日志尾部行数（越大回溯越久，但越慢）
     */
    public static void scan(final Context ctx, final int lines, final Callback cb) {
        new Thread(() -> {
            Set<String> found = new LinkedHashSet<>();
            String err = null;
            Process p = null;
            try {
                if (check(ctx) != State.READY) {
                    // 状态不对时交由上层用 check() 给出具体引导
                    err = "Shizuku 尚未就绪";
                } else {
                    // 以 shell 权限执行；-d 表示读取缓冲区后退出
                    String cmd = "logcat -d -t " + Math.max(200, Math.min(lines, 20000));
                    // 经 sh -c 执行，便于统一写法；Shizuku 会以 shell(uid 2000) 身份启动
                    p = newShellProcess(new String[]{"sh", "-c", cmd});
                    if (p == null) {
                        err = "无法启动日志读取进程（Shizuku 权限不足）";
                    } else {
                        InputStream in = p.getInputStream();
                        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                        String line;
                        int guard = 0;
                        while ((line = br.readLine()) != null && guard++ < 200000) {
                            // 快速预筛：绝大多数行不含 authkey，先跳过
                            if (line.indexOf("authkey") < 0) continue;
                            collect(line, found);
                        }
                        try { p.waitFor(); } catch (Throwable ignored) { }
                        if (found.isEmpty()) {
                            err = null;   // 找不到不算错误，由上层提示用户先打开游戏历史记录
                        }
                    }
                }
            } catch (Throwable t) {
                err = t.getMessage() == null ? t.toString() : t.getMessage();
            } finally {
                if (p != null) {
                    try { p.destroy(); } catch (Throwable ignored) { }
                }
            }

            final Set<String> fall = found;
            final String ferr = err;
            String best = null;
            // 后出现的通常更新，取最后一个
            for (String s : fall) best = s;
            final String fbest = best;
            UI.post(() -> cb.onResult(fbest, fall, ferr));
        }, "HyVqGachaScan").start();
    }

    /**
     * 以 shell(uid 2000) 身份启动进程。
     *
     * <p>Shizuku 的 {@code newProcess} 是 private（{@code ShizukuRemoteProcess} 的构造
     * 也不对外），因此这里用反射调用。Shizuku 属于第三方库的类，不受 Android
     * hidden API 黑名单限制，反射可用。</p>
     */
    private static Process newShellProcess(String[] cmd) throws Exception {
        java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        Object r = m.invoke(null, cmd, null, null);
        if (r instanceof Process) return (Process) r;
        throw new IllegalStateException("Shizuku 未返回进程对象");
    }

    /** 从一行日志里提取抽卡链接（可能含多个，也可能被转义） */
    private static void collect(String line, Set<String> out) {
        try {
            Matcher m = P_URL.matcher(line);
            while (m.find()) {
                String url = m.group(1);
                if (!P_AUTHKEY.matcher(url).find()) continue;   // 必须带 authkey
                // 去掉可能的转义与尾部杂字符
                url = url.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&");
                int cut = url.length();
                for (int i = 0; i < url.length(); i++) {
                    char c = url.charAt(i);
                    if (c == '"' || c == '\'' || c == '<' || c == '>' || c == '\\' || c == ' ') {
                        cut = i;
                        break;
                    }
                }
                url = url.substring(0, cut);
                if (url.contains("authkey=") && url.length() > 80) out.add(url);
            }
        } catch (Throwable ignored) {
        }
    }
}
