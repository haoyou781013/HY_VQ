package com.aliya.hy_vq.plugin;

import java.util.HashMap;
import java.util.Map;

/**
 * 插件 manifest（{@code plugin.json}）的解析结果。
 *
 * <h3>为什么手写 JSON 解析</h3>
 * 宿主的 {@code android.jar} 是桩 jar，{@code org.json} 所有方法体都是
 * {@code throw new RuntimeException("Stub!")}。插件 SDK 必须能在无 Android
 * 运行时的情况下编译与自测，故用极简字符串扫描，零依赖。
 *
 * <p>只支持<b>单层平铺</b>结构 —— manifest 本来就不该有嵌套。
 * 数组与嵌套对象会被正确跳过。</p>
 *
 * <h3>完整字段</h3>
 * <pre>
 * {
 *   "id": "github",                     // 必填，[A-Za-z0-9_.-]{1,64}
 *   "name": "GitHub",                   // 必填，显示名
 *   "version": "1.0.0",                 // 必填，SemVer
 *   "entry": "com.aliya...GithubPlugin",// 必填，实现 HyVqPlugin 的全限定类名
 *   "summary": "浏览仓库与 Issue",        // 选填，侧边栏/商店一行说明
 *   "icon": "github",                   // 选填，内置图标名
 *   "order": 120,                       // 选填，侧边栏排序权重
 *   "minHostVersion": 109,              // 选填，最低宿主 versionCode
 *   "author": "haoyou781013",           // 选填
 *   "homepage": "https://..."           // 选填
 * }
 * </pre>
 */
public final class PluginMeta {

    public String id = "";
    public String name = "";
    public String version = "";
    /** 入口类全限定名（manifest 字段名就是 entry） */
    public String entry = "";
    /** 一句话说明 */
    public String summary = "";
    /** 内置图标名 */
    public String icon = "";
    /** 侧边栏排序权重，小的在前 */
    public int order = 500;
    /** 最低宿主 versionCode；0 表示不限 */
    public int minHostVersion = 0;
    public String author = "";
    public String homepage = "";

    /** manifest 原始文本（调试用） */
    public String raw = "";

    /**
     * 解析 plugin.json。
     *
     * @return 解析结果；{@code id}/{@code name}/{@code version}/{@code entry}
     *         任一缺失或 id 非法时返回 {@code null}（宿主应拒绝加载）。
     */
    public static PluginMeta parse(String json) {
        if (json == null) return null;
        Map<String, String> m = scan(json);

        PluginMeta meta = new PluginMeta();
        meta.raw = json;
        meta.id = get(m, "id");
        meta.name = get(m, "name");
        meta.version = get(m, "version");
        meta.entry = get(m, "entry");
        meta.summary = get(m, "summary");
        meta.icon = get(m, "icon");
        meta.author = get(m, "author");
        meta.homepage = get(m, "homepage");
        meta.order = toInt(get(m, "order"), 500);
        meta.minHostVersion = toInt(get(m, "minHostVersion"), 0);

        if (meta.id.isEmpty() || meta.name.isEmpty()
                || meta.version.isEmpty() || meta.entry.isEmpty()) {
            return null;
        }
        // id 白名单：id 会参与文件路径拼接，必须防 ../ 之类
        if (!meta.id.matches("[A-Za-z0-9_.-]{1,64}")) return null;
        return meta;
    }

    /** 该插件能否在指定宿主 versionCode 上运行 */
    public boolean acceptsHost(int hostVersionCode) {
        return minHostVersion <= 0 || hostVersionCode >= minHostVersion;
    }

    private static String get(Map<String, String> m, String k) {
        String v = m.get(k);
        return v == null ? "" : v;
    }

    private static int toInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 扫描单层 JSON 的字符串 / 数字 / 布尔字段。
     * <p>正确跳过数组与嵌套对象（按深度配平，且不把对象内部的引号当 key）。</p>
     */
    private static Map<String, String> scan(String s) {
        Map<String, String> out = new HashMap<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            int kq = s.indexOf('"', i);
            if (kq < 0) break;
            int kEnd = findStringEnd(s, kq);
            if (kEnd < 0) break;
            String key = unescape(s.substring(kq + 1, kEnd));

            int colon = s.indexOf(':', kEnd + 1);
            if (colon < 0) break;

            int p = colon + 1;
            while (p < n && Character.isWhitespace(s.charAt(p))) p++;
            if (p >= n) break;

            char ch = s.charAt(p);
            if (ch == '"') {
                int vEnd = findStringEnd(s, p);
                if (vEnd < 0) break;
                out.put(key, unescape(s.substring(p + 1, vEnd)));
                i = vEnd + 1;
            } else if (ch == '{') {
                i = skipBalanced(s, p, '{', '}');
            } else if (ch == '[') {
                i = skipBalanced(s, p, '[', ']');
            } else {
                int q = p;
                while (q < n) {
                    char cq = s.charAt(q);
                    if (cq == ',' || cq == '}' || Character.isWhitespace(cq)) break;
                    q++;
                }
                out.put(key, s.substring(p, q));
                i = q;
            }
        }
        return out;
    }

    /** 从开括号处跳到配对闭括号之后；字符串内的括号不计入深度 */
    private static int skipBalanced(String s, int openIdx, char open, char close) {
        int depth = 0;
        int i = openIdx;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                int e = findStringEnd(s, i);
                if (e < 0) return s.length();
                i = e + 1;
                continue;
            }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i + 1;
            }
            i++;
        }
        return s.length();
    }

    /** 找字符串字面量的收尾引号（处理反斜杠转义），返回引号下标 */
    private static int findStringEnd(String s, int quoteIdx) {
        int i = quoteIdx + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '"') return i;
            i++;
        }
        return -1;
    }

    /** 只处理 JSON 最常用的几个转义，够 manifest 用 */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char d = s.charAt(++i);
                switch (d) {
                    case 'n':  b.append('\n'); break;
                    case 't':  b.append('\t'); break;
                    case 'r':  b.append('\r'); break;
                    case '"':  b.append('"');  break;
                    case '\\': b.append('\\'); break;
                    case '/':  b.append('/');  break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            try {
                                b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (Exception e) { b.append(d); }
                        } else {
                            b.append(d);
                        }
                        break;
                    default: b.append(d);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    @Override
    public String toString() {
        return id + "@" + version + " (order " + order + ")";
    }
}
