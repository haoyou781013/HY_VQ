package com.aliya.hy_vq.plugin;

import android.content.Context;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 插件 UI 工具（宿主 → 插件的稳定契约）。
 *
 * <h3>为什么需要它</h3>
 * 插件是独立 dex，<b>不能引用宿主 {@code R.*}</b>：插件的资源 id 与宿主不在
 * 同一 namespace，引用会拿到错误资源或直接崩。但插件要长得像宿主，
 * 就得拿到主题色 —— 而 {@code ModuleUiKit.color(ctx, R.attr.colorPrimary)}
 * 的第二个参数正是 R 常量，插件写不出来。
 *
 * <p>所以这里把「主题属性查询」封装成<b>按语义命名</b>的方法：
 * 插件说「我要主色」，宿主决定主色是哪个 attr、怎么解析。
 * 宿主换主题体系时，插件不用动。
 *
 * <h3>取色为什么要 Activity 上下文</h3>
 * 主题属性挂在 Activity 的 theme 上。用 Application 上下文取不到，
 * 会返回兜底色甚至透明 —— 宿主 GithubModule 曾因此整页透明。
 * 所有方法都要求在 Activity 上下文下调用（插件拿到的 inflater 就是）。
 */
public final class PluginUi {

    private PluginUi() {}

    // ── 主题色（语义化，不暴露 R.attr）──

    /** 主色。按钮、选中态、强调文字。 */
    public static int primary(Context c) {
        return attr(c, android.R.attr.colorAccent, 0xFF6750A4);
    }

    /** 主色之上的文字色（与 {@link #primary} 形成对比）。 */
    public static int onPrimary(Context c) {
        return attr(c, android.R.attr.textColorPrimaryInverse, 0xFFFFFFFF);
    }

    /** 页面背景。 */
    public static int background(Context c) {
        return attr(c, android.R.attr.colorBackground, 0xFFFEF7FF);
    }

    /** 卡片/浮层表面。比背景略高一层。 */
    public static int surface(Context c) {
        return attr(c, android.R.attr.colorBackground, 0xFFF7F2FA);
    }

    /** 主要文字。 */
    public static int onSurface(Context c) {
        return attr(c, android.R.attr.textColorPrimary, 0xFF1D1B20);
    }

    /** 次要文字（说明、时间戳）。 */
    public static int onSurfaceVariant(Context c) {
        return attr(c, android.R.attr.textColorSecondary, 0xFF49454F);
    }

    /** 分隔线。 */
    public static int divider(Context c) {
        return attr(c, android.R.attr.textColorTertiary, 0x1F000000);
    }

    /** 错误态。 */
    public static int error(Context c) {
        return 0xFFB3261E;
    }

    /** 成功态。 */
    public static int success(Context c) {
        return 0xFF1A7F37;
    }

    // ── 尺寸 ──

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static float sp(Context c, float v) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }

    // ── 常用视图工厂（省掉插件里重复的样板）──

    /** 生成一个统一风格的标题。 */
    public static TextView title(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setTextColor(onSurface(c));
        return tv;
    }

    /** 生成一个统一风格的正文。 */
    public static TextView body(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(onSurfaceVariant(c));
        tv.setLineSpacing(dp(c, 4), 1f);
        return tv;
    }

    /**
     * 根视图的 LayoutParams。
     *
     * <p>插件必须自己设 MATCH_PARENT/MATCH_PARENT，否则在基于 weight 的容器里
     * 会测量成 0 高度 —— 表现为「插件打开了但是一片空白」。
     */
    public static ViewGroup.LayoutParams matchParent() {
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // ── 宿主视图工厂（插件拿宿主现成页面）──

    /**
     * 宿主页面工厂。插件通过它拿到宿主已经实现好的视图，
     * 而不是把上千行业务代码搬进 dex。
     *
     * <h3>为什么采用这种方式</h3>
     * 真正剥离（把 {@code ShareAppsView} 602 行、动漫 7186 行搬进插件）会遇到
     * 一个硬约束：这些类大量使用 {@code R.drawable.*} 和 {@code R.id.*}，
     * 而插件的资源 id 与宿主不在同一 namespace，搬过去直接崩。
     *
     * <p>所以插件在这里做的是<b>包装层</b>：业务实现仍留在宿主 APK 内，
     * 插件负责「出现在侧边栏、有独立版本号、可单独卸载」。
     * 这样既满足分离诉求，又不承担重写风险。
     */
    public interface HostPageFactory {
        /**
         * @param key 页面标识，如 "share" / "anime" / "gacha" / "genshin"
         * @return 页面根视图；返回 null 表示宿主不支持该页面
         */
        android.view.View createPage(String key, android.view.LayoutInflater inflater,
                                     android.view.ViewGroup parent);

        /** 该页面是否是当前可用状态（未登录/初始化未完成时可能不可用）。 */
        default boolean isAvailable(String key) {
            return true;
        }
    }

    private static volatile HostPageFactory pageFactory;

    /** 宿主启动时注册，插件加载前完成。 */
    public static void setHostPageFactory(HostPageFactory factory) {
        pageFactory = factory;
    }

    /**
     * 请求宿主创建页面。
     *
     * @return 页面视图；宿主未注册工厂或不支持该 key 时返回 null，
     *         插件应自行给出降级提示而不是崩溃。
     */
    public static android.view.View createHostPage(
            String key, android.view.LayoutInflater inflater, android.view.ViewGroup parent) {
        HostPageFactory f = pageFactory;
        if (f == null) return null;
        return f.createPage(key, inflater, parent);
    }

    /** 宿主是否支持某个页面。 */
    public static boolean hostPageAvailable(String key) {
        HostPageFactory f = pageFactory;
        return f != null && f.isAvailable(key);
    }

    // ── 内部 ──

    /**
     * 解析主题属性，失败回退到给定颜色。
     *
     * <p>用 resolveAttribute 而不是 obtainStyledAttributes：前者不分配
     * TypedArray，插件高频取色时不会制造 GC 压力。
     */
    private static int attr(Context c, int attrRes, int fallback) {
        if (c == null) return fallback;
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(attrRes, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
            if (tv.resourceId != 0) {
                try {
                    return androidx.core.content.ContextCompat.getColor(c, tv.resourceId);
                } catch (Throwable ignored) {
                    // 资源不可解析时走兜底，不让插件因为取色崩溃
                }
            }
        }
        return fallback;
    }
}
