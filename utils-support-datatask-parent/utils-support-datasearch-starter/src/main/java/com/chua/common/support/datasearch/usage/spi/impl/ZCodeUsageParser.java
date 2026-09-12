package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.datasearch.usage.spi.BaseUsageParser;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.sqlite.support.engine.SqliteReactorEngine;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
* z编码 usage parser.
*
* <p>ZCode (Z.ai's Agentic Development Environment) persists per-request
* usage 入 the {@code model_usage} table 的
* {@code ~/.zcode/cli/db/db.sqlite} once the CLI/desktop agent runs:</p>
*
* <pre>{@code
* CREATE TABLE model_usage (
*   id, session_id, turn_id, provider_id, model_id,
*   status, started_at, completed_at,
*   duration_ms, time_to_first_token_ms, finish_reason,
*   input_tokens, output_tokens, reasoning_tokens,
*   cache_creation_input_tokens, cache_read_input_tokens, ...
* )   -- timestamps in epoch millis
* }</pre>ache_creation_input_tokens, cache_read_input_tokens, ...
* )   -- timestamps in epoch millis
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("zcode")
public class ZCodeUsageParser extends BaseUsageParser {

    private static final Path DB_PATH = Path.of(
            System.getProperty("user.home"), ".zcode", "cli", "db", "db.sqlite");

    private static final String SQL_MODEL_USAGE =
            "SELECT id, session_id, provider_id, model_id, status, "
                    + "started_at, duration_ms, time_to_first_token_ms, finish_reason, "
                    + "input_tokens, output_tokens, reasoning_tokens, "
                    + "cache_creation_input_tokens, cache_read_input_tokens "
                    + "FROM model_usage "
                    + "WHERE input_tokens > 0 OR output_tokens > 0 "
                    + "ORDER BY started_at ASC";

    private static final String PROVIDER_ZCODE = "zcode"; // 提供者zcode

    /**
    * 返回 SPI 名称。
    *
    * @return {@code "zcode"}
     */
    public String name() {
        return "zcode";
    }

    /**
     * 流式解析全部模型请求用量记录。
     */
    @Override
    public Flux<AiUsage> streamAll() {
        if (!Files.exists(DB_PATH)) {
            log.debug("[zcode] database not found: {} (ZCode CLI has not run yet)", DB_PATH);
            return Flux.empty();
        }
        SqliteReactorEngine engine = new SqliteReactorEngine()
                .addDataSource("zcode", DB_PATH.toString());
        return engine.query(SQL_MODEL_USAGE)
                .map(this::toAiUsage)
                .doOnComplete(() -> log.info("[zcode] stream complete"));
    }

    private AiUsage toAiUsage(Map<String, Object> row) {
        int inputTokens = asInt(row.get("input_tokens"));
        int outputTokens = asInt(row.get("output_tokens"));
        int reasoning = asInt(row.get("reasoning_tokens"));
        int cacheRead = asInt(row.get("cache_read_input_tokens"));
        int cacheWrite = asInt(row.get("cache_creation_input_tokens"));
        long startedAt = asLong(row.get("started_at"));
        long durationMs = asLong(row.get("duration_ms"));
        long ttft = asLong(row.get("time_to_first_token_ms"));

        String providerId = asStr(row.get("provider_id"));

        return AiUsage.builder()
                .provider(PROVIDER_ZCODE)
                .model(asStr(row.get("model_id")))
                .requestId(firstNonBlank(asStr(row.get("id")), asStr(row.get("session_id"))))
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .reasoningTokens(reasoning > 0 ? Integer.valueOf(reasoning) : null)
                .cacheTokens(cacheRead > 0 ? Integer.valueOf(cacheRead)
                        : cacheWrite > 0 ? Integer.valueOf(cacheWrite) : null)
                .currency("CREDITS")
                .startTime(startedAt > 0 ? startedAt : null)
                .durationMillis(durationMs > 0 ? durationMs : null)
                .firstTokenLatencyMillis(ttft > 0 ? ttft : null)
                .finishReason(firstNonBlank(asStr(row.get("finish_reason")),
                        asStr(row.get("status"))) + "@" + providerId)
                .build();
    }
}
