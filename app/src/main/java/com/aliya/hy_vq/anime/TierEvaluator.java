package com.aliya.hy_vq.anime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * tier 实测补全（决策 6「自动选源」的前提）。
 *
 * <h3>为什么必须做</h3>
 * 订阅里的静态 tier 已与现实<b>反向</b>：实测标注 t0 的源（girigiri、稀饭动漫）
 * 搜索全失败，而能拿到直链的（樱花动漫、wedm）被标 t4。
 * Stage2 的线路胶囊正按 tier 排序 —— 假 tier 会让<b>第一个胶囊是坏的</b>。
 *
 * <h3>评测链路（三段，越往下越贵）</h3>
 * <pre>
 * search  → 该源能否搜到条目
 * channels→ 条目能否取出线路与单集
 * resolve → 线路能否拿到真实直链（m3u8/mp4）
 * </pre>
 *
 * <h3>打分（越小越优，对齐 Ani 约定）</h3>
 * <pre>
 * 拿到直链且 ≤3s → 0    拿到直链 ≤8s → 1    拿到直链更慢 → 2
 * 只到线路(需 WebView) → 4
 * 只搜到条目 → 5        完全失败 → 9（死源）
 * </pre>
 * 结果按得分升序映射成 tier，同时记录线路级结果（写入 channelTiers）。
 */
public final class TierEvaluator {

    /** 单个源的评测结果 */
    public static class Result {
        public String sourceName = "";
        public boolean searchOk, channelOk, resolveOk;
        public long searchMs, channelMs, resolveMs;
        public String mediaUrl = "";       // 拿到的直链（可为空）
        public String failStage = "";      // 卡在哪一段
        public String error = "";
        public int tier = 9;               // 计算结果
        /** 线路名 → tier（用于 channelTiers 覆盖） */
        public final java.util.Map<String, Integer> channelTiers = new java.util.HashMap<>();

        public long totalMs() {
            return searchMs + channelMs + resolveMs;
        }

        public String summary() {
            if (resolveOk) return "✅ " + totalMs() + "ms";
            if (channelOk) return "⚠ 线路可达·直链失败";
            if (searchOk) return "⚠ 仅搜索可用";
            return "✗ " + (failStage.isEmpty() ? "失败" : failStage);
        }
    }

    private TierEvaluator() {
    }

    public interface Callback {
        void onProgress(int done, int total, String current);
        void onDone(List<Result> results);
    }

    /**
     * 批量评测（并发，限流避免打挂源站）。
     *
     * @param keyword 用哪个番剧名测——建议用已知有多条线路的常见番
     */
    public static void evaluateAll(final List<AnimeSource> srcs, final String keyword,
                                   final Callback cb, final long budgetMs) {
        final int total = srcs == null ? 0 : srcs.size();
        if (total == 0) {
            if (cb != null) cb.onDone(new ArrayList<>());
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, total));
        final CompletionService<Result> cs = new ExecutorCompletionService<>(pool);
        for (final AnimeSource s : srcs) {
            cs.submit(() -> evaluateOne(s, keyword));
        }

        final List<Result> out = new ArrayList<>();
        new Thread(() -> {
            int done = 0;
            long deadline = System.currentTimeMillis() + budgetMs;
            while (done < total) {
                try {
                    long remain = deadline - System.currentTimeMillis();
                    Future<Result> f = remain > 0
                            ? cs.poll(Math.min(remain, 6000), TimeUnit.MILLISECONDS)
                            : cs.poll();
                    if (f == null) {
                        if (remain <= 0) break;
                        continue;
                    }
                    Result r = f.get();
                    done++;
                    if (r != null) {
                        out.add(r);
                        if (cb != null) cb.onProgress(done, total, r.sourceName);
                    }
                } catch (Throwable ignored) {
                    done++;
                }
            }
            // 收尾：把已完成但没轮到的收完
            for (int i = 0; i < total; i++) {
                try {
                    Future<Result> f = cs.poll();
                    if (f == null) break;
                    Result r = f.get();
                    if (r != null) out.add(r);
                } catch (Throwable ignored) {
                    break;
                }
            }
            pool.shutdownNow();
            assignTiers(out);
            if (cb != null) cb.onDone(out);
        }, "tier-eval").start();
    }

    /** 评测单个源：search → channels → resolve 三段 */
    public static Result evaluateOne(AnimeSource src, String keyword) {
        Result r = new Result();
        r.sourceName = src.name == null ? "" : src.name;
        if (keyword == null || keyword.isEmpty()) {
            r.failStage = "无关键词";
            return r;
        }
        try {
            SourceEngine engine = new SourceEngine();

            // 第一段：搜索
            long t0 = System.currentTimeMillis();
            List<AnimeSource.Subject> subs = engine.search(src, keyword);
            r.searchMs = System.currentTimeMillis() - t0;
            if (subs == null || subs.isEmpty()) {
                r.failStage = "搜索无结果";
                r.error = "规则可能已失效";
                return r;
            }
            r.searchOk = true;

            // 第二段：取线路
            t0 = System.currentTimeMillis();
            List<AnimeSource.Channel> chs = engine.channels(src, subs.get(0));
            r.channelMs = System.currentTimeMillis() - t0;
            if (chs == null || chs.isEmpty()) {
                r.failStage = "无线路";
                return r;
            }
            r.channelOk = true;

            // 第三段：解析直链（只测线路数 ≤3 条，控制耗时）
            t0 = System.currentTimeMillis();
            AnimeSource.Channel best = null;
            for (AnimeSource.Channel ch : chs) {
                if (ch.episodes == null || ch.episodes.isEmpty()) continue;
                if (best == null
                        || src.tierFor(ch.name) < src.tierFor(best.name)) {
                    best = ch;   // 优先测 tier 最小的线路
                }
                if (r.channelTiers.size() >= 3) break;
            }
            if (best != null) {
                try {
                    AnimeSource.PlayLink pl = engine.resolve(
                            src, best.episodes.get(0), best.name);
                    r.resolveMs = System.currentTimeMillis() - t0;
                    if (pl != null && pl.url != null && !pl.url.isEmpty()) {
                        r.resolveOk = true;
                        r.mediaUrl = pl.url;
                        r.channelTiers.put(best.name, 0);
                    }
                } catch (Throwable t) {
                    r.resolveMs = System.currentTimeMillis() - t0;
                    r.error = t.getMessage() == null ? "" : t.getMessage();
                }
            }
            if (!r.resolveOk) r.failStage = "直链解析失败";

            // 给其余线路一个粗略档位：有直链的源，其它线路记 1（未验证）
            if (r.resolveOk) {
                for (AnimeSource.Channel ch : chs) {
                    if (ch.episodes == null || ch.episodes.isEmpty()) continue;
                    r.channelTiers.putIfAbsent(ch.name, 1);
                }
            }
            return r;
        } catch (Throwable t) {
            r.failStage = "异常";
            r.error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            return r;
        }
    }

    /** 按成功率+耗时计算 tier（0 最优，9 最差） */
    static void assignTiers(List<Result> results) {
        if (results == null || results.isEmpty()) return;
        // 先按质量与耗时排序
        results.sort(Comparator
                .comparingInt((Result r) -> qualityRank(r))
                .thenComparingLong(Result::totalMs));
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            if (!r.searchOk) {
                r.tier = 9;                       // 死源
            } else if (r.resolveOk) {
                long ms = r.totalMs();
                r.tier = ms <= 3000 ? 0 : (ms <= 8000 ? 1 : 2);
            } else if (r.channelOk) {
                r.tier = 4;                       // 需 WebView
            } else {
                r.tier = 5;                       // 仅搜索
            }
        }
    }

    private static int qualityRank(Result r) {
        if (r.resolveOk) return 0;
        if (r.channelOk) return 1;
        if (r.searchOk) return 2;
        return 3;
    }
}
