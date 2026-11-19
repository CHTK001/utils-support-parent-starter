package com.chua.parquet.support.migration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注一个数据迁移插件：声明插件的名称（迁移方向）与执行顺序。
 *
 * <p>插件实现类必须直接或间接实现 {@link DataMigrator}；推荐继承
 * {@link AbstractPluginMigrator} 以获得回滚跟踪等公共能力。</p>
 *
 * <p>插件名称约定为「源存储类型-&gt;目标存储类型」，例如 {@code parquet->rdbms}。
 * {@link MigrationService} 优先按请求中的 {@code migratorName} 精确匹配插件，
 * 未显式指定时按 {@code sourceType + "->" + targetType} 推导匹配。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MigrationPlugin {

    /**
     * 插件名称，约定为「源存储类型-&gt;目标存储类型」，如 {@code parquet->rdbms}、{@code rdbms->parquet}。
     *
     * @return 插件名称，不得为空
     */
    String name();

    /**
     * 执行顺序，数值越小越靠前。{@link MigrationService} 启动时会校验插件名称唯一，
     * 顺序仅决定收集与展示次序。
     *
     * @return 执行顺序，默认 0
     */
    int order() default 0;
}
