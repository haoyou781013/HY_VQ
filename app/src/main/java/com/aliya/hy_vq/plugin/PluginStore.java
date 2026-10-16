package com.aliya.hy_vq.plugin;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

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
 * GitHub Release → 123云盘。任一成功即可。
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

    private static final String REL_BASE =
            "https://github.com/haoyou781013/HY_VQ/releases/download/plugins/";

    /**
     * 内置插件目录。
     * <p>size/md5 在宿主发布时由 {@code tools/build_plugins.sh} 校准并回写本文件。</p>
     */
    public static List<Entry> catalog() {
        List<Entry> l = new ArrayList<>();
        l.add(new Entry("github", "GitHub", "仓库浏览、搜索、文件树、README 渲染、"
                        + "Issue/PR 只读、Token 或设备码登录",
                "1.0.0", 0, "", new String[]{
                REL_BASE + "hyvq-plugin-github-1.0.0.zip"}));
        l.add(new Entry("anime", "动漫", "多源聚合追番、番剧详情、追番清单、播放",
                "1.0.0", 0, "", new String[]{
                REL_BASE + "hyvq-plugin-anime-1.0.0.zip"}));
        l.add(new Entry("wuwaemoji", "鸣潮表情包", "鸣潮表情包检索、生成与保存",
                "1.0.0", 0, "", new String[]{
                REL_BASE + "hyvq-plugin-wuwaemoji-1.0.0.zip"}));
        return l;
    }

    /**
     * 构造插件商店视图。
     *
     * @param onBack 返回回调
     */
    public static View createView(Context act, PluginManager pm, Runnable onBack) {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int pd = dp(act, 12);
        root.setPadding(pd, dp(act, 8), pd, dp(act, 24));

        // 顶部条：返回 + 标题（每屏最多一个标题，Primer 官方要求）
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView back = tv(act, 16, "← 返回");
        back.setTextColor(colorOn(act, R.attr.colorPrimary));
        back.setMinHeight(dp(act, 48));
        back.setGravity(android.view.Gravity.CENTER_VERTICAL);
        back.setOnClickListener(v -> onBack.run());
        top.addView(back);
        root.addView(top);

        TextView title = tv(act, 20, "插件");
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(act, 4);
        tlp.bottomMargin = dp(act, 4);
        root.addView(title, tlp);

        TextView note = tv(act, 12,
                "插件与主程序分开发布，需要哪个下哪个。安装后重启 App 生效"
                        + "（dex 加载时机限制，Android 机制所致）。");
        note.setTextColor(colorOn(act, R.attr.colorOnSurfaceVariant));
        root.addView(note);

        // 已加载状态
        TextView stat = tv(act, 12, "已加载 " + pm.loadedCount() + " 个 · 已安装 "
                + pm.installedIds().size() + " 个");
        stat.setTextColor(colorOn(act, R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(act, 8);
        slp.bottomMargin = dp(act, 12);
        root.addView(stat, slp);

        // 目录
        for (Entry e : catalog()) {
            root.addView(pluginRow(act, pm, e, stat));
        }

        // 清理未完成的下载
        List<File> leftovers = pm.leftoverDownloads();
        if (!leftovers.isEmpty()) {
            TextView lv = tv(act, 12, "有 " + leftovers.size() + " 个未完成的下载残留");
            lv.setTextColor(colorOn(act, R.attr.colorOnSurfaceVariant));
            root.addView(lv);
        }

        return root;
    }

    private static View pluginRow(Context act, PluginManager pm, Entry e, TextView stat) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        int pd = dp(act, 12);
        col.setPadding(pd, dp(act, 12), pd, dp(act, 12));
        col.setMinimumHeight(dp(act, 48));

        boolean installed = pm.isInstalled(e.id);
        boolean needRestart = pm.needsRestart(e.id);

        int bg = colorOn(act, R.attr.colorSurfaceContainerLow);
        col.setBackground(com.aliya.hy_vq.module.ModuleUiKit.rounded(act, 12, bg, 0));

        LinearLayout row1 = new LinearLayout(act);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView name = tv(act, 16, e.name);
        name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        row1.addView(name);
        row1.addView(new View(act), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String state = needRestart ? "待重启生效" : (installed ? "已安装" : "未安装");
        TextView st = tv(act, 12, state);
        st.setTextColor(needRestart ? colorOn(act, R.attr.colorTertiary)
                : colorOn(act, R.attr.colorOnSurfaceVariant));
        row1.addView(st);
        col.addView(row1);

        TextView desc = tv(act, 12, e.desc);
        desc.setTextColor(colorOn(act, R.attr.colorOnSurfaceVariant));
        desc.setMaxLines(3);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(act, 4);
        col.addView(desc, dlp);

        String ver = "v" + e.version + (e.size > 0 ? " · " + (e.size / 1024) + " KB" : "");
        TextView verText = tv(act, 11, ver);
        verText.setTextColor(colorOn(act, R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = dp(act, 2);
        col.addView(verText, vlp);

        // 行动按钮
        TextView act1 = com.aliya.hy_vq.module.GhUi.button(
                act, installed ? "重新下载" : "下载并安装",
                v -> confirmDownload(act, pm, e, stat));
        if (installed) {
            TextView act2 = com.aliya.hy_vq.module.GhUi.buttonOutline(act, "卸载",
                    v -> confirmUninstall(act, pm, e, stat));
            LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            b2.leftMargin = dp(act, 8);
            LinearLayout.LayoutParams wrap = (LinearLayout.LayoutParams) act1.getLayoutParams();
            if (wrap == null) {
                wrap = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                act1.setLayoutParams(wrap);
            }
            wrap.topMargin = dp(act, 8);
            col.addView(act1, wrap);
            col.addView(act2, b2);
        } else {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(act, 8);
            col.addView(act1, blp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(act, 8);
        col.setLayoutParams(lp);
        return col;
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