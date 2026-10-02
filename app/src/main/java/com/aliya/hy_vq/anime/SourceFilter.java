package com.aliya.hy_vq.anime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 源过滤（决策 4）—— <b>按「源」过滤，不按「标签」过滤</b>。
 *
 * <h3>为什么必须是源级</h3>
 * 实测 ani-subs 订阅里，成人内容是以**独立数据源**的形式存在的：
 * <pre>
 *   { "name": "hanime1_1080p", ... "searchUrl": "https://hanime1.me/search?query=..." }
 *   { "name": "hanime1_720p",  ... }
 * </pre>
 * 所以按源名/域名屏蔽是<b>精确的、零误伤</b>的。
 *
 * <p>而早期的设想是"按标签/genre 过滤"——那个方案确实有问题：源站的分类标签经常乱打，
 * 会把正常番剧误判进来。本类因此**只做源级匹配**，不碰任何内容标签。</p>
 *
 * <h3>默认值语义</h3>
 * {@link #DEFAULT_BLOCKED} 是<b>默认内置、但用户可以删除</b>的：
 * 删掉后即可看到该源（删除时 UI 应给二次确认）。
 */
public final class SourceFilter {

    /**
     * 默认屏蔽的源关键字（匹配源名或源内 URL 的域名）。
     * 用户可在 设置 → 内容过滤 里增删。
     */
    public static final String[] DEFAULT_BLOCKED = {
            "hanime1.me",
            "hanime1",
    };

    private SourceFilter() {
    }

    /** 默认黑名单的可变副本（写入设置时用） */
    public static Set<String> defaultBlockedSet() {
        Set<String> s = new LinkedHashSet<>();
        for (String k : DEFAULT_BLOCKED) s.add(k);
        return s;
    }

    /**
     * 判断单个源是否命中黑名单。
     * 匹配范围：源名 + 搜索地址域名（两者任一命中即屏蔽）。
     */
    public static boolean isBlocked(AnimeSource src, Set<String> blocked) {
        if (src == null || blocked == null || blocked.isEmpty()) return false;
        String name = src.name == null ? "" : src.name.toLowerCase(Locale.ROOT);
        String url = src.search == null || src.search.searchUrl == null
                ? "" : src.search.searchUrl.toLowerCase(Locale.ROOT);
        for (String raw : blocked) {
            if (raw == null) continue;
            String k = raw.trim().toLowerCase(Locale.ROOT);
            if (k.isEmpty()) continue;
            if (name.contains(k) || url.contains(k)) return true;
        }
        return false;
    }

    /** 批量过滤。blocked 为 null/空 时原样返回（即"过滤已关闭"）。 */
    public static List<AnimeSource> filter(List<AnimeSource> in, Set<String> blocked) {
        List<AnimeSource> out = new ArrayList<>();
        if (in == null) return out;
        if (blocked == null || blocked.isEmpty()) {
            out.addAll(in);
            return out;
        }
        for (AnimeSource s : in) {
            if (!isBlocked(s, blocked)) out.add(s);
        }
        return out;
    }

    /** 被过滤掉的源名（供 UI 提示"已屏蔽 N 个源"） */
    public static List<String> blockedNames(List<AnimeSource> in, Set<String> blocked) {
        List<String> out = new ArrayList<>();
        if (in == null) return out;
        for (AnimeSource s : in) {
            if (isBlocked(s, blocked)) out.add(s.name == null ? "(未命名)" : s.name);
        }
        return out;
    }
}
