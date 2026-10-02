package com.aliya.hy_vq.anime;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.aliya.hy_vq.MediaPlayerActivity;
import com.aliya.hy_vq.anime.AnimeSource.Channel;
import com.aliya.hy_vq.anime.AnimeSource.Episode;
import com.aliya.hy_vq.anime.AnimeSource.PlayLink;
import com.aliya.hy_vq.anime.AnimeSource.Subject;
import com.aliya.hy_vq.module.ModuleUiKit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动漫模块主视图（内置一级页面）。
 *
 * <h3>UI 规范（对齐项目其它页面）</h3>
 * 全项目**不使用原生 Button/EditText 的默认样式** —— GachaView 等页面一律是
 * {@code TextView + ModuleUiKit.rounded()} 的圆角卡片风格。本页同样遵循：
 * <ul>
 *   <li>卡片：{@code rounded(14, colorSurfaceContainerLow, colorOutlineVariant)}</li>
 *   <li>按钮：{@code btn()} —— 圆角 TextView，不用 android.widget.Button</li>
 *   <li>分节：{@link ModuleUiKit#sectionHeader}</li>
 * </ul>
 *
 * <h3>参考的两个开源项目</h3>
 * <ul>
 *   <li>Animeko 媒体选择器：源与线路**两级展示**、线路用**胶囊按钮**、选中态高亮</li>
 *   <li>Kazumi 选集页：条目用圆角网格卡片，不用纯文本列表</li>
 * </ul>
 *
 * <h3>工作流</h3>
 * <pre>
 *   输入关键词 → 多源并发搜索（逐源实时出结果 + 进度）→ 按源分组的卡片
 *      → 点一条 → 解析线路 → 按 tier 排序 → 逐线路试首集
 *          → 成功：带请求头唤起统一播放器
 *          → 全失败：线路胶囊列表（逃生舱）
 * </pre>
 */
public class AnimeView extends LinearLayout {

    private final Activity ctx;
    private final SourceRepository repo;
    private final AnimeSearchManager searcher = new AnimeSearchManager();
    private final WatchHistory history;
    private LinearLayout continueBox;

    private EditText etKeyword;
    private LinearLayout resultBox;
    private TextView tvStatus;
    private final List<AnimeSource> shownSources = new ArrayList<>();
    private int searchTotal = 0;

    public AnimeView(Activity context) {
        super(context);
        this.ctx = context;
        this.repo = new SourceRepository(context);
        this.history = new WatchHistory(context);
        setOrientation(VERTICAL);
        setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildUi();
        refreshStatus();
        refreshContinue();
    }

    // ══════════════════ UI ══════════════════

    private void buildUi() {
        int pad = dp(14);

        // ── 搜索行：圆角输入框 + 胶囊按钮 ──
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, pad, pad, dp(4));

        etKeyword = new EditText(ctx);
        etKeyword.setHint("输入番剧名");
        etKeyword.setSingleLine(true);
        etKeyword.setInputType(InputType.TYPE_CLASS_TEXT);
        etKeyword.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        etKeyword.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        etKeyword.setHintTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        etKeyword.setBackground(ModuleUiKit.rounded(ctx, 10,
                color(com.google.android.material.R.attr.colorSurfaceContainerHigh), 0));
        etKeyword.setPadding(dp(12), dp(9), dp(12), dp(9));
        bar.addView(etKeyword, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnSearch = btn("搜索", color(com.google.android.material.R.attr.colorOnPrimary),
                color(com.google.android.material.R.attr.colorPrimary));
        btnSearch.setOnClickListener(v -> doSearch());
        LayoutParams blp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(8);
        bar.addView(btnSearch, blp);
        addView(bar);

        // ── 状态行（小字，次要色）──
        tvStatus = new TextView(ctx);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvStatus.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvStatus.setPadding(pad, 0, pad, dp(6));
        addView(tvStatus);

        // ── 结果区（顶部含「继续观看」卡片）──
        ScrollView sv = new ScrollView(ctx);
        resultBox = new LinearLayout(ctx);
        resultBox.setOrientation(VERTICAL);
        resultBox.setPadding(pad, 0, pad, dp(8));
        continueBox = new LinearLayout(ctx);
        continueBox.setOrientation(VERTICAL);
        resultBox.addView(continueBox);
        sv.addView(resultBox);
        addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── 底部：源管理 ──
        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(HORIZONTAL);
        foot.setGravity(Gravity.END);
        foot.setPadding(pad, dp(4), pad, pad);
        TextView btnSrc = btn("源管理", color(com.google.android.material.R.attr.colorPrimary),
                color(com.google.android.material.R.attr.colorSurfaceContainerHigh));
        btnSrc.setOnClickListener(v -> showSourceManager());
        foot.addView(btnSrc);
        addView(foot);
    }

    /** 项目统一的按钮写法（对齐 GachaView.btn）：圆角 TextView，而非原生 Button */
    private TextView btn(String text, int fg, int bg) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(fg);
        t.setPadding(dp(16), dp(9), dp(16), dp(9));
        t.setBackground(ModuleUiKit.rippleBg(ctx, ModuleUiKit.rounded(ctx, 10, bg, 0)));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** 项目统一的卡片写法（对齐 GachaView.card） */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(VERTICAL);
        int pad = dp(12);
        c.setPadding(pad, pad, pad, pad);
        c.setBackground(ModuleUiKit.rounded(ctx, 14,
                color(com.google.android.material.R.attr.colorSurfaceContainerLow),
                color(com.google.android.material.R.attr.colorOutlineVariant)));
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private void refreshStatus() {
        int total = repo.size();
        int active = repo.active().size();
        int blocked = total - active;
        if (total == 0) {
            tvStatus.setText("尚未导入源 —— 点右下「源管理」→「导入」");
        } else if (blocked > 0) {
            tvStatus.setText("已导入 " + total + " 个源 · 屏蔽 " + blocked + " 个 · 参与搜索 " + active + " 个");
        } else {
            tvStatus.setText("已导入 " + total + " 个源 · 参与搜索 " + active + " 个");
        }
    }

    // ══════════════════ 搜索 ══════════════════

    private void doSearch() {
        final String kw = etKeyword.getText().toString().trim();
        if (kw.isEmpty()) {
            ModuleUiKit.toast(ctx, "请输入番剧名");
            return;
        }
        List<AnimeSource> srcs = repo.active();
        if (srcs.isEmpty()) {
            ModuleUiKit.toast(ctx, repo.isEmpty() ? "还没有源，请先导入" : "所有源都被屏蔽了");
            return;
        }
        resultBox.removeAllViews();
        shownSources.clear();
        searchTotal = srcs.size();
        searcher.search(srcs, kw, new AnimeSearchManager.Callback() {
            @Override public void onStart(int total) {
                tvStatus.setText("搜索中… 0/" + total);
            }

            @Override public void onSourceDone(AnimeSource source, List<Subject> subjects) {
                addSourceCard(source, subjects);
            }

            @Override public void onSourceError(AnimeSource source, String reason) {
                // 失败不刷屏，靠进度行体现
            }

            /** 实时进度：让用户看到"在动"，而不是干等 */
            @Override public void onProgress(int done, int total) {
                tvStatus.setText("搜索中… " + done + "/" + total
                        + "（已出结果 " + shownSources.size() + " 个源）");
            }

            @Override public void onAllDone(int ok, int total) {
                tvStatus.setText("完成： " + ok + "/" + total + " 个源有结果"
                        + (ok == 0 ? "（源规则可能已失效，实测失效率很高）" : ""));
            }
        });
    }

    /**
     * 「继续观看」卡片（对齐 Kazumi）：取最近一条未看完的记录。
     * 点击后用它存的**源配置 + 集地址重新解析**（不存直链，因为直链会过期）。
     */
    private void refreshContinue() {
        if (continueBox == null) return;
        continueBox.removeAllViews();
        final WatchHistory.Entry e = history.latestUnfinished();
        if (e == null) return;

        LinearLayout c = card();
        TextView head = new TextView(ctx);
        head.setText("继续观看");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        head.setTypeface(null, Typeface.BOLD);
        head.setTextColor(color(com.google.android.material.R.attr.colorPrimary));
        c.addView(head);

        TextView body = new TextView(ctx);
        body.setText(e.subjectName + "\n" + (e.episodeTitle == null || e.episodeTitle.isEmpty()
                ? "（未命名集）" : e.episodeTitle)
                + "   " + e.positionText()
                + (e.channelName == null || e.channelName.isEmpty() ? "" : "   · " + e.channelName));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        body.setPadding(dp(10), dp(10), dp(10), dp(10));
        body.setBackground(ModuleUiKit.rippleBg(ctx, ModuleUiKit.rounded(ctx, 10,
                color(com.google.android.material.R.attr.colorSurfaceContainerHigh), 0)));
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        body.setLayoutParams(lp);
        body.setOnClickListener(v -> resumeFrom(e));
        c.addView(body);

        continueBox.addView(c);
    }

    /** 续播：重建源配置 → 重新解析该集 → 带起点播放 */
    private void resumeFrom(final WatchHistory.Entry e) {
        final AnimeSource src = AnimeSource.parse(e.sourceJson);
        if (src == null) {
            ModuleUiKit.toast(ctx, "该记录缺少源配置，无法续播");
            return;
        }
        tvStatus.setText("续播解析中…（" + src.name + "）");
        new Thread(() -> {
            try {
                SourceEngine engine = new SourceEngine();
                Episode ep = new Episode("", e.episodeTitle, e.episodeUrl);
                PlayLink pl = engine.resolve(src, ep, e.channelName);
                final PlayLink link = pl;
                Channel ch = new Channel(e.channelName == null ? "继续观看" : e.channelName);
                ui(() -> {
                    Subject sub = new Subject(e.subjectName, e.episodeUrl,
                            src.name);
                    play(link, sub, ch, ep, src);
                });
            } catch (Throwable t) {
                final String msg = t.getMessage();
                ui(() -> {
                    tvStatus.setText("续播失败（直链需重新解析）：" + msg);
                    // 退回完整流程：重新解析线路
                    autoPlay(src, new Subject(e.subjectName, e.episodeUrl, src.name));
                });
            }
        }, "anime-resume").start();
    }

    /** 一个源 = 一张卡片（源名 + tier 徽标 + 条目） */
    private void addSourceCard(AnimeSource src, List<Subject> subs) {
        shownSources.add(src);
        LinearLayout c = card();

        // 卡片头：源名 + tier 徽标
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(ctx);
        name.setText(src.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        name.setTypeface(null, Typeface.BOLD);
        name.setTextColor(color(com.google.android.material.R.attr.colorPrimary));
        head.addView(name, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tier = new TextView(ctx);
        tier.setText(src.tier >= 0 ? ("tier " + src.tier) : "未评测");
        tier.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tier.setTextColor(color(com.google.android.material.R.attr.colorOnTertiaryContainer));
        tier.setBackground(ModuleUiKit.rounded(ctx, 6,
                color(com.google.android.material.R.attr.colorTertiaryContainer), 0));
        tier.setPadding(dp(6), dp(1), dp(6), dp(1));
        head.addView(tier);
        c.addView(head);

        // 条目
        for (final Subject sub : subs) {
            TextView tv = new TextView(ctx);
            tv.setText(sub.name);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
            tv.setPadding(dp(10), dp(10), dp(10), dp(10));
            tv.setBackground(ModuleUiKit.rippleBg(ctx, ModuleUiKit.rounded(ctx, 10,
                    color(com.google.android.material.R.attr.colorSurfaceContainerHigh), 0)));
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> autoPlay(src, sub));
            c.addView(tv);
        }
        resultBox.addView(c);
    }

    // ══════════════════ 自动选源播放 ══════════════════

    private void autoPlay(final AnimeSource src, final Subject sub) {
        tvStatus.setText("解析线路中…（" + src.name + "）");
        new Thread(() -> {
            try {
                SourceEngine engine = new SourceEngine();
                List<Channel> chs = engine.channels(src, sub);
                if (chs.isEmpty()) {
                    ui(() -> {
                        tvStatus.setText("未解析到线路");
                        ModuleUiKit.toast(ctx, "该源未解析到线路（规则可能失效）");
                    });
                    return;
                }
                final List<Channel> sorted = new ArrayList<>(chs);
                sorted.sort((a, b) -> tierRank(src, a) - tierRank(src, b));

                ui(() -> tvStatus.setText("共 " + sorted.size() + " 条线路，按 tier 尝试…"));

                for (Channel ch : sorted) {
                    if (ch.episodes.isEmpty()) continue;
                    Episode first = ch.episodes.get(0);
                    ui(() -> tvStatus.setText("尝试「" + ch.name + "」tier="
                            + (src.tierFor(ch.name) >= 0 ? src.tierFor(ch.name) : "未评测") + " …"));
                    try {
                        PlayLink pl = engine.resolve(src, first, ch.name);
                        final PlayLink link = pl;
                        final Channel chosen = ch;
                        final Episode chosenEp = first;
                        ui(() -> play(link, sub, chosen, chosenEp, src));
                        return;
                    } catch (Throwable ignore) {
                        // 换下一条线路
                    }
                }
                final List<Channel> fallback = sorted;
                // 纯解析全失败 → 转 WebView 嗅探兜底（原理参考 Animeko）
                ui(() -> {
                    tvStatus.setText("纯解析未取到直链，改用浏览器解析…");
                    tryWebView(src, sub, fallback);
                });
            } catch (Throwable t) {
                final String msg = t.getMessage();
                ui(() -> {
                    tvStatus.setText("解析失败：" + msg);
                    ModuleUiKit.toast(ctx, "解析失败：" + msg);
                });
            }
        }, "anime-resolve").start();
    }

    /**
     * WebView 兜底取链（原理参考 Animeko 的 AndroidWebViewVideoExtractor）。
     *
     * <p>适用两类纯解析拿不到的源：第三方解析 iframe（直链在别人手里）、
     * encrypt=3（解密 JS 在外部脚本）。共同点是"只要 WebView 能播，请求就会露出来"。</p>
     */
    private void tryWebView(final AnimeSource src, final Subject sub, final List<Channel> chs) {
        if (chs == null || chs.isEmpty()) {
            ui(() -> ModuleUiKit.toast(ctx, "没有可用线路"));
            return;
        }
        final Channel ch = chs.get(0);
        if (ch.episodes == null || ch.episodes.isEmpty()) {
            ui(() -> ModuleUiKit.toast(ctx, "该线路没有单集"));
            return;
        }
        final Episode ep = ch.episodes.get(0);

        final WebViewLinkExtractor ex = new WebViewLinkExtractor(ctx);
        ex.extract(ep.url, src.search.userAgent, ep.url, null,
                WebViewLinkExtractor.DEFAULT_TIMEOUT_MS, new WebViewLinkExtractor.Callback() {
                    @Override public void onFound(String url) {
                        ui(() -> {
                            PlayLink pl = new PlayLink();
                            pl.url = url;
                            pl.channelName = ch.name;
                            pl.nested = true;
                            // 分片请求一般需要 Referer（指向播放页）与相同 UA
                            pl.headers.put("Referer", ep.url);
                            if (src.search.userAgent != null && !src.search.userAgent.isEmpty()) {
                                pl.headers.put("User-Agent", src.search.userAgent);
                            }
                            tvStatus.setText("浏览器解析成功，开始播放");
                            play(pl, sub, ch, ep, src);
                        });
                    }

                    @Override public void onFailed(String reason) {
                        ui(() -> {
                            tvStatus.setText("浏览器解析也失败：" + reason);
                            showChannelPicker(src, sub, chs);
                        });
                    }
                });
    }

    private int tierRank(AnimeSource src, Channel ch) {
        int t = src.tierFor(ch.name);
        return t < 0 ? 999 : t;
    }

    private void play(PlayLink link, Subject sub, Channel ch) {
        play(link, sub, ch, null, null);
    }

    /**
     * 播放并记录观看历史。
     *
     * @param ep  当前集（用于 historyKey 与续播定位）；null 表示不记录
     * @param src 源（把源配置 JSON 一起存，续播时可重新解析拿新直链）
     */
    private void play(PlayLink link, Subject sub, Channel ch, Episode ep, AnimeSource src) {
        if (link == null || link.url == null) {
            ModuleUiKit.toast(ctx, "无可用播放地址");
            return;
        }
        Map<String, String> headers = link.headers;
        String title = sub.name + (ch != null ? " · " + ch.name : "");

        String key = ep != null && ep.url != null ? ep.url : null;
        long startMs = 0L;
        if (key != null) {
            startMs = history.positionOf(key);         // 上次看到哪
            WatchHistory.Entry en = new WatchHistory.Entry();
            en.subjectName = sub.name;
            en.sourceName = src != null ? src.name : (sub.sourceName == null ? "" : sub.sourceName);
            en.sourceJson = src != null ? src.rawJson : "";
            en.channelName = ch != null ? ch.name : "";
            en.episodeUrl = key;
            en.episodeTitle = ep.title == null ? "" : ep.title;
            history.record(en);                        // 先登记，进度由播放器回写
        }
        Intent i = MediaPlayerActivity.urlIntent(ctx, link.url, title, headers, key, startMs);
        try {
            ctx.startActivity(i);
            tvStatus.setText("已开始播放：" + title);
        } catch (Throwable t) {
            ModuleUiKit.toast(ctx, "无法打开播放器：" + t.getMessage());
        }
    }

    /**
     * 逃生舱：手动选线路。
     * 参考 Animeko 媒体选择器 —— 线路用**胶囊按钮** + tier 标注，而不是纯文本列表。
     */
    private void showChannelPicker(final AnimeSource src, final Subject sub, List<Channel> chs) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(ctx, "选择线路 · " + sub.name));

        TextView hint = new TextView(ctx);
        hint.setText("自动选源未取到直链，可手动指定线路（tier 越小越优）");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(dp(4), 0, dp(4), dp(8));
        box.addView(hint);

        ScrollView sv = new ScrollView(ctx);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(VERTICAL);
        sv.addView(col);
        box.addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)));

        final Dialog d = ModuleUiKit.glassDialog(ctx, box);
        for (final Channel ch : chs) {
            int t = src.tierFor(ch.name);
            TextView pill = btn("【" + ch.name + "】  tier=" + (t >= 0 ? t : "?")
                            + "   " + ch.episodes.size() + " 集",
                    color(com.google.android.material.R.attr.colorPrimary),
                    color(com.google.android.material.R.attr.colorSurfaceContainerHigh));
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            pill.setLayoutParams(lp);
            pill.setOnClickListener(v -> {
                d.dismiss();
                if (ch.episodes.isEmpty()) return;
                new Thread(() -> {
                    try {
                        SourceEngine engine = new SourceEngine();
                        Episode first = ch.episodes.get(0);
                        PlayLink pl = engine.resolve(src, first, ch.name);
                        ui(() -> play(pl, sub, ch, first, src));
                    } catch (Throwable e) {
                        ui(() -> ModuleUiKit.toast(ctx, "该线路也不可用：" + e.getMessage()));
                    }
                }).start();
            });
            col.addView(pill);
        }
        d.show();
    }

    // ══════════════════ 源管理 ══════════════════

    private void showSourceManager() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(ctx, "源管理"));

        final Dialog d = ModuleUiKit.glassDialog(ctx, box);

        final TextView info = new TextView(ctx);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        info.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        info.setPadding(dp(4), 0, dp(4), dp(10));
        info.setText("已导入 " + repo.size() + " 个源");
        box.addView(info);

        // 仓库地址（可改）
        TextView lab = new TextView(ctx);
        lab.setText("仓库地址（可改）");
        lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lab.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        lab.setPadding(dp(4), 0, dp(4), dp(4));
        box.addView(lab);

        final EditText etUrl = new EditText(ctx);
        etUrl.setText(repo.repoUrl());
        etUrl.setSingleLine(true);
        etUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        etUrl.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        etUrl.setBackground(ModuleUiKit.rounded(ctx, 10,
                color(com.google.android.material.R.attr.colorSurfaceContainerHigh), 0));
        etUrl.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.addView(etUrl);

        final TextView btnImport = btn("从该地址导入 / 更新源",
                color(com.google.android.material.R.attr.colorOnPrimary),
                color(com.google.android.material.R.attr.colorPrimary));
        LayoutParams ilp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(8);
        btnImport.setLayoutParams(ilp);
        btnImport.setGravity(Gravity.CENTER);
        btnImport.setOnClickListener(v -> {
            final String url = etUrl.getText().toString().trim();
            repo.setRepoUrl(url);
            btnImport.setEnabled(false);
            btnImport.setText("导入中…");
            new Thread(() -> {
                try {
                    int n = repo.importFrom(url);
                    ui(() -> {
                        btnImport.setEnabled(true);
                        btnImport.setText("从该地址导入 / 更新源");
                        info.setText("已导入 " + n + " 个源");
                        refreshStatus();
                        ModuleUiKit.toast(ctx, "导入成功：" + n + " 个源");
                    });
                } catch (Throwable t) {
                    ui(() -> {
                        btnImport.setEnabled(true);
                        btnImport.setText("从该地址导入 / 更新源");
                        ModuleUiKit.toast(ctx, "导入失败：" + t.getMessage());
                    });
                }
            }).start();
        });
        box.addView(btnImport);

        // 屏蔽列表
        TextView lab2 = new TextView(ctx);
        lab2.setText("屏蔽的源关键字（点条目删除）");
        lab2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lab2.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        lab2.setPadding(dp(4), dp(12), dp(4), dp(4));
        box.addView(lab2);

        final LinearLayout blockCol = new LinearLayout(ctx);
        blockCol.setOrientation(VERTICAL);
        box.addView(blockCol);
        renderBlocked(blockCol);

        // 过滤开关（用项目风格的行，而不是原生 Switch）
        final LinearLayout filterRow = new LinearLayout(ctx);
        filterRow.setOrientation(HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams flp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(12);
        filterRow.setLayoutParams(flp);

        final TextView swLabel = new TextView(ctx);
        swLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        swLabel.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        filterRow.addView(swLabel, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView swBtn = btn("", 0, 0);
        filterRow.addView(swBtn);
        box.addView(filterRow);

        final Runnable paintSwitch = () -> {
            boolean on = repo.filterEnabled();
            swLabel.setText("启用源过滤（按源屏蔽）");
            swBtn.setText(on ? "已开启" : "已关闭");
            swBtn.setTextColor(on
                    ? color(com.google.android.material.R.attr.colorOnPrimary)
                    : color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            swBtn.setBackground(ModuleUiKit.rippleBg(ctx, ModuleUiKit.rounded(ctx, 10,
                    on ? color(com.google.android.material.R.attr.colorPrimary)
                       : color(com.google.android.material.R.attr.colorSurfaceContainerHigh), 0)));
        };
        paintSwitch.run();
        swBtn.setOnClickListener(v -> {
            repo.setFilterEnabled(!repo.filterEnabled());
            paintSwitch.run();
            refreshStatus();
            renderBlocked(blockCol);
        });


        // 新增
        LinearLayout addRow = new LinearLayout(ctx);
        addRow.setOrientation(HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams alp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(8);
        addRow.setLayoutParams(alp);

        final EditText etNew = new EditText(ctx);
        etNew.setHint("添加屏蔽关键字，如 example.com");
        etNew.setSingleLine(true);
        etNew.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        etNew.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        etNew.setHintTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        etNew.setBackground(ModuleUiKit.rounded(ctx, 10,
                color(com.google.android.material.R.attr.colorSurfaceContainerHigh), 0));
        etNew.setPadding(dp(10), dp(8), dp(10), dp(8));
        addRow.addView(etNew, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnAdd = btn("添加", color(com.google.android.material.R.attr.colorOnPrimary),
                color(com.google.android.material.R.attr.colorPrimary));
        LayoutParams ablp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ablp.leftMargin = dp(8);
        addRow.addView(btnAdd, ablp);
        box.addView(addRow);
        btnAdd.setOnClickListener(v -> {
            if (repo.addBlocked(etNew.getText().toString())) {
                etNew.setText("");
                renderBlocked(blockCol);
                refreshStatus();
            } else {
                ModuleUiKit.toast(ctx, "已存在或为空");
            }
        });

        TextView btnClose = btn("关闭", color(com.google.android.material.R.attr.colorPrimary),
                color(com.google.android.material.R.attr.colorSurfaceContainerHigh));
        LayoutParams clp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(14);
        btnClose.setLayoutParams(clp);
        btnClose.setGravity(Gravity.CENTER);
        btnClose.setOnClickListener(v -> d.dismiss());
        box.addView(btnClose);

        d.show();
    }

    private void renderBlocked(LinearLayout blockCol) {
        if (blockCol == null) return;
        blockCol.removeAllViews();
        Set<String> bl = repo.blocked();
        if (bl.isEmpty()) {
            TextView tv = new TextView(ctx);
            tv.setText("（空 —— 当前不屏蔽任何源）");
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            blockCol.addView(tv);
            return;
        }
        for (final String k : bl) {
            TextView tv = new TextView(ctx);
            tv.setText("• " + k + "       删 除");
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
            tv.setPadding(dp(10), dp(9), dp(10), dp(9));
            tv.setBackground(ModuleUiKit.rippleBg(ctx, ModuleUiKit.rounded(ctx, 10,
                    color(com.google.android.material.R.attr.colorSurfaceContainerHigh), 0)));
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> {
                repo.removeBlocked(k);
                renderBlocked(blockCol);
                refreshStatus();
            });
            blockCol.addView(tv);
        }
    }

    // ══════════════════ 工具 ══════════════════

    private int color(int attr) {
        return ModuleUiKit.color(ctx, attr);
    }

    private void ui(Runnable r) {
        ctx.runOnUiThread(r);
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    public void onDestroy() {
        try {
            searcher.cancel();
        } catch (Throwable ignored) {
        }
    }
}
