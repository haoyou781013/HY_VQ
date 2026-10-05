package com.aliya.hy_vq.anime;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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
    /** Stage1 的渐变 Hero 头部（Stage2 时整体隐藏，露出简洁详情页） */
    private LinearLayout heroHeader;
    private TextView stageTitle;
    // ── 底栏（同步 Ani：多 Tab 切换，内容内嵌在同一容器）──
    private FrameLayout contentArea;
    private LinearLayout searchPanel, historyPanel, sourcePanel, infoPanel, cachePanel;
    /** 离线缓存管理器 */
    private OfflineCache offlineCache;
    /** 缓存列表容器 */
    private LinearLayout cacheListBox;
    /** 缓存订阅（避免重建时丢引用） */
    private OfflineCache.Listener cacheListener;
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
        WebViewPageFetcher.setAppContext(context);   // Kazumi 规则需 WebView 渲染
        offlineCache = new OfflineCache(context);
        cacheListener = this::renderCachePanel;
        offlineCache.setListener(cacheListener);
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
        stageHeader.setPadding(dp(10), dp(6), pad, dp(6));
        stageHeader.setVisibility(View.GONE);
        // 返回条整条压在主渐变上 —— 与 Hero 同源，Stage2 仍保有品牌色
        GradientDrawable shBg = Aurora.grad(ctx, Aurora.G_HERO,
                GradientDrawable.Orientation.LEFT_RIGHT);
        shBg.setCornerRadius(dp(Aurora.R_INNER));
        stageHeader.setBackground(shBg);

        TextView backBtn = btn("← 返回", 0xFFFFFFFF, 0);
        backBtn.setBackground(ModuleUiKit.rounded(ctx, dp(Aurora.R_PILL), 0x33FFFFFF, 0));
        backBtn.setOnClickListener(v -> backToStage1());
        stageHeader.addView(backBtn);

        stageTitle = new TextView(ctx);
        stageTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        stageTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        stageTitle.setTextColor(0xFFFFFFFF);
        stageTitle.setMaxLines(1);
        stageTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams stlp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stlp.leftMargin = dp(10);
        stageTitle.setLayoutParams(stlp);
        stageHeader.addView(stageTitle);
        searchPanel.addView(stageHeader);

        // ═══ Aurora Hero：渐变头图区（原为平铺搜索行）═══
        // 结构：深紫→紫→粉 三段渐变、底部大圆角，标题压在其上，搜索条悬浮其中。
        // 目的是让首屏有明确的视觉锚点，而不是一上来就是白底输入框。
        LinearLayout hero = new LinearLayout(ctx);
        hero.setOrientation(VERTICAL);
        GradientDrawable heroBg = Aurora.grad(ctx, Aurora.G_HERO,
                GradientDrawable.Orientation.TL_BR);
        heroBg.setCornerRadii(new float[]{
                0, 0, 0, 0, dp(Aurora.R_CARD + 8), dp(Aurora.R_CARD + 8),
                dp(Aurora.R_CARD + 8), dp(Aurora.R_CARD + 8)});
        hero.setBackground(heroBg);
        hero.setPadding(pad, dp(20), pad, dp(16));

        TextView heroTitle = new TextView(ctx);
        heroTitle.setText("探索");
        heroTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        heroTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        heroTitle.setTextColor(0xFFFFFFFF);
        heroTitle.setLetterSpacing(-0.03f);
        heroHeader = hero;
        searchPanel.addView(hero);

        TextView heroSub = new TextView(ctx);
        heroSub.setText("找一部，然后让它自己去找源");
        heroSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        heroSub.setTextColor(0xD9FFFFFF);
        LayoutParams hslp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hslp.topMargin = dp(2);
        heroSub.setLayoutParams(hslp);

        // 搜索条：白色圆角胶囊（在深色渐变上跳出来）+ 渐变按钮
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams barLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(14);
        bar.setLayoutParams(barLp);

        etKeyword = new EditText(ctx);
        etKeyword.setHint("输入番剧名…");
        etKeyword.setSingleLine(true);
        etKeyword.setInputType(InputType.TYPE_CLASS_TEXT);
        etKeyword.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        etKeyword.setTextColor(0xFF2E2350);
        etKeyword.setHintTextColor(0x8A2E2350);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(0xFFFFFFFF);
        inputBg.setCornerRadius(dp(Aurora.R_INNER));
        etKeyword.setBackground(inputBg);
        etKeyword.setPadding(dp(14), dp(11), dp(14), dp(11));
        bar.addView(etKeyword, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnSearch = btn("搜", 0xFFFFFFFF, 0);
        btnSearch.setBackground(Aurora.grad(ctx, Aurora.G_MAIN, dp(Aurora.R_PILL)));
        btnSearch.setPadding(dp(22), dp(12), dp(22), dp(12));
        btnSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btnSearch.setTypeface(null, android.graphics.Typeface.BOLD);
        btnSearch.setOnClickListener(v -> doSearch());
        LayoutParams blp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(8);
        bar.addView(btnSearch, blp);

        hero.addView(heroTitle);
        hero.addView(heroSub);
        hero.addView(bar);
        stageSearchBar = bar;   // Stage2 时整体隐藏（连同标题一起藏，见 backToStage1）

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
        // ═══ 缓存面板 ═══
        cachePanel = buildCachePanel();
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
        contentArea.addView(cachePanel, fullMatch());
        contentArea.addView(infoPanel, fullMatch());
        historyPanel.setVisibility(View.GONE);
        sourcePanel.setVisibility(View.GONE);
        cachePanel.setVisibility(View.GONE);
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
    /**
     * 底栏（Aurora 重构）：深紫底 + 选中项渐变胶囊。
     *
     * <p>原设计是单色圆角条 + 图标变色，信息弱；新设计让「选中」由**形状与色相**同时表达，
     * 远看也能一眼定位当前位置。</p>
     */
    /**
     * 底栏（按用户反馈重做）：
     * <ul>
     *   <li><b>高度</b>：原 50dp 显得太矮 → 提到 64dp 最小高；</li>
     *   <li><b>背景</b>：原深紫不透明块在浅色页上"方方一块很显眼"，
     *       改<b>浅色渐变</b>并与页面同调，视觉上不再是一块异物；</li>
     *   <li><b>圆角</b>：用 clipToOutline 强制裁剪，杜绝"呈方形"；</li>
     *   <li><b>切换</b>：无任何动画。</li>
     * </ul>
     */
    private LinearLayout buildBottomNav() {
        bottomNav = new LinearLayout(ctx);
        bottomNav.setOrientation(HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER_VERTICAL);
        bottomNav.setClipChildren(true);

        // 浅色渐变（与主渐变同色相，仅降低饱和度）：浅紫 → 浅粉 → 浅橙
        GradientDrawable bg = Aurora.grad(ctx,
                new int[]{0xFFF4EEFF, 0xFFFFEEF4, 0xFFFFF4E9},
                GradientDrawable.Orientation.TL_BR);
        bg.setCornerRadius(dp(26));
        bottomNav.setBackground(bg);
        // 强制按圆角裁剪 —— 单靠 drawable 圆角在某些边界布局下会被裁成方形
        final int navRadius = dp(26);
        bottomNav.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), navRadius);
            }
        });
        bottomNav.setClipToOutline(true);

        int pad = dp(6);
        bottomNav.setPadding(pad, dp(7), pad, dp(7));   // 上下加高，解决"太低"
        bottomNav.setMinimumHeight(dp(64));

        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(6);
        bottomNav.setLayoutParams(lp);

        addAnimeTab("search", "探索", R.drawable.ic_search, v -> switchTab("search"));
        addAnimeTab("history", "历史", R.drawable.ic_star, v -> switchTab("history"));
        addAnimeTab("source", "源", R.drawable.ic_settings, v -> switchTab("source"));
        addAnimeTab("cache", "缓存", R.drawable.ic_download, v -> switchTab("cache"));
        addAnimeTab("info", "说明", R.drawable.ic_info, v -> switchTab("info"));

        updateBottomNav();
        return bottomNav;
    }

    /**
     * 单个 Tab：图标 + 文字竖排；<b>选中态是渐变胶囊</b>（主渐变紫→粉→橙），
     * 图标与文字转白；未选中是透明 + 弱化色。切换不带动画（用户要求：无过度动画）。
     */
    private void addAnimeTab(final String key, String text, int iconRes, View.OnClickListener onClick) {
        LinearLayout tab = new LinearLayout(ctx);
        tab.setOrientation(VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setClickable(true);
        tab.setFocusable(true);
        tab.setOnClickListener(onClick);
        tab.setPadding(dp(6), dp(6), dp(6), dp(6));

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        tab.addView(icon, new LayoutParams(dp(20), dp(20)));

        TextView label = new TextView(ctx);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        LayoutParams llp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(2);
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
        cachePanel.setVisibility("cache".equals(key) ? View.VISIBLE : View.GONE);
        infoPanel.setVisibility("info".equals(key) ? View.VISIBLE : View.GONE);
        if ("cache".equals(key)) renderCachePanel();
        if ("history".equals(key)) {
            refreshContinue();
            renderHistory();
        }
        if ("source".equals(key)) refreshStatus();
        updateBottomNav();
    }

    /** 选中态 = 渐变胶囊 + 白色前景 + 呼吸反馈；未选中 = 透明 + 弱化前景 */
    private void updateBottomNav() {
        boolean anyActive = false;
        for (int i = 0; i < navTabs.size(); i++) {
            View tab = navTabs.get(i);
            Object[] tag = (Object[]) tab.getTag();
            if (tag == null || tag.length < 3) continue;
            String key = (String) tag[0];
            ImageView icon = (ImageView) tag[1];
            TextView label = (TextView) tag[2];
            boolean active = key.equals(currentTab);
            if (active) anyActive = true;

            if (active) {
                // 渐变胶囊：紫 → 粉 → 橙，左上到右下
                GradientDrawable bg = Aurora.grad(ctx, Aurora.G_MAIN, dp(Aurora.R_PILL));
                tab.setBackground(bg);
                icon.setColorFilter(0xFFFFFFFF);
                label.setTextColor(0xFFFFFFFF);   // 呼吸动画已移除
            } else {
                tab.setBackground(null);   // 浅色底上用深字，保证可读
                int muted = 0xB0463A5F;
                icon.setColorFilter(muted);
                label.setTextColor(muted);
            }
        }
        // 偶发兜底：当前 Tab 不在底栏（如详情页）→ 高亮"探索"
        if (!anyActive && !navTabs.isEmpty()) {
            View first = navTabs.get(0);
            Object[] tag = (Object[]) first.getTag();
            if (tag != null && tag.length >= 3) {
                ((ImageView) tag[1]).setColorFilter(0xFFFFFFFF);
                ((TextView) tag[2]).setTextColor(0xFFFFFFFF);   // 保持选中态白字
            }
        }
    }

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
            empty.setTextColor(0xFF6A5AE0);
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

    // ══════════════════ 缓存面板（离线缓存） ══════════════════

    private LinearLayout buildCachePanel() {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(VERTICAL);
        panel.setPadding(dp(14), dp(6), dp(14), dp(6));
        panel.addView(ModuleUiKit.sectionHeader(ctx, "离线缓存"));

        TextView tip = new TextView(ctx);
        tip.setText("在番剧详情页点「缓存本集」即可离线观看；已缓存的视频在下方列表。");
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tip.setPadding(dp(4), 0, dp(4), dp(6));
        panel.addView(tip);

        ScrollView sv = new ScrollView(ctx);
        cacheListBox = new LinearLayout(ctx);
        cacheListBox.setOrientation(VERTICAL);
        sv.addView(cacheListBox);
        panel.addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    /** 重绘缓存列表（下载中 / 已完成 两段） */
    private void renderCachePanel() {
        if (cacheListBox == null || offlineCache == null) return;
        cacheListBox.removeAllViews();
        List<OfflineCache.Task> tasks = offlineCache.tasks();

        List<OfflineCache.Task> pending = new ArrayList<>();
        List<OfflineCache.Task> done = new ArrayList<>();
        List<OfflineCache.Task> failed = new ArrayList<>();
        for (OfflineCache.Task t : tasks) {
            if (t.state == OfflineCache.Task.State.DONE) done.add(t);
            else if (t.state == OfflineCache.Task.State.FAILED) failed.add(t);
            else pending.add(t);
        }

        if (tasks.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无缓存任务");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setPadding(dp(4), dp(12), dp(4), dp(4));
            empty.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            cacheListBox.addView(empty);
            return;
        }

        // 下载中
        if (!pending.isEmpty()) {
            cacheListBox.addView(sectionLabel("下载中（" + pending.size() + "）"));
            for (OfflineCache.Task t : pending) cacheListBox.addView(taskRow(t, false));
        }
        // 已完成
        if (!done.isEmpty()) {
            cacheListBox.addView(sectionLabel("已缓存（" + done.size() + "）"));
            for (OfflineCache.Task t : done) cacheListBox.addView(taskRow(t, true));
        }
        // 失败
        if (!failed.isEmpty()) {
            cacheListBox.addView(sectionLabel("失败（" + failed.size() + "）"));
            for (OfflineCache.Task t : failed) cacheListBox.addView(taskRow(t, false));
        }
    }

    /**
     * 分组标题（Aurora）：左侧渐变色条 + 粗体文字。
     *
     * <p>原设计只靠颜色区分层级，弱光环境下几乎看不出分组；
     * 加一条 4dp 渐变竖条后，分组边界在任何光线下都成立。</p>
     */
    private TextView sectionLabel(String text) {
        TextView t = new TextView(ctx);
        t.setText("  " + text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        t.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bar = Aurora.grad(ctx, Aurora.G_MAIN, dp(4));
        t.setBackground(bar);
        t.setPadding(dp(10), dp(7), dp(10), dp(7));
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    /** 单条缓存任务：已完成 → 点击播放 + 删除；进行中 → 进度条 + 取消 */
    private View taskRow(final OfflineCache.Task t, boolean completed) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        // 渐变描边卡片（已完成用薄荷，进行中用冷紫，失败用日落）
        int[] edge = completed ? Aurora.G_MINT
                : (t.state == OfflineCache.Task.State.FAILED ? Aurora.G_SUN : Aurora.G_ICE);
        row.setBackground(ModuleUiKit.rounded(ctx, dp(Aurora.R_CARD),
                color(com.google.android.material.R.attr.colorSurfaceContainerLow),
                edge[0]));
        row.setPadding(dp(13), dp(10), dp(13), dp(10));
        LayoutParams rlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(6);
        row.setLayoutParams(rlp);

        // 标题 + 状态
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        title.setText(t.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (completed) {
            TextView size = new TextView(ctx);
            size.setText(t.file != null ? fmtSize(t.file.length()) : "");
            size.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            size.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            head.addView(size);
            TextView play = new TextView(ctx);
            play.setText("  播放");
            play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            play.setTextColor(color(com.google.android.material.R.attr.colorPrimary));
            head.addView(play);
            row.setOnClickListener(v -> playCached(t));
        } else if (t.state == OfflineCache.Task.State.FAILED) {
            TextView err = new TextView(ctx);
            err.setText("  " + (t.error == null ? "失败" : t.error));
            err.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            err.setTextColor(0xFFE53935);
            head.addView(err);
        } else {
            TextView pct = new TextView(ctx);
            pct.setText("  " + t.progress() + "%");
            pct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            pct.setTextColor(color(com.google.android.material.R.attr.colorPrimary));
            head.addView(pct);
            TextView cancel = new TextView(ctx);
            cancel.setText("  取消");
            cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            cancel.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            cancel.setOnClickListener(v -> offlineCache.cancel(t));
            head.addView(cancel);
        }
        row.addView(head);

        // 进行中的进度条
        if (!completed && t.state != OfflineCache.Task.State.FAILED) {
            // 进度条：自绘 View（原生 ProgressBar 无法做渐变填充，而渐变是本次设计语言的主轴）
            final OfflineCache.Task task = t;
            View progress = new View(ctx) {
                @Override protected void onDraw(android.graphics.Canvas cv) {
                    int w = getWidth(), h = getHeight();
                    // 轨道
                    GradientDrawable track = new GradientDrawable();
                    track.setColor(0x262E2350);
                    track.setCornerRadius(h / 2f);
                    track.setBounds(0, 0, w, h);
                    track.draw(cv);
                    // 渐变填充
                    int fw = (int) (w * task.progress() / 100f);
                    if (fw > 0) {
                        GradientDrawable fill = Aurora.grad(getContext(),
                                task.state == OfflineCache.Task.State.QUEUED
                                        ? Aurora.G_ICE : Aurora.G_MINT,
                                GradientDrawable.Orientation.LEFT_RIGHT);
                        fill.setCornerRadius(h / 2f);
                        fill.setBounds(0, 0, fw, h);
                        fill.draw(cv);
                    }
                }
            };
            LayoutParams plp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7));
            plp.topMargin = dp(7);
            row.addView(progress, plp);

            TextView detail = new TextView(ctx);
            String d;
            if (t.totalParts > 0) {
                d = "分段 " + t.doneParts + "/" + t.totalParts + " · " + fmtSize(t.doneBytes);
            } else {
                d = fmtSize(t.doneBytes)
                        + (t.totalBytes > 0 ? " / " + fmtSize(t.totalBytes) : "");
            }
            detail.setText(d);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            detail.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
            LayoutParams dlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(3);
            detail.setLayoutParams(dlp);
            row.addView(detail);
        }

        // 已完成：长按删除
        if (completed) {
            row.setOnLongClickListener(v -> {
                offlineCache.remove(t);
                return true;
            });
        }
        return row;
    }

    /** 播放已缓存的本地文件（直接走统一播放器） */
    private void playCached(OfflineCache.Task t) {
        if (t.file == null || !t.file.exists()) {
            ModuleUiKit.toast(ctx, "文件不存在，可能已被清理");
            return;
        }
        try {
            Intent i = new Intent(ctx, MediaPlayerActivity.class);
            i.putExtra(MediaPlayerActivity.EXTRA_PATH, t.file.getAbsolutePath());
            i.putExtra(MediaPlayerActivity.EXTRA_TITLE, t.title);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ex) {
            ModuleUiKit.toast(ctx, "无法打开播放器：" + ex.getMessage());
        }
    }

    /**
     * 缓存入口（详情页「缓存本集」调用）：把**最佳线路的第一集**入队。
     * 与播放共用同一解析链路，拿到的直链与请求头一并交给缓存。
     */
    public void cacheEpisode(final AnimeSource src, final Subject sub, final Channel ch) {
        if (offlineCache == null || ch == null || ch.episodes == null || ch.episodes.isEmpty()) {
            ModuleUiKit.toast(ctx, "该线路无单集");
            return;
        }
        final Episode ep = ch.episodes.get(0);
        tvStatus.setText("解析缓存地址…");
        new Thread(() -> {
            try {
                SourceEngine engine = new SourceEngine();
                PlayLink link = engine.resolve(src, ep, ch.name);
                if (link == null || link.url == null) {
                    ui(() -> ModuleUiKit.toast(ctx, "解析失败，无法缓存"));
                    return;
                }
                final String title = (currentEntry != null ? currentEntry.title + " · " : "")
                        + ep.title;
                OfflineCache.Task task = offlineCache.enqueue(
                        title, link.url, link.headers);
                ui(() -> {
                    if (task == null) {
                        ModuleUiKit.toast(ctx, "该资源已在缓存队列");
                    } else {
                        ModuleUiKit.toast(ctx, "已加入缓存队列");
                        switchTab("cache");
                    }
                });
            } catch (Throwable e) {
                final String msg = e.getMessage();
                ui(() -> ModuleUiKit.toast(ctx, "解析失败：" + msg));
            }
        }, "cache-enqueue").start();
    }

    private String fmtSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format(java.util.Locale.CHINA, "%.1f KB", bytes / 1024.0);
        if (bytes < 1073741824L) return String.format(java.util.Locale.CHINA, "%.1f MB", bytes / 1048576.0);
        return String.format(java.util.Locale.CHINA, "%.2f GB", bytes / 1073741824.0);
    }

    /** 说明面板：模块能力与合规说明（阶段 4 的模块告知在此可随时查看） */
    /**
     * 说明 Tab —— <b>首个 Compose 页面</b>（[AnimeInfoScreen]）。
     * <p>用 ComposeView 内嵌进 XML View 体系，验证「新页面用 Compose」链路；
     * 旧页面仍是 XML View，按约定渐进迁移。</p>
     */
    private LinearLayout buildInfoPanel() {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(VERTICAL);

        // 用 ComposeView 承载 Compose 内容（AnimeInfoHost 是 Kotlin object 门面：
        // @Composable 函数无法从 Java 直调，故在此处封装）
        final int sourceCount = repo.size();
        final boolean danmakuOn = ctx.getSharedPreferences("anime_prefs",
                android.content.Context.MODE_PRIVATE)
                .getBoolean("danmaku_default_on", true);
        final int cacheCount = offlineCache != null ? offlineCache.finished().size() : 0;
        android.view.View composeView = AnimeInfoHost.create(
                ctx, /* moduleCount */ 1, sourceCount, danmakuOn, cacheCount);
        panel.addView(composeView, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    private LinearLayout buildInfoPanelXml() {
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

    /** 项目统一的按钮写法（对齐 GachaView.btn）：圆角 TextView，而非原生 Button */    private TextView btn(String text, int fg, int bg) {
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

    /** 卡片网格（Aurora：2 列 + stagger 入场），动画只在数据到达时跑一次 */
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

        // 入场动画已移除（用户要求：无过度动画）
    }

    /**
     * 探索卡片（Aurora 重构）：
     * 封面 22dp 圆角 + 底部渐变遮罩压字 + 标题/评分浮于其上。
     *
     * <p>原设计是「图下配字」，标题与封面脱节；现在文字压在遮罩里，
     * 与封面构成一个视觉单元，扫视时更容易按图认番。</p>
     */
    private View buildGridCard(final AnimeMetadata.Entry e) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(VERTICAL);
        card.setGravity(Gravity.BOTTOM);
        card.setClickable(true);
        card.setFocusable(true);
        card.setClipChildren(true);
        int r = dp(Aurora.R_CARD);
        card.setBackground(Aurora.grad(ctx, Aurora.G_ICE, r));
        card.setPadding(dp(3), dp(3), dp(3), dp(3));

        // 封面容器：**必须 clipToOutline 裁到圆角**
        // 教训：给 ImageView.setBackground(圆角) 只圆背景、不裁图片内容 →
        // 方形图片溢出，把卡片渐变边框压到只剩底部可见。
        FrameLayout coverBox = new FrameLayout(ctx);
        final int boxRadius = dp(Aurora.R_IMAGE);
        coverBox.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), boxRadius);
            }
        });
        coverBox.setClipToOutline(true);
        card.addView(coverBox, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200)));

        android.widget.ImageView cover = new android.widget.ImageView(ctx);
        cover.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        coverBox.addView(cover, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (e.cover != null && !e.cover.isEmpty()) loadCover(cover, e.cover);

        // 底部渐变遮罩（同被裁剪，与封面共用同一圆角）
        View scrim = new View(ctx);
        GradientDrawable sg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC1A1030, 0x001A1030});
        coverBox.addView(scrim, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setBackground(sg);

        // 文字叠加
        LinearLayout caption = new LinearLayout(ctx);
        caption.setOrientation(VERTICAL);
        caption.setPadding(dp(10), dp(4), dp(10), dp(9));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = android.view.Gravity.BOTTOM;
        caption.setLayoutParams(clp);
        coverBox.addView(caption);

        TextView title = new TextView(ctx);
        title.setText(e.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFFFFFFFF);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.addView(title);

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
            meta.setTextColor(0xE6FFD8E8);
            LayoutParams mlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(2);
            meta.setLayoutParams(mlp);
            caption.addView(meta);
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
        if (heroHeader != null) heroHeader.setVisibility(View.VISIBLE);
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
        head.setText("  继续观看");
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        head.setTypeface(null, Typeface.BOLD);
        head.setTextColor(0xFFFFFFFF);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackground(Aurora.grad(ctx, Aurora.G_MAIN, dp(4)));
        head.setPadding(dp(10), dp(7), dp(10), dp(7));
        c.addView(head);

        TextView body = new TextView(ctx);
        body.setText(e.subjectName + "\n" + (e.episodeTitle == null || e.episodeTitle.isEmpty()
                ? "（未命名集）" : e.episodeTitle)
                + "   " + e.positionText()
                + (e.channelName == null || e.channelName.isEmpty() ? "" : "   · " + e.channelName));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setTextColor(0xFFFFFFFF);
        body.setPadding(dp(13), dp(12), dp(13), dp(12));
        body.setLineSpacing(0, 1.35f);
        // 主操作卡：整块主渐变，直接告诉用户"点这里接着看"
        body.setBackground(ModuleUiKit.rippleBg(ctx,
                Aurora.grad(ctx, Aurora.G_ICE, dp(Aurora.R_INNER))));
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
        if (heroHeader != null) heroHeader.setVisibility(View.GONE);
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

    /**
     * 详情页头部（Aurora 重构）：<b>封面铺满 banner + 底部渐变遮罩 + 白字压顶</b>。
     *
     * <p>原设计是「左小图 + 右信息」的列表排布，与下方选源列表无主次之分；
     * 新设计让封面成为整块视觉主体，信息压在遮罩里，先建立"这是哪部番"的强认知，
     * 下方再进入功能区（缓存 / 可用源）。</p>
     */
    private View buildDetailHeader(final AnimeMetadata.Entry e) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.setPadding(0, 0, 0, dp(8));

        // ── Banner：封面 240dp，渐变遮罩，标题压底 ──
        FrameLayout banner = new FrameLayout(ctx);
        banner.setClipChildren(true);
        // 同探索卡片：必须 clipToOutline，否则方形封面会盖掉渐变圆角边框
        final int bnRadius = dp(Aurora.R_CARD);
        banner.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), bnRadius);
            }
        });
        banner.setClipToOutline(true);
        GradientDrawable brBg = Aurora.grad(ctx, Aurora.G_MAIN, dp(Aurora.R_CARD));
        banner.setBackground(brBg);
        LayoutParams blp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240));
        blp.topMargin = dp(4);
        banner.setLayoutParams(blp);

        android.widget.ImageView cover = new android.widget.ImageView(ctx);
        cover.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        banner.addView(cover, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (e.cover != null && !e.cover.isEmpty()) loadCover(cover, e.cover);

        // 遮罩：底部深 → 顶部透明
        View scrim = new View(ctx);
        GradientDrawable sg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6160C28, 0x00160C28});
        scrim.setBackground(sg);
        banner.addView(scrim, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 文字叠加（底部）
        LinearLayout cap = new LinearLayout(ctx);
        cap.setOrientation(VERTICAL);
        cap.setPadding(dp(14), dp(10), dp(14), dp(14));
        FrameLayout.LayoutParams cfl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cfl.gravity = android.view.Gravity.BOTTOM;
        cap.setLayoutParams(cfl);

        TextView title = new TextView(ctx);
        title.setText(e.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFFFFFFFF);
        title.setLetterSpacing(-0.02f);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cap.addView(title);

        if (e.nativeTitle != null && !e.nativeTitle.isEmpty()
                && !e.nativeTitle.equals(e.title)) {
            TextView nt = new TextView(ctx);
            nt.setText(e.nativeTitle);
            nt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            nt.setMaxLines(1);
            nt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nt.setTextColor(0xCCFFFFFF);
            LayoutParams nlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(2);
            nt.setLayoutParams(nlp);
            cap.addView(nt);
        }

        // 徽标行：评分(渐变高亮) / 集数 / 来源
        LinearLayout badges = new LinearLayout(ctx);
        badges.setOrientation(HORIZONTAL);
        LayoutParams bl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(9);
        badges.setLayoutParams(bl);
        if (e.score >= 0) badges.addView(bannerBadge("★ " + e.scoreText(), true));
        if (e.episodes > 0) badges.addView(bannerBadge(e.episodes + " 集", false));
        if (!e.source.isEmpty()) badges.addView(bannerBadge(e.source, false));
        cap.addView(badges);

        banner.addView(cap);
        box.addView(banner);

        // ── 功能区：缓存按钮（渐变胶囊）──
        TextView cacheBtn = btn("缓存本集（离线观看）", 0xFFFFFFFF, 0);
        cacheBtn.setBackground(Aurora.grad(ctx, Aurora.G_MINT, dp(Aurora.R_PILL)));
        cacheBtn.setPadding(dp(18), dp(11), dp(18), dp(11));
        cacheBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        cacheBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        cacheBtn.setOnClickListener(v -> startCacheCurrent(e));
        LayoutParams cbtnLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cbtnLp.topMargin = dp(10);
        cacheBtn.setLayoutParams(cbtnLp);
        box.addView(cacheBtn);

        // ── 标签（渐变描边胶囊）──
        if (e.tags != null && !e.tags.isEmpty()) {
            LinearLayout tags = new LinearLayout(ctx);
            tags.setOrientation(HORIZONTAL);
            LayoutParams tlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(9);
            tags.setLayoutParams(tlp);
            for (int i = 0; i < e.tags.size() && i < 6; i++) tags.addView(softTag(e.tags.get(i)));
            box.addView(tags);
        }

        // ── 简介 ──
        if (e.desc != null && !e.desc.isEmpty()) {
            TextView desc = new TextView(ctx);
            String d = e.desc.replaceAll("<[^>]+>", "");
            if (d.length() > 300) d = d.substring(0, 300) + "…";
            desc.setText(d);
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            desc.setMaxLines(5);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            desc.setLineSpacing(0, 1.45f);
            desc.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
            LayoutParams dlp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(11);
            desc.setLayoutParams(dlp);
            box.addView(desc);
        }

        // 分隔标题
        TextView sep = ModuleUiKit.sectionHeader(ctx, "可用播放源");
        LayoutParams slp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        sep.setLayoutParams(slp);
        box.addView(sep);

        return box;
    }

    /** banner 内徽标：highlight 用主渐变填充，否则半透明白 */
    private TextView bannerBadge(String text, boolean highlight) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(dp(9), dp(3), dp(9), dp(3));
        if (highlight) {
            t.setTextColor(0xFFFFFFFF);
            t.setBackground(Aurora.grad(ctx, Aurora.G_SUN, dp(Aurora.R_PILL)));
        } else {
            t.setTextColor(0xE6FFFFFF);
            t.setBackground(ModuleUiKit.rounded(ctx, dp(Aurora.R_PILL), 0x33FFFFFF, 0));
        }
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    /** 柔和标签：渐变描边胶囊（原为无样式纯文本） */
    private TextView softTag(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        t.setBackground(ModuleUiKit.rounded(ctx, dp(Aurora.R_PILL),
                color(com.google.android.material.R.attr.colorSurfaceContainerHigh), 0));
        t.setPadding(dp(10), dp(5), dp(10), dp(5));
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
    /** 详情页「缓存本集」：查源 → 取 tier 最优线路 → 入队 */
    private void startCacheCurrent(final AnimeMetadata.Entry entry) {
        final List<AnimeSource> srcs = repo.active();
        if (srcs.isEmpty()) {
            ModuleUiKit.toast(ctx, "还没有源");
            return;
        }
        tvStatus.setText("解析缓存线路…");
        final java.util.List<String> kws = entry.searchKeywords();
        if (kws.isEmpty()) {
            ModuleUiKit.toast(ctx, "缺少关键词");
            return;
        }
        // 复用多关键词搜源：找到第一条可用线路即入队
        cachePickAndEnqueue(srcs, kws, 0, entry);
    }

    private void cachePickAndEnqueue(final List<AnimeSource> srcs,
                                     final java.util.List<String> kws, final int idx,
                                     final AnimeMetadata.Entry entry) {
        if (idx >= kws.size()) {
            ui(() -> {
                tvStatus.setText("无可用源可缓存");
                ModuleUiKit.toast(ctx, "没有找到可缓存的源");
            });
            return;
        }
        final String kw = kws.get(idx);
        ui(() -> tvStatus.setText("查源… 关键词 " + (idx + 1) + "/" + kws.size()));
        searcher.search(srcs, kw, new AnimeSearchManager.Callback() {
            volatile boolean got = false;

            @Override public void onStart(int total) { }

            @Override public void onSourceDone(final AnimeSource source, List<Subject> subjects) {
                if (got || subjects == null || subjects.isEmpty()) return;
                got = true;
                new Thread(() -> {
                    try {
                        SourceEngine engine = new SourceEngine();
                        List<Channel> chs = engine.channels(source, subjects.get(0));
                        if (chs == null || chs.isEmpty()) return;
                        // 取 tier 最优线路
                        Channel best = null;
                        for (Channel c : chs) {
                            if (c.episodes == null || c.episodes.isEmpty()) continue;
                            if (best == null || source.tierFor(c.name) < source.tierFor(best.name)) best = c;
                        }
                        if (best == null) return;
                        final Channel chosen = best;
                        // 入队（cacheEpisode 会解析直链）
                        ui(() -> cacheEpisode(source, subjects.get(0), chosen));
                    } catch (Throwable ignored) {
                    }
                }, "cache-pick").start();
            }

            @Override public void onSourceError(AnimeSource source, String reason) { }

            @Override public void onProgress(int done, int total) { }

            @Override public void onAllDone(int ok, int total) {
                if (!got) cachePickAndEnqueue(srcs, kws, idx + 1, entry);
            }
        });
    }

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
        // 大圆角卡片 + 左侧 3dp 渐变竖条（与分组标题同属一套视觉语言）
        row.setPadding(dp(13), dp(11), dp(13), dp(11));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(com.google.android.material.R.attr.colorSurfaceContainerLow));
        bg.setCornerRadius(dp(Aurora.R_CARD));
        GradientDrawable bar = Aurora.grad(ctx, Aurora.G_ICE,
                GradientDrawable.Orientation.TOP_BOTTOM);
        bar.setCornerRadius(dp(3));
        row.setBackground(new LayerDrawable(new android.graphics.drawable.Drawable[]{bg, bar}));
        ((android.graphics.drawable.LayerDrawable) row.getBackground())
                .setLayerInset(1, 0, dp(9), dp(-3), dp(9));
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
            pill.setTypeface(idx == 0 ? android.graphics.Typeface.DEFAULT_BOLD
                    : android.graphics.Typeface.DEFAULT);
            pill.setPadding(dp(14), dp(7), dp(14), dp(7));
            if (idx == 0) {
                // 首选线路：主渐变填充（视觉上直接告诉用户"点这个"）
                pill.setTextColor(0xFFFFFFFF);
                GradientDrawable g = Aurora.grad(ctx, Aurora.G_MAIN,
                        GradientDrawable.Orientation.LEFT_RIGHT);
                g.setCornerRadius(dp(Aurora.R_PILL));
                pill.setBackground(ModuleUiKit.rippleBg(ctx, g));
            } else {
                // 其余线路：薄荷渐变描边（区分层级但不喧宾夺主）
                pill.setTextColor(Aurora.G_MINT[0]);
                GradientDrawable g = new GradientDrawable();
                g.setColor(0x00000000);
                g.setCornerRadius(dp(Aurora.R_PILL));
                g.setStroke(dp(1), Aurora.G_MINT[0]);
                pill.setBackground(ModuleUiKit.rippleBg(ctx, g));
            }
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
        // 弹幕（基础版）：带上 B站 season_id 与集序，播放器据此拉弹幕
        if (currentEntry != null && currentEntry.seasonId > 0) {
            i.putExtra(MediaPlayerActivity.EXTRA_DANMAKU_SEASON, currentEntry.seasonId);
            int epNo = 1;
            if (ep != null && ep.sort != null) {
                try {
                    epNo = Integer.parseInt(ep.sort.replaceAll("[^0-9]", ""));
                } catch (Throwable ignored) {
                }
            }
            i.putExtra(MediaPlayerActivity.EXTRA_DANMAKU_EP, epNo);
        }
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

        // ── KazumiRules 代理源仓库（第二引擎：XPath 规则）──
        TextView labK = new TextView(ctx);
        labK.setText("KazumiRules 代理源仓库（XPath 规则 · 与上面的订阅合流）");
        labK.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        labK.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        labK.setPadding(dp(4), dp(10), dp(4), dp(4));
        box.addView(labK);

        final EditText etKazumi = new EditText(ctx);
        etKazumi.setText(SourceRepository.DEFAULT_KAZUMI_RULES_REPO);
        etKazumi.setSingleLine(true);
        etKazumi.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        etKazumi.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        etKazumi.setHintTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        etKazumi.setBackground(ModuleUiKit.rounded(ctx, 10,
                color(com.google.android.material.R.attr.colorSurfaceContainerHigh), 0));
        etKazumi.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.addView(etKazumi);

        final TextView btnKazumi = btn("从 KazumiRules 导入（约 16 条规则）",
                color(com.google.android.material.R.attr.colorOnPrimary),
                color(com.google.android.material.R.attr.colorPrimary));
        LayoutParams klp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.topMargin = dp(8);
        klp.bottomMargin = dp(6);
        btnKazumi.setLayoutParams(klp);
        btnKazumi.setGravity(Gravity.CENTER);
        final EditText etK = etKazumi;
        btnKazumi.setOnClickListener(v -> {
            btnKazumi.setEnabled(false);
            btnKazumi.setText("导入中…");
            new Thread(() -> {
                try {
                    int n = repo.importFromKazumi(etK.getText().toString().trim());
                    ui(() -> {
                        btnKazumi.setEnabled(true);
                        btnKazumi.setText("从 KazumiRules 导入（约 16 条规则）");
                        refreshStatus();
                        ModuleUiKit.toast(ctx, "已导入 " + n + " 条 Kazumi 规则");
                        rebuildSourcePanel();
                    });
                } catch (Throwable t) {
                    final String msg = t.getMessage();
                    ui(() -> {
                        btnKazumi.setEnabled(true);
                        btnKazumi.setText("从 KazumiRules 导入（约 16 条规则）");
                        ModuleUiKit.toast(ctx, "导入失败：" + msg);
                    });
                }
            }).start();
        });
        box.addView(btnKazumi);

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
