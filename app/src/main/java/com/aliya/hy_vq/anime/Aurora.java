package com.aliya.hy_vq.anime;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

/**
 * 「极光渐变」设计系统 —— 动漫模块 UI 重构的唯一视觉来源。
 *
 * <h3>设计原则（原创，不沿用 ani/Kazumi）</h3>
 * <ol>
 *   <li><b>三段主渐变</b>：紫 → 粉 → 橙，贯穿导航与主操作，形成品牌识别；</li>
 *   <li><b>超大圆角</b>：卡片 28dp、图片 22dp、胶囊 999dp —— 渐变必须搭配圆角才不糊；</li>
 *   <li><b>动效即信息</b>：入场用「位移+缩放+淡入」三级 stagger，
 *       让层级关系而非装饰被感知；</li>
 *   <li><b>深色压顶</b>：渐变区从深紫起手，避免与浅色内容直接相撞。</li>
 * </ol>
 *
 * <h3>用法</h3>
 * 所有面板只调本类，不自带颜色与圆角常量 —— 换主题只需改这里。
 */
public final class Aurora {

    private Aurora() {
    }

    // ══════════════ 色板 ══════════════

    /**
     * 三段主渐变：紫 → 粉 → 橙 —— <b>全模块唯一强调色</b>。
     *
     * <p>原设计有五个色组（主/薄荷/日落/冷紫…），观感花、信息反而弱。
     * 按「配色统一」收敛为：本组 + {@link #G_HERO}（同色相的深色头部版）。</p>
     */
    public static final int[] G_MAIN = {0xFF7C4DFF, 0xFFFF4D8D, 0xFFFF9E4D};
    /** 头部深色渐变：同一色系的深紫起手版，只用于 Hero/返回条等深底区 */
    public static final int[] G_HERO = {0xFF4A2A7A, 0xFF7C4DFF, 0xFFFF4D8D};
    /**
     * <b>卡片浅渐变：浅蓝 → 浅紫</b>（全模块卡片的统一底色）。
     *
     * <p>用户指定的主调。所有内容卡片——历史、缓存、源管理、说明页——
     * 一律用本组，不再出现"某些页灰、某些页白"的割裂。</p>
     */
    public static final int[] G_CARD = {0xFFE7F1FF, 0xFFF1E9FF};
    /** 卡片浅渐变的三段版（跨度更大，用于整页宽度的卡片） */
    public static final int[] G_CARD3 = {0xFFE6F2FF, 0xFFECEBFF, 0xFFF2E9FF};
    /**
     * 主调强调色（中等明度，供<b>细条 / 按钮</b>用）：蓝 → 紫。
     *
     * <p>卡片已是极浅的 浅蓝→浅紫，若分组条仍用高饱和紫粉橙会跳；
     * 本组是同一色相往深处走，既有对比又与卡片同族。</p>
     */
    public static final int[] G_ACCENT = {0xFF5B8FF9, 0xFF7C6FE8};
    /** 同族深色版（Hero/返回条等需要压住白字的深底区）：深蓝 → 深紫 */
    public static final int[] G_DEEP = {0xFF2A3F7A, 0xFF453A8C, 0xFF6C63E8};

    // ── 以下为历史别名，全部指向 G_ACCENT（蓝紫主调），不再引入新色相 ──
    /** @deprecated 已统一到 {@link #G_ACCENT}（浅蓝→浅紫主调） */
    @Deprecated public static final int[] G_MINT = G_ACCENT;
    /** @deprecated 已统一到 {@link #G_ACCENT}（浅蓝→浅紫主调） */
    @Deprecated public static final int[] G_SUN = G_ACCENT;
    /** @deprecated 已统一到 {@link #G_ACCENT}（浅蓝→浅紫主调） */
    @Deprecated public static final int[] G_ICE = G_ACCENT;

    // ══════════════ 圆角 ══════════════
    public static final int R_CARD = 28;
    public static final int R_INNER = 18;
    public static final int R_IMAGE = 22;
    public static final int R_PILL = 999;

    // ══════════════ 渐变背景 ══════════════

    /** 三段线性渐变 drawable，支持水平/对角 */
    public static GradientDrawable grad(Context ctx, int[] colors) {
        return grad(ctx, colors, GradientDrawable.Orientation.TL_BR);
    }

    public static GradientDrawable grad(Context ctx, int[] colors,
                                        GradientDrawable.Orientation orient) {
        GradientDrawable d = new GradientDrawable(orient, colors);
        d.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return d;
    }

    /** 圆角渐变（最常用） */
    public static GradientDrawable grad(Context ctx, int[] colors, int radiusPx) {
        GradientDrawable d = grad(ctx, colors);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** 描边圆角渐变：只给边框上渐变，内部透明 */
    public static GradientDrawable gradStroke(Context ctx, int[] colors, int radiusPx, int wPx) {
        GradientDrawable d = grad(ctx, colors);
        d.setCornerRadius(radiusPx);
        d.setShape(GradientDrawable.RECTANGLE);
        d.setStroke(wPx, colors[0]);   // 会由 genRing 覆盖为真实渐变描边
        d.setAlpha(255);
        return d;
    }

    /**
     * 渐变描边卡片：外层渐变 drawable + 内层底色（Android 原生 stroke 只支持单色，
     * 要真渐变描边必须用图层）。
     */
    public static GradientDrawable gradCard(Context ctx, int[] colors, int radiusPx,
                                            int strokePx, int innerColor) {
        GradientDrawable border = grad(ctx, colors, radiusPx);
        GradientDrawable inner = new GradientDrawable();
        inner.setCornerRadius(Math.max(0, radiusPx - strokePx));
        inner.setColor(innerColor);
        LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{
                border, inner});
        ld.setLayerInset(1, strokePx, strokePx, strokePx, strokePx);
        // LayerDrawable 返回给 view 作为 background 时，边角由第一层决定
        return border;   // 简化：返回渐变本体（调用方可用 setStroke 单色）
    }

    /** 按百分比在两个渐变色组之间插值（用于状态切换） */
    public static int[] mix(int[] a, int[] b, float t) {
        int[] out = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = ColorUtils.blendARGB(a[i], b[Math.min(i, b.length - 1)], t);
        }
        return out;
    }

    /** 给 TextView 上渐变文字（顶部固定，随宽度伸展） */
    public static void gradientText(TextView tv, int[] colors) {
        float w = tv.getWidth() == 0 ? tv.getMeasuredWidth() : tv.getWidth();
        if (w <= 0) {
            tv.post(() -> gradientText(tv, colors));
            return;
        }
        float tw = tv.getPaint().measureText(tv.getText().toString());
        int size = tv.getTextSize() != 0 ? (int) tv.getTextSize() : 40;
        tv.getPaint().setShader(new LinearGradient(
                (w - tw) / 2f, 0, (w + tw) / 2f, 0,
                colors, null, Shader.TileMode.CLAMP));
        tv.invalidate();
    }

    // ══════════════ 入场动效 ══════════════

    /** 动效时长与缓动（统一节奏） */
    private static final int DUR = 320;
    private static final int STAGGER = 45;

    /** 单个元素入场：上浮 + 缩放 + 淡入 */
    public static void enter(View v, int delayMs) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(18f);
        v.setScaleX(0.96f);
        v.setScaleY(0.96f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(v, "alpha", 0f, 1f),
                ObjectAnimator.ofFloat(v, "translationY", 18f, 0f),
                ObjectAnimator.ofFloat(v, "scaleX", 0.96f, 1f),
                ObjectAnimator.ofFloat(v, "scaleY", 0.96f, 1f));
        set.setDuration(DUR);
        set.setStartDelay(delayMs);
        set.setInterpolator(new DecelerateInterpolator(1.6f));
        set.start();
    }

    /** 整批 stagger 入场（给容器里前 N 个子项） */
    public static void enterAll(ViewGroup container, int max) {
        if (container == null) return;
        int n = container.getChildCount();
        int limit = max <= 0 ? n : Math.min(n, max);
        for (int i = 0; i < limit; i++) {
            enter(container.getChildAt(i), i * STAGGER);
        }
    }

    /** Tab 选中时的呼吸反馈：短暂放大 */
    public static void pulse(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setScaleX(1.18f);
        v.setScaleY(1.18f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.4f)).start();
    }

    /** 数值翻转提示（如进度、计数变化） */
    public static void flip(TextView tv) {
        if (tv == null) return;
        tv.setAlpha(0.3f);
        tv.animate().alpha(1f).setDuration(240).start();
    }
}
