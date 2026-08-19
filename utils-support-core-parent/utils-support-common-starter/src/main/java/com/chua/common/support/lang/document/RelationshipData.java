package com.chua.common.support.lang.document;

import lombok.Builder;

/**
 * 表关系（外键）数据。
 *
 * <p>描述一张表到另一张表的引用关系，即外键约束。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Builder
public record RelationshipData(
    /** 外键名（约束名） */
    String fkName,
    /** 源表名（外键所在表） */
    String fkTableName,
    /** 源列名（外键列） */
    String fkColumnName,
    /** 目标表名（被引用表） */
    String pkTableName,
    /** 目标列名（被引用列） */
    String pkColumnName,
    /** 更新规则（CASCADE / SET NULL / NO ACTION / RESTRICT） */
    String updateRule,
    /** 删除规则 */
    String deleteRule
) {
    /** 获取FkName */
    public String getFkName() { return fkName; }
    /** 获取FkTableName */
    public String getFkTableName() { return fkTableName; }
    /** 获取FkColumnName */
    public String getFkColumnName() { return fkColumnName; }
    /** 获取PkTableName */
    public String getPkTableName() { return pkTableName; }
    /** 获取PkColumnName */
    public String getPkColumnName() { return pkColumnName; }
    /** 获取更新Rule */
    public String getUpdateRule() { return updateRule; }
    /** 获取删除Rule */
    public String getDeleteRule() { return deleteRule; }
}
