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
import android.widget.Button;
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
 * 动漫模块主视图（内置一级页面，与文件管理/抽卡分析同款：进 contentFrame，不跳独立 Activity）。
 *
 * <h3>工作流</h3>
 * <pre>
 *   输入关键词 → 多源并发搜索 → 结果按源分组（源按 tier 升序）
 *      → 点击某条 → 解析线路 → 按 tier 排序 → 逐线路尝试首集
 *          → 成功：带请求头唤起 MediaPlayerActivity 播放
 *          → 全失败：列出线路让用户手动选（逃生舱）
 * </pre>
 *
 * <h3>两处刻意的设计</h3>
 * <ul>
 *   <li><b>默认不自动拉取源</b>（决策 2）：首次进入是空的，用户点了"导入"才联网。</li>
 *   <li><b>自动选源保留手动兜底</b>：自动失败时给线路列表，而不是死路。</li>
 * </ul>
 */
public class AnimeView extends LinearLayout {

    private final Activity ctx;
    private final SourceRepository repo;
    private final AnimeSearchManager searcher = new AnimeSearchManager();

    private EditText etKeyword;
    private LinearLayout resultBox;
    private TextView tvStatus;
    private final List<AnimeSource> lastResults = new ArrayList<>();

    public AnimeView(Activity context) {
        super(context);
        this.ctx = context;
        this.repo = new SourceRepository(context);
        setOrientation(VERTICAL);
        setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildUi();
        refreshStatus();
    }

    // ══════════════════ UI ══════════════════

    private void buildUi() {
        int pad = dp(12);

        // ── 搜索栏 ──
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, pad, pad, dp(6));

        etKeyword = new EditText(ctx);
        etKeyword.setHint("输入番剧名");
        etKeyword.setSingleLine(true);
        etKeyword.setInputType(InputType.TYPE_CLASS_TEXT);
        etKeyword.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bar.addView(etKeyword, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnSearch = new Button(ctx);
        btnSearch.setText("搜索");
        btnSearch.setAllCaps(false);
        btnSearch.setOnClickListener(v -> doSearch());
        LayoutParams blp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(8);
        bar.addView(btnSearch, blp);
        addView(bar);

        // ── 状态行 ──
        tvStatus = new TextView(ctx);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvStatus.setTextColor(ModuleUiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvStatus.setPadding(pad, 0, pad, dp(6));
        addView(tvStatus);

        // ── 结果区（可滚动，占满剩余）──
        ScrollView sv = new ScrollView(ctx);
        resultBox = new LinearLayout(ctx);
        resultBox.setOrientation(VERTICAL);
        resultBox.setPadding(pad, 0, pad, dp(8));
        sv.addView(resultBox);
        addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── 底部：源管理 ──
        LinearLayout foot = new LinearLayout(ctx);
        foot.setOrientation(HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(pad, dp(6), pad, pad);

        Button btnSrc = new Button(ctx);
        btnSrc.setText("源管理");
        btnSrc.setAllCaps(false);
        btnSrc.setOnClickListener(v -> showSourceManager());
        foot.addView(btnSrc);
        addView(foot);
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
        lastResults.clear();
        searcher.search(srcs, kw, new AnimeSearchManager.Callback() {
            @Override public void onStart(int total) {
                tvStatus.setText("搜索中… 0/" + total + " 源");
            }

            @Override public void onSourceDone(AnimeSource source, List<Subject> subjects) {
                addSourceResult(source, subjects);
            }

            @Override public void onSourceError(AnimeSource source, String reason) {
                // 失败不刷屏：只在状态行累计
            }

            @Override public void onAllDone(int ok, int total) {
                tvStatus.setText("完成： " + ok + "/" + total + " 个源有结果"
                        + (ok == 0 ? "（源规则可能已失效，实测失效率很高）" : ""));
            }
        });
    }

    /** 把某个源的结果追加到列表（实时逐源显示） */
    private void addSourceResult(AnimeSource src, List<Subject> subs) {
        lastResults.add(src);
        TextView head = new TextView(ctx);
        head.setText("▼ " + src.name + "   [tier " + (src.tier >= 0 ? src.tier : "未评测") + "]");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        head.setTypeface(null, Typeface.BOLD);
        head.setPadding(0, dp(10), 0, dp(4));
        head.setTextColor(ModuleUiKit.color(ctx,
                com.google.android.material.R.attr.colorPrimary));
        resultBox.addView(head);

        for (final Subject sub : subs) {
            TextView tv = new TextView(ctx);
            tv.setText("    " + sub.name);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setPadding(dp(4), dp(8), dp(4), dp(8));
            tv.setTextColor(ModuleUiKit.color(ctx,
                    com.google.android.material.R.attr.colorOnSurface));
            tv.setBackground(ModuleUiKit.rippleBg(ctx, ModuleUiKit.rounded(ctx, 8,
                    ModuleUiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceVariant),
                    0)));
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> autoPlay(src, sub));
            resultBox.addView(tv);
        }
    }

    // ══════════════════ 自动选源播放 ══════════════════

    /**
     * 点击一条结果后的自动流程：解析线路 → 按 tier 升序 → 逐线路试首集 → 播放。
     * 全部失败则给出手动线路选择（逃生舱）。
     */
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
                // 按 tier 升序（线路级优先，回退源级）；未评测排最后
                final List<Channel> sorted = new ArrayList<>(chs);
                sorted.sort((a, b) -> tierRank(src, a) - tierRank(src, b));

                ui(() -> tvStatus.setText("共 " + sorted.size() + " 条线路，按 tier 尝试…"));

                for (Channel ch : sorted) {
                    if (ch.episodes.isEmpty()) continue;
                    Episode first = ch.episodes.get(0);
                    ui(() -> tvStatus.setText("尝试线路「" + ch.name + "」tier="
                            + (src.tierFor(ch.name) >= 0 ? src.tierFor(ch.name) : "未评测") + " …"));
                    try {
                        PlayLink pl = engine.resolve(src, first, ch.name);
                        final PlayLink link = pl;
                        final Channel chosen = ch;
                        ui(() -> play(link, sub, chosen));
                        return;
                    } catch (Throwable ignore) {
                        // 该线路不可用 → 试下一条
                    }
                }
                // 全失败
                final List<Channel> fallback = sorted;
                ui(() -> {
                    tvStatus.setText("所有线路都未取到直链（可能需要 WebView 的源）");
                    showChannelPicker(src, sub, fallback);
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

    /** 排序权重：tier 越小越靠前；未评测(-1)排最后 */
    private int tierRank(AnimeSource src, Channel ch) {
        int t = src.tierFor(ch.name);
        return t < 0 ? 999 : t;
    }

    /** 唤起统一播放器（带请求头） */
    private void play(PlayLink link, Subject sub, Channel ch) {
        if (link == null || link.url == null) {
            ModuleUiKit.toast(ctx, "无可用播放地址");
            return;
        }
        Map<String, String> headers = link.headers;
        String title = sub.name + (ch != null ? " · " + ch.name : "");
        Intent i = MediaPlayerActivity.urlIntent(ctx, link.url, title, headers);
        try {
            ctx.startActivity(i);
            tvStatus.setText("已开始播放：" + title);
        } catch (Throwable t) {
            ModuleUiKit.toast(ctx, "无法打开播放器：" + t.getMessage());
        }
    }

    /** 逃生舱：手动选线路（自动全失败时） */
    private void showChannelPicker(final AnimeSource src, final Subject sub, List<Channel> chs) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(ctx, "手动选择线路 · " + sub.name));

        ScrollView sv = new ScrollView(ctx);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(VERTICAL);
        sv.addView(col);
        box.addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));

        final Dialog d = ModuleUiKit.glassDialog(ctx, box);
        for (final Channel ch : chs) {
            TextView tv = new TextView(ctx);
            int t = src.tierFor(ch.name);
            tv.setText("【" + ch.name + "】 tier=" + (t >= 0 ? t : "未评测")
                    + "  " + ch.episodes.size() + " 集");
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setPadding(dp(10), dp(10), dp(10), dp(10));
            tv.setOnClickListener(v -> {
                d.dismiss();
                if (ch.episodes.isEmpty()) return;
                new Thread(() -> {
                    try {
                        SourceEngine engine = new SourceEngine();
                        PlayLink pl = engine.resolve(src, ch.episodes.get(0), ch.name);
                        ui(() -> play(pl, sub, ch));
                    } catch (Throwable e) {
                        ui(() -> ModuleUiKit.toast(ctx, "该线路也不可用：" + e.getMessage()));
                    }
                }).start();
            });
            col.addView(tv);
        }
        d.show();
    }

    // ══════════════════ 源管理 ══════════════════

    private void showSourceManager() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(ctx, "源管理"));

        final TextView info = new TextView(ctx);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        info.setPadding(dp(4), 0, dp(4), dp(8));
        info.setText("已导入 " + repo.size() + " 个源");
        box.addView(info);

        final Dialog d = ModuleUiKit.glassDialog(ctx, box);

        // 仓库地址（可改）
        TextView lab = new TextView(ctx);
        lab.setText("仓库地址（可改）");
        lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lab.setPadding(dp(4), dp(6), dp(4), dp(2));
        box.addView(lab);

        final EditText etUrl = new EditText(ctx);
        etUrl.setText(repo.repoUrl());
        etUrl.setSingleLine(true);
        etUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(etUrl);

        Button btnImport = new Button(ctx);
        btnImport.setText("从该地址导入 / 更新源");
        btnImport.setAllCaps(false);
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

        // 过滤开关
        final android.widget.Switch sw = new android.widget.Switch(ctx);
        sw.setText("启用源过滤（按源屏蔽）");
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sw.setChecked(repo.filterEnabled());
        sw.setOnCheckedChangeListener((b, checked) -> {
            repo.setFilterEnabled(checked);
            refreshStatus();
        });
        box.addView(sw);

        // 屏蔽列表
        TextView lab2 = new TextView(ctx);
        lab2.setText("屏蔽的源关键字（点条目可删除）");
        lab2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        lab2.setPadding(dp(4), dp(10), dp(4), dp(2));
        box.addView(lab2);

        final LinearLayout blockCol = new LinearLayout(ctx);
        blockCol.setOrientation(VERTICAL);
        box.addView(blockCol);

        showBlockedInto(blockCol);

        // 新增屏蔽关键字
        LinearLayout addRow = new LinearLayout(ctx);
        addRow.setOrientation(HORIZONTAL);
        final EditText etNew = new EditText(ctx);
        etNew.setHint("添加屏蔽关键字，如 example.com");
        etNew.setSingleLine(true);
        etNew.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        addRow.addView(etNew, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button btnAdd = new Button(ctx);
        btnAdd.setText("添加");
        btnAdd.setAllCaps(false);
        btnAdd.setOnClickListener(v -> {
            if (repo.addBlocked(etNew.getText().toString())) {
                etNew.setText("");
                showBlockedInto(blockCol);
                refreshStatus();
            } else {
                ModuleUiKit.toast(ctx, "已存在或为空");
            }
        });
        addRow.addView(btnAdd);
        box.addView(addRow);

        Button btnClose = new Button(ctx);
        btnClose.setText("关闭");
        btnClose.setAllCaps(false);
        btnClose.setOnClickListener(v -> d.dismiss());
        box.addView(btnClose);

        d.show();
    }

    /** 重绘屏蔽列表（提取出来便于删除后刷新） */
    private void showBlockedInto(LinearLayout blockCol) {
        blockCol.removeAllViews();
        Set<String> bl = repo.blocked();
        if (bl.isEmpty()) {
            TextView tv = new TextView(ctx);
            tv.setText("（空 —— 当前不屏蔽任何源）");
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            blockCol.addView(tv);
            return;
        }
        for (final String k : bl) {
            TextView tv = new TextView(ctx);
            tv.setText("• " + k + "    [删除]");
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tv.setPadding(dp(10), dp(8), dp(10), dp(8));
            tv.setOnClickListener(v -> {
                repo.removeBlocked(k);
                refreshStatus();
                showBlockedInto(blockCol);
            });
            blockCol.addView(tv);
        }
    }

    // ══════════════════ 工具 ══════════════════

    private void ui(Runnable r) {
        ctx.runOnUiThread(r);
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    /** 页面被销毁时停掉搜索 */
    public void onDestroy() {
        try {
            searcher.cancel();
        } catch (Throwable ignored) {
        }
    }
}
