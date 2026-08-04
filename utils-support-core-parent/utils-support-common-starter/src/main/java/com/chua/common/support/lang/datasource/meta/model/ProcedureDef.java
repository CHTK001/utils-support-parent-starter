package com.chua.common.support.lang.datasource.meta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * 存储过程定义，描述数据库中的一个存储过程或函数。
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcedureDef {

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
    private List<ProcedureParamDef> params;

    /**
     * 返回值类型（函数使用，存储过程通常为 null）
     */
    private String returnType;

    /**
     * 过程体 SQL
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
