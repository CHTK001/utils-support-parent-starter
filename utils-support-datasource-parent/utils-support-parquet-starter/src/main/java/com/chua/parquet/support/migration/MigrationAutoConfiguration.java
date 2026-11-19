package com.chua.parquet.support.migration;

import com.chua.common.support.lang.datasource.engine.Engine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

/**
 * 数据迁移能力自动装配：注册 {@link MigrationService} 并通过
 * {@link AdditionalPluginsConfiguration} 收集内置迁移插件与容器中的自定义插件。
 *
 * <p>内置 rdbms 侧插件依赖容器中的 {@code JdbcTemplate} Bean：宿主存在
 * {@code JdbcTemplate}（通常由 {@code JdbcTemplateAutoConfiguration} 或业务方注册）时
 * 自动注册 {@code parquet->rdbms}、{@code rdbms->parquet} 两个内置插件；
 * 宿主也可自行注册 {@link DataMigrator} Bean 扩展迁移方向（插件名约定
 * {@code 源类型->目标类型}），或直接声明 {@link MigrationService} Bean 覆盖默认实现。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
@Import(AdditionalPluginsConfiguration.class)
public class MigrationAutoConfiguration {

    /**
     * 注册迁移服务：注入容器中全部 Engine Bean（按名索引）与全部 DataMigrator 插件。
     *
     * @param engines   容器中的 Engine Bean（Bean 名 -> 实例），无 Engine 时为空 Map
     * @param migrators 容器中的迁移插件，无插件时为空 List
     * @return 迁移服务
     */
    @Bean
    @ConditionalOnMissingBean
    public MigrationService migrationService(Map<String, Engine> engines, List<DataMigrator> migrators) {
        return new MigrationService(engines, migrators);
    }
}
