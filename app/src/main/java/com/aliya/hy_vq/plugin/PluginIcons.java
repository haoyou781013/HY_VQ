package com.aliya.hy_vq.plugin;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;
import android.content.Context;
import android.util.TypedValue;

import java.util.HashMap;
import java.util.Map;

/**
 * 插件字形图标表。
 *
 * <p>为什么不用 drawable：插件是独立 dex，<b>不能引用宿主的 {@code R.drawable}</b>
 * （资源 id 不在同一 namespace，会拿到错误资源或崩）。
 * 而让每个插件自带图标又得塞进 zip，且要处理多分辨率。
 * 故改用 <b>Unicode 字形 + 圆角底色</b>——零资源依赖，任何插件都能显示。</p>
 *
 * <p>字形取自 Unicode 常用符号区，Android 各版本字体覆盖稳定。
 * 新增图标只需在这里加一行映射。</p>
 */
public final class PluginIcons {

    private PluginIcons() {}

    /** 图标名 → {字形, 底色} */
    private static final Map<String, String[]> ICONS = new HashMap<>();
    /** 默认图标 */
    private static final String[] FALLBACK = {"\u25C6", "#59636E"};   // ◆

    static {
        ICONS.put("code",    new String[]{"\u2328", "#0969DA"});   // ⌨
        ICONS.put("github",  new String[]{"\u25CF", "#1F2328"});   // ●
        ICONS.put("anime",   new String[]{"\u25B6", "#8250DF"});   // ▶
        ICONS.put("emoji",   new String[]{"\u263A", "#9A6700"});   // ☺
        ICONS.put("share",   new String[]{"\u21AA", "#1A7F37"});   // ↪
        ICONS.put("game",    new String[]{"\u2666", "#D1242F"});   // ♦
        ICONS.put("star",    new String[]{"\u2605", "#9A6700"});   // ★
        ICONS.put("folder",  new String[]{"\u25A0", "#0969DA"});   // ■
        ICONS.put("info",    new String[]{"\u2139", "#0969DA"});   // ℹ
        ICONS.put("tools",   new String[]{"\u2699", "#59636E"});   // ⚙
        ICONS.put("music",   new String[]{"\u266B", "#8250DF"});   // ♫
        ICONS.put("image",   new String[]{"\u25A3", "#1A7F37"});   // ▣
        ICONS.put("book",    new String[]{"\u2261", "#0969DA"});   // ≡
        ICONS.put("flask",   new String[]{"\u2697", "#9A6700"});   // ⚗
        ICONS.put("lightning", new String[]{"\u26A1", "#9A6700"}); // ⚡
    }

    /** 取字形字符。未知名字回退到 ◆ */
    public static String glyph(String iconName) {
        String[] v = iconName == null ? null : ICONS.get(iconName);
        return v == null ? FALLBACK[0] : v[0];
    }

    /** 取底色。未知名字回退到灰 */
    public static int color(String iconName) {
        String[] v = iconName == null ? null : ICONS.get(iconName);
        if (v == null) v = FALLBACK;
        try {
            return Color.parseColor(v[1]);
        } catch (Exception e) {
            return 0xFF59636E;
        }
    }

    /** 已注册的图标名（供文档与校验用） */
    public static String[] names() {
        return ICONS.keySet().toArray(new String[0]);
    }

    /**
     * 生成一个图标视图：圆角底色 + 居中字形。
     *
     * @param sizeDp 正方形边长（dp），建议 40
     */
    public static TextView iconView(Context c, String iconName, int sizeDp) {
        float density = c.getResources().getDisplayMetrics().density;
        int sz = Math.round(sizeDp * density);

        TextView t = new TextView(c);
        t.setText(glyph(iconName));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(12 * density);
        bg.setColor(color(iconName));
        t.setBackground(bg);

        t.setLayoutParams(new android.view.ViewGroup.LayoutParams(sz, sz));
        return t;
    }
}
