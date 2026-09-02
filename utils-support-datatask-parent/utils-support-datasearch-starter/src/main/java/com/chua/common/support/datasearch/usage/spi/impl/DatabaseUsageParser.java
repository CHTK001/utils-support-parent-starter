package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库用量解析器 — 从关系型数据库读取 AI 用量数据。
 *
 * <p>支持通过 Spring Boot 配置自定义查询和字段映射，默认适配 sys_ai_usage 表结构。</p>
 *
 * <p>配置示例：
 * <pre>
 * ai.usage.database:
 *   enabled: true
 *   query-sql: "SELECT provider, model, input_tokens, output_tokens, total_tokens, cost, start_time FROM ai_usage"
 *   field-mapping:
 *     provider: provider
 *     model: model
 *     input-tokens: input_tokens
 *     output-tokens: output_tokens
 *     total-tokens: total_tokens
 *     start-time: start_time
 *     cost: cost
 * </pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
@Component
@ConditionalOnClass(DataSource.class)
@ConditionalOnProperty(prefix = "ai.usage.database", name = "enabled", havingValue = "true", matchIfMissing = false)
public class DatabaseUsageParser extends BaseUsageParser {

    /**
     * Parser 名称标识
     */
    public static final String NAME = "database";

    /**
     * 默认查询 SQL（适配 sys_ai_usage 表结构）
     */
    private static final String DEFAULT_QUERY_SQL =
            "SELECT sys_ai_usage_provider, sys_ai_usage_model," +
            "       sys_ai_usage_prompt_tokens, sys_ai_usage_completion_tokens," +
            "       sys_ai_usage_total_tokens, sys_ai_usage_total_cost," +
            "       sys_ai_usage_input_cost, sys_ai_usage_output_cost," +
            "       sys_ai_usage_call_time" +
            "  FROM sys_ai_usage" +
            " WHERE sys_ai_usage_total_tokens IS NOT NULL" +
            " ORDER BY sys_ai_usage_call_time DESC";

    /**
     * 默认字段映射
     */
    private static final Map<String, String> DEFAULT_FIELD_MAPPING = new LinkedHashMap<>();
    static {
        DEFAULT_FIELD_MAPPING.put("provider", "sys_ai_usage_provider");
        DEFAULT_FIELD_MAPPING.put("model", "sys_ai_usage_model");
        DEFAULT_FIELD_MAPPING.put("input-tokens", "sys_ai_usage_prompt_tokens");
        DEFAULT_FIELD_MAPPING.put("output-tokens", "sys_ai_usage_completion_tokens");
        DEFAULT_FIELD_MAPPING.put("total-tokens", "sys_ai_usage_total_tokens");
        DEFAULT_FIELD_MAPPING.put("start-time", "sys_ai_usage_call_time");
        DEFAULT_FIELD_MAPPING.put("cost", "sys_ai_usage_total_cost");
        DEFAULT_FIELD_MAPPING.put("input-cost", "sys_ai_usage_input_cost");
        DEFAULT_FIELD_MAPPING.put("output-cost", "sys_ai_usage_output_cost");
    }

    /**
     * JDBC 模板
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 查询 SQL
     */
    private final String querySql;

    /**
     * 字段映射
     */
    private final Map<String, String> fieldMapping;

    /**
     * 构造函数。
     *
     * @param dataSource 数据源
     * @param querySql   自定义查询 SQL
     * @param fieldMapping 字段映射配置
     */
    public DatabaseUsageParser(
            DataSource dataSource,
            @org.springframework.beans.factory.annotation.Value("${ai.usage.database.query-sql:}") String querySql,
            @org.springframework.beans.factory.annotation.Value("#{${ai.usage.database.field-mapping:{}}}") Map<String, String> fieldMapping) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.querySql = querySql != null && !querySql.isBlank() ? querySql : DEFAULT_QUERY_SQL;
        this.fieldMapping = fieldMapping != null && !fieldMapping.isEmpty() ? fieldMapping : DEFAULT_FIELD_MAPPING;
        log.info("[DatabaseUsageParser] 初始化完成，使用 SQL: {}", this.querySql);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Flux<AiUsage> streamAll() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(querySql);
            List<AiUsage> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                try {
                    AiUsage usage = mapToAiUsage(row);
                    if (usage != null) {
                        result.add(usage);
                    }
                } catch (Exception e) {
                    log.warn("[DatabaseUsageParser] 单条记录映射失败: {}", e.getMessage());
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
     * 将数据库行映射为 AiUsage 对象。
     */
    private AiUsage mapToAiUsage(Map<String, Object> row) {
        AiUsage.AiUsageBuilder builder = AiUsage.builder();

        // 映射字段
        String provider = asStr(row.get(getMappedColumn("provider")));
        builder.provider(provider);

        String model = asStr(row.get(getMappedColumn("model")));
        builder.model(model);

        Integer inputTokens = asInt(row.get(getMappedColumn("input-tokens")));
        builder.inputTokens(inputTokens);

        Integer outputTokens = asInt(row.get(getMappedColumn("output-tokens")));
        builder.outputTokens(outputTokens);

        Integer totalTokens = asInt(row.get(getMappedColumn("total-tokens")));
        builder.totalTokens(totalTokens);

        // 时间字段
        Object startTime = row.get(getMappedColumn("start-time"));
        if (startTime instanceof java.sql.Timestamp ts) {
            builder.startTime(ts.getTime());
        } else if (startTime instanceof java.util.Date d) {
            builder.startTime(d.getTime());
        } else if (startTime instanceof Number n) {
            builder.startTime(n.longValue());
        }

        // 费用字段
        Double cost = asDouble(row.get(getMappedColumn("cost")));
        if (cost != null && cost > 0) {
            builder.totalCost(java.math.BigDecimal.valueOf(cost));
        }

        Double inputCost = asDouble(row.get(getMappedColumn("input-cost")));
        if (inputCost != null && inputCost > 0) {
            builder.inputCost(java.math.BigDecimal.valueOf(inputCost));
        }

        Double outputCost = asDouble(row.get(getMappedColumn("output-cost")));
        if (outputCost != null && outputCost > 0) {
            builder.outputCost(java.math.BigDecimal.valueOf(outputCost));
        }

        // 默认货币
        builder.currency("CNY");

        return builder.build();
    }

    /**
     * 获取映射后的列名。
     */
    private String getMappedColumn(String field) {
        return fieldMapping.getOrDefault(field, field);
    }
}
