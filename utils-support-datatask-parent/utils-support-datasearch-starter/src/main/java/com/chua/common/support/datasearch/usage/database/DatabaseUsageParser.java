package com.chua.common.support.datasearch.usage.database;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 数据库用量解析器 — 通过 {@link UsageRowProvider} SPI 从关系型数据库读取 AI 用量数据。
 *
 * <p>本类只负责「数据行 → {@link AiUsage}」的映射与编排；表名、列名与查询 SQL
 * 由持有该存储的业务模块通过 {@link UsageRowProvider} 实现提供（如 AI 调用用量表），
 * utils 层不感知任何业务表结构。</p>
 *
 * <p>本类刻意不放在 {@code usage.spi} 包下：SPI 的同包扫描会把该包及其子包内的所有实现
 * 登记为扩展点，而本类的构造需要注入 Spring 容器中的 {@link UsageRowProvider}，
 * 反射实例化必然失败并从 {@code list()} 中静默消失；更重要的是它读取的通常是本地工具
 * 用量同步的落库目标，一旦进入解析器广播，库里的记录会被当作外部数据源再读回一遍。
 * 需要它的调用方应从 Spring 容器取得，不要指望 {@code ServiceProvider} 能列出。</p>
 *
 * <p>开关：{@code ai.usage.database.enabled=true} 时生效；容器中不存在
 * {@link UsageRowProvider} 实现时告警并返回空流。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.usage.database", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DatabaseUsageParser extends BaseUsageParser {

    /**
     * Parser 名称标识
     */
    public static final String NAME = "database";

    /**
     * 用量行查询 SPI（由持有用量存储的模块提供实现）
     */
    private final ObjectProvider<UsageRowProvider> rowProvider;

    /**
     * 构造函数。
     *
     * @param rowProvider 用量行查询 SPI，允许容器中暂无实现
     */
    public DatabaseUsageParser(ObjectProvider<UsageRowProvider> rowProvider) {
        this.rowProvider = rowProvider;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Flux<AiUsage> streamAll() {
        UsageRowProvider provider = rowProvider.getIfAvailable();
        if (provider == null) {
            log.warn("[DatabaseUsageParser] 容器中不存在 UsageRowProvider 实现，跳过数据库用量读取");
            return Flux.empty();
        }
        try {
            List<Map<String, Object>> rows = provider.queryRows();
            List<AiUsage> result = Wraps.list();
            for (Map<String, Object> row : rows) {
                try {
                    AiUsage usage = mapToAiUsage(row);
                    if (usage != null) {
                        result.add(usage);
                    }
                } catch (Exception e) {
                    log.warn("[DatabaseUsageParser] 单条记录映射失败: {}", e.getMessage(), e);
                }
            }
            log.debug("[DatabaseUsageParser] 读取 {} 条记录", result.size());
            return Flux.fromIterable(result);
        } catch (Exception e) {
            log.error("[DatabaseUsageParser] 查询失败: {}", e.getMessage(), e);
            return Flux.empty();
        }
    }

    /**
     * 将数据库行映射为 AIusage 对象。
     * @param row row
     * @return 映射转为AIusage的结果
     */
    private AiUsage mapToAiUsage(Map<String, Object> row) {
        AiUsage.AiUsageBuilder builder = AiUsage.builder();

        // 映射字段
        String provider = asStr(row.get(UsageRowProvider.PROVIDER));
        builder.provider(provider);

        String model = asStr(row.get(UsageRowProvider.MODEL));
        builder.model(model);

        Integer inputTokens = asInt(row.get(UsageRowProvider.INPUT_TOKENS));
        builder.inputTokens(inputTokens);

        Integer outputTokens = asInt(row.get(UsageRowProvider.OUTPUT_TOKENS));
        builder.outputTokens(outputTokens);

        Integer totalTokens = asInt(row.get(UsageRowProvider.TOTAL_TOKENS));
        builder.totalTokens(totalTokens);

        // 时间字段
        Object startTime = row.get(UsageRowProvider.START_TIME);
        if (startTime instanceof java.sql.Timestamp ts) {
            builder.startTime(ts.getTime());
        } else if (startTime instanceof java.util.Date d) {
            builder.startTime(d.getTime());
        } else if (startTime instanceof Number n) {
            builder.startTime(n.longValue());
        }

        // 费用字段
        Double cost = asDouble(row.get(UsageRowProvider.COST));
        if (cost != null && cost > 0) {
            builder.totalCost(java.math.BigDecimal.valueOf(cost));
        }

        Double inputCost = asDouble(row.get(UsageRowProvider.INPUT_COST));
        if (inputCost != null && inputCost > 0) {
            builder.inputCost(java.math.BigDecimal.valueOf(inputCost));
        }

        Double outputCost = asDouble(row.get(UsageRowProvider.OUTPUT_COST));
        if (outputCost != null && outputCost > 0) {
            builder.outputCost(java.math.BigDecimal.valueOf(outputCost));
        }

        // 默认货币
        builder.currency("CNY");

        return builder.build();
    }
}
