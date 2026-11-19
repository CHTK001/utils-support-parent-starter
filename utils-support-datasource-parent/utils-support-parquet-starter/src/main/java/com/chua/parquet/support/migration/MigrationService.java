package com.chua.parquet.support.migration;

import com.chua.common.support.lang.datasource.engine.Engine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据迁移服务：在两类存储之间按批搬迁数据，是 {@link DataMigrator} 插件的统一入口。
 *
 * <p>流程：按请求选择插件 → 解析源/目标句柄 → {@code begin} → 循环
 * {@code read -> write}（直到源端返回空批）→ 成功返回 {@link MigrateResult#success}；
 * 任一批失败则回滚目标端已写入数据并返回 {@link MigrateResult#failure}；
 * 结束后无论成败都执行 {@code cleanup} 清理会话。</p>
 *
 * <p>插件选择规则：优先使用 {@link MigrateRequest#getMigratorName()} 精确匹配；
 * 未指定时按 {@code sourceType + "->" + targetType} 推导（内置插件名为
 * {@code parquet->rdbms}、{@code rdbms->parquet}）。非 RDBMS 侧的存储句柄从容器中的
 * {@link Engine} Bean 解析（Bean 名缺省取存储类型名，如 {@code parquet}）；
 * RDBMS 侧由插件内 {@code JdbcTemplate} 支撑，句柄传 {@code null}。</p>
 *
 * <p>依赖注入采用 JSR-330 标准注解 {@code @Inject} 构造注入。注：Spring Framework 7
 * 起不再运行期处理 {@code javax.inject}，实际装配由
 * {@code MigrationAutoConfiguration} 的 {@code @Bean} 方法参数解析完成。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class MigrationService {

    /**
     * 容器中的存储引擎：Bean 名 -> Engine
     */
    private final Map<String, Engine> engines;

    /**
     * 已注册迁移插件，按 order 升序排列
     */
    private final List<DataMigrator> migrators;

    /**
     * 构造迁移服务。
     *
     * @param engines   容器中的存储引擎（Bean 名 -> Engine），可为空 Map
     * @param migrators 容器中的迁移插件，可为空 List
     */
    @Inject
    public MigrationService(Map<String, Engine> engines, List<DataMigrator> migrators) {
        this.engines = engines == null ? Collections.emptyMap() : new LinkedHashMap<>(engines);
        this.migrators = migrators == null ? new ArrayList<>() : new ArrayList<>(migrators);
        this.migrators.sort(Comparator.comparingInt(DataMigrator::getOrder));
    }

    /**
     * 启动校验：插件名称必须非空且全局唯一，并打印已注册插件清单。
     *
     * @throws IllegalStateException 插件名称为空或重复时抛出
     */
    @PostConstruct
    public void init() {
        Set<String> seen = new java.util.HashSet<>();
        for (DataMigrator migrator : migrators) {
            String name = migrator.getName();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("迁移插件名称不能为空: " + migrator.getClass().getName());
            }
            if (!seen.add(name)) {
                throw new IllegalStateException("迁移插件名称重复: " + name);
            }
        }
        log.info("[migration] 已注册 {} 个数据迁移插件: {}", migrators.size(), describe(migrators));
    }

    /**
     * 执行一次数据迁移。
     *
     * @param request 迁移请求
     * @return 迁移结果；插件缺失或迁移失败时返回失败结果（不抛异常）
     */
    public MigrateResult migrate(MigrateRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        String pluginKey = request.getMigratorName() == null || request.getMigratorName().isBlank()
                ? request.pluginKey()
                : request.getMigratorName().trim();
        DataMigrator plugin = migrators.stream()
                .filter(migrator -> pluginKey.equals(migrator.getName()))
                .findFirst()
                .orElse(null);
        if (plugin == null) {
            log.error("[migration] 未找到迁移插件: {}，已注册插件: {}", pluginKey, describe(migrators));
            return MigrateResult.failure(pluginKey, request, "未找到迁移插件: " + pluginKey);
        }
        long start = System.currentTimeMillis();
        long totalRead = 0L;
        long totalWritten = 0L;
        int batchCount = 0;
        boolean began = false;
        try {
            Object source = "rdbms".equalsIgnoreCase(request.getSourceType())
                    ? null
                    : resolveEngine(request.effectiveSourceEngine(), "源端");
            Object target = "rdbms".equalsIgnoreCase(request.getTargetType())
                    ? null
                    : resolveEngine(request.effectiveTargetEngine(), "目标端");
            if (plugin instanceof AbstractPluginMigrator abstractPlugin) {
                abstractPlugin.begin(request);
                began = true;
            }
            while (true) {
                List<Map<String, Object>> batch = plugin.read(source, request, totalRead, request.getBatchSize());
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                totalWritten += plugin.write(target, request, batch);
                totalRead += batch.size();
                batchCount++;
                if (batch.size() < request.getBatchSize()) {
                    // 不足一批即为末批，避免多一次空读
                    break;
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            log.info("[migration] 迁移完成: 插件 {}，{} -> {}，读取 {} 行，写入 {} 行，{} 批，耗时 {} ms",
                    plugin.getName(), request.getSourceTable(), request.getTargetTable(),
                    totalRead, totalWritten, batchCount, elapsed);
            return MigrateResult.success(plugin.getName(), request, totalRead, totalWritten, batchCount, elapsed);
        } catch (Exception e) {
            log.error("[migration] 迁移失败: {}，准备回滚目标端数据", request, e);
            String message = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
            if (began && plugin instanceof AbstractPluginMigrator abstractPlugin) {
                try {
                    abstractPlugin.rollback(request);
                    message = message + "（已回滚目标端数据）";
                } catch (Exception rollbackError) {
                    log.error("[migration] 回滚失败: {}", request, rollbackError);
                    message = message + "；回滚失败: "
                            + (rollbackError.getMessage() == null ? rollbackError.getClass().getName() : rollbackError.getMessage());
                }
            }
            return MigrateResult.failure(plugin.getName(), request, message);
        } finally {
            if (began && plugin instanceof AbstractPluginMigrator abstractPlugin) {
                abstractPlugin.cleanup();
            }
        }
    }

    /**
     * 释放全部插件资源。
     */
    @PreDestroy
    public void destroy() {
        for (DataMigrator migrator : migrators) {
            try {
                migrator.close();
            } catch (Exception e) {
                log.error("[migration] 插件 {} 关闭失败", migrator.getName(), e);
            }
        }
    }

    /**
     * 按存储类型名解析容器中的 Engine Bean。
     *
     * @param beanName Engine Bean 名称
     * @param side     端别描述（源端/目标端），用于异常信息
     * @return Engine 实例
     * @throws IllegalStateException 未找到对应 Engine Bean 时抛出
     */
    private Engine resolveEngine(String beanName, String side) {
        Engine engine = engines.get(beanName);
        if (engine == null) {
            throw new IllegalStateException(side + " Engine 未找到: " + beanName
                    + "，已注册 Engine: " + engines.keySet());
        }
        return engine;
    }

    /**
     * 插件清单描述，用于日志输出。
     *
     * @param list 插件列表
     * @return 形如 {@code name(order=n)} 的逗号串
     */
    private static String describe(List<DataMigrator> list) {
        return list.stream()
                .map(migrator -> migrator.getName() + "(order=" + migrator.getOrder() + ")")
                .collect(Collectors.joining(", "));
    }
}
