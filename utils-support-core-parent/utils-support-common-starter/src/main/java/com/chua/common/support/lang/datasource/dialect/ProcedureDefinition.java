package com.chua.common.support.lang.datasource.dialect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 存储过程定义，描述数据库中的一个存储过程或函数。
 * <p>
 * 由方言的 {@link Dialect#getProcedures(java.sql.Connection, String)} 系列方法返回，
 * 字段包含名称、参数列表、返回值类型以及过程体内容。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcedureDefinition {

    /**
     * 存储过程名
     */
    private String name;

    /**
     * catalog 名称
     */
    private String catalog;

    /**
     * schema 名称
     */
    private String schema;

    /**
     * 参数列表
     */
    private List<ProcedureParameter> params;

    /**
     * 返回值类型（函数使用，存储过程通常为 null）
     */
    private String returnType;

    /**
     * 过程体内容
     */
    private String body;

    /**
     * 语言（SQL / JAVA / PLPGSQL 等）
     */
    private String language;

    /**
     * 安全类型（DEFINER / INVOKER）
     */
    private String securityType;

    /**
     * 注释
     */
    private String comment;

    /**
     * 状态（VALID / INVALID，仅 Oracle 等支持）
     */
    private String status;
}
