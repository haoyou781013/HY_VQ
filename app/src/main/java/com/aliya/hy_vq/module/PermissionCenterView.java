package com.aliya.hy_vq.module;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限中心：<b>完整列出应用持有的全部权限</b>，并逐项说明用途。
 *
 * <h3>设计原则</h3>
 * <ol>
 *   <li><b>列全</b>：通过 {@link PackageManager#getPackageInfo(String, int)} +
 *       {@code GET_PERMISSIONS} <b>动态读取</b> {@code requestedPermissions}，
 *       而不是维护一份手写清单。这样即使将来新增了权限而忘了写说明文案，
 *       <b>用户仍然能看到它</b>（标注"暂无说明"），不会被静默隐藏。</li>
 *   <li><b>说明可选，显示必须</b>：文案表只负责"怎么解释"，不决定"是否展示"。</li>
 *   <li><b>状态判断分类</b>：特殊权限（悬浮窗、所有文件）不在
 *       {@code checkSelfPermission} 的管辖内，必须各用各的 API，
 *       否则会全部误报为"未授予"。</li>
 *   <li><b>诚实</b>：既写用途，也写"它能做什么"（风险提示），
 *       并明确告知能否关闭、如何关闭。</li>
 * </ol>
 */
public class PermissionCenterView extends LinearLayout {

    private final Context ctx;
    /** 页面重建回调（用于跳系统设置后回来自刷新） */
    private final Runnable onRefresh;

    public PermissionCenterView(Context context, Runnable onRefresh) {
        super(context);
        this.ctx = context;
        this.onRefresh = onRefresh;
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(12), dp(16), dp(32));
        build();
    }

    // ══════════════════════════════════════════════
    //  数据模型
    // ══════════════════════════════════════════════

    /** 权限级别（决定状态判断方式与排序） */
    private static final int LV_DANGEROUS = 0;   // 危险权限：运行时申请
    private static final int LV_SPECIAL = 1;     // 特殊权限：需跳设置手动开
    private static final int LV_NORMAL = 2;      // 普通权限：安装即授予
    private static final int LV_CUSTOM = 3;      // 自定义（第三方库/Shizuku）

    private static class PermItem {
        String key = "";            // 权限全名
        String label = "";          // 中文名
        String simple = "";         // 简单说明（弹窗第一层）
        String detail = "";         // 详细说明（弹窗第二层）
        String[] abilities;         // 授予后具备的能力（风险提示）
        String howToDisable = "";   // 如何关闭
        int level = LV_NORMAL;
        boolean granted = false;
        boolean explained = false;  // 是否有文案
    }

    // ══════════════════════════════════════════════
    //  文案表：只提供说明，不决定是否显示
    // ══════════════════════════════════════════════

    private static final Map<String, PermItem> EXplain = new LinkedHashMap<>();

    private static void def(String key, int level, String label, String simple,
                            String detail, String[] abilities, String howToDisable) {
        PermItem p = new PermItem();
        p.key = key;
        p.level = level;
        p.label = label;
        p.simple = simple;
        p.detail = detail;
        p.abilities = abilities;
        p.howToDisable = howToDisable;
        p.explained = true;
        EXplain.put(key, p);
    }

    static {
        def("android.permission.MANAGE_EXTERNAL_STORAGE", LV_SPECIAL,
                "所有文件访问",
                "文件管理功能需要读写设备上的任意文件。",
                "普通应用默认只能访问自己的目录和公共媒体目录。"
                        + "要像文件管理器那样浏览整个存储（含 Android/data 等），"
                        + "必须获得「所有文件访问」。\n\n"
                        + "导出安装包、管理下载目录、编辑任意文本文件等都依赖它。"
                        + "未授权时，文件管理模块会引导你前往系统设置开启。",
                new String[]{"读取设备上的任何文件", "修改或删除任何文件",
                        "访问其他应用的私有目录"},
                "设置 → 应用 → HY_VQ → 所有文件访问");

        def("android.permission.SYSTEM_ALERT_WINDOW", LV_SPECIAL,
                "悬浮窗",
                "检测到抽卡链接时就地提示，不必切回本应用。",
                "用于在其它应用上层显示一个小提示窗，"
                        + "这样在游戏里拿到链接后能立刻知道结果。\n\n"
                        + "本应用只在需要提示时短暂显示，不会常驻。"
                        + "不需要时可以关闭，不影响其它功能。",
                new String[]{"在其它应用上方显示内容"},
                "设置 → 应用 → HY_VQ → 显示在其他应用的上层");

        def("android.permission.QUERY_ALL_PACKAGES", LV_NORMAL,
                "应用列表",
                "用于在文件管理里浏览 /data/app 的目录结构。",
                "系统不允许普通应用直接读取 /data/app（权限为 771，其他用户无读权限）。"
                        + "但通过系统接口可以拿到每个已安装应用的位置信息，"
                        + "据此还原出该目录的树状结构，让你能像普通目录一样浏览进去，"
                        + "并在可读的层级提取 APK。\n\n"
                        + "该权限由系统在安装时自动授予，无法在设置里关闭。"
                        + "本应用只读取「有哪些应用、装在哪里」，不读取任何应用内的数据。",
                new String[]{"获取已安装应用的包名与安装位置",
                        "据此推断出你安装了哪些应用"},
                "系统自动授予，无法关闭");

        def("android.permission.POST_NOTIFICATIONS", LV_DANGEROUS,
                "通知",
                "用于下载更新时显示进度提醒。",
                "在后台下载软件更新时，通过通知栏显示进度，"
                        + "让你能知道下载是否完成。\n\n"
                        + "这是可选项，拒绝后其它功能不受影响，"
                        + "只是下载进度不会在通知栏出现。",
                new String[]{"向你发送通知"},
                "设置 → 应用 → HY_VQ → 通知（可随时关闭）");

        def("android.permission.REQUEST_INSTALL_PACKAGES", LV_NORMAL,
                "安装应用",
                "用于安装下载好的软件更新包。",
                "下载完新版安装包后，需要拉起系统安装器完成安装。"
                        + "此权限只允许「发起安装请求」，"
                        + "最终是否安装仍由系统的安装界面确认，应用无法静默安装。",
                new String[]{"发起应用安装请求（仍需你在系统界面确认）"},
                "设置 → 应用 → HY_VQ → 安装未知应用");

        def("android.permission.INTERNET", LV_NORMAL,
                "网络访问",
                "用于下载更新、获取软件分享列表。",
                "本应用需要联网的功能包括：检查并下载软件更新、"
                        + "读取实用软件分享列表、获取抽卡记录。\n\n"
                        + "这是基础网络权限，安装时由系统自动授予。"
                        + "应用不会上传你的个人文件或输入内容。",
                new String[]{"访问网络"},
                "系统自动授予，无法单独关闭（可用系统「流量管理」限制）");

        def("android.permission.ACCESS_NETWORK_STATE", LV_NORMAL,
                "网络状态",
                "用于判断当前网络是否可用。",
                "在下载前检查网络连接状态，避免在无网络时反复重试。\n\n"
                        + "该权限只读取「网络是否连通」这一状态，"
                        + "不涉及任何网络内容。",
                new String[]{"读取网络连接状态"},
                "系统自动授予，无法关闭");

        def("moe.shizuku.manager.permission.API_V23", LV_CUSTOM,
                "Shizuku 权限",
                "读取系统日志，自动提取抽卡链接。",
                "游戏打开「祈愿 → 历史记录」时，会把含 authkey 的网址写进系统日志。"
                        + "读取日志需要 adb(shell) 权限，普通应用拿不到；"
                        + "Shizuku 能在不 root 的前提下把这个权限授予本应用。\n\n"
                        + "本应用只以 shell 身份读取日志，不使用 root 权限。"
                        + "你可以随时在 Shizuku 应用内撤销授权。"
                        + "不使用此功能时无需授权。",
                new String[]{"以 shell 身份读取系统日志",
                        "（日志中可能包含其它应用输出的信息）"},
                "打开 Shizuku 应用 → 已授权应用 → 撤销 HY_VQ");

        def("com.aliya.hy_vq.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", LV_CUSTOM,
                "内部广播保护",
                "系统组件自动添加，用于保护应用内部通信。",
                "这是 AndroidX 库在编译时自动添加的自定义权限，"
                        + "作用是让应用内部发送的广播不被其它应用接收。\n\n"
                        + "它不涉及任何隐私数据，也不会向任何方授予访问能力，"
                        + "属于系统框架层面的保护机制。",
                new String[]{"（仅用于应用内部广播隔离，无对外能力）"},
                "系统组件自动添加，无需也无法手动管理");
    }

    // ══════════════════════════════════════════════
    //  数据收集
    // ══════════════════════════════════════════════

    /** 动态读取应用申请的全部权限（这是"列全"的根本保证） */
    private List<PermItem> collect() {
        List<PermItem> out = new ArrayList<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(),
                    PackageManager.GET_PERMISSIONS);
            String[] req = pi.requestedPermissions;
            if (req == null) return out;
            int[] flags = pi.requestedPermissionsFlags;
            for (int i = 0; i < req.length; i++) {
                String key = req[i];
                if (key == null) continue;
                PermItem meta = EXplain.get(key);
                PermItem it;
                if (meta != null) {
                    // 复制一份，避免修改静态表
                    it = new PermItem();
                    it.key = meta.key;
                    it.level = meta.level;
                    it.label = meta.label;
                    it.simple = meta.simple;
                    it.detail = meta.detail;
                    it.abilities = meta.abilities;
                    it.howToDisable = meta.howToDisable;
                    it.explained = true;
                } else {
                    // ★ 没有文案也要显示 —— 这是"列全"的关键
                    it = new PermItem();
                    it.key = key;
                    it.label = shortName(key);
                    it.level = guessLevel(key);
                    it.simple = "（暂无说明）";
                    it.detail = "该权限尚未补充用途说明。\n\n"
                            + "它由应用或所集成的组件申请。"
                            + "如果你对它存疑，可以据此反馈，我们会补充说明；"
                            + "若确认无用，后续版本会移除。";
                    it.abilities = null;
                    it.howToDisable = "请参考系统设置中的权限管理";
                    it.explained = false;
                }
                it.granted = checkGranted(key, flags != null && i < flags.length
                        ? flags[i] : 0);
                out.add(it);
            }
        } catch (Throwable ignored) {
        }
        // 按级别排序：危险 → 特殊 → 普通 → 自定义；同级按名称
        out.sort((a, b) -> {
            if (a.level != b.level) return Integer.compare(a.level, b.level);
            return a.label.compareTo(b.label);
        });
        return out;
    }

    /**
     * 状态判断 —— <b>必须分类</b>。
     *
     * <p>若统一用 {@code checkSelfPermission}，特殊权限（悬浮窗、所有文件访问）
     * 会全部误报为"未授予"，因为它们根本不在其管辖范围内。</p>
     */
    private boolean checkGranted(String key, int flag) {
        try {
            switch (key) {
                case "android.permission.MANAGE_EXTERNAL_STORAGE":
                    // isExternalStorageManager 是 API 30 引入的，低版本调用会
                    // NoSuchMethodError —— 必须做版本判断
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        return Environment.isExternalStorageManager();
                    }
                    return true;   // API 30 以下无此权限概念，视为已具备
                case "android.permission.SYSTEM_ALERT_WINDOW":
                    // canDrawOverlays 为 API 23+，项目 minSdk 28，安全
                    return Settings.canDrawOverlays(ctx);
                case "moe.shizuku.manager.permission.API_V23":
                    // 由 Shizuku 自身管理，此处只做展示，不在这里判定
                    return false;
                default:
                    return ctx.checkSelfPermission(key)
                            == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Throwable t) {
            return (flag & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
        }
    }

    private int guessLevel(String key) {
        if (key.startsWith("com.") || key.contains("DYNAMIC_RECEIVER")) return LV_CUSTOM;
        // 常见危险权限
        String[] dangerous = {"CAMERA", "RECORD_AUDIO", "ACCESS_FINE_LOCATION",
                "ACCESS_COARSE_LOCATION", "READ_CONTACTS", "READ_SMS",
                "READ_CALENDAR", "BODY_SENSORS", "CALL_PHONE", "READ_PHONE_STATE",
                "POST_NOTIFICATIONS"};
        for (String d : dangerous) {
            if (key.endsWith("." + d)) return LV_DANGEROUS;
        }
        return LV_NORMAL;
    }

    private static String shortName(String key) {
        int i = key.lastIndexOf('.');
        return i > 0 ? key.substring(i + 1) : key;
    }

    // ══════════════════════════════════════════════
    //  UI 构建
    // ══════════════════════════════════════════════

    private void build() {
        removeAllViews();
        List<PermItem> all = collect();

        // 顶部说明
        TextView intro = new TextView(ctx);
        intro.setText("以下列出本应用持有的全部权限（共 " + all.size() + " 项），"
                + "包含系统自动授予、你在设置里不会看到的项目。\n"
                + "点击任意一项可查看用途说明。");
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        intro.setTextColor(color(R.attr.colorOnSurfaceVariant));
        intro.setLineSpacing(dp(2), 1.3f);
        intro.setPadding(dp(2), 0, dp(2), dp(8));
        addView(intro);

        // 按级别分组
        List<PermItem> dangerous = new ArrayList<>(), special = new ArrayList<>(),
                normal = new ArrayList<>(), custom = new ArrayList<>();
        for (PermItem p : all) {
            switch (p.level) {
                case LV_DANGEROUS: dangerous.add(p); break;
                case LV_SPECIAL: special.add(p); break;
                case LV_CUSTOM: custom.add(p); break;
                default: normal.add(p);
            }
        }

        if (!special.isEmpty()) addSection("需要你在设置里手动开启", special,
                "这些权限不会自动获得，必须由你明确授权。未开启时相关功能不可用，"
                        + "但其它功能不受影响。");
        if (!dangerous.isEmpty()) addSection("运行时授权", dangerous,
                "首次使用时系统会弹出询问，你可以允许或拒绝，之后也能随时更改。");
        if (!normal.isEmpty()) addSection("系统自动授予", normal,
                "安装时由系统自动授予，不会弹窗询问，你在系统设置里通常看不到它们。"
                        + "列出来是为了让你知道应用具备这些能力。");
        if (!custom.isEmpty()) addSection("其它", custom, null);

        addCapabilitySection();
    }

    private void addSection(String title, List<PermItem> items, String note) {
        addView(ModuleUiKit.sectionHeader(ctx, title));
        if (note != null && !note.isEmpty()) {
            TextView t = new TextView(ctx);
            t.setText(note);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setTextColor(color(R.attr.colorOnSurfaceVariant));
            t.setLineSpacing(dp(2), 1.25f);
            t.setPadding(dp(2), 0, dp(2), dp(4));
            addView(t);
        }
        // 每一项独立成卡片（淡色背景 + 边框），不再用「共用一张卡 + 分隔线」——
        // 后者在项数多时层次不清，边框卡片更容易一眼分辨条目边界。
        for (int i = 0; i < items.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            addView(buildRow(items.get(i)), lp);
        }
        View gap = new View(ctx);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        addView(gap, glp);
    }

    private View buildRow(final PermItem p) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(13), dp(14), dp(13));

        // 卡片外观：淡色容器背景 + 主色细边框，外面再包一层点击水波纹。
        // 用 colorPrimaryContainer 作底色可自动跟随主题（蓝主题即淡蓝 #BBDEFB，
        // 绿主题即淡绿，暗色主题则为深色容器），比写死颜色更协调。
        // 注意：背景只能设一个，所以必须用 rippleBg 把两者合成，不能分两次设置。
        android.graphics.drawable.GradientDrawable cardBg = ModuleUiKit.rounded(
                ctx, 12,
                color(R.attr.colorPrimaryContainer),   // 淡色背景（跟随主题）
                color(R.attr.colorPrimary));           // 边框
        row.setBackground(ModuleUiKit.rippleBg(ctx, cardBg));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(col, clp);

        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(ctx);
        name.setText(p.label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTextColor(color(R.attr.colorOnSurface));
        titleRow.addView(name);

        if (!p.explained) {
            // 没有文案的项目给一个可见标记，而不是隐藏它
            TextView badge = new TextView(ctx);
            badge.setText("暂无说明");
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            badge.setTextColor(color(R.attr.colorOnTertiaryContainer));
            badge.setBackground(ModuleUiKit.rounded(ctx, 6,
                    color(R.attr.colorTertiaryContainer), 0));
            badge.setPadding(dp(6), dp(1), dp(6), dp(1));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = dp(8);
            titleRow.addView(badge, blp);
        }
        col.addView(titleRow);

        TextView sum = new TextView(ctx);
        sum.setText(p.simple);
        sum.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sum.setTextColor(color(R.attr.colorOnSurfaceVariant));
        sum.setMaxLines(2);
        sum.setLineSpacing(dp(1), 1.2f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(3);
        col.addView(sum, slp);

        // 状态标记
        TextView st = new TextView(ctx);
        if (p.level == LV_CUSTOM && p.key.startsWith("moe.shizuku")) {
            st.setText("按需");
            st.setTextColor(color(R.attr.colorOnSurfaceVariant));
        } else if (p.level == LV_NORMAL) {
            st.setText("系统授予");
            st.setTextColor(color(R.attr.colorOnSurfaceVariant));
        } else if (p.granted) {
            st.setText("已开启");
            st.setTextColor(color(R.attr.colorPrimary));
        } else {
            st.setText("未开启");
            st.setTextColor(color(R.attr.colorOnSurfaceVariant));
        }
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.leftMargin = dp(10);
        row.addView(st, stlp);

        row.setOnClickListener(v -> showDetail(p));
        return row;
    }

    // ══════════════════════════════════════════════
    //  详情弹窗：简单说明在上、详细说明在下
    // ══════════════════════════════════════════════

    private void showDetail(final PermItem p) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(VERTICAL);
        box.addView(ModuleUiKit.sectionHeader(ctx, p.label));

        // ── 第一层：简单说明 ──
        TextView simple = new TextView(ctx);
        simple.setText(p.simple);
        simple.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        simple.setTypeface(Typeface.DEFAULT_BOLD);
        simple.setTextColor(color(R.attr.colorOnSurface));
        simple.setLineSpacing(dp(2), 1.3f);
        simple.setPadding(dp(4), dp(6), dp(4), dp(4));
        box.addView(simple);

        // 状态
        TextView st = new TextView(ctx);
        st.setText("当前状态：" + statusText(p));
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        st.setTextColor(color(R.attr.colorPrimary));
        st.setPadding(dp(4), 0, dp(4), dp(8));
        box.addView(st);

        // ── 第二层：详细说明 ──
        addDivider(box);
        TextView dTitle = new TextView(ctx);
        dTitle.setText("详细说明");
        dTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        dTitle.setTypeface(Typeface.DEFAULT_BOLD);
        dTitle.setTextColor(color(R.attr.colorOnSurfaceVariant));
        dTitle.setPadding(dp(4), dp(10), dp(4), dp(4));
        box.addView(dTitle);

        TextView detail = new TextView(ctx);
        detail.setText(p.detail);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        detail.setTextColor(color(R.attr.colorOnSurface));
        detail.setLineSpacing(dp(3), 1.35f);
        detail.setPadding(dp(4), 0, dp(4), dp(6));
        box.addView(detail);

        // 系统标识
        TextView keyTv = new TextView(ctx);
        keyTv.setText("系统标识：" + p.key);
        keyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        keyTv.setTextColor(color(R.attr.colorOnSurfaceVariant));
        keyTv.setPadding(dp(4), dp(2), dp(4), dp(6));
        box.addView(keyTv);

        // 能力清单（风险提示）
        if (p.abilities != null && p.abilities.length > 0) {
            addDivider(box);
            TextView aTitle = new TextView(ctx);
            aTitle.setText("⚠️ 授予后具备的能力");
            aTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            aTitle.setTypeface(Typeface.DEFAULT_BOLD);
            aTitle.setTextColor(color(R.attr.colorError));
            aTitle.setPadding(dp(4), dp(10), dp(4), dp(4));
            box.addView(aTitle);
            for (String a : p.abilities) {
                TextView t = new TextView(ctx);
                t.setText("· " + a);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                t.setTextColor(color(R.attr.colorOnSurface));
                t.setLineSpacing(dp(2), 1.25f);
                t.setPadding(dp(8), dp(1), dp(4), dp(1));
                box.addView(t);
            }
        }

        // 如何关闭
        addDivider(box);
        TextView hTitle = new TextView(ctx);
        hTitle.setText("如何关闭");
        hTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hTitle.setTypeface(Typeface.DEFAULT_BOLD);
        hTitle.setTextColor(color(R.attr.colorOnSurfaceVariant));
        hTitle.setPadding(dp(4), dp(10), dp(4), dp(4));
        box.addView(hTitle);
        TextView how = new TextView(ctx);
        how.setText(p.howToDisable);
        how.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        how.setTextColor(color(R.attr.colorOnSurface));
        how.setLineSpacing(dp(2), 1.3f);
        how.setPadding(dp(4), 0, dp(4), dp(4));
        box.addView(how);

        final android.app.Dialog d = ModuleUiKit.glassDialog(ctx, box);
        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(14);
        box.addView(btns, blp);

        // 能跳系统设置的，给个入口
        final String jump = settingsAction(p.key);
        if (jump != null) {
            TextView go = new TextView(ctx);
            go.setText("去系统设置");
            go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            go.setTextColor(color(R.attr.colorOnPrimary));
            go.setBackground(ModuleUiKit.rounded(ctx, 10,
                    color(R.attr.colorPrimary), 0));
            go.setPadding(dp(14), dp(8), dp(14), dp(8));
            go.setOnClickListener(v -> {
                ModuleUiKit.dismissWithAnim(d);
                try {
                    ctx.startActivity(new android.content.Intent(jump));
                } catch (Throwable t) {
                    ModuleUiKit.toast(ctx, "无法打开系统设置，请手动前往：\n" + p.howToDisable);
                }
            });
            btns.addView(go);
        }

        TextView close = new TextView(ctx);
        close.setText("关闭");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        close.setTextColor(color(R.attr.colorPrimary));
        close.setBackground(ModuleUiKit.rounded(ctx, 10,
                color(R.attr.colorSurfaceContainerHighest), 0));
        close.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(8);
        close.setOnClickListener(v -> ModuleUiKit.dismissWithAnim(d));
        btns.addView(close, clp);

        d.show();
    }

    private String statusText(PermItem p) {
        if (p.level == LV_NORMAL) return "系统自动授予";
        if (p.level == LV_CUSTOM && p.key.startsWith("moe.shizuku")) return "按需授权";
        return p.granted ? "已开启" : "未开启";
    }

    /** 返回可跳转的系统设置 action，无则 null */
    private String settingsAction(String key) {
        switch (key) {
            case "android.permission.MANAGE_EXTERNAL_STORAGE":
                return Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION;
            case "android.permission.SYSTEM_ALERT_WINDOW":
                return Settings.ACTION_MANAGE_OVERLAY_PERMISSION;
            case "android.permission.POST_NOTIFICATIONS":
                return Settings.ACTION_APP_NOTIFICATION_SETTINGS;
            case "android.permission.REQUEST_INSTALL_PACKAGES":
                return Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES;
            default:
                return null;
        }
    }

    // ══════════════════════════════════════════════
    //  能力区（非 Manifest 授权，单独说明）
    // ══════════════════════════════════════════════

    private void addCapabilitySection() {
        addView(ModuleUiKit.sectionHeader(ctx, "能力（非权限）"));

        TextView note = new TextView(ctx);
        note.setText("以下不是系统权限，而是你在使用过程中按需授予的能力。"
                + "它们不会出现在系统权限列表里，但同样影响数据访问范围。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        note.setTextColor(color(R.attr.colorOnSurfaceVariant));
        note.setLineSpacing(dp(2), 1.25f);
        note.setPadding(dp(2), 0, dp(2), dp(6));
        addView(note);

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(VERTICAL);
        card.setBackground(ModuleUiKit.rounded(ctx, 14,
                color(R.attr.colorSurfaceContainerHigh), 0));

        // Shizuku
        card.addView(buildCapabilityRow("Shizuku 授权",
                "以 adb(shell) 身份读取系统日志，用于自动提取抽卡链接。"
                        + "不使用该功能时无需授权。",
                capabilityShizukuState()));
        card.addView(divider());
        // SAF
        card.addView(buildCapabilityRow("存储访问框架（SAF）",
                "你在文件管理里通过系统文件选择器授权的目录。"
                        + "应用只能访问你明确选择过的位置。",
                "按需授权"));

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(14);
        addView(card, clp);
    }

    /** 读取 Shizuku 授权状态（反射调用，未安装时安全降级） */
    private String capabilityShizukuState() {
        try {
            Class<?> cls = Class.forName("rikka.shizuku.Shizuku");
            Object ping = cls.getMethod("pingBinder").invoke(null);
            if (!Boolean.TRUE.equals(ping)) return "未运行";
            Object granted = cls.getMethod("checkSelfPermission").invoke(null);
            return (granted instanceof Integer && ((Integer) granted) == 0)
                    ? "已授权" : "未授权";
        } catch (Throwable t) {
            return "未安装";
        }
    }

    private View buildCapabilityRow(String label, String desc, String status) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(ctx);
        name.setText(label);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTextColor(color(R.attr.colorOnSurface));
        titleRow.addView(name);

        TextView st = new TextView(ctx);
        st.setText(status);
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        st.setTextColor(color(R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.leftMargin = dp(8);
        titleRow.addView(st, stlp);
        col.addView(titleRow);

        TextView d = new TextView(ctx);
        d.setText(desc);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        d.setTextColor(color(R.attr.colorOnSurfaceVariant));
        d.setLineSpacing(dp(2), 1.25f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(3);
        col.addView(d, dlp);
        return col;
    }

    // ══════════════════════════════════════════════
    //  小工具
    // ══════════════════════════════════════════════

    private View divider() {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(16);
        v.setBackgroundColor(color(R.attr.colorOutlineVariant));
        v.setLayoutParams(lp);
        return v;
    }

    private void addDivider(LinearLayout box) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(6);
        v.setBackgroundColor(color(R.attr.colorOutlineVariant));
        box.addView(v, lp);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int color(int attr) {
        return ModuleUiKit.color(ctx, attr);
    }
}
