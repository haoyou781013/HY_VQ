package com.aliya.hy_vq.gacha;

import android.app.Activity;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aliya.hy_vq.R;
import com.aliya.hy_vq.module.ModuleUiKit;

import java.util.ArrayList;
import java.util.List;

/**
 * 提瓦特文字转换 —— **独立页面**（与抽卡分析同款形态：switchContent 进 contentFrame）。
 *
 * <p>用户反馈「需要单独页面，像抽卡分析一样」，故从原神帮助页的内嵌展开改为独立页。
 * 点卡片 → {@code switchToTeyvat()} → 本视图；系统返回 → {@code switchToGenshinHelp()}。</p>
 *
 * <p>数据：4 套 Wenti-D/GenshinFonts 的 TTF（{@code res/font/}），
 * 已自写 TTF cmap 解析器验证 4/4 全覆盖 A–Z，拉丁文直译即可。</p>
 */
public class TeyvatConverterView extends LinearLayout {

    private static final String[] TYPE_NAMES = {"通用·蒙德", "稻妻", "须弥沙漠", "古体·坎瑞亚"};
    private static final int[] FONT_RES = {
            R.font.teyvat_common, R.font.teyvat_inazuma,
            R.font.teyvat_deshret, R.font.teyvat_khaenriah};

    private final Activity ctx;
    private EditText input;
    private TextView out;
    private int style = 0;
    private final List<TextView> chips = new ArrayList<>();

    public TeyvatConverterView(Activity context) {
        super(context);
        this.ctx = context;
        setOrientation(VERTICAL);
        int pad = dp(16);   // ModuleUiKit.dp 是 private，用本类方法
        setPadding(pad, pad, pad, pad);
        build();
    }

    /** 帮助页用 dp 转换（本类自持，避免依赖外部工具方法） */
    private int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private void build() {
        // 标题
        TextView title = new TextView(ctx);
        title.setText("提瓦特文字转换");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ModuleUiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        title.setLayoutParams(tp);
        addView(title);

        TextView desc = new TextView(ctx);
        desc.setText("输入拉丁字母，即时渲染为所选提瓦特文字。共 4 种可选。");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        desc.setTextColor(ModuleUiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dp2.topMargin = dp(4); dp2.bottomMargin = dp(12);
        desc.setLayoutParams(dp2);
        addView(desc);

        // 类型 chips（单行 4 个）
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rp);
        addView(row);
        for (int i = 0; i < TYPE_NAMES.length; i++) {
            final int idx = i;
            TextView c = new TextView(ctx);
            c.setText(TYPE_NAMES[i]);
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            c.setPadding(dp(13), dp(9), dp(13), dp(9));
            c.setClickable(true);
            c.setFocusable(true);
            c.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cp.rightMargin = dp(6);
            c.setLayoutParams(cp);
            c.setOnClickListener(v -> selectStyle(idx));
            row.addView(c);
            chips.add(c);
        }

        // 输入
        input = new EditText(ctx);
        input.setHint("输入要转换的拉丁文字，如 TEYVAT");
        input.setSingleLine(false);
        input.setMaxLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setTextColor(ModuleUiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        input.setHintTextColor(ModuleUiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        input.setBackground(ModuleUiKit.rounded(ctx, 12,
                ModuleUiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh), 0));
        input.setPadding(dp(12), dp(11), dp(12), dp(11));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = dp(14);
        input.setLayoutParams(ip);
        addView(input);

        // 输出
        out = new TextView(ctx);
        out.setText(" ");
        out.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        out.setLineSpacing(0, 1.45f);
        out.setPadding(dp(14), dp(18), dp(14), dp(18));
        out.setTextColor(ModuleUiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        out.setBackground(ModuleUiKit.rounded(ctx, 12,
                ModuleUiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerLow),
                ModuleUiKit.color(ctx, com.google.android.material.R.attr.colorOutlineVariant)));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.topMargin = dp(10);
        out.setLayoutParams(op);
        addView(out);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { render(); }
            @Override public void afterTextChanged(Editable e) { }
        });

        // 免责 + 署名
        TextView foot = new TextView(ctx);
        foot.setText("字形非官方，仅供娱乐参考，一切以官方为准。\n"
                + "字体整理：Wenti-D/GenshinFonts（仓库无授权声明，仅个人非商业用途）；"
                + "文字破译参考 @提瓦特图研所 / Genshin Impact Wiki。");
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        foot.setLineSpacing(0, 1.35f);
        foot.setTextColor(ModuleUiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.topMargin = dp(14);
        foot.setLayoutParams(fp);
        addView(foot);

        selectStyle(0);
    }

    /** 切换字体系并重绘（选中态胶囊） */
    private void selectStyle(int idx) {
        style = idx;
        for (int i = 0; i < chips.size(); i++) {
            TextView c = chips.get(i);
            boolean on = i == idx;
            if (on) {
                c.setTextColor(0xFFFFFFFF);
                c.setBackground(ModuleUiKit.rounded(ctx, 999,
                        ModuleUiKit.color(ctx, com.google.android.material.R.attr.colorPrimary), 0));
            } else {
                c.setTextColor(ModuleUiKit.color(ctx,
                        com.google.android.material.R.attr.colorOnSurface));
                c.setBackground(ModuleUiKit.rounded(ctx, 999,
                        ModuleUiKit.color(ctx, com.google.android.material.R.attr.colorSurfaceContainerHigh), 0));
            }
        }
        render();
    }

    /** 按当前字体系渲染输入 */
    private void render() {
        if (out == null || input == null) return;
        String raw = input.getText() == null ? "" : input.getText().toString();
        if (raw.trim().isEmpty()) {
            out.setText(" ");
            out.setTextColor(ModuleUiKit.color(ctx,
                    com.google.android.material.R.attr.colorOnSurfaceVariant));
            return;
        }
        try {
            out.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(
                    ctx, FONT_RES[Math.max(0, Math.min(3, style))]));
        } catch (Throwable t) {
            out.setTypeface(android.graphics.Typeface.DEFAULT);
        }
        out.setTextColor(ModuleUiKit.color(ctx,
                com.google.android.material.R.attr.colorOnSurface));
        out.setText(raw);
    }
}
