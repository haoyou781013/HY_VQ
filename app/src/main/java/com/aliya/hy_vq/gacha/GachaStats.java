package com.aliya.hy_vq.gacha;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 祈愿记录统计。
 *
 * <p><b>核心是按「共享保底组」统计，而不是按 gacha_type 统计。</b></p>
 *
 * <p>依据 UIGF 标准的 uigf_gacha_type 映射（官方 bundle 的 itemTypeMap 亦印证）：</p>
 * <pre>
 *   uigf_gacha_type | gacha_type          含义
 *        301        |  301 | 400         角色活动祈愿 与 角色活动祈愿-2 共享保底
 *        500        |  500 | 5001        集录祈愿 与 溯光祈愿 共享保底
 * </pre>
 *
 * <p>⚠️ 若直接按 gacha_type 分组，301 与 400 之间的水位会被割裂：
 * 比如在 301 垫了 60 抽、转到 400 又抽 20 抽出货，
 * 会被误算成「20 抽出货」而不是正确的「80 抽出货」，垫抽也会算错。</p>
 */
public final class GachaStats {

    private GachaStats() {}

    /** 一次五星出货 */
    public static class FiveStar {
        public String name = "";
        public String time = "";
        /** 第几抽出（从上次五星之后开始计，跨共享保底的池累计） */
        public int pulls = 0;
        /** 出货时所在的精确卡池类型 */
        public String gachaType = "";
        public boolean weapon = false;

        public String poolName() {
            return GachaRecord.poolName(gachaType);
        }
    }

    /** 一个「共享保底组」的统计 */
    public static class PoolStat {
        /** 归一化后的组类型（301/500/…） */
        public String groupType = "";
        /** 组显示名（角色活动祈愿 / 集录祈愿 …） */
        public String name = "";
        /** 组内实际出现过的精确 gacha_type（如 {301, 400}） */
        public final Set<String> subTypes = new LinkedHashSet<>();
        /** 组内各精确类型的抽数 */
        public final Map<String, Integer> counts = new LinkedHashMap<>();

        public int total = 0;
        public int count5 = 0;
        public int count4 = 0;
        /** 全部五星（按时间升序） */
        public final List<FiveStar> fiveStars = new ArrayList<>();
        /** 当前垫抽数（距上次五星已抽多少，跨 301/400 累计） */
        public int pity = 0;
        /** 平均出货抽数（仅当有五星时有效） */
        public double avg5 = 0;
        public String firstTime = "";
        public String lastTime = "";

        public String avg5Text() {
            if (count5 == 0) return "—";
            return String.format(java.util.Locale.US, "%.1f", avg5);
        }

        /** 组内细分明细，如 "角色活动祈愿 120 + 角色活动祈愿-2 30"；只有单一池时返回空串 */
        public String subBreakdown() {
            if (subTypes.size() <= 1) return "";
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (sb.length() > 0) sb.append(" + ");
                sb.append(GachaRecord.poolName(e.getKey())).append(' ').append(e.getValue());
            }
            return sb.toString();
        }
    }

    /** 总统计结果 */
    public static class Result {
        public int totalPulls = 0;
        public int total5 = 0;
        public int total4 = 0;
        public final List<PoolStat> pools = new ArrayList<>();
        public String uid = "";
        public String firstTime = "";
        public String lastTime = "";
        /** 记录里出现过的全部精确类型（用于排查未知池） */
        public final Set<String> allTypes = new LinkedHashSet<>();
    }

    /** 从完整记录表计算统计（传入顺序不限，内部自行排序） */
    public static Result compute(List<GachaRecord> records) {
        Result res = new Result();
        if (records == null || records.isEmpty()) return res;

        // ── 按共享保底组分组（关键：不是按 gacha_type） ──
        Map<String, List<GachaRecord>> byGroup = new LinkedHashMap<>();
        for (GachaRecord r : records) {
            if (r == null) continue;
            if (res.uid.isEmpty()) res.uid = r.uid;
            res.allTypes.add(r.gachaType);
            byGroup.computeIfAbsent(r.uigfType(), k -> new ArrayList<>()).add(r);
        }

        // 固定顺序：301 → 302 → 500 → 200 → 100 → 其它
        List<String> order = new ArrayList<>();
        for (String t : new String[]{"301", "302", "500", "200", "100"}) {
            if (byGroup.containsKey(t)) order.add(t);
        }
        for (String t : byGroup.keySet()) {
            if (!order.contains(t)) order.add(t);
        }

        for (String g : order) {
            List<GachaRecord> list = byGroup.get(g);
            if (list == null) continue;
            // 时间升序（同秒按 id 兜底），跨 301/400 合并排序才能算对水位
            Collections.sort(list, (a, b) -> a.sortKey().compareTo(b.sortKey()));

            PoolStat ps = new PoolStat();
            ps.groupType = g;
            ps.name = GachaRecord.groupName(g);
            int sinceLast5 = 0;
            boolean firstSet = false;

            for (GachaRecord r : list) {
                ps.total++;
                res.totalPulls++;
                ps.subTypes.add(r.gachaType);
                Integer c = ps.counts.get(r.gachaType);
                ps.counts.put(r.gachaType, c == null ? 1 : c + 1);

                if (!firstSet) {
                    ps.firstTime = r.time;
                    res.firstTime = r.time;
                    firstSet = true;
                }
                ps.lastTime = r.time;
                res.lastTime = r.time;

                sinceLast5++;
                if (r.rankType == 5) {
                    FiveStar f = new FiveStar();
                    f.name = r.name;
                    f.time = r.time;
                    f.pulls = sinceLast5;
                    f.gachaType = r.gachaType;
                    f.weapon = "302".equals(r.gachaType) || "武器".equals(r.itemType);
                    ps.fiveStars.add(f);
                    ps.count5++;
                    res.total5++;
                    sinceLast5 = 0;
                } else if (r.rankType == 4) {
                    ps.count4++;
                    res.total4++;
                }
            }
            ps.pity = sinceLast5;
            if (ps.count5 > 0) {
                int sum = 0;
                for (FiveStar f : ps.fiveStars) sum += f.pulls;
                ps.avg5 = (double) sum / ps.count5;
            }
            res.pools.add(ps);
        }
        return res;
    }
}
