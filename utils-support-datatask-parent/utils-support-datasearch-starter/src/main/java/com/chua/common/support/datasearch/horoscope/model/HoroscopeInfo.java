package com.chua.common.support.datasearch.horoscope.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 星座运势信息模型。
 *
 * <p>描述指定星座在今日 / 本周 / 本月维度的综合运势、
 * 爱情 / 事业 / 财运 / 健康指数与幸运信息。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HoroscopeInfo {

    /**
     * 星座（如：白羊座）
     */
    private final String sign;

    /**
     * 运势类型：today / week / month
     */
    private final String type;

    /**
     * 综合运势指数（百分制）
     */
    private final int overall;

    /**
     * 爱情运势指数（百分制）
     */
    private final int love;

    /**
     * 事业运势指数（百分制）
     */
    private final int career;

    /**
     * 财运指数（百分制）
     */
    private final int wealth;

    /**
     * 健康指数（百分制）
     */
    private final int health;

    /**
     * 幸运数字
     */
    private final String luckyNumber;

    /**
     * 幸运颜色
     */
    private final String luckyColor;

    /**
     * 运势描述
     */
    private final String description;

    /**
      * 创建 horoscope信息 实例
     *
     * @param sign        星座
     * @param type        运势类型
     * @param overall     综合运势
     * @param love        爱情运势
     * @param career      事业运势
     * @param wealth      财运
     * @param health      健康
     * @param luckyNumber 幸运数字
     * @param luckyColor  幸运颜色
     * @param description 运势描述
     */
    public HoroscopeInfo(String sign, String type, int overall, int love, int career,
                         int wealth, int health, String luckyNumber, String luckyColor, String description) {
        this.sign = sign;
        this.type = type;
        this.overall = overall;
        this.love = love;
        this.career = career;
        this.wealth = wealth;
        this.health = health;
        this.luckyNumber = luckyNumber;
        this.luckyColor = luckyColor;
        this.description = description;
    }

    /**
     * 获取星座
     *
     * @return 获取标志的结果
     */
    public String getSign() {
        return sign;
    }

    /**
     * 获取运势类型
     *
     * @return 获取类型的结果
     */
    public String getType() {
        return type;
    }

    /**
     * 获取综合运势指数
     *
     * @return 获取overall的结果
     */
    public int getOverall() {
        return overall;
    }

    /**
     * 获取爱情运势指数
     *
     * @return 获取love的结果
     */
    public int getLove() {
        return love;
    }

    /**
     * 获取事业运势指数
     *
     * @return 获取career的结果
     */
    public int getCareer() {
        return career;
    }

    /**
     * 获取财运指数
     *
     * @return 获取wealth的结果
     */
    public int getWealth() {
        return wealth;
    }

    /**
     * 获取健康指数
     *
     * @return 获取健康的结果
     */
    public int getHealth() {
        return health;
    }

    /**
     * 获取幸运数字
     *
     * @return 获取lucky数字的结果
     */
    public String getLuckyNumber() {
        return luckyNumber;
    }

    /**
     * 获取幸运颜色
     *
     * @return 获取luckycolor的结果
     */
    public String getLuckyColor() {
        return luckyColor;
    }

    /**
     * 获取运势描述
     *
     * @return 获取description的结果
     */
    public String getDescription() {
        return description;
    }

    /**
      * 转换为 映射 用于 JSON 序列化
     *
     * @return Map 表示
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sign", sign);
        map.put("type", type);
        map.put("overall", overall);
        map.put("love", love);
        map.put("career", career);
        map.put("wealth", wealth);
        map.put("health", health);
        map.put("luckyNumber", luckyNumber);
        map.put("luckyColor", luckyColor);
        map.put("description", description);
        return map;
    }
}