package com.chua.common.support.lang.datasource.schema;

import lombok.Data;
import lombok.experimental.Accessors;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构驱动的执行上下文。
 *
 * <p>承载一次结构初始化所需的全部输入：目标数据源、数据库方言协议、
 * 实体类列表与日志开关。仅依赖 JDK 类型（{@code javax.sql.DataSource}），
 * 不携带任何 spring 类型，供各层 {@link SchemaDriver} 实现中性消费。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Data
@Accessors(chain = true)
public class SchemaDriverContext {

    /**
     * 目标数据源（必填）。
     */
    private DataSource dataSource;

    /**
     * 数据库方言协议名（如 {@code mysql}、{@code h2}、{@code postgresql}）。
     * <p>与 {@code Dialect} / {@code DdlProvider} SPI 的注册别名一致；
     * 为空时实现方可使用内置通用语法兜底。</p>
     */
    private String protocol;

    /**
     * 参与结构初始化的实体类列表（实体驱动模式使用）。
     */
    private List<Class<?>> entityClasses = new ArrayList<>();

    /**
     * 是否输出详细日志。
     */
    private boolean verbose = true;
}
