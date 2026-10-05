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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.aliya.hy_vq.MediaPlayerActivity;
import com.aliya.hy_vq.R;
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

    // ── Kazumi 方案：源做成横向 Tab，一次只看一个源的结果 ──
    /** 源名 → 该源的搜索结果（按出现顺序） */
    private final java.util.LinkedHashMap<String, List<Subject>> resultsBySource =
            new java.util.LinkedHashMap<>();
    /** 源名 → 源对象 */
    private final java.util.LinkedHashMap<String, AnimeSource> sourceByName =
            new java.util.LinkedHashMap<>();
    /** 当前选中的源名（null = 还没选中） */
    private String currentSource = null;
    private LinearLayout listBox;
    // ── 两段式（方案 B：元数据搜索 → 选中后再查源）──
    /** 第二段：选源器头部（返回 + 番剧名），Stage1 隐藏 */
    private LinearLayout stageHeader;
    private TextView stageTitle;
    // ── 底栏（同步 Ani：多 Tab 切换，内容内嵌在同一容器）──
    private FrameLayout contentArea;
    private LinearLayout searchPanel, historyPanel, sourcePanel, infoPanel;
    /** Stage2 时隐藏的搜索栏 */
    private LinearLayout stageSearchBar;
    private LinearLayout bottomNav;
    private final java.util.List<View> navTabs = new java.util.ArrayList<>();
    private String currentTab = "search";
    /** 历史列表容器（历史 Tab） */
    private LinearLayout historyListBox;
    /** 当前选中的元数据条目（Stage2 上下文） */
    private AnimeMetadata.Entry currentEntry;
    /** 选源器是否正在加载 */
    private volatile boolean selectorLoading;
    /** 最近一次搜索结果（空 → 返回时展示推荐流） */
    private List<AnimeMetadata.Entry> lastSearchEntries = null;

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
        loadFeed();   // 首页推荐（P2）
        // 阶段 4：模块级二次告知（首次进入动漫页时）
        showModuleNoticeIfNeeded();
    }

    /** 模块告知是否已确认（与 SourceRepository 共用 anime_prefs） */
    private static final String KEY_MODULE_NOTICE = "anime_notice_accepted_v1";

    /**
     * 动漫模块的二次告知（决策 7 的"模块级提醒"）。
     *
     * <p>与首启协议的区别：首启协议覆盖全软件；这一条聚焦本模块特有的两件事
     * ——**第三方数据源**与**内置浏览器会开启 JavaScript**。放在模块入口而不是
     * 只在首启说一次，是因为用户可能很久以后才第一次点开这个模块。</p>
     */
    private void showModuleNoticeIfNeeded() {
        android.content.SharedPreferences p = ctx.getSharedPreferences(
                "anime_prefs", android.content.Context.MODE_PRIVATE);
        if (p.getBoolean(KEY_MODULE_NOTICE, false)) return;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(ctx, "使用前须知"));

        TextView body = new TextView(ctx);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        body.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        body.setLineSpacing(0, 1.25f);
        body.setText(String.join(System.lineSeparator(), new String[]{
            "本模块聚合第三方网站的内容，请注意：",
            "",
            "· 数据源来自你自行导入的订阅，本软件不提供、不存储、不分发内容。",
            "· 源由第三方维护，随时可能失效（实测约半数规则已失效）。",
            "· 为解析部分加密源，会启动内置浏览器并开启 JavaScript —— ",
            "  这意味着该页面的脚本会在本机执行，请知悉此风险。",
            "· 请确保你的使用符合当地法律法规。",
            "",
            "继续使用即表示你已知悉上述事项。"
        }));
        ScrollView sv = new ScrollView(ctx);
        sv.addView(body);
        box.addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

        final Dialog d = ModuleUiKit.glassDialog(ctx, box, false);
        d.setCancelable(false);

        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(HORIZONTAL);
        LayoutParams alp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(12);
        actions.setLayoutParams(alp);

        TextView decline = btn("我知道了，返回", color(
                com.google.android.material.R.attr.colorOnSurfaceVariant), color(
                com.google.android.material.R.attr.colorSurfaceContainerHigh));
        decline.setGravity(Gravity.CENTER);
        decline.setOnClickListener(v -> {
            ModuleUiKit.dismissWithAnim(d);
            ctx.onBackPressed();
        });
        actions.addView(decline, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView accept = btn("同意并继续", color(
                com.google.android.material.R.attr.colorOnPrimary), color(
                com.google.android.material.R.attr.colorPrimary));
        accept.setGravity(Gravity.CENTER);
        LayoutParams acp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        acp.leftMargin = dp(8);
        accept.setOnClickListener(v -> {
            p.edit().putBoolean(KEY_MODULE_NOTICE, true).apply();
            ModuleUiKit.dismissWithAnim(d);
        });
        actions.addView(accept, acp);
        box.addView(actions);

        d.show();
    }

    // ══════════════════ UI ══════════════════

    private void buildUi() {
        int pad = dp(14);

        // ═══ 搜索面板（默认 Tab）═══
        searchPanel = new LinearLayout(ctx);
        searchPanel.setOrientation(VERTICAL);

        // Stage2 头部：返回 + 番剧名（内嵌，不跳页）
        stageHeader = new LinearLayout(ctx);
        stageHeader.setOrientation(HORIZONTAL);
        stageHeader.setGravity(Gravity.CENTER_VERTICAL);
        stageHeader.setPadding(pad, dp(6), pad, dp(6));
        stageHeader.setVisibility(View.GONE);

        TextView backBtn = btn("← 返回", color(com.google.android.material.R.attr.colorOnPrimary),
                color(com.google.android.material.R.attr.colorPrimary));
        backBtn.setOnClickListener(v -> backToStage1());
        stageHeader.addView(backBtn);

        stageTitle = new TextView(ctx);
        stageTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        stageTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        stageTitle.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        stageTitle.setMaxLines(1);
        stageTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams stlp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stlp.leftMargin = dp(10);
        stageTitle.setLayoutParams(stlp);
        stageHeader.addView(stageTitle);
        searchPanel.addView(stageHeader);

        // 搜索行：圆角输入框 + 胶囊按钮
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
        searchPanel.addView(bar);
        stageSearchBar = bar;

        // 状态行
        tvStatus = new TextView(ctx);
        tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvStatus.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvStatus.setPadding(pad, 0, pad, dp(6));
        searchPanel.addView(tvStatus);

        // 搜索结果滚动区
        ScrollView sv = new ScrollView(ctx);
        resultBox = new LinearLayout(ctx);
        resultBox.setOrientation(VERTICAL);
        resultBox.setPadding(pad, 0, pad, dp(8));
        listBox = new LinearLayout(ctx);
        listBox.setOrientation(VERTICAL);
        resultBox.addView(listBox);
        sv.addView(resultBox);
        searchPanel.addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ═══ 历史面板 ═══
        historyPanel = buildHistoryPanel();
        // ═══ 源面板 ═══
        sourcePanel = buildSourcePanel();
        // ═══ 说明面板 ═══
        infoPanel = buildInfoPanel();

        // 内容容器：同一位置切换（内嵌，不跳页）
        contentArea = new FrameLayout(ctx);
        LinearLayout.LayoutParams clp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        contentArea.setLayoutParams(clp);
        contentArea.addView(searchPanel, fullMatch());
        contentArea.addView(historyPanel, fullMatch());
        contentArea.addView(sourcePanel, fullMatch());
        contentArea.addView(infoPanel, fullMatch());
        historyPanel.setVisibility(View.GONE);
        sourcePanel.setVisibility(View.GONE);
        infoPanel.setVisibility(View.GONE);
        addView(contentArea);

        // ═══ 底栏（同步 Ani）═══
        addView(buildBottomNav());

        refreshContinue();
    }

    private LayoutParams fullMatch() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // ══════════════════ 底栏 ══════════════════

    /** 底栏（对齐文件管理底栏写法 + Ani 的 Tab 结构） */
    private LinearLayout buildBottomNav() {
        bottomNav = new LinearLayout(ctx);
        bottomNav.setOrientation(HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER);
        int bg = color(com.google.android.material.R.attr.colorSurfaceContainer);
        bottomNav.setBackground(ModuleUiKit.rounded(ctx, 14, bg, 0));
        int pad = dp(4);
        bottomNav.setPadding(pad, dp(3), pad, dp(3));
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        bottomNav.setLayoutParams(lp);

        addAnimeTab("search", "搜索", R.drawable.ic_search,
                v -> switchTab("search"));
        addAnimeTab("history", "历史", R.drawable.ic_star,
                v -> switchTab("history"));
        addAnimeTab("source", "源", R.drawable.ic_settings,
                v -> switchTab("source"));
        addAnimeTab("info", "说明", R.drawable.ic_info,
                v -> switchTab("info"));

        updateBottomNav();   // 无入场动画（用户要求）
        return bottomNav;
    }

    private void addAnimeTab(final String key, String text, int iconRes, View.OnClickListener onClick) {
        LinearLayout tab = new LinearLayout(ctx);
        tab.setOrientation(VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setClickable(true);
        tab.setFocusable(true);
        tab.setOnClickListener(onClick);
        tab.setPadding(dp(8), dp(3), dp(8), dp(3));

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tab.addView(icon, new LayoutParams(dp(24), dp(24)));

        TextView label = new TextView(ctx);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        label.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        LayoutParams llp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(1);
        label.setLayoutParams(llp);
        tab.addView(label);

        tab.setTag(new Object[]{key, icon, label});
        bottomNav.addView(tab, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        navTabs.add(tab);
    }

    /** 切换底栏 Tab：同一容器内显隐（内嵌，不跳页） */
    private void switchTab(String key) {
        currentTab = key;
        searchPanel.setVisibility("search".equals(key) ? View.VISIBLE : View.GONE);
        historyPanel.setVisibility("history".equals(key) ? View.VISIBLE : View.GONE);
        sourcePanel.setVisibility("source".equals(key) ? View.VISIBLE : View.GONE);
        infoPanel.setVisibility("info".equals(key) ? View.VISIBLE : View.GONE);
        if ("history".equals(key)) {
            refreshContinue();
            renderHistory();
        }
        if ("source".equals(key)) refreshStatus();
        updateBottomNav();
    }

    private void updateBottomNav() {
        for (View tab : navTabs) {
            Object[] tg = (Object[]) tab.getTag();
            String key = (String) tg[0];
            ImageView icon = (ImageView) tg[1];
            TextView label = (TextView) tg[2];
            boolean on = key.equals(currentTab);
            int tint = color(on ? com.google.android.material.R.attr.colorPrimary
                    : com.google.android.material.R.attr.colorOnSurfaceVariant);
            icon.setColorFilter(tint);
            label.setTextColor(tint);
        }
    }

    // ══════════════════ 历史面板 ══════════════════

    private LinearLayout buildHistoryPanel() {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(VERTICAL);
        panel.setPadding(dp(14), dp(6), dp(14), dp(6));

        continueBox = new LinearLayout(ctx);
        continueBox.setOrientation(VERTICAL);
        panel.addView(continueBox);

        TextView head = ModuleUiKit.sectionHeader(ctx, "观看历史");
        panel.addView(head);

        ScrollView sv = new ScrollView(ctx);
        historyListBox = new LinearLayout(ctx);
        historyListBox.setOrientation(VERTICAL);
        sv.addView(historyListBox);
        panel.addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    /** 渲染完整观看历史 */
    private void renderHistory() {
        if (historyListBox == null) return;
        historyListBox.removeAllViews();
        java.util.List<WatchHistory.Entry> list = history.recent();
        if (list.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无观看记录");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setPadding(dp(4), dp(12), dp(4), dp(4));
            empty.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            historyListBox.addView(empty);
            return;
        }
        for (final WatchHistory.Entry e : list) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(ModuleUiKit.rippleBg(ctx, ModuleUiKit.rounded(ctx, 14,
                    color(com.google.android.material.R.attr.colorSurfaceContainerLow),
                    color(com.google.android.material.R.attr.colorOutlineVariant))));
            LayoutParams rlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(6);
            row.setLayoutParams(rlp);
            row.setClickable(true);

            TextView t1 = new TextView(ctx);
            t1.setText(e.subjectName);
            t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t1.setTypeface(null, android.graphics.Typeface.BOLD);
            t1.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
            t1.setMaxLines(1);
            t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(t1);

            TextView t2 = new TextView(ctx);
            String ep = (e.episodeTitle == null || e.episodeTitle.isEmpty())
                    ? "（未命名集）" : e.episodeTitle;
            t2.setText(ep + "  ·  " + e.positionText()
                    + (e.channelName == null || e.channelName.isEmpty() ? "" : "  · " + e.channelName));
            t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t2.setMaxLines(1);
            t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t2.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            LayoutParams t2lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            t2lp.topMargin = dp(2);
            t2.setLayoutParams(t2lp);
            row.addView(t2);

            row.setOnClickListener(v -> resumeFrom(e));
            historyListBox.addView(row);
        }
    }

    // ══════════════════ 源面板 / 说明面板 ══════════════════

    /** 源面板：把原「源管理」弹窗内容内嵌到 Tab */
    private LinearLayout buildSourcePanel() {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(VERTICAL);
        panel.setPadding(dp(14), dp(6), dp(14), dp(6));

        ScrollView sv = new ScrollView(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        buildSourceContent(box, null);
        sv.addView(box);
        panel.addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    /** 说明面板：模块能力与合规说明（阶段 4 的模块告知在此可随时查看） */
    private LinearLayout buildInfoPanel() {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(VERTICAL);
        panel.setPadding(dp(14), dp(6), dp(14), dp(6));
        panel.addView(ModuleUiKit.sectionHeader(ctx, "动漫模块说明"));

        TextView tv = new TextView(ctx);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLineSpacing(0, 1.45f);
        tv.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        tv.setText(String.join(System.lineSeparator(), new String[]{
            "【两段式搜索】",
            "第一步只查元数据库（B站/AniList），秒回并带封面评分；",
            "选中某部番后，第二步才去查该番的播放源。",
            "",
            "【自动选源】",
            "线路按 tier 升序排列，未评测排后；点线路胶囊即可播放，",
            "解析失败会自动改用内置浏览器（WebView）解析。",
            "",
            "【数据来源】",
            "· 元数据：B站番剧索引 / AniList（仅标题封面评分，不涉及播放）",
            "· 播放源：你自行导入的第三方订阅，本软件不提供、不存储内容",
            "· 源由第三方维护，随时可能失效（属此类工具的固有问题）",
            "",
            "【安全提示】",
            "为解析部分加密源，会启动内置浏览器并开启 JavaScript，",
            "这会在本机执行第三方页面脚本，请知悉此风险。",
            "",
            "【合规】",
            "请确保你的使用行为符合当地法律法规。",
            "",
            "—— 详见「关于」页与首次启动协议 ——"
        }));
        panel.addView(tv);
        return panel;
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

    /**
     * 首页推荐（P2）：对标 Ani/Kazumi 首页信息流——无需搜索即展示新番。
     * 数据源：B站番剧时间表（7天，实测 21KB 带封面）。
     */
    private void loadFeed() {
        if (listBox == null) return;
        listBox.removeAllViews();
        TextView hint = new TextView(ctx);
        hint.setText("热门番剧 · Bangumi 镜像 · 点击查看可用源");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(dp(4), dp(6), dp(4), dp(4));
        listBox.addView(hint);
        tvStatus.setText("加载推荐中…");

        new Thread(() -> {
            try {
                // 源优先级（用户要求：第三方私有镜像只作最末回退）
                //   1) AniList 热门 —— 公开 API，主力
                //   2) Kazumi Bangumi 镜像 —— 他人的私有镜像，仅作下下位替代（已在关于页致谢）
                List<AnimeMetadata.Entry> feed = null;
                try {
                    feed = AnimeMetadata.feedTrending();         // 主力：AniList（含中文名反查）
                } catch (Throwable ignored) {
                }
                if (feed == null || feed.isEmpty()) {
                    try {
                        feed = AnimeMetadata.feedPopular();      // 下下位：Kazumi 私有镜像
                    } catch (Throwable ignored) {
                    }
                }
                final List<AnimeMetadata.Entry> finalFeed = feed;
                ui(() -> {
                    if (finalFeed == null || finalFeed.isEmpty()) {
                        tvStatus.setText("推荐加载失败（不影响搜索）");
                        return;
                    }
                    renderGrid(finalFeed);
                    tvStatus.setText(finalFeed.size() + " 部热门番剧 · 或直接搜索");
                });
            } catch (Throwable t) {
                ui(() -> tvStatus.setText("推荐加载失败（不影响搜索）"));
            }
        }, "feed-load").start();
    }

    /** 大卡片网格（2 列，对齐 Kazumi 首页形态），无入场动画 */
    private void renderGrid(List<AnimeMetadata.Entry> entries) {
        if (listBox == null) return;
        listBox.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < entries.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(HORIZONTAL);
                LayoutParams rlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = dp(8);
                row.setLayoutParams(rlp);
                listBox.addView(row);
            }
            final AnimeMetadata.Entry e = entries.get(i);
            View card = buildGridCard(e);
            LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(4);
            card.setLayoutParams(lp);
            row.addView(card);
            // 第二列右边距
            if (i % 2 == 0 && i == entries.size() - 1) {
                // 末尾单卡：占满半行即可
            }
        }
    }

    /** 大卡片：大封面 + 标题 + 评分/集数（无动画） */
    private View buildGridCard(final AnimeMetadata.Entry e) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(VERTICAL);
        card.setPadding(dp(6), dp(6), dp(6), dp(8));
        card.setBackground(ModuleUiKit.rounded(ctx, 14,
                color(com.google.android.material.R.attr.colorSurfaceContainerLow),
                color(com.google.android.material.R.attr.colorOutlineVariant)));
        card.setClickable(true);
        card.setFocusable(true);

        // 封面（大图，3:4）
        android.widget.ImageView cover = new android.widget.ImageView(ctx);
        cover.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(ModuleUiKit.rounded(ctx, 10,
                color(com.google.android.material.R.attr.colorSurfaceContainerHigh), 0));
        card.addView(cover, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));
        if (e.cover != null && !e.cover.isEmpty()) loadCover(cover, e.cover);

        // 标题（2 行）
        TextView title = new TextView(ctx);
        title.setText(e.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams tlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(7);
        title.setLayoutParams(tlp);
        card.addView(title);

        // 评分 · 集数
        if (e.score >= 0 || e.episodes > 0) {
            TextView meta = new TextView(ctx);
            StringBuilder sb = new StringBuilder();
            if (e.score >= 0) sb.append("★ ").append(e.scoreText());
            if (e.episodes > 0) {
                if (sb.length() > 0) sb.append("   ");
                sb.append(e.episodes).append(" 集");
            }
            meta.setText(sb.toString());
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            meta.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            LayoutParams mlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(2);
            meta.setLayoutParams(mlp);
            card.addView(meta);
        }

        card.setOnClickListener(v -> openSourceSelector(e));
        return card;
    }

    private void doSearch() {
        final String kw = etKeyword.getText().toString().trim();
        if (kw.isEmpty()) {
            ModuleUiKit.toast(ctx, "请输入番剧名");
            return;
        }
        backToStage1();
        listBox.removeAllViews();
        tvStatus.setText("搜索中…（元数据库）");

        new Thread(() -> {
            List<AnimeMetadata.Entry> entries = AnimeMetadata.search(kw, (list, route) ->
                    ui(() -> renderMetadata(list)));
            ui(() -> {
                if (entries == null || entries.isEmpty()) {
                    lastSearchEntries = entries;
                    listBox.removeAllViews();
                    TextView empty = new TextView(ctx);
                    empty.setText("没有找到相关番剧，换个关键词试试");
                    empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    empty.setPadding(dp(4), dp(14), dp(4), dp(4));
                    empty.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
                    listBox.addView(empty);
                    tvStatus.setText("0 条结果");
                } else {
                    lastSearchEntries = entries;
                    renderGrid(entries);
                    tvStatus.setText(entries.size() + " 部 · 点任意一部查看可用源");
                }
            });
        }, "meta-search").start();
    }

    /** 渲染元数据卡片（封面 + 标题 + 评分 + 集数 + 标签） */
    private void renderMetadata(final List<AnimeMetadata.Entry> entries) {
        if (listBox == null) return;
        listBox.removeAllViews();
        for (final AnimeMetadata.Entry e : entries) {
            listBox.addView(buildMetadataCard(e));
        }
    }

    /** 单条元数据卡片 */
    private View buildMetadataCard(final AnimeMetadata.Entry e) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.setBackground(ModuleUiKit.rippleBg(ctx, ModuleUiKit.rounded(ctx, 14,
                color(com.google.android.material.R.attr.colorSurfaceContainerLow),
                color(com.google.android.material.R.attr.colorOutlineVariant))));
        LayoutParams rlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(8);
        row.setLayoutParams(rlp);
        row.setClickable(true);
        row.setFocusable(true);

        // 封面（无封面时用色块占位）
        android.widget.ImageView cover = new android.widget.ImageView(ctx);
        cover.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(ModuleUiKit.rounded(ctx, 8,
                color(com.google.android.material.R.attr.colorSurfaceContainerHigh), 0));
        LayoutParams clp = new LayoutParams(dp(56), dp(78));
        row.addView(cover, clp);
        if (e.cover != null && !e.cover.isEmpty()) {
            loadCover(cover, e.cover);
        }

        // 文字区
        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(VERTICAL);
        LayoutParams tlp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        text.setLayoutParams(tlp);

        TextView title = new TextView(ctx);
        title.setText(e.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(title);

        if (e.nativeTitle != null && !e.nativeTitle.isEmpty()
                && !e.nativeTitle.equals(e.title)) {
            TextView sub = new TextView(ctx);
            sub.setText(e.nativeTitle);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            sub.setMaxLines(1);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            LayoutParams slp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(2);
            sub.setLayoutParams(slp);
            text.addView(sub);
        }

        // 评分 + 集数 + 标签
        LinearLayout meta = new LinearLayout(ctx);
        meta.setOrientation(HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams mlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(6);
        meta.setLayoutParams(mlp);

        if (e.score >= 0) {
            TextView sc = new TextView(ctx);
            sc.setText("★ " + e.scoreText());
            sc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sc.setTextColor(color(com.google.android.material.R.attr.colorPrimary));
            meta.addView(sc);
        }
        if (e.episodes > 0) {
            TextView ep = new TextView(ctx);
            ep.setText("  " + e.episodes + " 集");
            ep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            ep.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            meta.addView(ep);
        }
        if (e.tags != null && !e.tags.isEmpty()) {
            TextView tg = new TextView(ctx);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, e.tags.size()); i++) {
                if (i > 0) sb.append(" · ");
                sb.append(e.tags.get(i));
            }
            tg.setText("  " + sb);
            tg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tg.setMaxLines(1);
            tg.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tg.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            LayoutParams glp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tg.setLayoutParams(glp);
            tg.setGravity(Gravity.END);
            meta.addView(tg);
        }
        text.addView(meta);

        row.addView(text);
        row.setOnClickListener(v -> openSourceSelector(e));
        return row;
    }

    /** 异步加载封面（简单缓存，避免重复下载） */
    private final java.util.Map<String, android.graphics.Bitmap> coverCache =
            new java.util.HashMap<>();
    private void loadCover(final android.widget.ImageView target, final String url) {
        android.graphics.Bitmap cached = coverCache.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageResource(android.R.color.darker_gray);
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile");
                java.io.InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                c.disconnect();
                byte[] data = bos.toByteArray();
                final android.graphics.Bitmap bmp =
                        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bmp != null) {
                    coverCache.put(url, bmp);
                    ui(() -> {
                        if (target.getTag() == null || target.getTag().equals(url)) {
                            target.setImageBitmap(bmp);
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
        }, "cover-load").start();
        target.setTag(url);
    }

    /** 回到第一段（元数据列表） */
    /**
     * 系统返回键处理。
     *
     * <p>Stage2（详情页/选源器）→ 先回 Stage1；非搜索 Tab → 先切回搜索 Tab；
     * 都不是则交给上层（返回首页）。否则用户在详情页按返回会直接被踢回首页。</p>
     *
     * @return true 表示已消费该返回事件
     */
    public boolean onBackPressed() {
        if (stageHeader != null && stageHeader.getVisibility() == View.VISIBLE) {
            backToStage1();
            return true;
        }
        if (currentTab != null && !"search".equals(currentTab)) {
            switchTab("search");
            return true;
        }
        return false;
    }

    private void backToStage1() {
        currentEntry = null;
        selectorLoading = false;
        if (stageHeader != null) stageHeader.setVisibility(View.GONE);
        if (stageSearchBar != null) stageSearchBar.setVisibility(View.VISIBLE);
        if (tvStatus != null) tvStatus.setVisibility(View.VISIBLE);
        if (listBox != null) listBox.removeAllViews();
        refreshStatus();
        // 若当前无搜索结果，回到推荐流（P2）
        if (lastSearchEntries == null || lastSearchEntries.isEmpty()) loadFeed();
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

    /**
     * 收到一个源的结果：**先缓存，不直接全渲染**（Kazumi 方案）。
     * 第一个出结果的源自动选中并展示，其余等用户点 Tab。
     */
    // ══════════════════ Stage 2：选源器（内嵌，Animeko 风格） ══════════════════

    /**
     * 第二段：对选中的番剧查可用源，并以 Animeko 式「源行 + 线路胶囊」展示。
     * 只有用户选中番剧后才查源 —— 搜索阶段只打元数据库（1 个请求）。
     */
    private void openSourceSelector(final AnimeMetadata.Entry entry) {
        final List<AnimeSource> srcs = repo.active();
        if (srcs.isEmpty()) {
            ModuleUiKit.toast(ctx, repo.isEmpty() ? "还没有源，请先导入" : "所有源都被屏蔽了");
            return;
        }
        currentEntry = entry;
        selectorLoading = true;

        // 切到 Stage2
        if (stageHeader != null) {
            stageHeader.setVisibility(View.VISIBLE);
            stageTitle.setText(entry.title);
        }
        if (stageSearchBar != null) stageSearchBar.setVisibility(View.GONE);
        if (listBox != null) listBox.removeAllViews();
        tvStatus.setVisibility(View.VISIBLE);

        // ── 详情页（P1：封面 + 评分 + 集数 + 标签 + 简介）──
        listBox.addView(buildDetailHeader(entry));

        TextView loading = new TextView(ctx);
        loading.setText("正在聚合可用源…");
        loading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        loading.setPadding(dp(4), dp(10), dp(4), dp(4));
        loading.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        listBox.addView(loading);
        tvStatus.setText("正在聚合该番剧的可用源…");

        // ── P3：多关键词按命中率依次试（中文 11/18 > 日文 1/18 > 罗马字 0/18）──
        final java.util.List<String> kws = entry.searchKeywords();
        if (kws.isEmpty()) {
            loading.setText("该条目缺少可用关键词");
            return;
        }
        trySourcesWithKeywords(srcs, kws, 0, entry, loading);
    }

    /** 详情页头部：大封面 + 标题 + 评分/集数 + 标签 + 简介 */
    private View buildDetailHeader(AnimeMetadata.Entry e) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(8));

        // 顶部：封面 + 基本信息
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(HORIZONTAL);

        android.widget.ImageView cover = new android.widget.ImageView(ctx);
        cover.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(ModuleUiKit.rounded(ctx, 8,
                color(com.google.android.material.R.attr.colorSurfaceContainerHigh), 0));
        top.addView(cover, new LayoutParams(dp(92), dp(128)));
        if (e.cover != null && !e.cover.isEmpty()) loadCover(cover, e.cover);

        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(VERTICAL);
        LayoutParams ilp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.leftMargin = dp(12);
        info.setLayoutParams(ilp);

        TextView title = new TextView(ctx);
        title.setText(e.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        info.addView(title);

        if (e.nativeTitle != null && !e.nativeTitle.isEmpty()
                && !e.nativeTitle.equals(e.title)) {
            TextView nt = new TextView(ctx);
            nt.setText(e.nativeTitle);
            nt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            nt.setMaxLines(2);
            nt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nt.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            LayoutParams nlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(3);
            nt.setLayoutParams(nlp);
            info.addView(nt);
        }

        // 评分 / 集数 徽标行
        LinearLayout badges = new LinearLayout(ctx);
        badges.setOrientation(HORIZONTAL);
        LayoutParams blp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        badges.setLayoutParams(blp);
        if (e.score >= 0) {
            badges.addView(detailBadge("★ " + e.scoreText(), true));
        }
        if (e.episodes > 0) {
            badges.addView(detailBadge(e.episodes + " 集", false));
        }
        if (!e.source.isEmpty()) {
            badges.addView(detailBadge(e.source, false));
        }
        info.addView(badges);

        // 标签
        if (e.tags != null && !e.tags.isEmpty()) {
            TextView tg = new TextView(ctx);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < e.tags.size() && i < 6; i++) {
                if (i > 0) sb.append(" · ");
                sb.append(e.tags.get(i));
            }
            tg.setText(sb.toString());
            tg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tg.setMaxLines(2);
            tg.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tg.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            LayoutParams tlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(6);
            tg.setLayoutParams(tlp);
            info.addView(tg);
        }

        top.addView(info);
        box.addView(top);

        // 简介
        if (e.desc != null && !e.desc.isEmpty()) {
            TextView desc = new TextView(ctx);
            String d = e.desc.length() > 300 ? e.desc.substring(0, 300) + "…" : e.desc;
            desc.setText(d.replaceAll("<[^>]+>", ""));
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            desc.setMaxLines(5);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            desc.setLineSpacing(0, 1.4f);
            desc.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
            LayoutParams dlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(10);
            desc.setLayoutParams(dlp);
            box.addView(desc);
        }

        // 分隔 + 小标题
        TextView sep = ModuleUiKit.sectionHeader(ctx, "可用播放源");
        LayoutParams slp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        sep.setLayoutParams(slp);
        box.addView(sep);

        return box;
    }

    private TextView detailBadge(String text, boolean highlight) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setPadding(dp(7), dp(3), dp(7), dp(3));
        t.setTextColor(highlight
                ? color(com.google.android.material.R.attr.colorOnPrimary)
                : color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        t.setBackground(ModuleUiKit.rounded(ctx, 10,
                color(highlight ? com.google.android.material.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorSurfaceContainerHigh), 0));
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    /**
     * 按关键词列表依次尝试搜源，任一关键词命中即停（P3）。
     * 中文关键词实测命中 11/18，日文仅 1/18 —— 顺序即命中率。
     */
    private void trySourcesWithKeywords(final List<AnimeSource> srcs,
                                        final java.util.List<String> kws,
                                        final int idx,
                                        final AnimeMetadata.Entry entry,
                                        final TextView loading) {
        if (idx >= kws.size()) {
            // 全部关键词都无源
            selectorLoading = false;
            ui(() -> {
                loading.setText("没有源能提供这部番剧（试过 " + kws.size() + " 组关键词）");
                tvStatus.setText("无可用源 · 可在「源」Tab 一键测评看源是否失效");
            });
            return;
        }
        final String kw = kws.get(idx);
        ui(() -> {
            loading.setText("正在聚合可用源…（关键词：" + kw + "）");
            tvStatus.setText("聚合中 · 关键词 " + (idx + 1) + "/" + kws.size());
        });

        searcher.search(srcs, kw, new AnimeSearchManager.Callback() {
            final java.util.concurrent.atomic.AtomicInteger got =
                    new java.util.concurrent.atomic.AtomicInteger(0);

            @Override public void onStart(int total) { }

            @Override public void onSourceDone(final AnimeSource source, List<Subject> subjects) {
                if (subjects == null || subjects.isEmpty()) return;
                got.incrementAndGet();
                new Thread(() -> {
                    try {
                        SourceEngine engine = new SourceEngine();
                        List<Channel> chs = engine.channels(source, subjects.get(0));
                        if (chs != null && !chs.isEmpty()) {
                            ui(() -> {
                                loading.setVisibility(View.GONE);
                                addSelectorRow(source, subjects.get(0), chs);
                            });
                        }
                    } catch (Throwable ignored) {
                    }
                }, "fetch-channels").start();
            }

            @Override public void onSourceError(AnimeSource source, String reason) { }

            @Override public void onProgress(int done, int total) {
                ui(() -> tvStatus.setText("聚合中 " + done + "/" + total + " · 关键词「" + kw + "」"));
            }

            @Override public void onAllDone(int ok, int total) {
                // 渠道异步取线路，稍等再判定是否真的有源
                new Thread(() -> {
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
                    ui(() -> {
                        int rows = countSelectorRows();
                        if (rows > 0) {
                            selectorLoading = false;
                            tvStatus.setText(rows + " 个源可用 · 点线路播放");
                        } else if (got.get() > 0) {
                            // 搜到条目但取不到线路 → 试下一个关键词
                            trySourcesWithKeywords(srcs, kws, idx + 1, entry, loading);
                        } else {
                            trySourcesWithKeywords(srcs, kws, idx + 1, entry, loading);
                        }
                    });
                }, "kw-decide").start();
            }
        });
    }

    /** 只数选源行（详情头部也是 LinearLayout，需跳过） */
    private int countSelectorRows() {
        if (listBox == null) return 0;
        int n = 0;
        // 详情头部固定是第 0 个子项，从 1 开始数
        for (int i = 1; i < listBox.getChildCount(); i++) {
            if (listBox.getChildAt(i) instanceof LinearLayout) n++;
        }
        return n;
    }

    /**
     * 追加一行「源 + 线路胶囊」（Animeko 媒体选择器样式）。
     * 线路按 tier 升序，未评测排最后；点胶囊即解析播放。
     */
    private void addSelectorRow(final AnimeSource src, final Subject sub,
                                final List<Channel> chs) {
        if (listBox == null) return;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(ModuleUiKit.rounded(ctx, 14,
                color(com.google.android.material.R.attr.colorSurfaceContainerLow),
                color(com.google.android.material.R.attr.colorOutlineVariant)));
        LayoutParams rlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(8);
        row.setLayoutParams(rlp);

        // 源名 + tier
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(ctx);
        name.setText(src.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        head.addView(name);

        TextView tier = new TextView(ctx);
        tier.setText(src.tier >= 0 ? ("  tier " + src.tier) : "  未评测");
        tier.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tier.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        head.addView(tier);
        row.addView(head);

        // 线路胶囊（按 tier 升序）
        List<Channel> sorted = new ArrayList<>(chs);
        sorted.sort((a, b) -> tierRank(src, a) - tierRank(src, b));

        LinearLayout pills = new LinearLayout(ctx);
        pills.setOrientation(HORIZONTAL);
        pills.setGravity(Gravity.START);
        LayoutParams plp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(8);
        pills.setLayoutParams(plp);

        int idx = 0;
        for (final Channel ch : sorted) {
            if (ch.episodes == null || ch.episodes.isEmpty()) continue;
            int t = src.tierFor(ch.name);
            TextView pill = new TextView(ctx);
            String label = (ch.name == null || ch.name.isEmpty() ? "线路" : ch.name)
                    + (t >= 0 ? ("  t" + t) : "");
            pill.setText(label);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            pill.setPadding(dp(10), dp(6), dp(10), dp(6));
            pill.setTextColor(idx == 0
                    ? color(com.google.android.material.R.attr.colorOnPrimary)
                    : color(com.google.android.material.R.attr.colorOnSurface));
            pill.setBackground(ModuleUiKit.rippleBg(ctx, ModuleUiKit.rounded(ctx, 14,
                    color(idx == 0 ? com.google.android.material.R.attr.colorPrimary
                            : com.google.android.material.R.attr.colorSurfaceContainerHigh), 0)));
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (idx > 0) lp.leftMargin = dp(6);
            pill.setLayoutParams(lp);
            pill.setOnClickListener(v -> playFromSelector(src, sub, ch));
            pills.addView(pill);
            idx++;
            if (idx >= 8) break;   // 胶囊上限，避免一行爆掉
        }
        row.addView(pills);
        listBox.addView(row);
    }

    /** 从选源器点线路：解析直链 → 播放（失败则 WebView 兜底 → 手动选线） */
    private void playFromSelector(final AnimeSource src, final Subject sub, final Channel ch) {
        tvStatus.setText("解析「" + ch.name + "」…");
        new Thread(() -> {
            try {
                SourceEngine engine = new SourceEngine();
                Episode first = ch.episodes.get(0);
                PlayLink pl = engine.resolve(src, first, ch.name);
                final PlayLink link = pl;
                ui(() -> play(link, sub, ch, first, src));
            } catch (Throwable t) {
                ui(() -> {
                    tvStatus.setText("该线路解析失败，尝试浏览器解析…");
                    tryWebView(src, sub, java.util.Collections.singletonList(ch));
                });
            }
        }, "selector-play").start();
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
        // 把整条线路的集列表一并传给播放器，这样在播放器内部就能选集
        // （参考 Kazumi：不必退回列表页）
        String[] epTitles = null, epUrls = null;
        int epIndex = -1;
        if (ch != null && ep != null && ch.episodes != null && !ch.episodes.isEmpty()) {
            int n = ch.episodes.size();
            epTitles = new String[n];
            epUrls = new String[n];
            for (int k = 0; k < n; k++) {
                Episode e2 = ch.episodes.get(k);
                epTitles[k] = (e2.title == null || e2.title.isEmpty())
                        ? ("第 " + (k + 1) + " 集") : e2.title;
                epUrls[k] = e2.url;
                if (e2.url != null && e2.url.equals(ep.url)) epIndex = k;
            }
        }
        Intent i = MediaPlayerActivity.urlIntent(ctx, link.url, title, headers, key, startMs,
                epTitles, epUrls, epIndex,
                src != null ? src.rawJson : null,
                ch != null ? ch.name : null);
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

    /**
     * 一键测评：对全部源跑 search→channels→resolve 三段实测，
     * 按结果重算 tier 并写入覆盖层（独立于订阅 JSON，重导入不丢）。
     *
     * <p>为什么必须做：订阅标注的 tier 已与现实反向（t0 源全挂、能播的反标 t4），
     * Stage2 线路胶囊按 tier 排序，假档位会让第一个胶囊是坏的。</p>
     */
    private void showTierEvalDialog() {
        List<AnimeSource> srcs = repo.active();
        if (srcs.isEmpty()) {
            ModuleUiKit.toast(ctx, repo.isEmpty() ? "还没有源" : "所有源都被屏蔽了");
            return;
        }
        final String keyword = (etKeyword != null
                && !etKeyword.getText().toString().trim().isEmpty())
                ? etKeyword.getText().toString().trim() : "葬送的芙莉莲";

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(ctx, "实测档位（tier）"));
        TextView tip = new TextView(ctx);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setLineSpacing(0, 1.4f);
        tip.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tip.setPadding(dp(4), 0, dp(4), dp(8));
        tip.setText("将对 " + srcs.size() + " 个源依次测：\n"
                + "搜索 → 取线路 → 解析直链，按结果重算 tier（0 最优 / 9 死源）。\n"
                + "测试关键词：" + keyword + "\n"
                + "约需 1~3 分钟，期间请勿关闭。");
        box.addView(tip);

        final TextView progress = new TextView(ctx);
        progress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        progress.setTextColor(color(com.google.android.material.R.attr.colorPrimary));
        progress.setPadding(dp(4), dp(4), dp(4), dp(4));
        progress.setText("准备中…");
        box.addView(progress);

        final android.widget.ProgressBar pb = new android.widget.ProgressBar(
                ctx, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(srcs.size());
        pb.setProgress(0);
        box.addView(pb, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));

        final Dialog d = ModuleUiKit.glassDialog(ctx, box, false);
        d.setCancelable(false);
        d.show();

        TierEvaluator.evaluateAll(srcs, keyword, new TierEvaluator.Callback() {
            @Override public void onProgress(int done, int total, String current) {
                ui(() -> {
                    pb.setProgress(done);
                    progress.setText("已测 " + done + "/" + total + " · " + current);
                });
            }

            @Override public void onDone(final List<TierEvaluator.Result> results) {
                ui(() -> {
                    // 写入覆盖层：源级 + 线路级
                    java.util.Map<String, Integer> put = new java.util.HashMap<>();
                    int dead = 0, usable = 0;
                    for (TierEvaluator.Result r : results) {
                        put.put(r.sourceName, r.tier);
                        if (r.tier >= 9) dead++;
                        if (r.resolveOk) usable++;
                        for (java.util.Map.Entry<String, Integer> ce : r.channelTiers.entrySet()) {
                            put.put(r.sourceName + "#" + ce.getKey(), ce.getValue());
                        }
                    }
                    repo.putTiers(put);
                    showTierResults(results, put.size(), usable, dead);
                    d.dismiss();
                    // 源面板刷新（按钮文案会变化）
                    rebuildSourcePanel();
                });
            }
        }, 180_000L);   // 180 秒总预算
    }

    /** 测评结果汇总 */
    private void showTierResults(final List<TierEvaluator.Result> results,
                                 int keys, int usable, int dead) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(ctx, "测评结果"));

        TextView sum = new TextView(ctx);
        sum.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sum.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        sum.setPadding(dp(4), 0, dp(4), dp(8));
        sum.setText("可直链播放 " + usable + " 个 · 死源 " + dead
                + " 个 · 已写入 " + keys + " 条档位");
        box.addView(sum);

        ScrollView sv = new ScrollView(ctx);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(VERTICAL);
        sv.addView(col);
        box.addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));

        // 按 tier 升序展示
        List<TierEvaluator.Result> sorted = new ArrayList<>(results);
        sorted.sort((a, b) -> a.tier - b.tier);
        for (TierEvaluator.Result r : sorted) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(5), dp(8), dp(5));

            TextView t = new TextView(ctx);
            t.setText("t" + r.tier);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setPadding(dp(6), dp(2), dp(6), dp(2));
            t.setTextColor(r.tier <= 2
                    ? color(com.google.android.material.R.attr.colorOnPrimary)
                    : color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            t.setBackground(ModuleUiKit.rounded(ctx, 8,
                    color(r.tier <= 2 ? com.google.android.material.R.attr.colorPrimary
                            : com.google.android.material.R.attr.colorSurfaceContainerHigh), 0));
            row.addView(t);

            TextView n = new TextView(ctx);
            n.setText("  " + r.sourceName);
            n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            n.setMaxLines(1);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            n.setLayoutParams(new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(n);

            TextView sm = new TextView(ctx);
            sm.setText(r.summary());
            sm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            sm.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            row.addView(sm);

            col.addView(row);
        }

        final Dialog d = ModuleUiKit.glassDialog(ctx, box);
        d.show();
    }

    /** 重建源面板内容（测评后刷新按钮与状态） */
    private void rebuildSourcePanel() {
        if (sourcePanel == null) return;
        sourcePanel.removeAllViews();
        ScrollView sv = new ScrollView(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        buildSourceContent(box, null);
        sv.addView(box);
        sourcePanel.addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        refreshStatus();
    }

    /** 源管理内容（内嵌到「源」Tab；d 为 null 时「关闭」切回搜索 Tab） */
    private void buildSourceContent(final LinearLayout box, final Dialog d) {

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
        btnClose.setOnClickListener(v -> {
            if (d != null) d.dismiss();
            else switchTab("search");
        });
        box.addView(btnClose);

    }

    /** 源管理弹窗入口（保留给 Stage2/其他场景复用） */
    private void showSourceManager() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(ctx, "源管理"));

        // ── 一键测评（tier 补全：订阅里的静态 tier 已与现实反向）──
        TextView btnEval = btn(repo.hasTierOverride() ? "重新测评（已有实测档位）" : "一键测评 · 生成实测档位",
                color(com.google.android.material.R.attr.colorOnPrimary),
                color(com.google.android.material.R.attr.colorPrimary));
        LayoutParams elp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.bottomMargin = dp(10);
        btnEval.setLayoutParams(elp);
        btnEval.setGravity(Gravity.CENTER);
        btnEval.setOnClickListener(v -> showTierEvalDialog());
        box.addView(btnEval);
        final Dialog d = ModuleUiKit.glassDialog(ctx, box);
        buildSourceContent(box, d);
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
