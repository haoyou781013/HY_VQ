package com.aliya.hy_vq.gacha;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 祈愿记录本地存档与「按 uid 融合」。
 *
 * <p><b>为什么必须本地存档</b>：米哈游服务端<b>只保留最近 6 个月</b>的祈愿记录，
 * 且每条 authkey 有效期很短。若不做增量落盘，旧记录到期即永久丢失。
 * 因此每次导入都是「拉取增量 → 与本地融合去重 → 回写」。</p>
 *
 * <p><b>融合键</b>：{@link GachaRecord#id}（服务端生成的记录唯一号）。
 * 同一 uid 下把多条链接、多次导入的数据合并到同一个文件，天然幂等 ——
 * 同一条记录重复导入不会产生重复数据。</p>
 */
public final class GachaStore {

    private static final String DIR = "gacha";
    private static final String SCHEMA = "hyvq-gacha-v1";

    private GachaStore() {}

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File fileOf(Context ctx, String uid) {
        return new File(dir(ctx), (uid == null || uid.isEmpty() ? "unknown" : uid) + ".json");
    }

    /** 已存档的 uid 列表（按最近更新时间倒序） */
    public static List<String> listUids(Context ctx) {
        List<String> out = new ArrayList<>();
        File[] fs = dir(ctx).listFiles();
        if (fs == null) return out;
        List<File> sorted = new ArrayList<>();
        for (File f : fs) {
            if (f.isFile() && f.getName().endsWith(".json")) sorted.add(f);
        }
        Collections.sort(sorted, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : sorted) out.add(f.getName().replace(".json", ""));
        return out;
    }

    /** 读取某 uid 的全部记录；不存在返回空表 */
    public static List<GachaRecord> load(Context ctx, String uid) {
        List<GachaRecord> out = new ArrayList<>();
        File f = fileOf(ctx, uid);
        if (!f.exists()) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            if (n <= 0) return out;
            JSONObject root = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("records");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(GachaRecord.fromJson(o));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 某 uid 已有的记录 id 集合（拉取时用于判断「已追平历史」并提前去重） */
    public static Set<String> idSet(Context ctx, String uid) {
        Set<String> s = new LinkedHashSet<>();
        for (GachaRecord r : load(ctx, uid)) {
            if (!r.id.isEmpty()) s.add(r.id);
        }
        return s;
    }

    /** 融合结果 */
    public static class MergeResult {
        public int added = 0;
        public int total = 0;
        public int skipped = 0;
    }

    /**
     * 把新拉取到的记录融合进本地存档（按 id 去重），并回写。
     *
     * @return 融合统计（新增 / 累计 / 重复跳过）
     */
    public static MergeResult merge(Context ctx, String uid, List<GachaRecord> incoming) {
        MergeResult mr = new MergeResult();
        if (uid == null || uid.isEmpty()) return mr;

        // 按 id 建立索引，保证同一条记录只留一份
        Map<String, GachaRecord> map = new LinkedHashMap<>();
        for (GachaRecord r : load(ctx, uid)) {
            if (!r.id.isEmpty()) map.put(r.id, r);
        }
        mr.total = map.size();

        if (incoming != null) {
            for (GachaRecord r : incoming) {
                if (r == null || r.id.isEmpty()) continue;
                if (map.containsKey(r.id)) {
                    mr.skipped++;
                    continue;
                }
                if (r.uid == null || r.uid.isEmpty()) r.uid = uid;
                map.put(r.id, r);
                mr.added++;
            }
        }
        mr.total = map.size();
        save(ctx, uid, new ArrayList<>(map.values()));
        return mr;
    }

    /** 回写（内部按时间升序整理，文件更可读） */
    public static void save(Context ctx, String uid, List<GachaRecord> records) {
        List<GachaRecord> sorted = new ArrayList<>(records);
        Collections.sort(sorted, (a, b) -> a.sortKey().compareTo(b.sortKey()));
        JSONObject root = new JSONObject();
        try {
            root.put("schema", SCHEMA);
            root.put("uid", uid);
            root.put("updated", System.currentTimeMillis() / 1000);
            root.put("count", sorted.size());
            JSONArray arr = new JSONArray();
            for (GachaRecord r : sorted) arr.put(r.toJson());
            root.put("records", arr);
        } catch (Throwable ignored) {
        }
        File f = fileOf(ctx, uid);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Throwable ignored) {
        }
    }

    /** 删除某 uid 的存档 */
    public static boolean remove(Context ctx, String uid) {
        return fileOf(ctx, uid).delete();
    }

    /** 存档文件大小（用于展示占用） */
    public static long sizeOf(Context ctx, String uid) {
        return fileOf(ctx, uid).length();
    }
}
