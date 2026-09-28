package com.aliya.hy_vq.gacha;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 祈愿记录统计。
 *
 * <p>核心是<b>按卡池分别</b>统计保底：不同卡池的保底计数互相独立
 * （角色活动祈愿内部跨期共享，武器活动祈愿另有自己的计数），
 * 所以必须先按 {@link GachaRecord#gachaType} 分组再逐条累加。</p>
 */
public final class GachaStats {

    private GachaStats() {}

    /** 一次五星出货 */
    public static class FiveStar {
        public String name = "";
        public String time = "";
        /** 第几抽出（从上次五星之后开始计） */
        public int pulls = 0;
        /** 是否为武器池 */
        public boolean weapon = false;
    }

    /** 单个卡池的统计 */
    public static class PoolStat {
        public String type = "";
        public String name = "";
        /** 总抽数 */
        public int total = 0;
        /** 五星数 / 四星数 */
        public int count5 = 0;
        public int count4 = 0;
        /** 全部五星（按时间升序） */
        public final List<FiveStar> fiveStars = new ArrayList<>();
        /** 当前垫抽数（距离上次五星已抽多少，即下一发的保底进度） */
        public int pity = 0;
        /** 平均出货抽数（仅当有五星时有效） */
        public double avg5 = 0;
        /** 最早/最晚记录时间 */
        public String firstTime = "";
        public String lastTime = "";

        public String avg5Text() {
            if (count5 == 0) return "—";
            return String.format(java.util.Locale.US, "%.1f", avg5);
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
    }

    /** 从完整记录表计算统计（传入的列表顺序不限，内部会自行排序） */
    public static Result compute(List<GachaRecord> records) {
        Result res = new Result();
        if (records == null || records.isEmpty()) return res;

        // 按池分组
        Map<String, List<GachaRecord>> byPool = new LinkedHashMap<>();
        for (GachaRecord r : records) {
            if (r == null) continue;
            if (res.uid.isEmpty()) res.uid = r.uid;
            byPool.computeIfAbsent(r.gachaType == null ? "" : r.gachaType,
                    k -> new ArrayList<>()).add(r);
        }

        // 固定顺序输出，便于阅读：301 → 302 → 200 → 100 → 500 → 其它
        List<String> order = new ArrayList<>();
        for (String t : new String[]{"301", "400", "302", "200", "100", "500"}) {
            if (byPool.containsKey(t)) order.add(t);
        }
        for (String t : byPool.keySet()) {
            if (!order.contains(t)) order.add(t);
        }

        for (String type : order) {
            List<GachaRecord> list = byPool.get(type);
            if (list == null) continue;
            // 时间升序（同秒按 id 兜底），保证「第几抽出」正确
            Collections.sort(list, (a, b) -> a.sortKey().compareTo(b.sortKey()));

            PoolStat ps = new PoolStat();
            ps.type = type;
            ps.name = GachaRecord.poolName(type);
            int sinceLast5 = 0;

            for (GachaRecord r : list) {
                ps.total++;
                res.totalPulls++;
                if (ps.firstTime.isEmpty()) ps.firstTime = r.time;
                ps.lastTime = r.time;
                res.lastTime = r.time;

                if (r.rankType >= 4) {
                    if (r.rankType == 5) ps.count5++;
                    else ps.count4++;
                }
                if (res.firstTime.isEmpty() ||
                        r.sortKey().compareTo(res.firstTime) < 0) res.firstTime = r.time;

                sinceLast5++;
                if (r.rankType == 5) {
                    FiveStar f = new FiveStar();
                    f.name = r.name;
                    f.time = r.time;
                    f.pulls = sinceLast5;
                    f.weapon = "302".equals(type) || "武器".equals(r.itemType);
                    ps.fiveStars.add(f);
                    res.total5++;
                    sinceLast5 = 0;
                } else if (r.rankType == 4) {
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
