package com.aliya.hy_vq.gacha;

import org.json.JSONObject;

/**
 * 单条祈愿记录。
 *
 * <p>字段来自米哈游抽卡 API（{@code /gacha_info/api/getGachaLog}）返回的 list 元素。
 * {@link #id} 是服务端生成的全局唯一记录号，<b>融合去重就靠它</b>。</p>
 */
public class GachaRecord {

    /** 记录唯一 ID（服务端生成；同一 uid 下全局唯一，用于去重） */
    public String id = "";
    /** 账号 uid */
    public String uid = "";
    /** 卡池类型：100 新手 / 200 常驻 / 301 角色活动 / 302 武器活动 / 500 集录 */
    public String gachaType = "";
    /** 物品名称 */
    public String name = "";
    /** 物品类型（角色/武器） */
    public String itemType = "";
    /** 星级：3 / 4 / 5 */
    public int rankType = 0;
    /** 抽取时间，格式 "2026-09-23 07:53:15" */
    public String time = "";

    public GachaRecord() {}

    /** 从 API 的单个 JSON 对象构造；字段缺失时用空串兜底 */
    public static GachaRecord fromJson(JSONObject o) {
        GachaRecord r = new GachaRecord();
        r.id = o.optString("id", "");
        r.uid = o.optString("uid", "");
        r.gachaType = o.optString("gacha_type", "");
        r.name = o.optString("name", "");
        r.itemType = o.optString("item_type", "");
        r.time = o.optString("time", "");
        try {
            r.rankType = Integer.parseInt(o.optString("rank_type", "0"));
        } catch (Throwable ignored) {
            r.rankType = 0;
        }
        return r;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("uid", uid);
            o.put("gacha_type", gachaType);
            o.put("name", name);
            o.put("item_type", itemType);
            o.put("rank_type", String.valueOf(rankType));
            o.put("time", time);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** 卡池中文名（精确到具体池） */
    public String poolName() {
        return poolName(gachaType);
    }

    /**
     * 卡池中文名。
     * <p>完整清单取自官方 bundle.js 的 itemTypeNameMap[zh-cn]（实测），
     * 共 7 种：100/200/301/302/400/500/5001。</p>
     */
    public static String poolName(String type) {
        switch (type == null ? "" : type) {
            case "100": return "新手祈愿";
            case "200": return "常驻祈愿";
            case "301": return "角色活动祈愿";
            case "302": return "武器活动祈愿";
            case "400": return "角色活动祈愿-2";
            case "500": return "集录祈愿";
            case "5001": return "溯光祈愿";
            default: return "其它祈愿(" + type + ")";
        }
    }

    /**
     * 归一化到「共享保底组」。
     *
     * <p>依据 UIGF 标准的 uigf_gacha_type 映射表：</p>
     * <pre>
     *   uigf_gacha_type | gacha_type
     *        301        |   301 | 400      ← 角色活动祈愿 与 角色活动祈愿-2 共享保底
     *        500        |   500 | 5001     ← 集录祈愿 与 溯光祈愿 共享保底
     * </pre>
     *
     * <p>官方 bundle 的 itemTypeMap 也印证：菜单只列 5 项，
     * 名称分别是「角色活动祈愿与角色活动祈愿-2」「集录祈愿与溯光祈愿」。</p>
     *
     * <p>⚠️ 若不做归一化而直接按 gacha_type 分组算保底，
     * 301/400 之间的水位会被割裂，算出的「出货抽数」和「垫抽」都会失真。</p>
     */
    public String uigfType() {
        return uigfType(gachaType);
    }

    public static String uigfType(String type) {
        if ("400".equals(type)) return "301";
        if ("5001".equals(type)) return "500";
        return type == null ? "" : type;
    }

    /** 共享保底组的显示名（合并后的池名） */
    public static String groupName(String type) {
        switch (uigfType(type)) {
            case "100": return "新手祈愿";
            case "200": return "常驻祈愿";
            case "301": return "角色活动祈愿";
            case "302": return "武器活动祈愿";
            case "500": return "集录祈愿";
            default: return "其它祈愿(" + type + ")";
        }
    }

    /** 稳定的排序键：时间字符串可直接字典序比较；同秒时按 id 兜底 */
    public String sortKey() {
        return (time == null ? "" : time) + "|" + (id == null ? "" : id);
    }
}
