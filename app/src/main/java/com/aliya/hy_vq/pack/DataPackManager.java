package com.aliya.hy_vq.pack;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 数据包管理器 —— 与 {@code UpdateManager}（代码包）<b>刻意分开</b>。
 *
 * <h3>为什么不做进 UpdateManager</h3>
 * 现有 .hyv 机制与「可执行代码」深度耦合：条目白名单只放
 * {@code manifest.json + classes.dex}、完整性校验绑的是 {@code dexMd5}、
 * RSA 签名覆盖的是 dex 哈希、还要对 dex 做敏感 API 静态审计，
 * 且全局只有<b>一个版本指针</b>。
 *
 * <p>数据包是纯数据（词库、模型、布局），既没有 dex 可校验/审计，
 * 又需要<b>多包并存、各自版本</b>。硬塞进同一套流程会互相牵制，
 * 并被迫削弱原有的验签与审计设计。因此独立实现。</p>
 *
 * <h3>包格式</h3>
 * <pre>
 * {id}-v{ver}.dpk     （标准 zip 容器）
 * ├── manifest.json   {
 * │                     "format":"hyv-datapack",
 * │                     "id":"ime-pinyin",        // 唯一标识
 * │                     "versionCode":3,
 * │                     "versionName":"1.2",
 * │                     "minBaseVersion":60,      // 可选：要求的最低壳版本
 * │                     "name":"拼音词库",         // 可选：展示名
 * │                     "zipMd5":"…",             // 整个 zip 的 MD5
 * │                     "signature":"…"           // 可选：RSA 签名（覆盖 canonical）
 * │                   }
 * └── ...（任意数据文件，如 dict.db / model.bin / layout.json）
 * </pre>
 *
 * <h3>签名方案（与代码包同构）</h3>
 * canonical = {@code format|id|versionCode|versionName|minBaseVersion|zipMd5}
 * → 签名覆盖 zipMd5 → <b>改任何字节都会使 zipMd5 变化，进而验签失败</b>。
 * 复用与代码包<b>同一把</b>发布公钥，无需第二套密钥。
 *
 * <h3>目录布局</h3>
 * <pre>
 * filesDir/data_packs/
 * ├── index.json         已安装清单（id → {versionCode, versionName, ...}）
 * └── {id}/v{ver}/       解压后的内容（消费者从这里读）
 * </pre>
 */
public final class DataPackManager {

    private static final String TAG = "DataPackManager";

    /** 专属格式标识 */
    public static final String FORMAT = "hyv-datapack";
    /** 包文件扩展名 */
    public static final String EXT = ".dpk";

    private static final String DIR_ROOT = "data_packs";
    private static final String FILE_INDEX = "index.json";
    private static final String ENTRY_MANIFEST = "manifest.json";

    /** 与 UpdateManager 使用同一把发布公钥 */
    private static final String PUBKEY_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtO7qy7qprOxDWTFZ8YkzVdHtle3DgruEO8bvDTF+Pde8ydyEJALYH6BRqEMrmjGe39kivpjNsJTXWKWHgjbOJZgM4d2jOemcKOEB8NIBYZNBAiG5FzH9sLSpmTfd0K0cZgpj/br8+ct0D4rlbC2FEK5rRSsiNYT5I+uNnydkXGjoeh1NqSR/WA5iYhwemN3x4JwBbe/K0x2WRCHxQuErDwdTmBrB4knHiz5lirDF9pk9g5NUDLhBUHHhqUgY8C0gO/vp5jpyFEK1GlHo3XD7bKxuWrNB4hwyP05sWYJz2/oaQQ2WJGFyVrsQmyYnU2+7NkDmQ8h52Q+b6an53lyYIwIDAQAB";
    private static final String SIG_ALG = "SHA256withRSA";

    private static final long MAX_PACK_SIZE = 256L * 1024 * 1024;   // 256MB

    private DataPackManager() {}

    // ══════════════════════════════════════════════
    //  数据模型
    // ══════════════════════════════════════════════

    /** 数据包元信息（来自 manifest） */
    public static class PackInfo {
        public String id = "";
        public String name = "";
        public int versionCode = 0;
        public String versionName = "";
        public int minBaseVersion = 0;
        public String zipMd5 = "";
        public String signature = "";
        /** 下载地址（来自远程索引，不在 manifest 内） */
        public String url = "";
        /** 文件大小（来自远程索引，可选） */
        public long size = 0;

        public String display() {
            return (name == null || name.isEmpty() ? id : name)
                    + (versionName.isEmpty() ? "" : " v" + versionName);
        }
    }

    /** 安装结果 */
    public static class Result {
        public boolean ok;
        public String message = "";
        public int versionCode;
        public String versionName = "";
    }

    /** 已安装记录 */
    public static class Installed {
        public String id = "";
        public String name = "";
        public int versionCode;
        public String versionName = "";
    }

    // ══════════════════════════════════════════════
    //  查询
    // ══════════════════════════════════════════════

    /** 解析远程索引（packs.json），得到可用数据包列表 */
    public static List<PackInfo> parseIndex(String json) {
        List<PackInfo> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr;
            String t = json.trim();
            if (t.startsWith("[")) {
                arr = new JSONArray(t);
            } else {
                arr = new JSONObject(t).optJSONArray("packs");
            }
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                PackInfo p = new PackInfo();
                p.id = o.optString("id", "");
                p.name = o.optString("name", "");
                p.versionCode = o.optInt("versionCode", 0);
                p.versionName = o.optString("versionName", "");
                p.minBaseVersion = o.optInt("minBaseVersion", 0);
                p.zipMd5 = o.optString("zipMd5", "");
                p.url = o.optString("url", "");
                p.size = o.optLong("size", 0);
                if (!p.id.isEmpty()) out.add(p);
            }
        } catch (Throwable t) {
            Log.e(TAG, "parseIndex failed", t);
        }
        return out;
    }

    /** 已安装清单 */
    public static List<Installed> installed(Context ctx) {
        List<Installed> out = new ArrayList<>();
        try {
            File f = new File(new File(ctx.getFilesDir(), DIR_ROOT), FILE_INDEX);
            if (!f.exists()) return out;
            JSONObject root = new JSONObject(readFile(f));
            java.util.Iterator<String> it = root.keys();
            while (it.hasNext()) {
                String id = it.next();
                JSONObject o = root.optJSONObject(id);
                if (o == null) continue;
                Installed ins = new Installed();
                ins.id = id;
                ins.name = o.optString("name", "");
                ins.versionCode = o.optInt("versionCode", 0);
                ins.versionName = o.optString("versionName", "");
                out.add(ins);
            }
        } catch (Throwable t) {
            Log.e(TAG, "installed failed", t);
        }
        return out;
    }

    /** 已安装的数据包目录（消费者从这里读数据）；未安装返回 null */
    public static File dataDir(Context ctx, String id) {
        int vc = installedVersion(ctx, id);
        if (vc <= 0) return null;
        File d = new File(new File(new File(ctx.getFilesDir(), DIR_ROOT), id), "v" + vc);
        return d.exists() ? d : null;
    }

    /** 已安装的版本号；未安装返回 0 */
    public static int installedVersion(Context ctx, String id) {
        try {
            File f = new File(new File(ctx.getFilesDir(), DIR_ROOT), FILE_INDEX);
            if (!f.exists()) return 0;
            JSONObject o = new JSONObject(readFile(f)).optJSONObject(id);
            return o == null ? 0 : o.optInt("versionCode", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ══════════════════════════════════════════════
    //  安装
    // ══════════════════════════════════════════════

    /**
     * 安装数据包。
     *
     * @param src 已下载到本地的包文件
     */
    public static Result install(Context ctx, File src) {
        Result r = new Result();
        File partial = null;
        try {
            if (src == null || !src.exists() || src.length() <= 0) {
                r.message = "文件不存在或为空";
                return r;
            }
            if (src.length() > MAX_PACK_SIZE) {
                r.message = "数据包过大（上限 " + (MAX_PACK_SIZE / 1024 / 1024) + "MB）";
                return r;
            }

            // ① 读取 manifest 并校验
            PackInfo info = new PackInfo();
            String zipMd5;
            try (ZipFile zf = new ZipFile(src)) {
                ZipEntry me = zf.getEntry(ENTRY_MANIFEST);
                if (me == null) {
                    r.message = "数据包缺少 manifest.json";
                    return r;
                }
                try (InputStream in = zf.getInputStream(me)) {
                    JSONObject m = new JSONObject(new String(readAll(in), "UTF-8"));
                    r.message = validate(ctx, m, info);
                    if (r.message != null) return r;
                }
            } catch (java.io.IOException ioe) {
                r.message = "数据包无法解析（损坏或非 zip）";
                return r;
            }

            // ② 完整性校验：整包 MD5
            zipMd5 = md5File(src);
            if (info.zipMd5.isEmpty()) {
                r.message = "manifest 缺少 zipMd5，无法校验完整性";
                return r;
            }
            if (!zipMd5.equalsIgnoreCase(info.zipMd5)) {
                r.message = "数据包校验失败（MD5 不匹配，可能下载不完整或被篡改）";
                return r;
            }

            // ③ 签名验签（有签名则必须通过；数据包强制要求）
            if (info.signature.isEmpty()) {
                r.message = "数据包未签名，来源不可信";
                return r;
            }
            if (!verifySignature(info)) {
                r.message = "签名验证失败，数据包来源不可信";
                return r;
            }

            // ④ 版本检查
            int cur = installedVersion(ctx, info.id);
            if (info.versionCode <= cur) {
                r.message = "已安装 v" + cur + "，无需更新";
                return r;
            }

            // ⑤ 解压到 v{N}.partial → 原子改名（中断不留半成品）
            File root = new File(ctx.getFilesDir(), DIR_ROOT);
            File packDir = new File(root, info.id);
            partial = new File(packDir, "v" + info.versionCode + ".partial");
            deleteRecursive(partial);
            if (!partial.mkdirs()) {
                r.message = "无法创建安装目录";
                return r;
            }
            if (!unzipSafe(src, partial)) {
                r.message = "解压失败（含非法路径条目）";
                deleteRecursive(partial);
                return r;
            }

            File finalDir = new File(packDir, "v" + info.versionCode);
            deleteRecursive(finalDir);
            if (!partial.renameTo(finalDir)) {
                r.message = "安装目录切换失败";
                deleteRecursive(partial);
                return r;
            }

            // ⑥ 写入索引（旧版本目录随后清理）
            writeIndex(ctx, info, finalDir);

            // ⑦ 清理旧版本（只保留当前版本）
            File[] olds = packDir.listFiles();
            if (olds != null) {
                for (File f : olds) {
                    if (f.isDirectory() && !f.getName().equals("v" + info.versionCode)) {
                        deleteRecursive(f);
                    }
                }
            }

            r.ok = true;
            r.versionCode = info.versionCode;
            r.versionName = info.versionName;
            r.message = "数据包「" + info.display() + "」安装成功";
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
            r.message = "安装失败：" + (t.getMessage() == null ? t.toString() : t.getMessage());
            if (partial != null) deleteRecursive(partial);
        }
        return r;
    }

    /** 校验 manifest 字段；通过返回 null，失败返回错误信息 */
    private static String validate(Context ctx, JSONObject m, PackInfo out) {
        if (!FORMAT.equals(m.optString("format"))) {
            return "不是数据包（format 不符）";
        }
        out.id = m.optString("id", "").trim();
        if (out.id.isEmpty()) return "manifest 缺少 id";
        // id 只允许安全字符，防路径穿越
        if (!out.id.matches("[A-Za-z0-9_-]{1,64}")) {
            return "id 含非法字符（仅允许字母数字、下划线、连字符）";
        }
        out.name = m.optString("name", "");
        out.versionCode = m.optInt("versionCode", 0);
        if (out.versionCode <= 0) return "versionCode 无效";
        out.versionName = m.optString("versionName", "");
        out.minBaseVersion = m.optInt("minBaseVersion", 0);
        out.zipMd5 = m.optString("zipMd5", "");
        out.signature = m.optString("signature", "");

        if (out.minBaseVersion > 0) {
            try {
                int base = ctx.getPackageManager()
                        .getPackageInfo(ctx.getPackageName(), 0).versionCode;
                if (base < out.minBaseVersion) {
                    return "应用版本过旧（需要 v" + out.minBaseVersion + "+），请先升级";
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 解压（带 Zip Slip 防护） */
    private static boolean unzipSafe(File zip, File dest) {
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> es = zf.entries();
            String destPath = dest.getCanonicalPath();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                if (e.isDirectory()) continue;
                File out = new File(dest, e.getName());
                // 防 Zip Slip：解析后的路径必须仍在目标目录内
                if (!out.getCanonicalPath().startsWith(destPath + File.separator)) {
                    Log.e(TAG, "zip slip detected: " + e.getName());
                    return false;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
                try (InputStream in = zf.getInputStream(e);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "unzip failed", t);
            return false;
        }
    }

    /** 卸载数据包（同时清理内容与索引） */
    public static void uninstall(Context ctx, String id) {
        try {
            deleteRecursive(new File(new File(ctx.getFilesDir(), DIR_ROOT), id));
            File idx = new File(new File(ctx.getFilesDir(), DIR_ROOT), FILE_INDEX);
            if (!idx.exists()) return;
            JSONObject root = new JSONObject(readFile(idx));
            root.remove(id);
            writeFile(idx, root.toString());
        } catch (Throwable t) {
            Log.e(TAG, "uninstall failed", t);
        }
    }

    // ══════════════════════════════════════════════
    //  签名
    // ══════════════════════════════════════════════

    /**
     * canonical 拼接 —— 与签名工具端必须一致：
     * {@code format|id|versionCode|versionName|minBaseVersion|zipMd5}
     */
    public static String canonical(PackInfo p) {
        return FORMAT
                + "|" + p.id
                + "|" + p.versionCode
                + "|" + p.versionName
                + "|" + p.minBaseVersion
                + "|" + p.zipMd5;
    }

    static boolean verifySignature(PackInfo p) {
        try {
            byte[] sigBytes = Base64.getDecoder().decode(p.signature);
            byte[] keyBytes = Base64.getDecoder().decode(PUBKEY_B64);
            PublicKey pub = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
            Signature sig = Signature.getInstance(SIG_ALG);
            sig.initVerify(pub);
            sig.update(canonical(p).getBytes("UTF-8"));
            return sig.verify(sigBytes);
        } catch (Throwable t) {
            Log.e(TAG, "verify signature failed", t);
            return false;
        }
    }

    // ══════════════════════════════════════════════
    //  索引读写
    // ══════════════════════════════════════════════

    private static void writeIndex(Context ctx, PackInfo info, File dir) throws Exception {
        File root = new File(ctx.getFilesDir(), DIR_ROOT);
        root.mkdirs();
        File idx = new File(root, FILE_INDEX);
        JSONObject obj = idx.exists() ? new JSONObject(readFile(idx)) : new JSONObject();
        JSONObject e = new JSONObject();
        e.put("versionCode", info.versionCode);
        e.put("versionName", info.versionName);
        e.put("name", info.name);
        e.put("dir", dir.getName());
        e.put("installedAt", System.currentTimeMillis());
        obj.put(info.id, e);
        writeFile(idx, obj.toString());
    }

    // ══════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════

    static String md5File(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return toHex(md.digest());
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in), "UTF-8");
        }
    }

    private static void writeFile(File f, String content) throws Exception {
        File p = f.getParentFile();
        if (p != null && !p.exists()) p.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes("UTF-8"));
        }
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
