package com.chua.parquet.support.migration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 内置迁移插件装配：以 {@code @Bean} 方法参数解析注入 {@code JdbcTemplate}。
 *
 * <p>Spring Framework 7 起不再运行期处理 {@code javax.inject}，插件类上的
 * {@code @Inject} 仅作为 JSR-330 标准元数据保留；真实装配在本类完成——
 * {@code ParquetToRdbmsMigrator} 走构造注入，{@code RdbmsToParquetMigrator}
 * 走字段注入（{@code @Bean} 方法调用其 setter）。</p>
 *
 * <p>宿主未配置 {@code JdbcTemplate} 时不注册内置 rdbms 侧插件，
 * 但不影响 {@link MigrationService} 与宿主自定义插件的使用。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Configuration(proxyBeanMethods = false)
public class AdditionalPluginsConfiguration {

    /**
     * 注册 Parquet -&gt; RDBMS 迁移插件。
     *
     * @param jdbcTemplate 数据库操作模板
     * @return 迁移插件
     */
    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean(name = "parquetToRdbmsMigrator")
    public ParquetToRdbmsMigrator parquetToRdbmsMigrator(JdbcTemplate jdbcTemplate) {
        return new ParquetToRdbmsMigrator(jdbcTemplate);
    }

    /**
     * 注册 RDBMS -&gt; Parquet 迁移插件。
     *
     * @param jdbcTemplate 数据库操作模板
     * @return 迁移插件
     */
    @Bean
    @ConditionalOnBean(JdbcTemplate.class)
    @ConditionalOnMissingBean(name = "rdbmsToParquetMigrator")
    public RdbmsToParquetMigrator rdbmsToParquetMigrator(JdbcTemplate jdbcTemplate) {
        RdbmsToParquetMigrator migrator = new RdbmsToParquetMigrator();
        migrator.setJdbcTemplate(jdbcTemplate);
        return migrator;
    }
}
