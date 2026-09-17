package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.sqlite.support.engine.SqliteReactorEngine;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 豆包（火山方舟 Ark）用量解析器。
 *
 * <p>豆包桌面端（{@code AppData\Local\Doubao}）是 CEF 应用，对话内容存于云端，
 * 本地 IndexedDB 仅保存会话索引与 UI 状态，不落 token 明细，因此直接扫描豆包
 * 本地数据不可行。</p>
 *
 * <p>本解析器通过 CC-Switch 的 {@code ~/.cc-switch/cc-switch.db} 读取
 * {@code proxy_request_logs} 表，其中 {@code provider_type} / {@code provider_id}
 * 标记为火山方舟（Ark / Doubao）的代理请求即豆包模型的实际用量记录。</p>
 *
 * <p>无数据库或无匹配 provider 时返回空流，与其余本地 parser 行为一致。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("doubao")
public class DoubaoUsageParser extends BaseUsageParser {

    private static final String PROVIDER_DOU_BAO = "doubao";

    /** 数据库路径（支持 DOUBAO_USAGE_DB 环境变量覆盖，便于测试注入）。 */
    private static final Path DB_PATH = resolveDbPath();

    /**
     * 匹配火山方舟 / 豆包 的 provider 标记。
     * <p>CC-Switch 中火山方舟 provider 的 id / type 常见取值：</p>
     * <ul>
     *   <li>{@code volc}、{@code volcano}、{@code volcengine}</li>
     *   <li>{@code ark}、{@code doubao}</li>
     * </ul>
     */
    private static final String PROVIDER_SQL_FILTER =
            "(LOWER(provider_type) IN ('volc','volcano','volcengine','ark','doubao') "
                    + "OR LOWER(provider_id) LIKE '%volc%' "
                    + "OR LOWER(provider_id) LIKE '%ark%' "
                    + "OR LOWER(provider_id) LIKE '%doubao%')";

    private static final String SQL =
            "SELECT request_id, provider_id, provider_type, model, "
                    + "input_tokens, output_tokens, "
                    + "cache_read_tokens, cache_creation_tokens, "
                    + "total_cost_usd, duration_ms, first_token_ms, "
                    + "status_code, session_id, created_at "
                    + "FROM proxy_request_logs "
                    + "WHERE (input_tokens > 0 OR output_tokens > 0) "
                    + "AND " + PROVIDER_SQL_FILTER + " "
                    + "ORDER BY created_at ASC";

    private static Path resolveDbPath() {
        String override = System.getenv("DOUBAO_USAGE_DB");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".cc-switch", "cc-switch.db");
    }

    /**
     * 返回 SPI 名称。
     *
     * @return {@code "doubao"}
     */
    @Override
    public String name() {
        return PROVIDER_DOU_BAO;
    }

    /**
     * 流式解析豆包（火山方舟）用量记录。
     *
     * <p>无数据库或无匹配 provider 时返回空流。</p>
     *
     * @return 用量记录流
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[doubao] cc-switch.db not found: {} (CC Switch not installed)", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("doubao", DB_PATH.toString());
        return engine.query(SQL)
                .map(this::toAiUsage)
                .doOnComplete(() -> log.info("[doubao] stream complete"));
    }

    /**
     * 将 proxy_request_logs 行映射为 {@link AiUsage}。
     *
     * @param row 数据库行
     * @return 用量记录
     */
    private AiUsage toAiUsage(Map<String, Object> row) {
        int input = asInt(row.get("input_tokens"));
        int output = asInt(row.get("output_tokens"));
        int cacheRead = asInt(row.get("cache_read_tokens"));
        int cacheCreation = asInt(row.get("cache_creation_tokens"));
        double totalCost = asDouble(row.get("total_cost_usd"));
        long durationMs = asLong(row.get("duration_ms"));
        long firstTokenMs = asLong(row.get("first_token_ms"));
        long createdAt = asLong(row.get("created_at"));
        int statusCode = asInt(row.get("status_code"));

        String model = firstNonBlank(asStr(row.get("model")), PROVIDER_DOU_BAO);
        String requestId = firstNonBlank(asStr(row.get("request_id")), asStr(row.get("session_id")));

        AiUsage.AiUsageBuilder builder = AiUsage.builder()
                .provider(PROVIDER_DOU_BAO)
                .model(model)
                .requestId(requestId)
                .inputTokens(input > 0 ? input : null)
                .outputTokens(output > 0 ? output : null)
                .totalTokens(input + output + cacheRead + cacheCreation)
                .currency("USD")
                .startTime(createdAt > 0 ? createdAt * 1000L : null)
                .finishReason(statusCode == 200 ? "stop" : "http-" + statusCode);

        if (cacheRead > 0 || cacheCreation > 0) {
            builder.cacheTokens(cacheRead + cacheCreation);
        }
        if (totalCost > 0) {
            builder.totalCost(BigDecimal.valueOf(totalCost));
        }
        if (durationMs > 0) {
            builder.durationMillis(durationMs);
        }
        if (firstTokenMs > 0) {
            builder.firstTokenLatencyMillis(firstTokenMs);
        }
        return builder.build();
    }
}
