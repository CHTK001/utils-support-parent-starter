package com.chua.common.support.lang.document;

import lombok.Builder;
import org.jspecify.annotations.NullUnmarked;

/**
 * 表关系（外键）数据。
 *
 * <p>描述一张表到另一张表的引用关系，即外键约束。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
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
    public String getFkName() { return fkName; }
    public String getFkTableName() { return fkTableName; }
    public String getFkColumnName() { return fkColumnName; }
    public String getPkTableName() { return pkTableName; }
    public String getPkColumnName() { return pkColumnName; }
    public String getUpdateRule() { return updateRule; }
    public String getDeleteRule() { return deleteRule; }
}
