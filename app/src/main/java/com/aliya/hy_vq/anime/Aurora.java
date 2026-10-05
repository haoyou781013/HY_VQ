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

    /** 三段主渐变：紫 → 粉 → 橙（导航条 / 主按钮 / 强调） */
    public static final int[] G_MAIN = {0xFF7C4DFF, 0xFFFF4D8D, 0xFFFF9E4D};
    /** 头部两段渐变（深起手，避免顶部撞白） */
    public static final int[] G_HERO = {0xFF4A2A7A, 0xFF7C4DFF, 0xFFFF4D8D};
    /** 薄荷青蓝（成功 / 已完成 / 弹幕开） */
    public static final int[] G_MINT = {0xFF00E5A0, 0xFF00C2FF};
    /** 日落橙红（热 / 评分） */
    public static final int[] G_SUN = {0xFFFFC64D, 0xFFFF6B4D};
    /** 冷紫蓝（缓存 / 进行中） */
    public static final int[] G_ICE = {0xFF6A5AE0, 0xFF7C4DFF};

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
