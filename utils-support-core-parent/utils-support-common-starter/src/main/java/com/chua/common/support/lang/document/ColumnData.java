package com.chua.common.support.lang.document;

import lombok.Builder;

/**
 * 列结构数据。
 * <p>
 * 描述数据库表中的单个列的结构信息，
 * 包括列名、数据类型、大小、精度、可空性、主键标识、默认值及备注。
 * </p>
 *
 * @author CH
 * @since 4.0.0.41
 */
@Builder
public record ColumnData(
        /** 列序号（从 1 开始） */
        int ordinalPosition,
        /** 列名 */
        String columnName,
        /** 数据类型名称，例如 VARCHAR、INTEGER、TEXT */
        String typeName,
        /** 列大小/长度 */
        int columnSize,
        /** 小数位数（仅数值类型） */
        Integer decimalDigits,
        /** 是否允许为空 */
        boolean nullable,
        /** 是否为主键 */
        boolean primaryKey,
        /** 默认值 */
        String defaultValue,
        /** 列备注/注释 */
        String remark
) {

    /**
     * 获取列序号。
     *
     * @return 列序号
     */
    public int getOrdinalPosition() {
        return ordinalPosition;
    }

    /**
     * 获取列名。
     *
     * @return 列名
     */
    public String getColumnName() {
        return columnName;
    }

    /**
     * 获取数据类型名称。
     *
     * @return 类型名称
     */
    public String getTypeName() {
        return typeName;
    }

    /**
     * 获取列大小。
     *
     * @return 列大小
     */
    public int getColumnSize() {
        return columnSize;
    }

    /**
     * 获取小数位数。
     *
     * @return 小数位数
     */
    public Integer getDecimalDigits() {
        return decimalDigits;
    }

    /**
     * 获取是否允许为空。
     *
     * @return 是否允许为空
     */
    public boolean isNullable() {
        return nullable;
    }

    /**
     * 获取是否为主键。
     *
     * @return 是否为主键
     */
    public boolean isPrimaryKey() {
        return primaryKey;
    }

    /**
     * 获取默认值。
     *
     * @return 默认值
     */
    public String getDefaultValue() {
        return defaultValue;
    }

    /**
     * 获取列备注。
     *
     * @return 列备注
     */
    public String getRemark() {
        return remark;
    }
}
