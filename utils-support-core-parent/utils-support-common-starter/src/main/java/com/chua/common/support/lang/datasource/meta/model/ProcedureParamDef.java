package com.chua.common.support.lang.datasource.meta.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NullUnmarked;

/**
 * 存储过程参数定义，描述存储过程或函数的一个参数。
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcedureParamDef {

    /**
     * 参数名
     */
    private String name;

    /**
     * 参数类型（如 VARCHAR、INT、CURSOR 等）
     */
    private String type;

    /**
     * 参数方向（IN / OUT / INOUT）
     */
    private String direction;

    /**
     * 参数位置（从 1 开始）
     */
    private Integer position;
}
