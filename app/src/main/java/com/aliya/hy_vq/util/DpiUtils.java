package com.aliya.hy_vq.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * 应用级 DPI（屏幕密度）覆盖。
 *
 * <p><b>原理</b>：通过 {@link Context#createConfigurationContext} 构造一个
 * {@code densityDpi} 被改写过的 Context，在 Activity 的
 * {@code attachBaseContext()} 阶段替换掉基类 Context。
 * 这样整棵 View 树的 {@code dp → px} 换算都会按新密度走，
 * 相当于把系统的「最小宽度 / 显示大小」设置<b>只对本应用</b>生效。</p>
 *
 * <p><b>为什么不用 {@code wm density}</b>：那是系统级设置，
 * 需要 WRITE_SECURE_SETTINGS 或 root，且会影响整机所有应用。
 * 本方案纯应用内实现，零权限、零副作用、卸载即还原。</p>
 *
 * <p>取值 {@link #MIN}~{@link #MAX}；{@link #DEFAULT}（0）表示跟随系统。</p>
 */
public final class DpiUtils {

    /** 与 MainActivity 共用的 preferences 文件 */
    public static final String PREF_FILE = "app_settings";
    public static final String PREF_KEY = "app_dpi";

    /** 允许的密度范围（用户要求 100–600） */
    public static final int MIN = 100;
    public static final int MAX = 600;
    /** 0 表示不覆盖，跟随系统 */
    public static final int DEFAULT = 0;

    /** 常用预设（对应系统「显示大小」各档位的典型值） */
    public static final int[] PRESETS = {320, 360, 400, 440, 480};

    private DpiUtils() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    /** 当前已保存的覆盖值；未设置或越界时返回 {@link #DEFAULT} */
    public static int get(Context c) {
        try {
            int v = prefs(c).getInt(PREF_KEY, DEFAULT);
            if (v == DEFAULT) return DEFAULT;
            if (v < MIN || v > MAX) return DEFAULT;
            return v;
        } catch (Throwable t) {
            return DEFAULT;
        }
    }

    /** 保存覆盖值（自动夹取到 [MIN, MAX]；传 0 表示恢复跟随系统） */
    public static void set(Context c, int dpi) {
        int v = (dpi == DEFAULT) ? DEFAULT : Math.max(MIN, Math.min(MAX, dpi));
        try {
            prefs(c).edit().putInt(PREF_KEY, v).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 清除覆盖，恢复跟随系统 */
    public static void reset(Context c) {
        try {
            prefs(c).edit().remove(PREF_KEY).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 是否处于覆盖状态 */
    public static boolean isOverridden(Context c) {
        return get(c) != DEFAULT;
    }

    /** 设备自身的密度（未覆盖时应当生效的值） */
    public static int systemDpi(Context c) {
        try {
            return c.getResources().getDisplayMetrics().densityDpi;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 把 DPI 覆盖应用到基类 Context。
     * <p>在各 Activity 的 {@code attachBaseContext()} 中调用：
     * <pre>
     *   &#64;Override
     *   protected void attachBaseContext(Context base) {
     *       super.attachBaseContext(DpiUtils.wrap(base));
     *   }
     * </pre>
     * 任何异常都原样返回 base，绝不因该功能导致应用起不来。</p>
     */
    public static Context wrap(Context base) {
        if (base == null) return null;
        try {
            int dpi = get(base);
            if (dpi == DEFAULT) return base;
            Configuration cfg = new Configuration(base.getResources().getConfiguration());
            cfg.densityDpi = dpi;
            return base.createConfigurationContext(cfg);
        } catch (Throwable t) {
            return base;
        }
    }
}
