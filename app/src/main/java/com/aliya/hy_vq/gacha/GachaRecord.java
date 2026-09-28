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

    /** 卡池中文名 */
    public String poolName() {
        return poolName(gachaType);
    }

    public static String poolName(String type) {
        switch (type == null ? "" : type) {
            case "100": return "新手祈愿";
            case "200": return "常驻祈愿";
            case "301": return "角色活动祈愿";
            case "302": return "武器活动祈愿";
            case "500": return "集录祈愿";
            default: return "未知卡池(" + type + ")";
        }
    }

    /** 稳定的排序键：时间字符串可直接字典序比较；同秒时按 id 兜底 */
    public String sortKey() {
        return (time == null ? "" : time) + "|" + (id == null ? "" : id);
    }
}
