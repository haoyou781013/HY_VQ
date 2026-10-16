package com.aliya.hy_vq.plugin;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 插件管理器：下载 → 校验 → 解压 → DexClassLoader 加载。
 *
 * <p><b>设计要点</b>
 * <ul>
 *   <li>插件以 zip 形式分发（内含 {@code classes.dex} + {@code plugin.json}），
 *       与主程序更新包同链路：复用 md5 校验、断点续传、Gitee/GitHub 多源。</li>
 *   <li><b>加载时机</b>：不在安装时立即加载（dex 校验耗时），而是下次冷启动时
 *       由 MainActivity 触发 {@link #loadAll} —— 与 Android 的插件框架惯例一致。</li>
 *   <li><b>隔离</b>：插件 dex 的 ClassLoader 父加载器是 App 的 ClassLoader，
 *       因此插件可以引用主程序的类，但主程序<b>不能</b>反向引用插件类
 *       （编译期依赖方向），故插件只通过 {@code HyVqPlugin} SPI 暴露入口。</li>
 *   <li>失败降级：单个插件加载异常不影响其他插件，也不影响主程序启动。</li>
 * </ul>
 *
 * <p><b>不做的事</b>（明确记录，避免以后误以为漏了）：
 * 不做热替换（同一进程内卸载 dex）、不做热更新（必须重启生效）、
 * 不做 dex 校验优化（v1 用全量 md5，够用）。
 */
public final class PluginManager {

    private static final String TAG = "HyVqPlugin";
    private static final String PREFS = "hyvq_plugins";
    private static final int HTTP_TIMEOUT = 30000;

    /** 已加载的插件：id → 实例 */
    private final Map<String, HyVqPlugin> loaded = new LinkedHashMap<>();
    /** 下载完成的插件目录：id → 目录 */
    private final Map<String, File> installed = new LinkedHashMap<>();

    private final Context app;
    private final SharedPreferences prefs;
    private final File rootDir;

    public PluginManager(Context context) {
        this.app = context.getApplicationContext();
        this.prefs = this.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.rootDir = new File(this.app.getFilesDir(), "plugins");
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            Log.w(TAG, "无法创建插件目录: " + rootDir);
        }
    }

    // ══════════════════════ 下载 ══════════════════════

    /** 下载结果 */
    public static class DownloadResult {
        public final boolean ok;
        public final String message;
        public final File file;

        DownloadResult(boolean ok, String message, File file) {
            this.ok = ok;
            this.message = message;
            this.file = file;
        }
    }

    /**
     * 下载插件包并校验。
     *
     * @param url      插件 zip 直链
     * @param expectMd5 期望 md5（小写十六进制）；null 或空则跳过校验
     * @param expectSize 期望字节数；0 表示未知
     */
    public DownloadResult download(String url, String expectMd5, long expectSize) {
        if (url == null || url.trim().isEmpty()) {
            return new DownloadResult(false, "下载地址为空", null);
        }
        File tmp = new File(rootDir, ".dl_" + System.currentTimeMillis() + ".part");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT);
            conn.setReadTimeout(HTTP_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "HY_VQ-Android");
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                conn.disconnect();
                return new DownloadResult(false, "HTTP " + code, null);
            }
            long total = conn.getContentLengthLong();
            if (expectSize > 0 && total > 0 && total != expectSize) {
                conn.disconnect();
                return new DownloadResult(false,
                        "大小不符（服务器 " + total + "，期望 " + expectSize + "）", null);
            }
            InputStream in = conn.getInputStream();
            OutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            long written = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                written += n;
            }
            out.flush();
            out.close();
            in.close();
            conn.disconnect();

            if (written <= 0) {
                tmp.delete();
                return new DownloadResult(false, "下载内容为空", null);
            }
            if (expectMd5 != null && !expectMd5.isEmpty()) {
                String md5 = md5(tmp);
                if (!expectMd5.equalsIgnoreCase(md5)) {
                    tmp.delete();
                    return new DownloadResult(false, "md5 校验失败\n期望 " + expectMd5 + "\n实际 " + md5, null);
                }
            }
            return new DownloadResult(true, "下载完成（" + (written / 1024) + " KB）", tmp);
        } catch (Exception e) {
            if (tmp.exists()) tmp.delete();
            return new DownloadResult(false, "下载失败：" + e.getMessage(), null);
        }
    }

    // ══════════════════════ 安装 ══════════════════════

    /**
     * 解压安装。成功后需重启 App 才会加载（dex 加载时机限制）。
     *
     * @param pluginId 插件 id，作为目录名（同时用于防目录穿越校验）
     */
    public String install(String pluginId, File zip) {
        if (pluginId == null || !pluginId.matches("[A-Za-z0-9_.-]{1,64}")) {
            return "插件 id 非法";
        }
        File dir = new File(rootDir, pluginId);
        File staging = new File(rootDir, ".stage_" + pluginId);
        deleteRec(staging);
        if (!staging.mkdirs()) return "无法创建暂存目录";
        try {
            ZipFile zf = new ZipFile(zip);
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                File out = new File(staging, e.getName());
                // 防目录穿越：解析后必须仍在 staging 内
                if (!out.getCanonicalPath().startsWith(staging.getCanonicalPath() + File.separator)) {
                    zf.close();
                    deleteRec(staging);
                    return "插件包包含非法路径";
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    zf.close();
                    deleteRec(staging);
                    return "无法创建目录";
                }
                InputStream in = zf.getInputStream(e);
                FileOutputStream os = new FileOutputStream(out);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.close();
                in.close();
            }
            zf.close();

            File dex = new File(staging, "classes.dex");
            if (!dex.exists()) {
                deleteRec(staging);
                return "插件包缺少 classes.dex";
            }
            File manifest = new File(staging, "plugin.json");
            if (!manifest.exists()) {
                deleteRec(staging);
                return "插件包缺少 plugin.json";
            }
            deleteRec(dir);
            if (!staging.renameTo(dir)) {
                deleteRec(staging);
                return "安装目录替换失败";
            }
            prefs.edit().putBoolean("installed_" + pluginId, true).apply();
            return null;   // null = 成功
        } catch (Exception e) {
            deleteRec(staging);
            return "安装失败：" + e.getMessage();
        }
    }

    /** 卸载插件 */
    public boolean uninstall(String pluginId) {
        HyVqPlugin p = loaded.remove(pluginId);
        if (p != null) {
            try {
                p.onUnload();
            } catch (Exception ignored) {
                // 插件卸载钩子异常不应阻断卸载
            }
        }
        installed.remove(pluginId);
        File dir = new File(rootDir, pluginId);
        deleteRec(dir);
        prefs.edit().putBoolean("installed_" + pluginId, false).apply();
        return true;
    }

    public boolean isInstalled(String pluginId) {
        return new File(rootDir, pluginId + "/classes.dex").exists();
    }

    /** 已安装但未加载（需重启） */
    public boolean needsRestart(String pluginId) {
        return isInstalled(pluginId) && !loaded.containsKey(pluginId);
    }

    /** 已下载但未安装（上次下载没走完） */
    public List<File> leftoverDownloads() {
        List<File> out = new ArrayList<>();
        File[] fs = rootDir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.getName().startsWith(".dl_") || f.getName().startsWith(".stage_")) {
                out.add(f);
            }
        }
        return out;
    }

    // ══════════════════════ 加载 ══════════════════════

    /**
     * 加载全部已安装插件。
     * <p>单个插件失败只记录日志，不影响其他插件与主程序。</p>
     *
     * @return 成功加载的插件数
     */
    public int loadAll() {
        int ok = 0;
        File[] dirs = rootDir.listFiles();
        if (dirs == null) return 0;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            String id = d.getName();
            if (id.startsWith(".")) continue;       // .stage_ 暂存目录
            File dex = new File(d, "classes.dex");
            File manifest = new File(d, "plugin.json");
            if (!dex.exists() || !manifest.exists()) continue;
            try {
                HyVqPlugin p = loadOne(id, dex, manifest);
                if (p != null) {
                    loaded.put(id, p);
                    ok++;
                    Log.i(TAG, "插件已加载: " + id + " (" + p.getName() + ") v" + p.getVersion());
                }
            } catch (Throwable t) {
                // 关键：ClassNotFound / NoClassDefFound / VerifyError 都不能让主程序崩
                Log.e(TAG, "插件加载失败: " + id + " → " + t);
            }
        }
        return ok;
    }

    private HyVqPlugin loadOne(String id, File dex, File manifest) throws Exception {
        String meta = readAll(manifest);
        // 插件 manifest 是极简 JSON，宿主自带解析（android.jar 是桩，org.json 不可用）
        PluginMeta pm = PluginMeta.parse(meta);

        String optDir = new File(app.getFilesDir(), "plugin-opt/" + id).getAbsolutePath();
        File opt = new File(optDir);
        if (!opt.exists() && !opt.mkdirs()) {
            throw new IllegalStateException("无法创建优化目录");
        }
        // 子加载器：父加载器 = 宿主，保证宿主类全局只有一份（见 DexLoader 注释）
        DexLoader loader = new DexLoader(app, dex.getAbsolutePath(), optDir);
        Class<?> cls = loader.loadClass(pm.entry);
        Object inst = cls.getDeclaredConstructor().newInstance();
        if (!(inst instanceof HyVqPlugin)) {
            throw new IllegalStateException("入口类未实现 HyVqPlugin: " + pm.entry);
        }
        HyVqPlugin p = (HyVqPlugin) inst;

        // 宿主版本门槛：插件 manifest 声明 minHostVersion，过低则拒绝加载。
        // 这样插件用了新宿主才有的 API 时，旧宿主会明确跳过而不是崩溃。
        int hostVer = hostVersionCode();
        if (!pm.acceptsHost(hostVer)) {
            throw new IllegalStateException("插件要求宿主 versionCode >= "
                    + pm.minHostVersion + "，当前 " + hostVer);
        }

        p.onAttach(app, pm);
        return p;
    }

    // ══════════════════════ 查询 ══════════════════════

    /** 已加载的插件实例（只读快照，调用方不应改结构） */
    public List<HyVqPlugin> getLoaded() {
        return new ArrayList<>(loaded.values());
    }

    public HyVqPlugin get(String id) {
        return loaded.get(id);
    }

    public int loadedCount() {
        return loaded.size();
    }

    /** 已安装的插件 id 列表（无论是否加载成功） */
    public List<String> installedIds() {
        List<String> out = new ArrayList<>();
        File[] fs = rootDir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isDirectory() && !f.getName().startsWith(".")) out.add(f.getName());
        }
        return out;
    }

    // ══════════════════════ 工具 ══════════════════════

    /** 宿主 versionCode，用于 minHostVersion 门槛校验 */
    private int hostVersionCode() {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;   // 取不到就当作「不限制」，宁可放行也不误杀
        }
    }

    /**
     * 已加载插件按侧边栏顺序排序（order 升序，同序按 id）。
     * <p>侧边栏动态加载直接用这个列表，保证顺序稳定可复现。</p>
     */
    public List<HyVqPlugin> loadedOrdered() {
        List<HyVqPlugin> list = new ArrayList<>(loaded.values());
        java.util.Collections.sort(list, (a, b) -> {
            int oa = orderOf(a), ob = orderOf(b);
            if (oa != ob) return oa - ob;
            return idOf(a).compareTo(idOf(b));
        });
        return list;
    }

    /** 插件声明的 order（SPI 默认 500） */
    private static int orderOf(HyVqPlugin p) {
        try { return p.order(); } catch (Throwable t) { return 500; }
    }

    /** 反查插件 id（用于同序时按 id 稳定排序） */
    private String idOf(HyVqPlugin p) {
        for (Map.Entry<String, HyVqPlugin> e : loaded.entrySet()) {
            if (e.getValue() == p) return e.getKey();
        }
        return "";
    }

    public static String md5(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            FileInputStreamHolder.read(md, f);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) {
                int v = b & 0xFF;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String readAll(File f) {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRec(c);
        }
        f.delete();
    }

    /** 小工具类：避免 md5 方法里重复写流拷贝 */
    private static final class FileInputStreamHolder {
        static void read(MessageDigest md, File f) throws Exception {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            in.close();
        }
    }
}