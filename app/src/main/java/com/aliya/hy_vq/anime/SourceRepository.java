package com.aliya.hy_vq.anime;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 源仓库（数据层）—— 源列表的加载 / 导入 / 过滤配置。
 *
 * <h3>落实决策 2</h3>
 * <ul>
 *   <li><b>默认不自动拉取</b>：{@link #load()} 只读本地文件，**绝不联网**。
 *       联网只发生在用户主动点"导入"时（{@link #importFrom}）。</li>
 *   <li><b>仓库地址可改</b>：存在偏好里，不是硬编码常量（用户可以换源）。</li>
 * </ul>
 *
 * <h3>落实决策 4</h3>
 * 过滤配置：开关 + 自定义屏蔽源列表；默认内置 {@link SourceFilter#DEFAULT_BLOCKED}，
 * 用户可以删除。
 */
public class SourceRepository {

    /** 默认订阅地址（ani-subs 的分发入口）。仅作为**默认值**，用户可改。 */
    public static final String DEFAULT_REPO_URL = "https://sub.creamycake.org/v1/css1.json";

    private static final String DIR = "anime";
    private static final String FILE_SOURCES = "sources.json";
    private static final String PREF = "anime_prefs";
    private static final String KEY_REPO_URL = "repo_url";
    private static final String KEY_FILTER_ON = "filter_enabled";
    private static final String KEY_BLOCKED = "blocked_sources";

    private final Context ctx;
    private final List<AnimeSource> sources = new ArrayList<>();

    public SourceRepository(Context context) {
        this.ctx = context.getApplicationContext();
        load();
    }

    // ══════════════ 源列表 ══════════════

    /** 从本地文件加载。**不联网**。 */
    public void load() {
        sources.clear();
        File f = sourcesFile();
        if (!f.exists()) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            String json = new String(buf, 0, Math.max(0, n), "UTF-8");
            sources.addAll(AnimeSource.parseSubscription(json));
        } catch (Throwable ignored) {
        }
    }

    /** 保存到本地文件。 */
    public void save() {
        try {
            File dir = new File(ctx.getFilesDir(), DIR);
            if (!dir.exists() && !dir.mkdirs()) return;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < sources.size(); i++) {
                if (i > 0) sb.append(',');
                AnimeSource s = sources.get(i);
                sb.append(s.rawJson == null || s.rawJson.isEmpty() ? "{}" : s.rawJson);
            }
            sb.append(']');
            try (FileOutputStream out = new FileOutputStream(sourcesFile())) {
                out.write(sb.toString().getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 全部源（未过滤） */
    public List<AnimeSource> all() {
        return new ArrayList<>(sources);
    }

    public int size() {
        return sources.size();
    }

    public boolean isEmpty() {
        return sources.isEmpty();
    }

    public void clear() {
        sources.clear();
        File f = sourcesFile();
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /**
     * 参与搜索的源（已应用黑名单过滤）。
     *
     * <p>★ 过滤放在这里而不是 UI 层：只要拿源的入口都走 {@code active()}，
     * 就不会出现"某个界面忘了过滤"导致被屏蔽的源又冒出来。</p>
     */
    public List<AnimeSource> active() {
        if (!filterEnabled()) return all();
        return SourceFilter.filter(sources, blocked());
    }

    /** 被屏蔽的源名（供 UI 提示） */
    public List<String> blockedNames() {
        return filterEnabled() ? SourceFilter.blockedNames(sources, blocked()) : new ArrayList<>();
    }

    /**
     * 从远程订阅地址导入源（**用户主动触发**）。
     *
     * @return 导入的源数量；失败抛 IOException（调用方展示原因）
     */
    public int importFrom(String url) throws IOException {
        if (url == null || url.trim().isEmpty()) throw new IOException("仓库地址为空");
        SourceEngine engine = new SourceEngine();
        String json = engine.get(url.trim(), null, null);
        List<AnimeSource> list = AnimeSource.parseSubscription(json);
        if (list.isEmpty()) throw new IOException("订阅里没有解析到任何源");
        sources.clear();
        sources.addAll(list);
        save();
        return list.size();
    }

    private File sourcesFile() {
        return new File(new File(ctx.getFilesDir(), DIR), FILE_SOURCES);
    }

    // ══════════════ 配置 ══════════════

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String repoUrl() {
        return prefs().getString(KEY_REPO_URL, DEFAULT_REPO_URL);
    }

    public void setRepoUrl(String url) {
        prefs().edit().putString(KEY_REPO_URL, url == null ? DEFAULT_REPO_URL : url).apply();
    }

    /** 过滤总开关，默认**开** */
    public boolean filterEnabled() {
        return prefs().getBoolean(KEY_FILTER_ON, true);
    }

    public void setFilterEnabled(boolean on) {
        prefs().edit().putBoolean(KEY_FILTER_ON, on).apply();
    }

    /**
     * 屏蔽源列表。首次使用（键不存在）返回**默认内置**的那份（决策 4：默认添加但可删）；
     * 用户删空后保存空串，此时返回空集合（即"用户主动关掉了内置屏蔽"）。
     */
    public Set<String> blocked() {
        SharedPreferences p = prefs();
        if (!p.contains(KEY_BLOCKED)) {
            return SourceFilter.defaultBlockedSet();
        }
        String raw = p.getString(KEY_BLOCKED, "");
        Set<String> out = new LinkedHashSet<>();
        if (raw != null && !raw.isEmpty()) {
            for (String s : raw.split("\n")) {
                if (!s.trim().isEmpty()) out.add(s.trim());
            }
        }
        return out;
    }

    public void setBlocked(Set<String> keys) {
        StringBuilder sb = new StringBuilder();
        if (keys != null) {
            for (String k : keys) {
                if (k != null && !k.trim().isEmpty()) sb.append(k.trim()).append('\n');
            }
        }
        prefs().edit().putString(KEY_BLOCKED, sb.toString()).apply();
    }

    /** 追加一个屏蔽关键字（返回 false 表示已存在） */
    public boolean addBlocked(String key) {
        if (key == null || key.trim().isEmpty()) return false;
        Set<String> cur = blocked();
        if (cur.contains(key.trim())) return false;
        cur.add(key.trim());
        setBlocked(cur);
        return true;
    }

    /** 移除一个屏蔽关键字 */
    public boolean removeBlocked(String key) {
        Set<String> cur = blocked();
        if (!cur.remove(key)) return false;
        setBlocked(cur);
        return true;
    }
}
