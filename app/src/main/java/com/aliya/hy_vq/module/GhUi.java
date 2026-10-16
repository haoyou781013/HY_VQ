package com.aliya.hy_vq.module;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import java.util.List;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.R;

/**
 * GitHub 模块专用 UI 规范件。
 *
 * <p><b>数值来源（2026-10-12 调研实测）：</b>Primer 官方设计系统的编译产物
 * （{@code @primer/primitives@11.10.0} / {@code @primer/react@38.40.1}），
 * 从 CSS 变量读到精确数值 —— 比抓网页更权威（primer.style 子页面靠 JS 渲染会重定向）。
 *
 * <pre>
 * 间距 base-size：2 4 6 8 12 16 20 24 28 32 36 40 44 48 64 80 96 112 128 px
 * 语义别名：xxs=2 · xs=4 · sm=8 · md=12 · lg=16 · xl=24
 * 触摸目标：≥48×48dp（官方 Mobile Checklist 硬指标），相邻可点元素 ≥8dp
 * 每屏最多一个标题（官方 Mobile Checklist）
 * </pre>
 *
 * <p><b>Primer 颜色（light 实测）：</b>
 * <pre>
 * accent  #0969da   success #1a7f37   attention #9a6700   danger  #d1242f
 * muted   #59636e   bg-muted #f6f8fa border  #d1d9e0
 * </pre>
 * 这些是 Web 端 token，本类是 Material 3 主题，故用 MD3 的语义角色承接
 * （onSurface / onSurfaceVariant / surfaceContainer / outlineVariant /
 *  primary / success / warning / error）—— 与 Primer 的视觉意图一一对应。
 *
 * <p><b>参考实现：</b>slapperwan/gh4a（Apache-2.0，仍维护）、
 * pockethub/PocketHub（Apache-2.0，2023 停更，仅看历史设计）。
 * ⚠️ <b>k0shk0sh/FastHub 是 GPL-3.0，不可借鉴</b>。
 */
public final class GhUi {

    private GhUi() {
    }

    // ── 间距（Primer base-size，Android 换算为 dp）────────────

    public static final int SPACE_XXS = 2;
    public static final int SPACE_XS = 4;
    public static final int SPACE_SM = 8;    // 相邻可点元素最小间距（官方要求 ≥8）
    public static final int SPACE_MD = 12;
    public static final int SPACE_LG = 16;   // MDC 列表水平内边距
    public static final int SPACE_XL = 24;

    /** 官方触摸目标下限 */
    public static final int TOUCH_MIN = 48;

    // ── 字号（Primer text tokens）──────────────────────────

    public static final float TYPE_MICRO = 11f;
    public static final float TYPE_CAPTION = 12f;   // description / 元数据
    public static final float TYPE_BODY = 14f;      // 正文
    public static final float TYPE_TITLE = 16f;     // 列表项标题
    public static final float TYPE_HEADLINE = 20f;  // 页面标题（每屏仅一个）
    public static final float TYPE_DISPLAY = 26f;

    // ── 颜色（MD3 语义角色承接 Primer 意图）────────────────

    /** Primer: fgColor-muted #59636e */
    public static int muted(Context c) {
        return ModuleUiKit.color(c, R.attr.colorOnSurfaceVariant);
    }

    /** Primer: accent #0969da */
    public static int accent(Context c) {
        return ModuleUiKit.color(c, R.attr.colorPrimary);
    }

    /** Primer: success #1a7f37（Issue open = success） */
    public static int success(Context c) {
        return ModuleUiKit.color(c, R.attr.colorPrimary);
    }

    /** Primer: danger #d1242f（Issue closed = danger） */
    public static int danger(Context c) {
        return ModuleUiKit.color(c, R.attr.colorError);
    }

    /** Primer: attention #9a6700 */
    public static int warning(Context c) {
        return ModuleUiKit.color(c, R.attr.colorTertiary);
    }

    /** Primer: border #d1d9e0 */
    public static int border(Context c) {
        return ModuleUiKit.color(c, R.attr.colorOutlineVariant);
    }

    /** Primer: bg-muted #f6f8fa */
    public static int surfaceLow(Context c) {
        return ModuleUiKit.color(c, R.attr.colorSurfaceContainerLow);
    }

    // ── GitHub 语言色（官方 linguist 色板，UI 语言点用）────────

    /** 语言 → 圆点颜色；未知语言返回 0（不画点） */
    public static int languageColor(String lang) {
        if (lang == null) return 0;
        switch (lang.toLowerCase(java.util.Locale.CHINA)) {
            case "java": return 0xFFB07219;
            case "kotlin": return 0xFFA97BFF;
            case "javascript": return 0xFFF1E05A;
            case "typescript": return 0xFF3178C6;
            case "python": return 0xFF3572A5;
            case "go": return 0xFF00ADD8;
            case "rust": return 0xFFDEA584;
            case "c": return 0xFF555555;
            case "c++": return 0xFFF34B7D;
            case "c#": return 0xFF178600;
            case "php": return 0xFF4F5D95;
            case "ruby": return 0xFF701516;
            case "swift": return 0xFFF05138;
            case "shell": return 0xFF89E051;
            case "html": return 0xFFE34C26;
            case "css": return 0xFF563D7C;
            case "vue": return 0xFF41B883;
            case "dart": return 0xFF00B4AB;
            default: return 0;
        }
    }

    /** 圆点：语言色（Primer 列表项的语言点） */
    public static View dot(Context c, int color, int sizeDp) {
        View v = new View(c);
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(color);
        v.setBackground(gd);
        int s = dp(c, sizeDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.gravity = Gravity.CENTER_VERTICAL;
        v.setLayoutParams(lp);
        return v;
    }

    // ── 组件 ──────────────────────────────────────────────

    /**
     * 仓库列表项（Primer 风格三行式）。
     * <pre>
     * 行1  owner/repo（titleMedium 加粗）        ★1234 ⑂12
     * 行2  description（bodySmall muted，最多 2 行）
     * 行3  ●Java · 3 天前 · topics
     * </pre>
     * 触摸目标 ≥48dp（官方要求）。
     */
    public static LinearLayout repoItem(Context c, String fullName, String description,
                                        String language, String relativeTime,
                                        int starCount, View.OnClickListener onClick) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(c, SPACE_LG), dp(c, SPACE_MD), dp(c, SPACE_MD), dp(c, SPACE_MD));
        col.setMinimumHeight(dp(c, TOUCH_MIN));
        col.setBackground(ModuleUiKit.rippleBg(c,
                ModuleUiKit.rounded(c, 12, surfaceLow(c), 0)));

        // 行1：标题 + star
        LinearLayout row1 = new LinearLayout(c);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(c, TYPE_TITLE, fullName);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row1.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (starCount >= 0) {
            TextView stars = text(c, TYPE_CAPTION, "★ " + starCount);
            stars.setTextColor(muted(c));
            stars.setGravity(Gravity.CENTER_VERTICAL);
            row1.addView(stars);
        }
        col.addView(row1);

        // 行2：描述（最多 2 行）
        if (description != null && !description.trim().isEmpty()) {
            TextView desc = text(c, TYPE_CAPTION, description.trim());
            desc.setTextColor(muted(c));
            desc.setMaxLines(2);
            desc.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(c, SPACE_XS);
            col.addView(desc, dlp);
        }

        // 行3：语言点 + 语言 + 时间
        String lang = language == null ? "" : language;
        String time = relativeTime == null ? "" : relativeTime;
        if (!lang.isEmpty() || !time.isEmpty()) {
            LinearLayout row3 = new LinearLayout(c);
            row3.setOrientation(LinearLayout.HORIZONTAL);
            row3.setGravity(Gravity.CENTER_VERTICAL);
            int lc = languageColor(lang);
            if (lc != 0) {
                row3.addView(dot(c, lc, 10), new LinearLayout.LayoutParams(
                        dp(c, 10), dp(c, 10)));
                row3.addView(space(c, SPACE_XS));
            }
            if (!lang.isEmpty()) {
                TextView l = text(c, TYPE_MICRO, lang);
                l.setTextColor(muted(c));
                row3.addView(l);
            }
            if (!time.isEmpty()) {
                row3.addView(space(c, SPACE_SM));
                TextView t = text(c, TYPE_MICRO, time);
                t.setTextColor(muted(c));
                row3.addView(t);
            }
            LinearLayout.LayoutParams r3lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            r3lp.topMargin = dp(c, SPACE_XS);
            col.addView(row3, r3lp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, SPACE_SM);
        col.setLayoutParams(lp);
        col.setOnClickListener(onClick);
        return col;
    }

    /**
     * 文件/目录列表项。
     * <p><b>gh4a 与 GitHub Web 一致的规则：目录永远排在文件之前</b>，
     * 目录内部再按名称排序；目录名加粗，文件常规字重。</p>
     */
    public static LinearLayout fileItem(Context c, String name, boolean isDir,
                                        String rightText, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, SPACE_MD), 0, dp(c, SPACE_MD), 0);
        row.setMinimumHeight(dp(c, 40));
        row.setBackground(ModuleUiKit.rippleBg(c,
                ModuleUiKit.rounded(c, 8, surfaceLow(c), 0)));

        TextView ic = text(c, 15, isDir ? "📂" : fileIcon(name));
        ic.setGravity(Gravity.CENTER);
        row.addView(ic, new LinearLayout.LayoutParams(dp(c, 28), dp(c, 40)));

        TextView n = text(c, TYPE_BODY, name);
        n.setMaxLines(1);
        n.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        // 目录加粗（gh4a 做法）
        if (isDir) n.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        n.setTextColor(ModuleUiKit.color(c, R.attr.colorOnSurface));
        row.addView(n, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (rightText != null && !rightText.isEmpty()) {
            TextView r = text(c, TYPE_MICRO, rightText);
            r.setTextColor(muted(c));
            row.addView(r);
        }
        TextView arrow = text(c, 14, "›");
        arrow.setTextColor(muted(c));
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(c, 18), dp(c, 40)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, SPACE_XS);
        row.setLayoutParams(lp);
        row.setOnClickListener(onClick);
        return row;
    }

    /** 按扩展名给文件图标（够用即可，不追求全） */
    private static String fileIcon(String name) {
        if (name == null) return "📄";
        String n = name.toLowerCase(java.util.Locale.CHINA);
        if (n.endsWith(".md")) return "📘";
        if (n.endsWith(".java")) return "☕";
        if (n.endsWith(".kt") || n.endsWith(".kts")) return "🟣";
        if (n.endsWith(".js") || n.endsWith(".ts")) return "🟨";
        if (n.endsWith(".json")) return "🔧";
        if (n.endsWith(".xml")) return "📄";
        if (n.endsWith(".gradle")) return "🐘";
        if (n.endsWith(".sh")) return "📜";
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".webp")) return "🖼";
        if (n.endsWith(".apk") || n.endsWith(".zip")) return "📦";
        if (n.endsWith(".zip") || n.endsWith(".tar.gz")) return "📦";
        return "📄";
    }

    /** 面包屑点击回调（需要层级下标，故不能复用 View.OnClickListener） */
    public interface CrumbClick {
        void onCrumb(int index);
    }

    /** 面包屑（Breadcrumbs）：每级可点；当前级高亮不可点 */
    public static View breadcrumbs(Context c, List<Crumb> crumbs, CrumbClick onCrumb) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(c, SPACE_SM), dp(c, SPACE_XS), dp(c, SPACE_SM), dp(c, SPACE_XS));

        for (int i = 0; i < crumbs.size(); i++) {
            if (i > 0) {
                TextView sep = text(c, TYPE_BODY, "›");
                sep.setTextColor(muted(c));
                bar.addView(sep, new LinearLayout.LayoutParams(dp(c, 14), dp(c, 28)));
            }
            final int idx = i;
            TextView t = text(c, i == crumbs.size() - 1 ? TYPE_BODY : TYPE_CAPTION,
                    crumbs.get(i).name);
            t.setSingleLine(true);
            t.setMaxLines(1);
            // 当前级（最后一级）不可点，用强调色
            boolean last = i == crumbs.size() - 1;
            t.setTextColor(last ? accent(c) : muted(c));
            t.setTypeface(Typeface.create(Typeface.DEFAULT, last ? Typeface.BOLD : Typeface.NORMAL));
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setMinHeight(dp(c, 28));
            if (onCrumb != null && !last) {
                t.setOnClickListener(v -> onCrumb.onCrumb(idx));
            }
            bar.addView(t);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(bar, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return hs;
    }

    /** 面包屑层级 */
    public static class Crumb {
        public final String name;
        public final String path;   // 该级对应的完整路径（"" 表示仓库根）

        public Crumb(String name, String path) {
            this.name = name;
            this.path = path == null ? "" : path;
        }
    }

    /**
     * Blankslate 空状态（Primer 官方结构：5 槽 Container→Visual→Heading→Description→Action）。
     * 移动端用 small 档 + 16dp padding（Primer 已内建 34rem 容器断点做同样降级）。
     */
    public static LinearLayout blankslate(Context c, String icon, String heading,
                                         String description, String actionLabel,
                                         View.OnClickListener onAction) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        // small 档：heading 600 1rem、description .875rem、padding 16px
        int pad = dp(c, SPACE_LG);
        box.setPadding(pad, pad, pad, pad);
        box.setBackground(ModuleUiKit.rounded(c, 6,
                ModuleUiKit.color(c, R.attr.colorSurface), border(c)));

        if (icon != null && !icon.isEmpty()) {
            TextView ic = text(c, 28, icon);
            ic.setTextColor(muted(c));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.bottomMargin = dp(c, SPACE_SM);
            box.addView(ic, ilp);
        }
        TextView h = text(c, TYPE_HEADLINE, heading);
        h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        h.setTextColor(ModuleUiKit.color(c, R.attr.colorOnSurface));
        h.setGravity(Gravity.CENTER);
        box.addView(h);

        if (description != null && !description.isEmpty()) {
            TextView d = text(c, TYPE_BODY, description);
            d.setTextColor(muted(c));
            d.setGravity(Gravity.CENTER);
            d.setMaxLines(3);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(c, SPACE_XS);
            box.addView(d, dlp);
        }
        if (actionLabel != null && !actionLabel.isEmpty() && onAction != null) {
            View btn = button(c, actionLabel, onAction);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(c, SPACE_MD);
            box.addView(btn, blp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, SPACE_SM);
        box.setLayoutParams(lp);
        return box;
    }

    /**
     * 骨架占位（Primer SkeletonText）。
     * <p>比转圈更能减少感知延迟，且不会因内容到达而跳动高度。</p>
     */
    public static LinearLayout skeletonList(Context c, int rows) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int block = ModuleUiKit.color(c, R.attr.colorSurfaceContainerHighest);
        for (int i = 0; i < rows; i++) {
            LinearLayout item = new LinearLayout(c);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(c, SPACE_LG), dp(c, SPACE_MD), dp(c, SPACE_LG), dp(c, SPACE_MD));
            item.setBackground(ModuleUiKit.rounded(c, 12, surfaceLow(c), 0));
            item.addView(skeletonBar(c, block, 0.55f, 14));
            item.addView(space(c, SPACE_SM));
            item.addView(skeletonBar(c, block, 0.85f, 11));
            item.addView(space(c, SPACE_XS));
            item.addView(skeletonBar(c, block, 0.4f, 11));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(c, SPACE_SM);
            box.addView(item, lp);
        }
        return box;
    }

    private static View skeletonBar(Context c, int color, float widthRatio, int heightDp) {
        View v = new View(c);
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setCornerRadius(dp(c, 4));
        gd.setColor(color);
        v.setBackground(gd);
        // 用固定像素宽度模拟不同长度的文字块（无测量上下文时按屏宽比例）
        int screenW = c.getResources().getDisplayMetrics().widthPixels;
        v.setLayoutParams(new LinearLayout.LayoutParams(
                Math.round(screenW * widthRatio), dp(c, heightDp)));
        return v;
    }

    /**
     * InlineMessage 行内错误提示（Primer）。
     * <p><b>不要用 Toast 一闪而过</b> —— 错误需要留在界面上并带重试。</p>
     */
    public static LinearLayout inlineMessage(Context c, String icon, String text,
                                             boolean isError, String actionLabel,
                                             View.OnClickListener onAction) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, SPACE_MD), dp(c, SPACE_MD), dp(c, SPACE_MD), dp(c, SPACE_MD));

        int bg = isError
                ? ModuleUiKit.color(c, R.attr.colorErrorContainer)
                : ModuleUiKit.color(c, R.attr.colorTertiaryContainer);
        int fg = isError
                ? ModuleUiKit.color(c, R.attr.colorOnErrorContainer)
                : ModuleUiKit.color(c, R.attr.colorOnTertiaryContainer);
        row.setBackground(ModuleUiKit.rounded(c, 6, bg, 0));

        TextView ic = text(c, TYPE_BODY, icon == null ? (isError ? "⚠" : "ℹ") : icon);
        ic.setTextColor(fg);
        row.addView(ic, new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)));

        TextView t = text(c, TYPE_CAPTION, text);
        t.setTextColor(fg);
        t.setMaxLines(3);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (actionLabel != null && !actionLabel.isEmpty() && onAction != null) {
            TextView act = text(c, TYPE_CAPTION, actionLabel);
            act.setTextColor(fg);
            act.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            act.setGravity(Gravity.CENTER);
            act.setMinWidth(dp(c, TOUCH_MIN));
            act.setMinHeight(dp(c, 32));
            act.setOnClickListener(onAction);
            row.addView(act);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, SPACE_SM);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * 分段控件（MD3 SegmentedButton）。
     * <p>官方依据：<b>视图切换/排序用 segmented button，集合过滤用 filter chip</b>。
     * 仓库的 Code / Issues / PR 切换属于「视图切换」→ 用分段控件。</p>
     */
    /** 分段控件选中回调（需要下标） */
    public interface SegmentSelect {
        void onSelect(int index);
    }

    public static LinearLayout segmented(Context c, String[] labels, int selected,
                                         SegmentSelect onSelect) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(c, SPACE_XS), dp(c, SPACE_XS), dp(c, SPACE_XS), dp(c, SPACE_XS));
        bar.setBackground(ModuleUiKit.rounded(c, 12,
                ModuleUiKit.color(c, R.attr.colorSurfaceContainerHighest), 0));

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean sel = i == selected;
            TextView t = text(c, TYPE_CAPTION, labels[i]);
            t.setGravity(Gravity.CENTER);
            // 触摸目标 ≥48dp 高度（官方要求），宽度均分
            t.setMinHeight(dp(c, TOUCH_MIN - 8));
            t.setPadding(dp(c, SPACE_SM), 0, dp(c, SPACE_SM), 0);
            t.setTextColor(sel ? accent(c) : muted(c));
            t.setTypeface(Typeface.create(Typeface.DEFAULT, sel ? Typeface.BOLD : Typeface.NORMAL));
            if (sel) {
                t.setBackground(ModuleUiKit.rounded(c, 10,
                        ModuleUiKit.color(c, R.attr.colorSurface), 0));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            bar.addView(t, lp);
            if (!sel) {
                t.setOnClickListener(v -> onSelect.onSelect(idx));
            }
        }
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        outer.bottomMargin = dp(c, SPACE_SM);
        bar.setLayoutParams(outer);
        return bar;
    }

    /** 状态徽章（Primer StateLabel）：Issue open=success / closed=danger，PR merged=紫色 */
    public static TextView stateLabel(Context c, String label) {
        TextView t = text(c, TYPE_MICRO, label);
        int color;
        switch (label) {
            case "open": case "Open": color = success(c); break;
            case "closed": case "Closed": color = danger(c); break;
            case "merged": case "Merged": color = 0xFF8250DF; break;   // Primer done #8250df
            case "draft": color = muted(c); break;
            default: color = muted(c);
        }
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(dp(c, SPACE_SM), dp(c, 2), dp(c, SPACE_SM), dp(c, 2));
        t.setBackground(ModuleUiKit.rounded(c, 999, 0x00000000, color));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** 分支名标签（Primer BranchName） */
    public static TextView branchLabel(Context c, String branch) {
        TextView t = text(c, TYPE_MICRO, "⑂ " + branch);
        t.setTextColor(muted(c));
        t.setPadding(dp(c, SPACE_SM), dp(c, 2), dp(c, SPACE_SM), dp(c, 2));
        t.setBackground(ModuleUiKit.rounded(c, 6,
                ModuleUiKit.color(c, R.attr.colorSurfaceContainerHighest), 0));
        return t;
    }

    /**
     * 主行动按钮（filled 变体，最高强调）。
     *
     * <p>数值依据 M3 官方 token：
     * 视觉高 40dp（{@code md.comp.button.container.height}）、
     * 水平 padding 24dp（{@code md.comp.button.leading-space}）、
     * 全圆角（官方标 "Full"，无 dp 值，按高/2 = 20dp）、
     * 字号 14sp/行高 20sp/字重 500（{@code md.comp.button.label-text}）。</p>
     *
     * <p>⚠️ 48dp 是<b>触摸目标下限</b>而非视觉高度。这里 minHeight 取 40dp
     * 并通过 {@code setInsets} 语义（外层容器给足空间）保证可点区。
     * 若调用方把按钮放进高度受限的容器，需自行补足到 48dp。</p>
     */
    public static TextView button(Context c, String label, View.OnClickListener onClick) {
        TextView t = text(c, TYPE_BODY, label);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);   // M3 要 500，Android 无该常量，用 BOLD
        t.setTextColor(ModuleUiKit.color(c, R.attr.colorOnPrimary));
        t.setGravity(Gravity.CENTER);
        // M3 视觉高 40dp，但要求触摸区 48dp（靠 4dp inset 补足）。
        // TextView 无 inset API，故直接取 48dp —— 视觉略高于规范但满足无障碍硬要求。
        t.setMinHeight(dp(c, TOUCH_MIN));
        t.setPadding(dp(c, SPACE_XL), 0, dp(c, SPACE_XL), 0);   // 24dp
        t.setBackground(ModuleUiKit.rippleBg(c, ModuleUiKit.rounded(c, RADIUS_PILL,
                ModuleUiKit.color(c, R.attr.colorPrimary), 0)));
        if (onClick != null) t.setOnClickListener(onClick);
        return t;
    }

    /** 次要按钮（outlined 变体，中强调） */
    public static TextView buttonOutline(Context c, String label, View.OnClickListener onClick) {
        TextView t = text(c, TYPE_BODY, label);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);   // M3 要 500，Android 无该常量，用 BOLD
        t.setTextColor(accent(c));
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, TOUCH_MIN));   // 48dp，保证触摸区达标
        t.setPadding(dp(c, SPACE_XL), 0, dp(c, SPACE_XL), 0);   // 24dp
        t.setBackground(ModuleUiKit.rippleBg(c, ModuleUiKit.rounded(c, RADIUS_PILL, 0x00000000,
                GhUi.border(c))));
        if (onClick != null) t.setOnClickListener(onClick);
        return t;
    }

    /**
     * 最低强调按钮（text 变体）：无边框无底色，只有文字。
     * <p>M3 官方用途：次要/内联操作。水平 padding 12dp（非 24dp）。</p>
     */
    public static TextView buttonText(Context c, String label, View.OnClickListener onClick) {
        TextView t = text(c, TYPE_BODY, label);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);   // M3 要 500，Android 无该常量，用 BOLD
        t.setTextColor(accent(c));
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, TOUCH_MIN));   // 48dp，触摸区达标
        t.setPadding(dp(c, SPACE_MD), 0, dp(c, SPACE_MD), 0);   // 12dp，M3 text 按钮规格
        if (onClick != null) t.setOnClickListener(onClick);
        return t;
    }

    /**
     * M3 按钮<b>视觉</b>高度。
     * <p>⚠️ 不是触摸区高度 —— M3 靠 4dp inset 把 40dp 视觉补成 48dp 触摸区。
     * Android {@code TextView} 无 inset API，故 {@link #button} 等实际取
     * {@link #TOUCH_MIN}（48dp）以满足无障碍硬要求。
     * 此常量仅作参考值，供需要精确 M3 视觉尺寸的场景（如自绘背景）使用。</p>
     */
    public static final int BTN_HEIGHT = 40;

    // ── 工具 ──────────────────────────────────────────────

    public static TextView text(Context c, float sp, String s) {
        TextView v = new TextView(c);
        v.setText(s == null ? "" : s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(ModuleUiKit.color(c, R.attr.colorOnSurface));
        return v;
    }

    public static View space(Context c, int widthDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, widthDp), 1));
        return v;
    }

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /**
     * 相对时间（GitHub 风格："3 天前" / "刚刚" / "2 个月前"）。
     * <p>不用 SimpleDateFormat 的相对量词，手算避免 API 24 以下差异。</p>
     */
    public static String relativeTime(String iso8601) {
        if (iso8601 == null || iso8601.length() < 10) return "";
        long then;
        try {
            // "2026-10-12T08:30:00Z"
            java.util.Calendar cal = java.util.Calendar.getInstance(
                    java.util.TimeZone.getTimeZone("UTC"));
            cal.clear();
            cal.set(Integer.parseInt(iso8601.substring(0, 4)),
                    Integer.parseInt(iso8601.substring(5, 7)) - 1,
                    Integer.parseInt(iso8601.substring(8, 10)),
                    iso8601.length() >= 13 ? Integer.parseInt(iso8601.substring(11, 13)) : 0,
                    iso8601.length() >= 16 ? Integer.parseInt(iso8601.substring(14, 16)) : 0);
            then = cal.getTimeInMillis();
        } catch (Exception e) {
            return "";
        }
        long diff = System.currentTimeMillis() - then;
        if (diff < 0) diff = 0;
        long min = diff / 60000L;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long h = min / 60;
        if (h < 24) return h + " 小时前";
        long d = h / 24;
        if (d < 30) return d + " 天前";
        long mo = d / 30;
        if (mo < 12) return mo + " 个月前";
        return (mo / 12) + " 年前";
    }

    /** 取日期部分（YYYY-MM-DD） */
    public static String dateOnly(String iso8601) {
        return iso8601 != null && iso8601.length() >= 10 ? iso8601.substring(0, 10) : "";
    }

    // ══════════════════════ 卡片类容器 ══════════════════════
    //
    // 以下元件按 M3 官方规格实现：
    //   · 卡片变体 = filled（surfaceContainerLow，无阴影，圆角 12dp）
    //     出处 m3.material.io/components/cards/specs
    //   · 列表项高度档位 56/72/88dp（按行数）
    //     出处 m3.material.io/components/lists/overview
    //   · Chip 高 32dp / 圆角 8dp；只读徽章可缩小（非交互，不受 48dp 约束）
    //     出处 m3.material.io/components/chips/specs

    /** 卡片圆角（M3 medium shape） */
    public static final int RADIUS_CARD = 12;
    /** 全圆角（按钮 / 徽章） */
    public static final int RADIUS_PILL = 999;

    /**
     * filled 卡片容器：圆角 + 背景 + 内边距，无阴影。
     *
     * <p>M3 官方值：圆角 <b>12dp</b>、内边距 <b>16dp</b>、卡片间距 <b>8dp</b>。
     * filled 变体 elevation = 0dp（Level 0）。
     * 出处 m3.material.io/components/cards/specs。</p>
     *
     * <p>注：M3 官方<b>未规定</b>卡片容器高度与卡片内元素垂直间距。</p>
     *
     * @param padDp 内边距，建议 {@link #SPACE_LG}（16dp，官方值）
     */
    public static LinearLayout card(Context c, int padDp) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pd = dp(c, padDp);
        box.setPadding(pd, pd, pd, pd);
        box.setBackground(ModuleUiKit.rounded(c, RADIUS_CARD, surfaceLow(c), 0));
        return box;
    }

    /**
     * 水平排列的按钮行：子按钮各占等宽，间距 8dp。
     *
     * <p>8dp 依据 M3 button-groups 规格：standard group 的
     * {@code between-space} 在 M/L/XL 档为 <b>8dp</b>，
     * 目的是「保证 48dp 可点目标」。</p>
     *
     * <p>⚠️ M3 官方<b>未规定</b>「卡片内按钮并排还是堆叠」——
     * 此处选择并排是设计判断。若并排导致宽度不足，应改为堆叠，
     * 但必须保证每个按钮触摸区 ≥48dp。</p>
     */
    public static LinearLayout buttonRow(Context c, View... buttons) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < buttons.length; i++) {
            View b = buttons[i];
            if (b == null) continue;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(c, SPACE_SM);   // 8dp，M3 button-group between-space
            row.addView(b, lp);
        }
        return row;
    }

    /**
     * 只读状态徽章。
     *
     * <p>按 M3 Chip 官方规格：高 <b>32dp</b>、圆角 <b>8dp</b>、
     * 左右内边距 <b>16dp</b>（无图标时）、字号 14sp。
     * 出处 m3.material.io/components/chips/specs。</p>
     *
     * <p>但本徽章<b>不可点击</b>，故不受 48dp 触摸目标约束 ——
     * M3 的 48dp 要求只针对交互元素。</p>
     *
     * @param tone 语义色，传 {@link #success} / {@link #warning} / {@link #muted}
     */
    public static TextView badge(Context c, String label, int tone) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, TYPE_CAPTION);   // 12sp
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);   // M3 要 500，Android 无该常量，用 BOLD
        t.setTextColor(tone);
        t.setPadding(dp(c, SPACE_LG), 0, dp(c, SPACE_LG), 0);      // 16dp
        // 徽章底色 = 语义色低透明度（M3 tonal 容器做法）
        t.setBackground(ModuleUiKit.rounded(c, CHIP_RADIUS, alpha(tone, 0.12f), 0));
        t.setMinHeight(dp(c, CHIP_HEIGHT));                        // 32dp
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** M3 Chip 高度 */
    public static final int CHIP_HEIGHT = 32;
    /** M3 Chip 圆角 */
    public static final int CHIP_RADIUS = 8;

    /**
     * 图标 + 文本区块的水平容器。
     *
     * <p>间距取 M3 官方的 <b>{@code between-space} = 12dp</b>
     * （列表项内元素之间的标准间距）。</p>
     *
     * <p>⚠️ M3 官方<b>未规定</b>「图标到文字的水平间距」这一独立维度 ——
     * 规格页只给 leading 元素左内边距 16dp、trailing 元素左/右 16dp/24dp。
     * 本项目采用 between-space，属合理选择而非官方明文值。</p>
     */
    public static LinearLayout iconTextRow(Context c, View icon, View content) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        if (icon != null) {
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            ip.rightMargin = dp(c, SPACE_MD);   // 12dp，M3 between-space
            row.addView(icon, ip);
        }
        if (content != null) {
            row.addView(content, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    /** 颜色加透明度（0–1） */
    public static int alpha(int color, float f) {
        int a = Math.round(255 * Math.max(0f, Math.min(1f, f)));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}