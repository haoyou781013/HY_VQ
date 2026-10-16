package com.aliya.hy_vq.plugin;

/**
 * 插件 manifest（{@code plugin.json}）的解析结果。
 *
 * <p><b>为什么手写解析</b>：编译期用的 {@code android.jar} 是桩 jar，
 * {@code org.json} 的方法体全是 {@code throw new RuntimeException("Stub!")}，
 * 运行期才换成真实现。所以<b>构建期任何用 org.json 的代码都跑不起来</b>
 * （这坑在写 gh 的响应解析时已踩过）。
 *
 * <p>manifest 格式极简（单层对象、字符串值），正则提取足够且零依赖：
 * <pre>
 * {
 *   "id": "github",
 *   "name": "GitHub",
 *   "version": "1.0.0",
 *   "entry": "com.aliya.hy_vq.plugins.github.GithubPlugin"
 * }
 * </pre>
 */
public final class PluginMeta {

    public String id = "";
    public String name = "";
    public String version = "0.0.0";
    /** 入口类全限定名 */
    public String entryClass = "";
    /** 一句话说明（可选） */
    public String description = "";

    public static PluginMeta parse(String json) {
        PluginMeta m = new PluginMeta();
        if (json == null) return m;
        m.id = str(json, "id");
        m.name = str(json, "name");
        m.version = str(json, "version");
        m.entryClass = str(json, "entry");
        m.description = str(json, "description");
        return m;
    }

    /** 提取 {@code "key": "value"} 里的 value */
    private static String str(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return "";
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return "";
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return "";
        return unescape(json.substring(q1 + 1, q2));
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        return s.replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t");
    }

    @Override
    public String toString() {
        return id + "@" + version;
    }
}