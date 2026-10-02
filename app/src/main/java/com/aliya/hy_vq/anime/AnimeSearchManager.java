package com.aliya.hy_vq.anime;

import android.os.Handler;
import android.os.Looper;

import com.aliya.hy_vq.anime.AnimeSource.Subject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聚合搜索管理器 —— 多源并发、独立超时、部分失败容忍。
 *
 * <h3>为什么必须是"容忍失败"的设计</h3>
 * 实测 18 个源：搜索成功率只有 50%（9/18）。也就是说**半数源每次都会失败**，
 * 这是常态而不是异常。所以设计上：
 * <ul>
 *   <li>逐源回调，**搜到一个显示一个**（不让快的源等慢的）</li>
 *   <li>单源失败只记错误，不影响其他源</li>
 *   <li>总耗时由最慢的源决定 → 给每个源设超时预算</li>
 * </ul>
 *
 * <h3>结果排序</h3>
 * 按源 tier 升序（{@code tierFor}），未评测(-1)排最后 —— 这是"自动选源"在列表层的体现。
 */
public class AnimeSearchManager {

    /** 单源超时预算：内部 OkHttp 另设 connect 15s / read 20s，这里做兜底 */
    private static final long PER_SOURCE_TIMEOUT_MS = 25_000L;
    /** 并发度：过高会触发源站风控（实测源站对密集请求敏感） */
    private static final int MAX_PARALLEL = 4;

    public interface Callback {
        /** 开始，total = 参与搜索的源数 */
        void onStart(int total);

        /** 某个源搜完（可能 0 条） */
        void onSourceDone(AnimeSource source, List<Subject> subjects);

        /** 某个源失败（超时/异常/规则失效） */
        void onSourceError(AnimeSource source, String reason);

        /** 全部结束。ok = 有结果的源数 */
        void onAllDone(int ok, int total);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private ExecutorService pool;

    /** 取消当前搜索（结果回调会停止） */
    public void cancel() {
        cancelled.set(true);
        if (pool != null) pool.shutdownNow();
    }

    /**
     * 并发搜索。回调都在主线程。
     *
     * @param sources 已过滤的源（调用方应传 {@code repo.active()}）
     */
    public void search(List<AnimeSource> sources, String keyword, Callback cb) {
        cancelled.set(false);
        final int total = sources == null ? 0 : sources.size();
        main.post(() -> cb.onStart(total));
        if (total == 0) {
            main.post(() -> cb.onAllDone(0, 0));
            return;
        }

        // 按 tier 预排序（未评测排最后），让高质量源先出结果
        List<AnimeSource> ordered = new ArrayList<>(sources);
        ordered.sort(Comparator.comparingInt(s -> s.tier >= 0 ? s.tier : 999));

        final ExecutorService exec = Executors.newFixedThreadPool(Math.min(MAX_PARALLEL, total));
        this.pool = exec;
        final CompletionService<Object[]> cs = new ExecutorCompletionService<>(exec);

        for (final AnimeSource s : ordered) {
            cs.submit(() -> {
                try {
                    SourceEngine engine = new SourceEngine();
                    List<Subject> subs = engine.search(s, keyword);
                    return new Object[]{s, subs, null};
                } catch (Throwable t) {
                    return new Object[]{s, null, t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : ": " + t.getMessage())};
                }
            });
        }

        // 收集线程：逐源回调
        new Thread(() -> {
            int ok = 0;
            int finished = 0;
            for (int i = 0; i < total; i++) {
                if (cancelled.get()) break;
                Future<Object[]> f;
                try {
                    f = cs.poll(PER_SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (f == null) continue;      // 预算内没等到 → 跳过（其余继续）
                Object[] r;
                try {
                    r = f.get();
                } catch (Throwable t) {
                    continue;
                }
                finished++;
                final AnimeSource src = (AnimeSource) r[0];
                @SuppressWarnings("unchecked")
                final List<Subject> subs = (List<Subject>) r[1];
                final String err = (String) r[2];
                if (subs != null && !subs.isEmpty()) {
                    ok++;
                    main.post(() -> cb.onSourceDone(src, subs));
                } else if (err != null) {
                    main.post(() -> cb.onSourceError(src, err));
                } else {
                    main.post(() -> cb.onSourceError(src, "无结果（规则可能已失效）"));
                }
            }
            final int okFinal = ok;
            main.post(() -> cb.onAllDone(okFinal, total));
            try {
                exec.shutdownNow();
            } catch (Throwable ignored) {
            }
        }, "anime-search").start();
    }
}
