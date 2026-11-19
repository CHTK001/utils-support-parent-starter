package com.chua.parquet.support.migration;

import java.util.List;
import java.util.Map;

/**
 * 数据迁移插件契约：在两类存储之间按批搬迁数据。
 *
 * <p>{@link MigrationService} 驱动标准流程：</p>
 * <ol>
 *   <li>按请求解析源端 / 目标端句柄（引擎型存储为 {@link com.chua.common.support.lang.datasource.engine.Engine} 实例，
 *       非引擎型存储（如 JdbcTemplate 支撑的 RDBMS）传入 {@code null}）；</li>
 *   <li>循环 {@code read -> write}，直到源端返回空批；</li>
 *   <li>任一批失败时回调 {@link AbstractPluginMigrator#rollback(MigrateRequest)} 回滚目标端已写入数据；</li>
 *   <li>销毁阶段统一调用 {@link #close()} 释放插件资源。</li>
 * </ol>
 *
 * <p>行数据统一使用 {@code Map<String, Object>}（列名 -&gt; 值）承载，与具体存储解耦；
 * 列的顺序以首行为准，插件内部应保持同一迁移过程中的列序一致。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public interface DataMigrator extends AutoCloseable {

    /**
     * 从源端读取一批数据。
     *
     * @param source    源端句柄；引擎型存储为 {@code Engine} 实例，非引擎型存储为 {@code null}
     * @param request   迁移请求
     * @param offset    已读取的行数（当前批的起始偏移）
     * @param batchSize 本批最多读取的行数，恒大于 0
     * @return 本批数据；无更多数据时返回空集合（不得返回 {@code null}）
     */
    List<Map<String, Object>> read(Object source, MigrateRequest request, long offset, int batchSize);

    /**
     * 将一批数据写入目标端。
     *
     * @param target  目标端句柄；引擎型存储为 {@code Engine} 实例，非引擎型存储为 {@code null}
     * @param request 迁移请求
     * @param batch   本批数据，非空
     * @return 实际写入的行数
     */
    long write(Object target, MigrateRequest request, List<Map<String, Object>> batch);

    /**
     * 插件名称，见 {@link MigrationPlugin#name()}。
     *
     * @return 插件名称，不得为空
     */
    String getName();

    /**
     * 插件执行顺序，数值越小越靠前，见 {@link MigrationPlugin#order()}。
     *
     * @return 执行顺序
     */
    int getOrder();

    /**
     * 释放插件持有的资源。由 {@link MigrationService} 在销毁阶段（{@code @PreDestroy}）统一调用，
     * 实现要求幂等、可重入；插件内部不应依赖 close 前必须存在迁移会话。
     */
    @Override
    void close();
}
