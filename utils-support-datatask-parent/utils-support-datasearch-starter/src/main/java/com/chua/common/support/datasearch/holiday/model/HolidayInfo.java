package com.chua.common.support.datasearch.holiday.model;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 法定节假日信息模型。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HolidayInfo {

    /**
     * 日期
     */
    private final LocalDate date;

    /**
     * 节假日名称（如：元旦、春节、劳动节；调休上班标注为「XX调休上班」）
     */
    private final String name;

    /**
      * 类型：假日=法定节假日，work=调休补班（占用休息日上班）
     */
    private final String type;

    /**
      * 是否放假（类型=假日 为 true）
     */
    private final boolean offDay;

    /**
      * 创建 假日信息 实例
     * @param date 日期
     * @param name 字符串
     * @param name 字符串
     * @param offDay 布尔值
     * @param name 名称
     * @param type 类型
     * @param offDay offday
     */
    public HolidayInfo(LocalDate date, String name, String type, boolean offDay) {
        this.date = date;
        this.name = name;
        this.type = type;
        this.offDay = offDay;
    }

    /**
     * 获取日期
     *
     * @return 获取日期的结果
     */
    public LocalDate getDate() {
        return date;
    }

    /**
     * 获取名称
     *
     * @return 获取名称的结果
     */
    public String getName() {
        return name;
    }

    /**
     * 获取类型
     *
     * @return 获取类型的结果
     */
    public String getType() {
        return type;
    }

    /**
     * 是否offday
     *
     * @return 是否offday的结果
     */
    public boolean isOffDay() {
        return offDay;
    }

    /**
      * 转换为 映射 用于 JSON 序列化
     *
     * @return Map 表示
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("date", date.toString());
        map.put("name", name);
        map.put("type", type);
        map.put("offDay", offDay);
        return map;
    }
}
