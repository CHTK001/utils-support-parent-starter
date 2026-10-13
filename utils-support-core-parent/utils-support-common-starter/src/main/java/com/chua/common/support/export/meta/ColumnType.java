package com.chua.common.support.export.meta;

/**
 * 列数据类型。
 *
 * <p>决定单元格写入的物理形态（数值 / 字符串 / 日期 / 布尔）与默认格式化方式。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum ColumnType {

    /**
     * 未声明，由属性类型推断。
     */
    AUTO,
    /**
     * 文本。
     */
    TEXT,
    /**
     * 整数。
     */
    INTEGER,
    /**
     * 小数。
     */
    DECIMAL,
    /**
     * 百分比（写入小数，显示为百分比）。
     */
    PERCENT,
    /**
     * 金额（保留两位小数）。
     */
    AMOUNT,
    /**
     * 日期。
     */
    DATE,
    /**
     * 时间。
     */
    TIME,
    /**
     * 日期时间。
     */
    DATETIME,
    /**
     * 布尔。
     */
    BOOLEAN,
    /**
     * 枚举 / 字典（按 {@code enumNames} 映射为文本）。
     */
    ENUM,
    /**
     * JSON 文本。
     */
    JSON;

    /**
     * 判断是否为数值语义（可用于数字右对齐与公式处理）。
     *
     * @return 是否为数值语义
     */
    public boolean isNumeric() {
        return this == INTEGER || this == DECIMAL || this == PERCENT || this == AMOUNT;
    }

    /**
     * 判断是否为日期时间语义。
     *
     * @return 是否为日期时间语义
     */
    public boolean isTemporal() {
        return this == DATE || this == TIME || this == DATETIME;
    }

    /**
     * 按名称解析，忽略大小写与连字符；解析失败返回 {@link #AUTO}。
     *
     * @param name 类型名，可为 {@code null}
     * @return 解析结果
     */
    public static ColumnType of(String name) {
        if (name == null || name.isBlank()) {
            return AUTO;
        }
        String key = name.trim().replace("-", "").replace("_", "");
        for (ColumnType type : values()) {
            if (type.name().equalsIgnoreCase(key)) {
                return type;
            }
        }
        return AUTO;
    }
}