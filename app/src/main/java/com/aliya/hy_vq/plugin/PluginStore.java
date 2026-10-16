package com.aliya.hy_vq.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.aliya.hy_vq.module.GhUi;
import com.google.android.material.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件商店：列出可下载插件，用户**选择下载**（与软件更新同链路，但不静默）。
 *
 * <p><b>插件目录（硬编码，随宿主发布）</b>：只有一个插件源（与更新源一致），
 * 因此没必要做动态索引 —— 真正的「可扩展」需要服务端目录协议，那是 v2 的事。
 * 这里刻意保持静态，避免引入「商店接口挂了 → 一个插件都装不了」的故障点。
 *
 * <p><b>下载源优先级</b>（与 {@code MainActivity.fetchReleases()} 一致）：
 * GitHub Release → Gitee Release。任一成功即可。
 */
public final class PluginStore {

    /** 插件描述 */
    public static class Entry {
        public final String id;
        public final String name;
        public final String desc;
        public final String version;
        public final long size;
        public final String md5;
        /** 多个下载源，按优先级排列 */
        public final String[] urls;

        Entry(String id, String name, String desc, String version,
              long size, String md5, String[] urls) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.version = version;
            this.size = size;
            this.md5 = md5;
            this.urls = urls;
        }
    }

    /**
     * 插件下载基址 —— <b>独立仓库</b>（HY_VQ-Plugins）。
     *
     * <p>2026-10-16 从主仓库 {@code HY_VQ/releases/download/plugins/} 迁出：
     * 插件与主程序分仓，可独立发布、独立升版本，主 APK 也不必再带插件代码。</p>
     */
    private static final String REL_BASE =
            "https://github.com/haoyou781013/HY_VQ-Plugins/releases/download/plugins-v1.0.0/";

    /** 国内镜像基址（Gitee 同名 tag），主源不可达时自动回退 */
    private static final String REL_BASE_CN =
            "https://gitee.com/haoyou781/HY_VQ-Plugins/releases/download/plugins-v1.0.0/";

    /**
     * 内置插件目录。
     *
     * <p>size/md5 由 {@code tools/build_plugins.sh} 生成后回写本文件 ——
     * 留 0/空表示「不校验」，下载仍可用，只是少了完整性防护。</p>
     */
    public static List<Entry> catalog() {
        List<Entry> l = new ArrayList<>();
        l.add(entry("github", "GitHub", "github",
                "仓库浏览、搜索、文件树、README 渲染、Issue/PR 只读、Token 或设备码登录"));
        l.add(entry("anime", "动漫", "anime",
                "多源聚合追番、番剧详情、追番清单、离线缓存与播放"));
        l.add(entry("share", "实用软件分享", "share",
                "浏览网友分享的实用软件，按分类查找与下载"));
        l.add(entry("genshin", "原神帮助", "game",
                "抽卡记录分析、提瓦特文字转换、游戏数据查询"));
        l.add(entry("wuwaemoji", "鸣潮表情包", "emoji",
                "随机鸣潮表情包，支持按角色筛选与保存到相册"));
        return l;
    }

    /**
     * 构造一个插件条目（双源：GitHub 主 + Gitee 国内备）。
     *
     * @param id      插件 id，决定 zip 文件名
     * @param version 插件版本，须与 {@code plugin.json} 里的 version 一致
     */
    private static Entry entry(String id, String name, String version, String desc) {
        String file = "hyvq-plugin-" + id + "-" + version + ".zip";
        return new Entry(id, name, desc, version, 0, "", new String[]{
                REL_BASE + file,
                REL_BASE_CN + file
        });
    }

    /**
     * 构造插件商店视图。
     *
     * @param onBack 返回回调
     */
    /**
     * 构造插件列表视图。
     *
     * <h3>设计依据（M3 官方）</h3>
     * 数值全部来自 m3.material.io 官方 data API 与 MDC-Android token：
     * <ul>
     *   <li>列表项高度 56/72/88dp（{@code md.comp.list.list-item.*-line.container.height}）</li>
     *   <li>列表项左右 padding 16dp（{@code m3_comp_list_list_item_leading_space}）</li>
     *   <li>元素间间距 {@code between-space} 12dp</li>
     *   <li>上下 padding {@code top-space}/{@code bottom-space} 各 10dp</li>
     *   <li>卡片圆角 12dp、内边距 16dp、间距 8dp</li>
     *   <li>按钮视觉高 40dp、水平 padding 24dp、pill 圆角</li>
     *   <li>Chip 高 32dp、圆角 8dp、左右 padding 16dp</li>
     *   <li>触摸目标 ≥48dp</li>
     * </ul>
     *
     * <h3>结构（三段横向）</h3>
     * <pre>
     * [1] 图标列 40dp  →  [2] 文本列 weight=1  →  [3] trailing 列 wrap
     * </pre>
     * 三段结构是「不简陋」的关键：统一左对齐锚点 + 文字三级层次 + 行高一致。
     *
     * @param onBack 返回回调
     */
    public static View createView(Context act, PluginManager pm, Runnable onBack) {
        // 滚动容器（原实现是裸 LinearLayout，列表长了底部够不到）
        android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(GhUi.SPACE_LG, GhUi.SPACE_SM, GhUi.SPACE_LG, GhUi.SPACE_XL);

        // ── 顶栏：返回（触摸目标 48dp）──
        TextView back = GhUi.text(act, GhUi.TYPE_BODY, "← 返回");
        back.setTextColor(GhUi.accent(act));
        back.setGravity(android.view.Gravity.CENTER_VERTICAL);
        back.setMinHeight(dp(act, GhUi.TOUCH_MIN));
        back.setPadding(0, 0, dp(act, GhUi.SPACE_LG), 0);
        back.setOnClickListener(v -> onBack.run());
        root.addView(back);

        // ── 标题（每屏仅一个，M3 要求）──
        TextView title = GhUi.text(act, GhUi.TYPE_HEADLINE, "插件");
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        title.setTextColor(colorOn(act, R.attr.colorOnSurface));
        LinearLayout.LayoutParams tlp = wrap();
        tlp.topMargin = dp(act, GhUi.SPACE_XS);
        root.addView(title, tlp);

        // ── 说明 ──
        TextView note = GhUi.text(act, GhUi.TYPE_CAPTION,
                "插件与主程序分开发布，按需下载。安装后重启 App 生效"
                        + "（Android dex 加载时机限制）。");
        note.setTextColor(GhUi.muted(act));
        LinearLayout.LayoutParams nlp = wrap();
        nlp.topMargin = dp(act, GhUi.SPACE_XS);
        root.addView(note, nlp);

        // ── 统计 ──
        TextView stat = GhUi.text(act, GhUi.TYPE_CAPTION, statText(pm));
        stat.setTextColor(GhUi.muted(act));
        LinearLayout.LayoutParams slp = wrap();
        slp.topMargin = dp(act, GhUi.SPACE_MD);
        slp.bottomMargin = dp(act, GhUi.SPACE_MD);
        root.addView(stat, slp);

        // ── 插件列表 ──
        List<Entry> list = catalog();
        if (list.isEmpty()) {
            // M3 官方无空状态规范，此处用项目既有的 blankslate 组件
            root.addView(GhUi.blankslate(act, "\u2295", "暂无可用插件",
                    "插件目录为空，稍后再试。", null, null));
        } else {
            for (Entry e : list) {
                root.addView(pluginRow(act, pm, e, stat));
            }
        }

        // ── 下载残留提示 ──
        List<File> leftovers = pm.leftoverDownloads();
        if (!leftovers.isEmpty()) {
            TextView lv = GhUi.text(act, GhUi.TYPE_CAPTION,
                    "有 " + leftovers.size() + " 个未完成的下载残留（重新下载会自动清理）");
            lv.setTextColor(GhUi.warning(act));
            LinearLayout.LayoutParams llp = wrap();
            llp.topMargin = dp(act, GhUi.SPACE_MD);
            root.addView(lv, llp);
        }

        scroll.addView(root);
        return scroll;
    }

    private static String statText(PluginManager pm) {
        return "已加载 " + pm.loadedCount() + " 个 · 已安装 "
                + pm.installedIds().size() + " 个";
    }

    /**
     * 单个插件条目 —— <b>三段横向结构</b>。
     *
     * <pre>
     * [1] 图标列(40dp) [2] 文本列(weight=1) [3] trailing列(wrap)
     *                 ├ 名称 16sp 500
     *                 ├ 描述 14sp（maxLines 2）
     *                 └ v1.0.0 · 17 KB 12sp  ← 合并，不单独占行
     * </pre>
     */
    private static View pluginRow(Context act, PluginManager pm, Entry e, TextView stat) {
        boolean installed = pm.isInstalled(e.id);
        boolean needRestart = pm.needsRestart(e.id);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // 列表项左右 16dp（M3 leading-space），上下 10dp（top/bottom-space）
        row.setPadding(dp(act, GhUi.SPACE_LG), dp(act, 10),
                dp(act, GhUi.SPACE_LG), dp(act, 10));
        row.setMinimumHeight(dp(act, 72));   // 双行高度下限
        // 描边卡片（M3 outlined：1dp 描边 + 0dp elevation）
        row.setBackground(com.aliya.hy_vq.module.ModuleUiKit.rippleBg(act,
                com.aliya.hy_vq.module.ModuleUiKit.rounded(
                        act, GhUi.RADIUS_CARD, colorOn(act, R.attr.colorSurface),
                        GhUi.border(act))));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(act, GhUi.SPACE_SM);   // 卡片间距 8dp
        row.setLayoutParams(rlp);

        // ── [1] 图标列：固定 40dp ──
        View icon = PluginIcons.iconView(act, iconOf(e.id), 40);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                dp(act, 40), dp(act, 40));
        ip.rightMargin = dp(act, GhUi.SPACE_MD);     // 12dp between-space
        row.addView(icon, ip);

        // ── [2] 文本列：weight=1 ──
        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 名称（16sp，主色，最高优先级）
        TextView name = GhUi.text(act, GhUi.TYPE_TITLE,
                installed && needRestart ? e.name : e.name);
        name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        name.setTextColor(installed && needRestart
                ? GhUi.muted(act) : colorOn(act, R.attr.colorOnSurface));
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(name);

        // 描述（14sp，次要色，限 2 行）
        TextView desc = GhUi.text(act, GhUi.TYPE_BODY, e.desc);
        desc.setTextColor(GhUi.muted(act));
        desc.setMaxLines(2);
        desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams dlp = wrap();
        dlp.topMargin = dp(act, GhUi.SPACE_XXS);
        mid.addView(desc, dlp);

        // 版本 + 大小合并一行（12sp，最弱）
        StringBuilder meta = new StringBuilder("v").append(e.version);
        if (e.size > 0) meta.append(" · ").append(e.size / 1024).append(" KB");
        TextView metaTv = GhUi.text(act, GhUi.TYPE_CAPTION, meta.toString());
        metaTv.setTextColor(GhUi.muted(act));
        LinearLayout.LayoutParams mlp = wrap();
        mlp.topMargin = dp(act, GhUi.SPACE_XS);
        mid.addView(metaTv, mlp);

        row.addView(mid);

        // ── [3] trailing 列：状态 chip + 操作按钮（垂直堆叠）──
        LinearLayout trail = new LinearLayout(act);
        trail.setOrientation(LinearLayout.VERTICAL);
        trail.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tpl2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tpl2.leftMargin = dp(act, GhUi.SPACE_SM);
        trail.setLayoutParams(tpl2);

        // 状态 chip（M3：按钮承担操作，chip 承担状态）
        if (needRestart) {
            TextView chip = GhUi.badge(act, "待重启", GhUi.warning(act));
            LinearLayout.LayoutParams clp = wrap();
            clp.bottomMargin = dp(act, GhUi.SPACE_XS);
            trail.addView(chip, clp);
        }

        // 操作按钮 —— 文案即动作，不做「已安装」这种反向语义
        TextView btn;
        if (needRestart) {
            btn = GhUi.buttonText(act, "重启生效", v -> restartApp(act));
        } else if (installed) {
            btn = GhUi.buttonOutline(act, "卸载", v -> confirmUninstall(act, pm, e, stat));
        } else {
            btn = GhUi.button(act, "安装", v -> confirmDownload(act, pm, e, stat));
        }
        btn.setMinWidth(dp(act, 88));
        trail.addView(btn);

        row.addView(trail);
        return row;
    }

    /** 插件 id → PluginIcons 图标名 */
    private static String iconOf(String id) {
        switch (id) {
            case "github":    return "github";
            case "anime":     return "anime";
            case "wuwaemoji": return "emoji";
            case "share":     return "share";
            case "genshin":   return "game";
            default:          return "code";
        }
    }

    /** 重启应用（提示用户，不静默） */
    private static void restartApp(Context act) {
        new AlertDialog.Builder(act)
                .setTitle("重启生效")
                .setMessage("插件需要重启 App 才能加载（Android dex 机制限制）。"
                        + "现在重启？")
                .setNegativeButton("稍后", null)
                .setPositiveButton("重启", (d, w) -> {
                    android.content.Intent i = act.getPackageManager()
                            .getLaunchIntentForPackage(act.getPackageName());
                    if (i != null) {
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                                | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        act.startActivity(i);
                    }
                    android.os.Process.killProcess(android.os.Process.myPid());
                })
                .show();
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 下载确认：显示大小与来源，不静默下载（与软件更新一致） */
    private static void confirmDownload(Context act, PluginManager pm, Entry e, TextView stat) {
        StringBuilder sb = new StringBuilder();
        sb.append("插件：").append(e.name).append("\n");
        sb.append("版本：v").append(e.version).append("\n");
        if (e.size > 0) sb.append("大小：").append(e.size / 1024).append(" KB\n");
        sb.append("来源：").append(uriHost(e.urls[0])).append("\n\n");
        sb.append("下载后需要重启 App 才会加载。是否继续？");

        new AlertDialog.Builder(act)
                .setTitle("下载插件")
                .setMessage(sb.toString())
                .setNegativeButton("取消", null)
                .setPositiveButton("下载", (d, w) -> doDownload(act, pm, e, stat))
                .show();
    }

    private static void doDownload(Context act, PluginManager pm, Entry e, TextView stat) {
        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("正在下载 " + e.name)
                .setMessage("请稍候…")
                .setCancelable(false)
                .create();
        dlg.show();

        new Thread(() -> {
            final android.os.Handler ui = new android.os.Handler(
                    android.os.Looper.getMainLooper());
            PluginManager.DownloadResult r = null;
            String lastErr = "";
            // 多源依次尝试（与更新源优先级一致）
            for (String u : e.urls) {
                r = pm.download(u, e.md5, e.size);
                if (r.ok) break;
                lastErr = r.message;
            }
            final PluginManager.DownloadResult res = r;
            final String err = lastErr;
            final File zip = res == null ? null : res.file;

            ui.post(() -> {
                if (zip == null) {
                    dlg.dismiss();
                    new AlertDialog.Builder(act)
                            .setTitle("下载失败")
                            .setMessage(err)
                            .setPositiveButton("确定", null)
                            .show();
                    return;
                }
                String errMsg = pm.install(e.id, zip);
                zip.delete();
                dlg.dismiss();
                if (errMsg != null) {
                    new AlertDialog.Builder(act)
                            .setTitle("安装失败")
                            .setMessage(errMsg)
                            .setPositiveButton("确定", null)
                            .show();
                } else {
                    new AlertDialog.Builder(act)
                            .setTitle("安装成功")
                            .setMessage(e.name + " 已安装。\n\n需要重启 App 后才会出现在侧边栏。")
                            .setPositiveButton("知道了", null)
                            .show();
                    if (stat != null) {
                        stat.setText("已加载 " + pm.loadedCount() + " 个 · 已安装 "
                                + pm.installedIds().size() + " 个（待重启生效）");
                    }
                }
            });
        }).start();
    }

    private static void confirmUninstall(Context act, PluginManager pm, Entry e, TextView stat) {
        new AlertDialog.Builder(act)
                .setTitle("卸载插件")
                .setMessage("确定卸载「" + e.name + "」？\n\n重启后侧边栏不再显示。")
                .setNegativeButton("取消", null)
                .setPositiveButton("卸载", (d, w) -> {
                    pm.uninstall(e.id);
                    new AlertDialog.Builder(act)
                            .setTitle("已卸载")
                            .setMessage("重启 App 后生效。")
                            .setPositiveButton("确定", null)
                            .show();
                    if (stat != null) {
                        stat.setText("已加载 " + pm.loadedCount() + " 个 · 已安装 "
                                + pm.installedIds().size() + " 个");
                    }
                })
                .show();
    }

    private static String uriHost(String url) {
        try {
            return new java.net.URI(url).getHost();
        } catch (Exception e) {
            return "未知";
        }
    }

    private static TextView tv(Context c, float sp, String s) {
        TextView v = new TextView(c);
        v.setText(s == null ? "" : s);
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(colorOn(c, R.attr.colorOnSurface));
        return v;
    }

    private static int colorOn(Context c, int attr) {
        return com.aliya.hy_vq.module.ModuleUiKit.color(c, attr);
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}