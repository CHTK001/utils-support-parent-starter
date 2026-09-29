package com.chua.common.support.lang.document;

import lombok.Builder;

/**
 * 列结构数据。
 * <p>
 * 描述数据库表中的单个列的结构信息，
 * 包括列名、数据类型、大小、精度、可空性、主键标识、默认值及备注。
 * </p>
 *
 * <p>取值来源为 JDBC {@link java.sql.DatabaseMetaData#getColumns} 读取的
 * {@code COLUMNS} 结果集（列序号取 {@code ORDINAL_POSITION}、类型名取
 * {@code TYPE_NAME}、大小取 {@code COLUMN_SIZE}、小数位取 {@code DECIMAL_DIGITS}、
 * 可空标记取 {@code NULLABLE}、默认值取 {@code COLUMN_DEF}、备注取
 * {@code REMARKS}），随后再遍历 {@code getPrimaryKeys} 的结果回填
 * {@link #primaryKey}。因此各组件反映的是<b>数据库当前实际结构</b>，
 * 而非建表脚本中的书写形式，不同数据库的方言差异会原样体现在
 * {@link #typeName} 与 {@link #defaultValue} 上。</p>
 *
 * <p>可空性：{@code String} 组件均允许为 {@code null}，取值来源的
 * {@code wasNull()} 未做归一；渲染方（HTML / Word / Markdown 模板）需自行
 * 判空。{@link #decimalDigits} 是唯一被显式归一的组件：来源为
 * {@code DECIMAL_DIGITS} 且经 {@code wasNull()} 或非负校验后，
 * 非数值列（DECIMAL_DIGITS 为 {@code null}）归一为 {@code null}，
 * 不可为负数。</p>
 *
 * @param ordinalPosition 列 序号，取自 {@code ORDINAL_POSITION}，
 *                        从 1 开始计数，代表该列在建表语句中的声明顺序，
 *                        也是渲染表格行号与排序的基准。原始 {@code int}，
 *                        语义上必须大于等于 1
 * @param columnName 列 名，取自 {@code COLUMN_NAME}，即数据库中的实际列标识
 *                   （大小写与保留字转义方式由数据库方言决定，例如部分数据库以双引号
 *                   包裹保留字）。用于匹配主键列名，必须与
 *                   {@code getPrimaryKeys} 返回的 {@code COLUMN_NAME} 完全一致；
 *                   允许为 {@code null}（未取到时），但此时主键回填会失效
 * @param typeName 数据 类型 名称，取自 {@code TYPE_NAME}，为数据库驱动返回的
 *                 类型名而非标准 SQL 类型，常见形态如 {@code VARCHAR} /
 *                 {@code INTEGER} / {@code TEXT} / {@code DECIMAL}。
 *                 渲染方按字符串包含关系（如包含 {@code DECIMAL} /
 *                 {@code NUMERIC} / {@code NUMBER} 时拼接精度）判断，
 *                 因此不要假定其为枚举或受控词表；允许为 {@code null}
 * @param columnSize 列 大小，取自 {@code COLUMN_SIZE}，单位随 {@link #typeName}
 *                    而变：字符类型为字符长度，二进制类型为字节数，整数类型为
 *                    整型位数。原始 {@code int}；驱动未提供或类型无长度概念时为 0，
 *                    渲染方以「大于 0」为条件决定是否拼接 {@code (size)}
 * @param decimalDigits 小数 位数，取自 {@code DECIMAL_DIGITS}，仅对数值类型有意义，
 *                      表示小数点后的位数。允许为 {@code null}，表示该列非数值类型
 *                      或驱动未提供（解析器已把来源的 {@code null} 与负值统一归一为
 *                      {@code null}）；渲染方需判空且通常还要求大于 0 才拼接
 * @param nullable 是否 允许 为空，由来源 {@code NULLABLE} 与
 *                 {@link java.sql.DatabaseMetaData#columnNullable} 比较得出，
 *                 两者相等时为 {@code true}。原始 {@code boolean}，
 *                 未取到时按「不可空」处理
 * @param primaryKey 是否 为主键列，构造时统一先置为 {@code false}，
 *                   再由解析器遍历 {@code getPrimaryKeys} 结果按列名匹配回填为
 *                   {@code true}。原始 {@code boolean}，复合主键的每一列均为
 *                   {@code true}；渲染方据此加粗或加钥匙图标
 * @param defaultValue 默认 值，取自 {@code COLUMN_DEF}，为数据库返回的默认表达式
 *                     <b>原文</b>而非求值结果，形如 {@code CURRENT_TIMESTAMP}
 *                     或带引号的字面量。允许为 {@code null}（该列无默认值时）；
 *                     渲染方需按「无默认值」展示，不要把 {@code null} 与空串混淆
 * @param remark 列 备注 / 注释，取自 {@code REMARKS}，即 {@code COMMENT} 声明的
 *                 说明文本。允许为 {@code null}，部分解析路径已把 {@code null}
 *                 归一为空串，因此消费方需同时兼容两种「无备注」表示
 * @author CH
 * @since 4.0.0.41
 */
@Builder
public record ColumnData(
        /**
         * 列序号（从 1 开始）
         */
        int ordinalPosition,
        /**
         * 列名
         */
        String columnName,
        /**
         * 数据类型名称，例如 VARCHAR、INTEGER、TEXT
         */
        String typeName,
        /**
         * 列大小/长度
         */
        int columnSize,
        /**
         * 小数位数（仅数值类型）
         */
        Integer decimalDigits,
        /**
         * 是否允许为空
         */
        boolean nullable,
        /**
         * 是否为主键
         */
        boolean primaryKey,
        /**
         * 默认值
         */
        String defaultValue,
        /**
         * 列备注/注释
         */
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
