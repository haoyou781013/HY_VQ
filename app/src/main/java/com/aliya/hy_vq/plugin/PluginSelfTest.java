package com.aliya.hy_vq.plugin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 插件链路自测器 —— <b>仅供开发调试</b>，通过 adb 触发，不面向用户。
 *
 * <p>为什么需要它：容器环境装不了 Android 官方模拟器
 * （Google 从未发布 {@code emulator-linux_aarch64}），而真机上点击
 * 需要人工介入。故提供一个可被 {@code am start} 触发的自测入口，
 * 走<b>与真实用户完全相同的代码路径</b>，把每步结果写进 logcat。</p>
 *
 * <h3>用法</h3>
 * <pre>
 * # 把插件 zip 放到 Download 目录后：
 * adb shell am start -n com.aliya.hy_vq/.MainActivity \
 *     --es hyvq_plugin_selftest "/sdcard/Download/hyvq-plugin-wuwaemoji-1.0.0.zip"
 *
 * # 只测加载（不重装）：
 * adb shell am start ... --es hyvq_plugin_selftest "load"
 *
 * # 卸载：
 * adb shell am start ... --es hyvq_plugin_selftest "uninstall:wuwaemoji"
 *
 * # 看结果：
 * adb logcat -s HYVQ_TEST
 * </pre>
 *
 * <h3>安全</h3>
 * 只在 <b>debug 构建</b>或显式传入 extra 时运行；正常用户启动
 * 不带这个 extra，等于不存在。不读取任何用户数据。
 */
public final class PluginSelfTest {

    private static final String TAG = "HYVQ_TEST";
    /** intent extra 键，随便取的，不与业务冲突 */
    public static final String EXTRA = "hyvq_plugin_selftest";

    private PluginSelfTest() {}

    /**
     * 若 intent 带自测 extra 则执行，返回 true 表示已处理（调用方应跳过正常流程）。
     */
    public static boolean maybeRun(Activity act, PluginManager pm) {
        Intent it = act.getIntent();
        if (it == null) return false;
        String cmd = it.getStringExtra(EXTRA);
        if (cmd == null || cmd.trim().isEmpty()) return false;

        cmd = cmd.trim();
        Log.i(TAG, "══════ 插件自测开始 ══════");
        Log.i(TAG, "命令: " + cmd);
        Log.i(TAG, "宿主: " + hostInfo(act));
        Log.i(TAG, "私有目录: " + act.getFilesDir().getAbsolutePath());

        try {
            if ("load".equals(cmd)) {
                testLoadOnly(act, pm);
            } else if (cmd.startsWith("uninstall:")) {
                testUninstall(act, pm, cmd.substring("uninstall:".length()).trim());
            } else if (cmd.startsWith("view:") || "view".equals(cmd)) {
                String id = cmd.startsWith("view:") ? cmd.substring(5).trim() : "";
                testCreateView(act, pm, id);
            } else if ("list".equals(cmd)) {
                testList(act, pm);
            } else {
                testFullInstall(act, pm, cmd);
            }
        } catch (Throwable t) {
            Log.e(TAG, "自测异常终止", t);
        }
        Log.i(TAG, "══════ 插件自测结束 ══════");
        return true;
    }

    // ── 全流程：复制 → 安装 → 加载 → 建视图 ──────────────

    private static void testFullInstall(Activity act, PluginManager pm, String srcPath) {
        Log.i(TAG, "── 步骤 1/4：定位源文件 ──");
        File src = resolve(act, srcPath);
        if (src == null || !src.exists()) {
            Log.e(TAG, "❌ 找不到插件包: " + srcPath);
            return;
        }
        Log.i(TAG, "✅ 源文件 " + src.getAbsolutePath() + " (" + src.length() + " 字节)");

        // 复制到应用可读目录（Download 目录受 scoped storage 限制）
        Log.i(TAG, "── 步骤 2/4：安装（校验 + 解压）──");
        File local = new File(act.getCacheDir(), src.getName());
        try {
            copy(src, local);
            Log.i(TAG, "   已复制到私有缓存: " + local.length() + " 字节");
        } catch (Exception e) {
            Log.e(TAG, "❌ 复制失败", e);
            return;
        }

        String id = guessId(src.getName());
        String r = pm.install(id, local);
        Log.i(TAG, "   install() 返回: " + r);
        if (!pm.isInstalled(id)) {
            Log.e(TAG, "❌ 安装未生效");
            return;
        }
        Log.i(TAG, "✅ 安装成功，installedIds=" + pm.installedIds());
        Log.i(TAG, "   needsRestart=" + pm.needsRestart(id));

        Log.i(TAG, "── 步骤 3/4：加载 dex ──");
        testLoadOnly(act, pm);

        Log.i(TAG, "── 步骤 4/4：构建视图 ──");
        testCreateView(act, pm, id);

        Log.i(TAG, ">>> 完整链路测试结束");
        Log.i(TAG, ">>> 侧边栏需重启应用生效（dex 不能在进程内热加载）");
    }

    // ── 只测加载 ────────────────────────────────────────

    private static void testLoadOnly(Activity act, PluginManager pm) {
        int n = pm.loadAll();
        Log.i(TAG, "loadAll() 加载 " + n + " 个");
        List<HyVqPlugin> list = pm.getLoaded();
        if (list == null || list.isEmpty()) {
            Log.w(TAG, "⚠️ 没有插件被加载");
            List<String> ids = pm.installedIds();
            Log.i(TAG, "   已安装目录: " + ids);
            for (String id : ids) {
                Log.w(TAG, "   检查 " + id + "：文件是否完整？");
            }
            return;
        }
        for (HyVqPlugin p : list) {
            try {
                Log.i(TAG, "✅ 已加载: id=" + pm.get(p.getName()) + " name=" + p.getName()
                        + " version=" + p.getVersion()
                        + " order=" + p.order()
                        + " icon=" + p.iconName()
                        + " showInDrawer=" + p.showInDrawer()
                        + " summary=" + p.summary());
            } catch (Throwable t) {
                Log.e(TAG, "读插件元信息失败: " + p, t);
            }
        }
        // 排序验证
        List<HyVqPlugin> ordered = pm.loadedOrdered();
        StringBuilder sb = new StringBuilder();
        for (HyVqPlugin p : ordered) sb.append(p.getName()).append("(").append(p.order()).append(") ");
        Log.i(TAG, "侧边栏顺序（按 order）: " + sb);
    }

    // ── 建视图 ──────────────────────────────────────────

    private static void testCreateView(Activity act, PluginManager pm, String id) {
        List<HyVqPlugin> list = pm.getLoaded();
        HyVqPlugin target = null;
        for (HyVqPlugin p : list) {
            if (id == null || id.isEmpty() || id.equalsIgnoreCase(p.getName())) {
                target = p;
                break;
            }
        }
        if (target == null) {
            Log.w(TAG, "⚠️ 找不到插件 " + id + "，已加载: " + list.size() + " 个");
            return;
        }
        try {
            long t0 = System.currentTimeMillis();
            View v = target.createView(LayoutInflater.from(act), null);
            long ms = System.currentTimeMillis() - t0;
            if (v == null) {
                Log.e(TAG, "❌ createView 返回 null");
                return;
            }
            // 强制测量，验证不会测量成 0（宿主曾踩过：无 LayoutParams → 高度 0）
            int w = View.MeasureSpec.makeMeasureSpec(
                    act.getResources().getDisplayMetrics().widthPixels,
                    View.MeasureSpec.EXACTLY);
            int h = View.MeasureSpec.makeMeasureSpec(
                    act.getResources().getDisplayMetrics().heightPixels,
                    View.MeasureSpec.AT_MOST);
            v.measure(w, h);
            Log.i(TAG, "✅ createView 成功 (" + ms + "ms)"
                    + " 测量尺寸=" + v.getMeasuredWidth() + "x" + v.getMeasuredHeight()
                    + " 类型=" + v.getClass().getSimpleName());
            if (v.getMeasuredHeight() == 0) {
                Log.e(TAG, "❌ 测量高度为 0 —— 视图会不可见（检查 LayoutParams）");
            }
            // 检查是否有文本内容（证明 UI 真构建出来了）
            if (v instanceof ViewGroup) {
                String s = dump((ViewGroup) v, 0);
                Log.i(TAG, "视图树（前 8 行）:\n" + s);
            }
        } catch (Throwable t) {
            Log.e(TAG, "❌ createView 抛异常", t);
        }
    }

    private static void testList(Activity act, PluginManager pm) {
        Log.i(TAG, "已安装: " + pm.installedIds());
        Log.i(TAG, "已加载: " + pm.getLoaded().size() + " 个");
        Log.i(TAG, "待重启: " + pm.needsRestart("") );
        Log.i(TAG, "残留下载: " + pm.leftoverDownloads());
    }

    private static void testUninstall(Activity act, PluginManager pm, String id) {
        if (id.isEmpty()) {
            Log.e(TAG, "❌ 需要插件 id，如 uninstall:wuwaemoji");
            return;
        }
        boolean ok = pm.uninstall(id);
        Log.i(TAG, (ok ? "✅" : "❌") + " 卸载 " + id);
        Log.i(TAG, "   剩余已安装: " + pm.installedIds());
    }

    // ── 工具 ────────────────────────────────────────────

    private static String hostInfo(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0);
            return "v" + pi.versionName + " (code " + pi.versionCode + ")"
                    + " debug=" + ((pi.applicationInfo.flags
                    & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        } catch (Exception e) {
            return "?";
        }
    }

    /** 把路径解析成可读文件（兼容 /sdcard 与 Download 两种位置） */
    private static File resolve(Context c, String path) {
        File f = new File(path);
        if (f.exists()) return f;
        // 只给文件名时去 Download 找
        File dl = new File(Environment.getExternalStorageDirectory(), "Download/" + path);
        if (dl.exists()) return dl;
        return f;
    }

    private static String guessId(String fileName) {
        // hyvq-plugin-wuwaemoji-1.0.0.zip → wuwaemoji
        String n = fileName;
        if (n.startsWith("hyvq-plugin-")) n = n.substring("hyvq-plugin-".length());
        if (n.endsWith(".zip")) n = n.substring(0, n.length() - 4);
        int dash = n.lastIndexOf('-');
        if (dash > 0) {
            String tail = n.substring(dash + 1);
            if (tail.matches("\\d+(\\.\\d+)*")) n = n.substring(0, dash);
        }
        return n;
    }

    private static void copy(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** 递归打印视图树（只取前若干行，避免刷屏） */
    private static String dump(ViewGroup g, int depth) {
        StringBuilder sb = new StringBuilder();
        int[] count = {0};
        walk(g, depth, sb, count);
        return sb.toString();
    }

    private static void walk(View v, int depth, StringBuilder sb, int[] count) {
        if (count[0] > 8 || depth > 4) return;
        count[0]++;
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < depth; i++) pad.append("    ");
        String desc = v.getClass().getSimpleName();
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t != null && t.length() > 0) {
                String s = t.toString().replace("\n", " ");
                desc += " \"" + (s.length() > 30 ? s.substring(0, 30) + "…" : s) + "\"";
            }
        }
        sb.append(pad).append(desc).append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), depth + 1, sb, count);
        }
    }
}
