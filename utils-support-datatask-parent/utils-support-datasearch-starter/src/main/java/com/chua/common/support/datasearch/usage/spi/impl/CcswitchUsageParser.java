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
 * CC Switch usage parser.
 *
 * <p>CC Switch (github.com/farion1231/cc-switch) is a Tauri desktop manager
 * for Claude Code / Codex / Gemini CLI providers. It keeps a SQLite database
 * at {@code ~/.cc-switch/cc-switch.db} whose {@code proxy_request_logs}
 * table records every routed request (proxy interception or CLI session
 * import) with full token, cost and latency detail.</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("ccswitch")
public class CcswitchUsageParser extends BaseUsageParser {

    private static final Path DB_PATH = Path.of(
            System.getProperty("user.home"), ".cc-switch", "cc-switch.db");

    private static final String SQL_REQUEST_LOGS =
            "SELECT request_id, app_type, model, input_tokens, output_tokens, "
                    + "cache_read_tokens, cache_creation_tokens, total_cost_usd, "
                    + "duration_ms, first_token_ms, status_code, session_id, created_at "
                    + "FROM proxy_request_logs "
                    + "WHERE input_tokens > 0 OR output_tokens > 0 "
                    + "ORDER BY created_at ASC";

    private static final String PROVIDER_CC_SWITCH = "cc-switch";

    private static final long EPOCH_SECONDS_TO_MILLIS = 1000L;

    private static final int HTTP_OK = 200;

    /**
    * 返回 SPI 名称。
    *
    * @return {@code "ccswitch"}
    */
    @Override
    public String name() {
        return "ccswitch";
    }

    /**
    * 响应式流式入口：通过 SqliteReactorEngine 流出请求日志。
    */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[ccswitch] database not found: {} (CC Switch not installed)", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("ccswitch", DB_PATH.toString());
        return engine.query(SQL_REQUEST_LOGS)
                .map(this::toAiUsage)
                .doOnComplete(() -> log.info("[ccswitch] stream complete"));
    }

    /**
    * 将 proxy_request_logs 行映射为用量记录。
    *
    * @param row 数据库行
    * @return 用量记录
    */
    private AiUsage toAiUsage(Map<String, Object> row) {
        int statusCode = asInt(row.get("status_code"));
        double costUsd = asDouble(row.get("total_cost_usd"));
        long createdAtSeconds = asLong(row.get("created_at"));
        long durationMs = asLong(row.get("duration_ms"));
        long firstTokenMs = asLong(row.get("first_token_ms"));
        int cacheRead = asInt(row.get("cache_read_tokens"));

        String appType = asStr(row.get("app_type"));

        return AiUsage.builder()
                .provider(PROVIDER_CC_SWITCH)
                .model(asStr(row.get("model")))
                .requestId(firstNonBlank(asStr(row.get("request_id")), asStr(row.get("session_id"))))
                .inputTokens(asInt(row.get("input_tokens")))
                .outputTokens(asInt(row.get("output_tokens")))
                .totalTokens(asInt(row.get("input_tokens")) + asInt(row.get("output_tokens")))
                .cacheTokens(cacheRead > 0 ? cacheRead : null)
                .totalCost(costUsd > 0 ? BigDecimal.valueOf(costUsd) : null)
                .currency("USD")
                .startTime(createdAtSeconds > 0
                        ? createdAtSeconds * EPOCH_SECONDS_TO_MILLIS : null)
                .durationMillis(durationMs > 0 ? durationMs : null)
                .firstTokenLatencyMillis(firstTokenMs > 0 ? firstTokenMs : null)
                .finishReason(statusCode == HTTP_OK ? appType + ":stop" : appType + ":http-" + statusCode)
                .build();
    }
}
