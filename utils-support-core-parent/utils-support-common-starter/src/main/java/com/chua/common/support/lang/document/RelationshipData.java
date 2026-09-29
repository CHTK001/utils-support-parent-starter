package com.chua.common.support.lang.document;

import lombok.Builder;

/**
 * 表关系（外键）数据。
 *
 * <p>描述一张表到另一张表的引用关系，即外键约束。</p>
 *
 * <p>本 record 没有紧凑构造器，各组件不做校验，全部为 JDBC 元数据读回的字符串，
 * 允许为 {@code null}（驱动未提供该列时）。取值来源为
 * {@code DatabaseDocumentParser} 调用 {@code DatabaseMetaData#getImportedKeys} /
 * {@code #getExportedKeys} 得到的 {@code ResultSet}，字段名直接对应 JDBC 外键列。</p>
 *
 * @param fkName 外键约束名，取 JDBC 列 {@code FK_NAME}；同一张表内多列参与的复合外键共用一个约束名。
 *               允许为 {@code null}（部分驱动不返回约束名）
 * @param fkTableName 外键所在表（引用方）名，取 JDBC 列 {@code FKTABLE_NAME}。
 *                    对 {@code importedKeys} 即当前表，对 {@code exportedKeys} 即另一张表
 * @param fkColumnName 外键列（引用方列）名，取 JDBC 列 {@code FKCOLUMN_NAME}；
 *                     复合外键时每个列一行，本组件只承载其中一列
 * @param pkTableName 被引用表名，取 JDBC 列 {@code PKTABLE_NAME}，即外键指向的主表
 * @param pkColumnName 被引用列名，取 JDBC 列 {@code PKCOLUMN_NAME}，即外键指向的主表列；
 *                    复合外键时逐列成行
 * @param updateRule 参照完整性动作：被引用行更新时外键列如何联动，取 JDBC 列
 *                   {@code UPDATE_RULE}，由 {@code resolveRule} 把 JDBC 短编码
 *                   {@code DatabaseMetaData.importedKey*} 归一为可读文本，取值集合为
 *                   {@code CASCADE} / {@code SET NULL} / {@code SET DEFAULT} /
 *                   {@code RESTRICT} / {@code NO ACTION} / {@code UNKNOWN}，无单位
 * @param deleteRule 参照完整性动作：被引用行删除时外键列如何联动，取 JDBC 列
 *                   {@code DELETE_RULE}，取值集合与归一规则同 {@link #updateRule}。允许为 {@code null}
 * @author CH
 * @since 4.0.0.42
 */
@Builder
public record RelationshipData(
    /**
     * 外键名（约束名）
     */
    String fkName,
    /**
     * 源表名（外键所在表）
     */
    String fkTableName,
    /**
     * 源列名（外键列）
     */
    String fkColumnName,
    /**
     * 目标表名（被引用表）
     */
    String pkTableName,
    /**
     * 目标列名（被引用列）
     */
    String pkColumnName,
    /**
     * 更新规则（CASCADE / SET NULL / NO ACTION / RESTRICT）
     */
    String updateRule,
    /**
     * 删除规则
     */
    String deleteRule
) {
    /**
     * 获取FkName
     */
    public String getFkName() { return fkName; }
    /**
     * 获取FkTableName
     */
    public String getFkTableName() { return fkTableName; }
    /**
     * 获取FkColumnName
     */
    public String getFkColumnName() { return fkColumnName; }
    /**
     * 获取PkTableName
     */
    public String getPkTableName() { return pkTableName; }
    /**
     * 获取PkColumnName
     */
    public String getPkColumnName() { return pkColumnName; }
    /**
     * 获取更新Rule
     */
    public String getUpdateRule() { return updateRule; }
    /**
     * 获取删除Rule
     */
    public String getDeleteRule() { return deleteRule; }
}
